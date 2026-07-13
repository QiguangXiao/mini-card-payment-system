# 事件、Outbox、Inbox 与 Kafka 统一说明

> 本文合并自三份旧文档：`kafka-outbox-design.md`（英文设计说明）、
> `kafka-learning-cn.md`（Kafka 配置详解）、`event-outbox-messaging-design-claude-cn.md`
> （实现走读 + 评价 + 面试问答）。合并时删除三者大量重复（dual-write、at-least-once、
> envelope、DLT、消费者幂等在三份里各讲一遍），保留各自的独特价值：设计动机、**Kafka 配置参考**、
> 逐类实现走读、真实 gap、硬核 Q&A。原三份已归档在 `docs/archive/`。

> 关键词：领域事件, 事务性发件箱, 至少一次, 幂等消费者, 死信, 分区有序, dual-write,
> transactional outbox, at-least-once, idempotent consumer, inbox, DLT, partition ordering,
> アウトボックス, 冪等消費者, デッドレター。

一句话定位：**Kafka 负责异步消息传递，但不替代数据库事务，也不消除 consumer 幂等。**

相关代码：`messaging/outbox/*`、`messaging/inbox/*`、`messaging/kafka/*`、`messaging/event/IntegrationEvent.java`、
各 context 的 `infrastructure/messaging/*OutboxAdapter` 与 `*Listener`、`src/main/resources/application.yml`。

---

## 1. 为什么需要 Outbox（dual-write 问题）

业务状态在 MySQL，消息要发到 Kafka，二者是**两个独立系统、没有联合事务**。在 `AuthorizationService` 里直接 `kafkaTemplate.send()` 会出现 dual-write：

```text
MySQL commit 成功，Kafka 失败 -> 业务发生了，但事件丢了
Kafka 成功，MySQL 回滚       -> 下游看到一个从未发生的决策
```

**Transactional Outbox** 的解法：把"要发的消息"作为业务事务的一部分，写进**同一个 MySQL 库**的 `outbox_events` 表，和业务状态**同一个 commit**；再由独立后台发布器异步投递。

```text
Authorization + CreditAccount + DelayJob + Outbox Event  ->  一个 MySQL commit
OutboxWorker  ->  稍后发布 Kafka
```

本质是**把"发消息的意图"持久化**。Kafka 挂了也不丢意图，发布器恢复后重试 PENDING 行。

> **反向事实：用 `@TransactionalEventListener(AFTER_COMMIT)` 发不就行了？** 仍是 best-effort：commit 之后、send 之前进程挂掉，事件**永久丢失**，没有持久重试来源。Outbox 的价值正是"意图落库 + 可重放"。它同时避免在主事务里同步等 broker，主交易延迟与可用性不被 Kafka 绑架。

---

## 2. 全链路总览

```text
[业务主事务  一个 MySQL TX]
  aggregate 状态变更  +  aggregate 缓冲 domain event
        │  service 同事务内：pullDomainEvents() -> publisher.append() -> outbox_events INSERT
        ▼
  COMMIT  ─────────►  业务行 + outbox 行 同生共死
                              │  (轮询，秒级延迟，独立后台线程)
                              ▼
[OutboxPoller] --claim--> [OutboxClaimer 短事务: PENDING->PROCESSING(lease)]
        │  claim 提交后才交给 worker pool
        ▼
[OutboxWorker] --publish--> Kafka(同步等 ack) --finalize(独立短事务)--> PUBLISHED / 重试 / DEAD
        ▲                                                      │
[OutboxRecoverer] 扫描 lease 超时的 PROCESSING ───────────────┘
                              ▼  Kafka topic（按 source context 拆，partitionKey=聚合id）
[KafkaListener@消费上下文] -> IntegrationEventReader(契约校验)
        -> service: ConsumerInbox.claim(去重①) + 业务唯一键(去重②)  [同一事务]
        -> 失败: DefaultErrorHandler 退避重试 -> DLT；契约错误 -> 直接 DLT
```

---

## 3. Producer side：领域事件如何变成 outbox 行

- **领域事件在 aggregate 内缓冲**。`Repayment` 持有 `List<RepaymentDomainEvent>`，`markReceived(...)` 时 `add(...)`；`pullDomainEvents()` 返回拷贝并清空。事件是"业务事实"的产物，不是 service 临时拼出来的。
- **同事务写 outbox**。`RepaymentService.receive(...)`（`@Transactional`）更新 account/statement/repayment 后，`publishDomainEvents()` 经 `RepaymentOutboxAdapter` 落 `outbox_events`。业务行与 outbox 行在**同一个 commit**——这就是 transactional outbox 的全部要点。
- **幂等重放不重复发事件**。同 `Idempotency-Key` 的重复请求走 `claimed=false && RECEIVED` 分支**提前 return，不 append**。一个业务事实只产生一个 `eventId`。
- **adapter 手工拼 payload（Anti-Corruption Layer）**。`RepaymentOutboxAdapter` 用 `objectMapper.createObjectNode()` 逐字段写，**不直接序列化 domain record**。domain 字段改名不会无意改动 Kafka contract。
- **`OutboxEvent` 是状态机**（`messaging/outbox/OutboxEvent.java`）：
  - 状态 `PENDING / PROCESSING / PUBLISHED / DEAD`，只读 getter，只能经 `markProcessing / markPublished / markFailed / markProcessingTimedOut` 推进。
  - `next_attempt_at` 只做时间语义：PENDING 时是下次可发布时间，PROCESSING 时是 lease deadline；
    `lease_token` 是 finalize 时比较的 owner token。
  - `markFailed` 指数退避：`delay = min(2^(attempts-1), 60s)`，`1L << n` 防溢出；`attempts >= maxAttempts` 转 `DEAD`，阻断 poison message 无限重试。
  - `lastError` 在 domain 内截断到 500 字符，避免 DB 列长异常盖掉真正失败原因。

---

## 4. Outbox dispatch：claim / publish / finalize / recover

复用项目统一的 **claimable-job 并发模型**（详见 `claimable-jobs` 文档），拆成 4 个单一职责类：

| 类 | 职责 | 事务边界 |
|---|---|---|
| `OutboxPoller` | `@Scheduled(fixedDelay)` 轮询，提交 worker pool | 无（只调度） |
| `OutboxClaimer` | `FOR UPDATE SKIP LOCKED` 领取 PENDING，改 PROCESSING lease | **短事务** |
| `OutboxWorker` | publish 等 ack，再 finalize delivery state | publish 不开事务；finalize 独立**短事务** |
| `OutboxRecoverer` | 扫描 lease 超时的 PROCESSING，按一次失败处理 | 独立事务 |

关键正确性细节（都是"反向事实"驱动的设计）：

- **claim 与 publish 分离**。claim 把 `PENDING->PROCESSING` 在短事务里提交后，才把 event 交给 `outboxWorkerExecutor`。**等 broker ack 期间不持任何 DB 行锁**——否则 broker latency 会被放大成 MySQL 行锁时间，拖垮连接池。
- **finalize 重校验 lease token**。`OutboxWorker` 重新 `FOR UPDATE` 取行，校验
  `status==PROCESSING && lease_token==claimed.lease_token`。迟到的老 worker 不能覆盖
  recoverer / 新 worker 已推进的状态（乐观并发）。
- **worker pool 拒绝也要 finalize**。`TaskRejectedException` → `markRejectedForRetry`，否则 event 会卡在 PROCESSING 直到 recoverer 才可见。
- **开关保护**。`OutboxPoller` / `OutboxRecoverer` 都 `@ConditionalOnProperty(outbox.publisher.enabled)`，开发/迁移时可只写 outbox 行不实际发 Kafka。

`send-timeout-ms`（应用等 `send().get()` 的时间，5s）必须 **小于** `processing-timeout-seconds`（outbox lease，30s）——否则健康 worker 还在等 broker ack 时，会被 recoverer 当成 stuck event 抢走。

---

## 5. Kafka 配置参考（每个配置解决什么问题）

配置在 `application.yml` + `KafkaTopicsConfiguration`（topic 声明）+ `KafkaConsumerConfiguration`（消费 retry/DLT 错误处理；容器由 Boot 默认 factory 创建，并发在各 `@KafkaListener(concurrency)`）+ `KafkaOutboxMessagePublisher`。

### 5.1 Producer

| 配置 | 值 | 作用 / 为什么 |
| --- | --- | --- |
| `acks` | `all` | 等 leader + in-sync replicas 确认才算成功。金融系统丢事件比延迟更严重。**但它只让 Kafka 写入更可靠，不解决 dual-write，所以仍需 Outbox。** |
| `enable.idempotence` | `true` | 让 broker 识别同一 producer session 内的重复写入，减少 **producer retry** 造成的 Kafka 内部重复。**≠ 业务 exactly-once、≠ consumer 不用幂等、≠ MySQL+Kafka 原子提交。** |
| `max.in.flight.requests.per.connection` | `5` | 单连接允许的在途请求数，影响吞吐；配合 idempotent producer 在现代 Kafka 仍安全（不乱序）。 |
| `delivery.timeout.ms` / `request.timeout.ms` | `10000` / `3000` | 一条消息总时限（含 retry）/ 单次 broker 请求时限。与 outbox lease 配合（见 §4）。 |

### 5.2 Consumer

| 配置 | 值 | 作用 / 为什么 |
| --- | --- | --- |
| `enable-auto-commit` | `false` | 处理成功后再提交 offset。**反向事实**：自动提交会"offset 已提交但 MySQL 还没写、consumer crash → side effect 丢失"。 |
| `auto-offset-reset` | `earliest` | 新 group 无历史 offset 时从最早读，方便学习/可重建投影。**生产慎用**：新 group 可能回放大量历史。 |
| `listener.ack-mode` | `record` | 每条成功后提交 offset，语义比 batch ack 清晰；每条事件独立幂等。 |

> 但禁用 auto-commit **仍不是原子**：MySQL commit 成功、Kafka offset commit 前 crash → Kafka redeliver → 靠 consumer 幂等挡重复。

### 5.3 Topic / partition

每个 source context 一个 topic（带 `.v1` 大版本后缀），各 **3 partitions / 1 replica**（本地单 broker，生产应 ≥3 副本 + `min.insync.replicas` 配合 `acks=all`）：

```text
mini-card.authorization-events.v1   authorization.approved/declined/expired/posted
mini-card.transaction-events.v1     card_transaction.posted
mini-card.statement-events.v1       statement.closed
mini-card.repayment-events.v1       repayment.received
```

**为什么按业务事实拆 topic**：`card_transaction.posted`（用户可见交易入账）该被 Notification 消费，而不是 `authorization.posted`（授权 hold 被消耗）；`statement.closed` 适合账单通知订阅；生产里 Notification 很可能是独立微服务，订阅它需要的 business facts，不依赖本工程 domain class。

**partition key = 聚合/账户范围**（Kafka 只保证 partition 内有序）：authorization→`authorizationId`，card_transaction→`cardTransactionId`，statement/repayment→`creditAccountId`。于是同一聚合/账户的事件进同一 partition 保持有序，不同聚合并行。

### 5.4 Consumer group 与 concurrency

每个 bounded context 一个独立 group（`mini-card-notification-v1` / `-risk-feature-v1` / `-ledger-v1`）。同 group 内一条消息只被一个实例处理；不同 group 各收一份——这正是 event-driven 的价值：一条 authorization event，notification 和 risk-feature 各收到一份独立处理。

`concurrency`（如 risk feature = 3）只提高同 group 内并行度，**有效并行度受 partition 数限制**（3 partitions → 最多 3 个有效线程，再大只是空闲）。数值配置在 `messaging.consumers.*.concurrency`，由各 `@KafkaListener(concurrency)` 占位符消费——它和 worker pool size 一样是"处理并行度"，统一放 YAML 而不是硬编码在注解里。`group-id` 同一个 YAML key 有两个读者：listener 的 `groupId` 占位符（消费进度归属）和 `KafkaConsumersProperties` 绑定（`KafkaConsumerConfiguration` 的失败 group → DLT 路由表）。

### 5.5 消费速率与 poll 循环：本项目刻意走默认值的参数（以及生产什么时候要拧）

先纠正一个常见直觉：**Kafka 没有"每秒最多推给你多少条"的配置，因为 consumer 是 pull 模型**——
你不调 `poll()` 就没有消息进来；处理不过来的消息留在 broker 里变成 consumer lag。
所以消费速率不是"配"出来的，而是由 `单批条数 × 处理一条的耗时` 自然决定的。能拧的旋钮都围绕
"一口咬多大"和"多久必须咬一口"。

本项目 `application.yml` 里**没有出现**、走 Kafka client 默认值的参数：

| 参数 | 默认值 | 管什么 | 本项目为什么默认就够 |
| --- | --- | --- | --- |
| `max.poll.records` | 500 | 一次 `poll()` 最多返回几条 | listener 单条只做"inbox 幂等插入 + 业务行"一个短事务（毫秒级）：500 × ~5ms ≈ 2.5s，远小于 5 分钟上限 |
| `max.poll.interval.ms` | 300000（5min） | 两次 `poll()` 的最大间隔，超过即被认定"消费卡死"踢出 group | 同上，单批 2.5s 安全余量巨大 |
| `session.timeout.ms` | 45000 | 心跳超时，判定"进程死没死" | 默认的宕机检测速度对秒级容忍的投影/通知足够 |
| `heartbeat.interval.ms` | 3000 | 后台心跳频率 | 跟随 session.timeout 的默认比例即可 |
| `fetch.min.bytes` / `fetch.max.wait.ms` | 1 / 500 | broker 攒多少数据/等多久才回包（吞吐 vs 延迟） | 本地流量小，不需要为吞吐攒批 |
| `partition.assignment.strategy` | RangeAssignor（列表里带 CooperativeSticky 供升级） | rebalance 时怎么重分 partition | 单实例学习环境感受不到 rebalance 代价 |

**"要不要配"的判断公式只有一条**：`max.poll.records × 单条最坏耗时 < max.poll.interval.ms`。
本项目满足是因为架构先把慢活挪走了——listener 只写 DB，昂贵的 provider HTTP 在 poller/worker 那条
DB 队列后面（§7 的 inbox + 通知的 delivery 队列）。**消费得快不是调参调出来的，是 listener 里不干慢活换来的。**

反过来，把慢活放进 listener 的系统长这样（生产最常见的 Kafka 事故模式）：

```text
事故重放：listener 里同步调慢 HTTP，max.poll.records 没校准
t0     poll() 返回 500 条，每条同步调一次外部接口（平均 800ms）→ 这批要 400s
t300s  broker 5 分钟内没等到下一次 poll()，判定该 consumer 消费卡死，踢出 group。
       注意：心跳线程此刻还在正常跳——心跳只证明进程活着（session.timeout 管这个），
       证明不了消费没卡住（max.poll.interval 管这个）。两条存活判定是分开的。
t300s  rebalance：它的 partition 分给同组其他实例，从上次提交的 offset 重放
       → 已处理未提交的几百条被重复处理（幂等没做好的系统在这里出业务事故）
t400s  原 consumer 处理完想提交 offset → CommitFailedException（已不是成员）→ 重新 join
       → 又触发一次 rebalance。处理速度不改，就无限循环 = rebalance storm
```

修法优先级：把慢活挪出 listener（本项目的结构性答案）> 调小 `max.poll.records`（50~100）>
调大 `max.poll.interval.ms`（治标，且拉长真故障的发现时间）。

**真实生产项目通常显式配置的清单**（按动机分组）：

1. **防 rebalance storm**：`max.poll.records` 按上面公式校准；升级到 `CooperativeStickyAssignor`
   （增量重分配，rebalance 不再全组 stop-the-world）；K8s 滚动发布再加 static membership
   （`group.instance.id`），重启的 pod 回来还认领原 partition，避免每次发布触发两轮 rebalance。
2. **吞吐**（日志/埋点这类高吞吐 topic）：调大 `fetch.min.bytes`（如 64KB）+ `fetch.max.wait.ms`，
   用一点延迟换 broker 攒批，网络往返数量级下降。
3. **正确性**：上游用 Kafka 事务时 consumer 配 `isolation.level=read_committed`；
   `auto.offset.reset` 生产多用 `latest` 并配合 lag 告警——注意它**只在 group 没有已提交 offset 时生效**
   （新 group / offset 过期被清），不是"每次启动从哪读"，这是最常被面试问倒的误解。
4. **容量**：partition 数是并行度天花板，扩 consumer 实例扩不过它；而 partition 只能加不能减，
   加了还会改变 key→partition 映射（短暂破坏同 key 有序），所以要提前规划。

**生产怎么应对"机器能力"——不是动态调参，而是三件套**：静态保守值（按最坏单批耗时留余量）+
**consumer lag 当压力表**（lag 持续增长 = 消费能力不足；lag 逼近 retention = 快要丢数据，双阈值告警）+
lag 驱动横向扩容（KEDA/HPA 按 lag 加实例，上限 partition 数）。pull 模型下弱机器自动消费得慢，
lag 会如实涨给你看；把扩容决策交给平台，比每台机器自己"感知能力动态调参"简单且可审计。

---

## 6. Kafka adapter 与 envelope

- `IntegrationEvent`（`messaging/event`）= 稳定 envelope `{eventId, eventType, eventVersion, occurredAt, payload(JsonNode)}`。**envelope 固定、payload 灵活**（Tolerant Reader），不为每种事件建一组 Java payload class。
- `KafkaOutboxMessagePublisher`：
  - `ProducerRecord(topicFor(eventType), partitionKey, payloadJson)` + headers `eventId/eventType/eventVersion/aggregateType/aggregateId`（consumer 不反序列化 JSON 就能路由/过滤/观测；DLT 排查也不用 parse）。
  - `kafkaTemplate.send(record).get(timeout)` **同步等 ack** 后才让 outbox 标 PUBLISHED；fire-and-forget 会在 broker 实际失败时误判已发。
  - `topicFor` 按 `eventType` 前缀路由到各 context topic，未知类型 fail-fast。
- payload 把 `amount` 表示为 **decimal 文本 + currency**，跨 JSON 存储保精度，防 consumer 误用二进制浮点。

> header 与 payload 都带 `eventId/eventType`：header 是 transport-level observability metadata（logging/DLT 排查），payload 是 durable contract。`IntegrationEventReader` 只信任 self-describing body；手工 replay 没有 header 也能正确消费，header 写错只会误导排查，不参与 correctness。

---

## 7. Consumer side：契约校验 + 双层幂等

- **每个 context 一个 listener + 独立 group + 独立 DLT**：`AuthorizationNotificationListener` / `CardTransactionNotificationListener` / `StatementNotificationListener` / `RepaymentNotificationListener`（Notification）、`AuthorizationRiskFeatureListener`（Risk）、`CardTransactionLedgerListener` / `RepaymentLedgerListener`（Ledger）。互不影响、可独立扩缩与重放。
- `IntegrationEventReader.read(...)`：反序列化 + 集中校验 self-describing body envelope（`eventId/payload` 必填、`eventType` 必填、`eventVersion >= 1`）；header 只用于 observability，不参与 correctness。无效 integration event 抛 `InvalidIntegrationEventException`（永久失败）。`requiredText/requiredUuid/requiredDecimal/requiredCurrency/requiredInstant` 统一读取 payload 的通用数据类型，具体 eventType 需要哪些字段仍由 listener 声明。
- **双层幂等（消费侧最关键）**：
  1. **Inbox claim（第一道）**：`ConsumerInboxRepository.claim(consumerName, eventId)`，底层 `INSERT INTO consumer_inbox`，靠 `PRIMARY KEY(consumer_name, event_id)` 判定"第一次消费"，`DuplicateKeyException → false`。
  2. **业务唯一键（第二道）**：`ledger_entries` 对 `source_event_id + entry_type` 唯一、`notifications.source_event_id` 唯一。挡住"绕过 inbox 的手工 replay / 补偿脚本"。
  - **inbox claim 与业务写在同一个 `@Transactional`**（见 `RecordLedgerEntryService`）。**反向事实**：否则 claim 成功但业务写失败会"假装消费过"，造成**丢消费**。
- **错误处理与 DLT**（`KafkaConsumerConfiguration`）：Boot 默认 listener factory + **全局唯一** `DefaultErrorHandler(FixedBackOff(1000ms, 2 次))`；`DeadLetterPublishingRecoverer` **按失败的 consumer group 路由**到对应 DLT——同一 topic 被多个 group 消费（transaction-events 同时进 Notification 和 Ledger），按 `record.topic()` 查表会把 Ledger 的失败发进 Notification 的 DLT，正确路由键是 `ListenerExecutionFailedException` 携带的失败 groupId（`KafkaConsumerConfigurationTest` 钉住）。保留原 partition 便于关联排查；**DLT 内顺序不等于源顺序**（只有失败消息进入、各自经历不同次数 retry），replay 的正确性仍靠双层幂等，不能依赖 DLT 顺序恢复业务顺序。**`addNotRetryableExceptions(InvalidIntegrationEventException.class)`**——永久契约错误直进 DLT 不空转，瞬时错误才退避重试。DLT topic：`mini-card.notification.dlt.v1` / `mini-card.authorization-risk-feature.dlt.v1` / `mini-card.ledger.dlt.v1`（partition 数与源 topic 对齐，`KafkaTopicsConfigurationTest` 钉住——否则源 partition N 的失败无法按原 partition 发布）。
  - **为什么每个 consumer 一个 DLT**：同一条消息可能对 Risk 成功、对 Notification 失败；分开后可只修复/replay 失败的 consumer，不影响其他 context。
  - **为什么不再是三个 per-context factory**：曾为"不同 DLT 名 + 不同 concurrency"建三个几乎相同的 factory。收敛为单 handler 消灭了两个坑：Boot 默认 factory 只按 bean 名让位，忘写 `containerFactory` 的新 listener 会静默挂上无 DLT 的默认 handler（重试后记日志、提交 offset、**消息丢弃**）；DLT 正确性曾依赖每个 listener 手选 factory。现在默认 factory 自带 DLT handler，路由跟随失败的消费组，无可绑错的配置点。只有 ack/批量/serde/事务/集群真正不同时才值得回到多 factory。
  - **当前运维边界**：系统只负责把失败 record 发进三个 DLT，尚无 listener/monitor group 消费它们，也没有 DLT metric、告警或 replay 工具。生产应增加独立 monitor group 同时订阅三个 DLT，记录原 topic/partition/offset、异常 headers 与 `eventId` 并告警；修复根因后再受控 replay，不能自动回投造成无限循环。

---

## 8. 已实现的三个 consumer + 数据模型

| Consumer | 建模 | 消费 | 副作用 / 幂等 |
| --- | --- | --- | --- |
| **Notification** | **独立 aggregate**（`PENDING→SENT/FAILED` 生命周期），Kafka 仅入站 adapter | authorization / card_transaction / statement / repayment | 创建 `notifications` 行；`source_event_id` 唯一防重复创建 |
| **Ledger** | **独立 projection**（学习用，非生产总账） | `card_transaction.posted` / `repayment.received` | append `ledger_entries`（`CARD_TRANSACTION_POSTED/DEBIT`、`REPAYMENT_RECEIVED/CREDIT`）；Inbox + `source_event_id+entry_type` 唯一 |
| **Risk feature** | **投影非 aggregate**（事件派生、最终一致、可重建） | `authorization` events | upsert `card_risk_features`；Inbox 同事务 |

- authorization 事件**不**产生 ledger 分录，因为授权是 credit hold，不是 posted receivable。
- Risk 投影刻意简单；硬 velocity 规则仍走 Redis/DB（见 `caching-and-rate-limiting`），因为**最终一致的投影不应静默地强制实时硬限额**。

```sql
outbox_events(
  id PK, aggregate_type, aggregate_id, event_type, event_version,
  partition_key, payload JSON, status, attempts, next_attempt_at, lease_token,
  created_at, published_at, last_error,
  INDEX idx_outbox_publishable (status, next_attempt_at, created_at),  -- 匹配 poller 查询
  CHECK status IN ('PENDING','PROCESSING','PUBLISHED','DEAD'), CHECK attempts >= 0
)
consumer_inbox(consumer_name, event_id, processed_at, PRIMARY KEY (consumer_name, event_id))
```

`idx_outbox_publishable` 的 `status` 做前导列，PENDING 行天然聚簇，PUBLISHED 堆积不拖慢 poller 的 `WHERE status='PENDING' AND next_attempt_at<=now ORDER BY created_at`。

---

## 9. 投递语义与有序性

- 端到端 **at-least-once**。`OutboxEvent` 注释直说"Kafka ack 后、finalize commit 前宕机会重发"，所以下游必须幂等。
- **Kafka 的 EOS（幂等 producer + 事务）只覆盖 Kafka 内部**（producer→broker→`__consumer_offsets`），不覆盖"消费者去改 MySQL"这种外部副作用。工程上追求 **effectively-once = at-least-once 投递 + 幂等消费者**。本项目没用 Kafka 事务，因为 outbox 已解决可靠性，再叠 EOS 收益小、复杂度高。
- **有序性**：partitionKey=聚合 id ⇒ 单聚合有序。`concurrency>1` 不破坏单 partition 顺序（同 group 多线程各吃不同 partition）。**DLT 会局部乱序**：某条重试耗尽进 DLT 后，同 key 后续继续（跳过失败那条）——ledger 是 append-only 独立分录、不依赖顺序，所以无害；若是"按序推导余额"的有状态投影，就要靠 DLT 重放补偿或暂停该 key。跨 topic（ledger 同时消费 transaction + repayment events）**无全局有序**，是有意取舍。

---

## 10. 通用领域知识 / 设计模式

- **Dual-write problem**：跨"DB + 消息中间件"无法原子写。解法谱系：Transactional Outbox（本项目）、Listen-to-yourself、CDC(Debezium) 拖 binlog、事件溯源。
- **Polling Publisher vs Transaction Log Tailing(CDC)**：Polling（本项目）实现简单、自洽、可控开关，代价是轮询延迟 + 扫表压力（靠索引化解）；CDC 低延迟、不轮询、不侵入业务查询，但要运维 connector、强依赖 binlog，且**通常仍需 outbox 表**携带干净事件语义。演进路径：把"轮询发布器"换成"CDC 拖 outbox 表"，**消费者侧完全不用改**。
- **Idempotent Consumer / Inbox**：持久化去重表（消费者名 + 消息 id），关键是**去重写与业务写同事务**；去重键用**业务幂等键**（`eventId`），不是 Kafka offset（offset 会随 topic 重建变化）。
- **Competing Consumers on a DB queue**：`SELECT ... FOR UPDATE SKIP LOCKED` 让多实例并发领取不同 outbox 行，无需额外队列中间件。
- **Lease / visibility timeout**：PROCESSING 是租约不是所有权；worker 宕机后租约到期，recoverer 放回重试；lease-token 防迟到 worker 覆盖新状态。
- **Poison message & DLT**：退避重试 N 次仍失败 → 死信；永久错误（坏 JSON、不兼容 schema）**不重试直接死信**；死信要可观测、可重放，**不要自动无限 replay**。
- **Schema evolution & Tolerant Reader**：envelope 稳定 + payload 宽松解析；向后兼容加字段，破坏性变更升 `eventVersion` 或新 `eventType`。
- **Choreography vs Orchestration**：本项目是**编排式 choreography**——主事务只发事实，下游各 context 自己决定怎么反应，没有中心 saga 协调器。优点解耦，代价是流程散落、端到端可观测更难。
- **Anti-Corruption Layer**：outbox adapter 手工映射 domain→integration event，domain 改名不破契约。

---

## 11. 评价：优点与真实 gap（已对齐代码核对）

**优点（可照着讲一轮系统设计面）**：Transactional Outbox 落地正确（aggregate 缓冲 → 同事务写 outbox → 幂等重放不重复 append）；投递复用 claim/publish/finalize/recover 四段、等 ack 不持锁、lease+token 重校验+recoverer 兜底、指数退避+DEAD；显式 at-least-once + 双层消费者幂等且同事务；self-describing body envelope 与 typed contract reader 边界清楚；Kafka 层职责干净（同步等 ack、按聚合 partition、per-context topic/group/DLT、可重试 vs 不可重试分类）；schema 配套索引/约束到位。

**真实 gap（都不是 bug，是"上生产还差的工程化"，本次已核对仍存在）**：

1. **数据保留 / 清理缺失（最该补）**。`outbox_events` 的 PUBLISHED 行、`consumer_inbox` 的所有行**永不删除**（已核对 `messaging` 下无 retention/cleanup job）。索引保证热路径快，但**存储无界增长**。生产需按 `published_at`/`processed_at` 滚动归档或时间分区 `DROP PARTITION`。*面试几乎必问"outbox 表会不会爆"。*
2. **producer 侧 DEAD 和 consumer 侧 DLT 都缺完整运维闭环**。消费失败会被写入三个 per-context DLT，但当前没有 listener/monitor group 消费，因而没有 DLT metric、告警或 replay 工具；outbox 事件转 `DEAD` 后也只是躺在表里。生产补齐时，consumer 侧应由独立 monitor group 订阅三个 DLT并告警，修复代码或数据后再受控 replay；producer 侧至少应有 `DEAD` 计数指标 + 把 DEAD 改回 PENDING 的运维路径。
3. **`eventVersion` 有字段但无消费侧版本协商**。`IntegrationEventReader` 只校验 `eventVersion >= 1`（已核对），**consumer 不按版本分支也不拒绝未知版本**。schema evolution 目前是"约定"不是"强制"：发不兼容的 v2 且字段恰好重叠时会被**静默误读**。要补：consumer 按 `(eventType, eventVersion)` 路由 / 拒绝，或上 schema registry。

> 跨 topic 无全局有序、polling 的秒级延迟，是**有意取舍**而非 gap。

---

## 12. 本地 vs 生产

| | 本地（Docker Compose） | 生产应补 |
| --- | --- | --- |
| broker | 单 broker，KRaft 合并模式 | 多 broker，replication ≥ 3 + `min.insync.replicas` |
| 安全 | 无认证 | TLS / SASL |
| 运维 | 简单 retry/DLT | topic retention 策略、consumer lag alert、DLT alert + replay 工具 |
| 契约 | envelope 约定 | schema registry 或更严格 contract 管理 |
| 观测 | — | producer/consumer metrics dashboard、outbox DEAD 指标 |

---

## 13. 硬核面试 Q&A

**Q1. 为什么不在业务事务里直接 `kafkaTemplate.send()`？**
dual-write：MySQL 成功 Kafka 失败→事件丢；Kafka 成功 MySQL 回滚→下游看到不存在的决策。Outbox 把"发消息的意图"和业务状态放进同一 commit，再异步投递。
- *追问：用 `@TransactionalEventListener(AFTER_COMMIT)` 行吗？* 仍 best-effort：commit 后 send 前挂掉就永久丢，没有持久重试来源。

**Q2. 投递语义是什么？能 exactly-once 吗？**
端到端 at-least-once（ack 后、finalize 前宕机会重发）。Kafka EOS 只保证 Kafka 内部；一旦消费者改 MySQL/发通知这种外部副作用，跨系统回到 at-least-once，靠**消费者幂等**收口成 effectively-once。

**Q3. 消费者幂等怎么做？有了 Inbox 为什么还要业务唯一键？**
Inbox（`consumer_inbox` 唯一键 INSERT-claim）是通用第一道；业务唯一键是第二道，挡绕过 Inbox 的手工 replay/补偿。
- *追问：Inbox claim 和业务写不在一个事务会怎样？* claim 成功但业务写失败 → 下次重投认为"已消费"跳过 → **丢消费**。必须同事务。
- *追问：consumer_inbox 无限增长？*（gap #1）按 `processed_at` 滚动删除/分区裁剪，或只保留去重窗口 + 业务键兜底窗口外重复。

**Q4. 为什么 claim 和 publish 分两段事务？**
claim 短事务（提交即放锁），publish 等 broker ack 时**不持任何行锁**；否则 broker latency 放大成行锁时间，连接池和锁一起爆。
- *追问：publish 成功但 finalize 失败？* 消息已进 Kafka 但行没标 PUBLISHED（仍 PROCESSING）→ recoverer 放回 PENDING → 重发 → 消费者幂等兜底。自洽。

**Q5. PROCESSING lease 的意义？lease 到期但老 worker 其实还活着会双发吗？**
PROCESSING 是租约；worker 宕机后 recoverer 在 `next_attempt_at<=now` 放回重试；finalize 前重校验 lease token 防迟到老 worker 覆盖新状态。会双发（recoverer 已放回 → 另一 worker 重发 → Kafka 两条），这是 at-least-once 正常表现，消费者 Inbox 去重。**lease 解决"卡死可见性"，不是"绝不重复"。**

**Q6. 顺序性怎么保证？什么时候乱序？**
partitionKey=聚合 id 保证同聚合有序；`concurrency=3` 不破坏单 partition 顺序（各吃不同 partition）。DLT 会局部乱序（同 key 跳过失败那条继续）；跨 topic 无全局有序。ledger 是 append-only 独立分录不依赖顺序，所以无害。

**Q7. outbox 表越来越大，poller 会变慢吗？存储无限涨呢？**
`idx_outbox_publishable(status,...)` 让 PENDING 聚簇，PUBLISHED 堆积不影响扫描。但存储无限涨是真 gap（#1）：按 `published_at` 滚动迁冷表/删除，或按天时间分区 `DROP PARTITION`（比 DELETE 省）。

**Q8. 为什么 payload 用 `JsonNode` 而不是强类型 DTO？字段写错谁兜？**
envelope 稳定 + payload 灵活（Tolerant Reader），避免 DTO 爆炸和版本僵化。`IntegrationEventReader.requiredText/requiredUuid/requiredDecimal/requiredCurrency/requiredInstant` 显式校验，缺字段或格式错误抛 `InvalidIntegrationEventException`→直进 DLT。reader 只提供共通读取规则，具体 eventType 需要哪些字段仍由 listener 声明。代价是无编译期类型安全，规模大时上 schema registry（Avro/Protobuf）。

**Q9. 如何演进 event schema 不炸下游？你这套现在能强制版本兼容吗？**
向后兼容加字段；破坏性变更升 `eventVersion` 或新 `eventType` 让新老 consumer 并存。**现在不能强制**（gap #3）：`eventVersion` 只校验 `>=1`，consumer 不按版本分支。要补按 `(eventType, eventVersion)` 路由/拒绝或上 schema registry。

**Q10. 既然有 CDC，为什么自己写 polling outbox？**
polling 简单、自洽、可控开关，秒级延迟对账单/通知足够；CDC 低延迟但要运维 connector、依赖 binlog，且通常仍需 outbox 表携带干净事件语义。演进时把轮询发布器换成 CDC 拖 outbox 表，**消费者侧不用改**。

**Q11. 为什么 topic/listener 按 source context 拆？Kafka 挂了授权还能成功吗？consumer 挂了会丢吗？**
拆：每个 context 独立 group + DLT + concurrency，故障与扩缩互不影响，便于只重放某投影。Kafka 挂：主事务只写 outbox 行，Kafka 挂只让 outbox 停在 PENDING/PROCESSING，恢复后 worker 重试，**授权照样成功**。consumer 挂：消息留在 topic，恢复后从 group offset 继续；但超过 retention 被删就消费不到，所以生产要配 retention + lag alert。

---

## 14. 一句话总结

> 用 Kafka 解耦授权后的异步副作用，用 Outbox 解决 MySQL 和 Kafka 的 dual-write lost-event；整体 at-least-once，所以 producer 等 broker ack、Outbox 负责 claim-lease-recover 重试、consumer 用 `eventId` 双层幂等、DLT 隔离坏消息。这是项目里最"可面试"的一块；真正待补的工程化只有三点：**outbox/inbox 数据保留清理、producer 侧 DEAD 可观测与重放、eventVersion 消费侧强制协商**。
