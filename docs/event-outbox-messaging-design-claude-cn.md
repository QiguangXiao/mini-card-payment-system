# Event / Outbox / Messaging 设计、评价与面试备战（Claude）

> 关键词：领域事件, 事务性发件箱, 至少一次, 幂等消费者, 死信, 分区有序,
> domain event, transactional outbox, at-least-once, idempotent consumer,
> dead letter, partition ordering, ドメインイベント, アウトボックス,
> 冪等消費者(べきとうしょうひしゃ), デッドレター。

本文聚焦工程里**完成度最高的一块**：从“业务事务产生领域事实”到“可靠投递进 Kafka”再到
“消费者幂等落地”的整条链路。它是 `kafka-outbox-design.md` 和
`async-workflows-comparison-cn.md` 的姊妹篇——前两篇偏“为什么这样设计 / 横向对比”，
本篇偏“**实际实现长什么样 + 真实 gap + 硬核面试问答**”。

---

## 第一部分：现在工程中的实际实现

### 1.1 全链路一张图

```text
[业务主事务  ONE MySQL TX]
  aggregate.状态变更  +  aggregate.buffer(domain event)
        │  service 在同一事务内：
        │  pullDomainEvents() -> publisher.append() -> outbox_events INSERT
        ▼
  COMMIT  ──────────────►  业务行 + outbox 行 同生共死
                                   │
                  (轮询，秒级延迟，独立后台线程)
                                   ▼
[OutboxPoller] --claim--> [OutboxClaimer  短事务: PENDING->PROCESSING(lease)]
        │  claim 提交后才交给 worker pool
        ▼
[OutboxWorker] --publish--> Kafka(等 broker ack) --finalize(独立短事务)--> PUBLISHED / 重试 / DEAD
        ▲                                                      │
[OutboxRecoverer] 扫描 lease 超时的 PROCESSING ───────────────┘

                                   ▼ Kafka topic（按 source context 拆，partitionKey=聚合id）
[KafkaListener@消费上下文] -> IntegrationEventReader(契约校验)
        -> service: ConsumerInbox.claim(去重①) + 业务唯一键(去重②)  [同一事务]
        -> 失败: DefaultErrorHandler 退避重试 -> DLT；契约错误 -> 直接 DLT
```

### 1.2 Producer side：领域事件如何变成 outbox 行

- **领域事件在 aggregate 内缓冲**。`Repayment` 持有 `List<RepaymentDomainEvent> domainEvents`，
  `markReceived(...)` 时 `add(new RepaymentReceivedDomainEvent(...))`；`pullDomainEvents()`
  返回拷贝并清空。事件是“业务事实”的产物，不是 service 临时拼出来的。
- **同事务写 outbox**。`RepaymentService.receive(...)`（`@Transactional`）在更新
  account/statement/repayment 之后，`publishDomainEvents()` 调
  `RepaymentDomainEventPublisher.append(event)`，由 `RepaymentOutboxAdapter` 落 `outbox_events`。
  **业务行与 outbox 行在同一个 MySQL commit**——这就是 transactional outbox 的全部要点。
- **幂等重放不重复发事件**。同 `Idempotency-Key` 的重复请求走 `claimed=false && RECEIVED`
  分支**提前 return，不 append**。所以一个业务事实只产生一个 `eventId`。
- **adapter 手工拼 payload**。`RepaymentOutboxAdapter` 用 `objectMapper.createObjectNode()`
  逐字段写 `repaymentId/statementId/creditAccountId/amount/...`，**不直接序列化 domain record**。
  domain 字段改名不会无意改动 Kafka contract（这是 anti-corruption 的体现）。
- **OutboxEvent 是状态机**（`messaging/outbox/OutboxEvent.java`）：
  - 状态 `PENDING / PROCESSING / PUBLISHED / DEAD`，只读 getter，状态只能经
    `markProcessing / markPublished / markFailed / markProcessingTimedOut` 推进。
  - `next_attempt_at` 只做时间语义：PENDING 时是下次可发布时间，PROCESSING 时是 lease deadline；
    `lease_token` 是 finalize 时比较的 owner token。
  - `markFailed` 指数退避：`delay = min(2^(attempts-1), 60s)`，`1L << n` 防溢出；
    `attempts >= maxAttempts` 转 `DEAD`，阻断 poison message 无限重试。
  - `lastError` 在 domain 内截断到 500 字符，避免 DB 列长异常盖掉真正的失败原因。

### 1.3 Outbox dispatch：claim / publish / finalize / recover

复用了项目统一的 **claimable-job 并发模型**，拆成 4 个单一职责类：

| 类 | 职责 | 事务边界 |
|---|---|---|
| `OutboxPoller` | `@Scheduled(fixedDelay)` 轮询，提交 worker pool | 无（只调度） |
| `OutboxClaimer` | `FOR UPDATE SKIP LOCKED` 领取 PENDING，改 PROCESSING lease | **短事务** |
| `OutboxWorker` | publish 等 ack，再 finalize delivery state | publish 不开事务；finalize 独立**短事务** |
| `OutboxRecoverer` | 扫描 lease 超时的 PROCESSING，按一次失败处理 | 独立事务 |

关键正确性细节：
- **claim 与 publish 分离**。claim 在短事务里把 `PENDING->PROCESSING` 提交后，才把 event 交给
  `outboxWorkerExecutor`。**等 broker ack 期间不持任何 DB 行锁**——否则 broker latency 会被
  放大成 MySQL 行锁时间，拖垮连接池。
- **finalize 重校验 lease token**。`OutboxWorker.lockCurrentLease` 重新 `FOR UPDATE` 取行，
  校验 `status==PROCESSING && lease_token==claimed.lease_token`。
  迟到的老 worker 不能覆盖 recoverer/新 worker 已经推进的状态。
- **worker pool 拒绝也要 finalize**。`TaskRejectedException` -> `markRejectedForRetry`，
  否则 event 会卡在 PROCESSING 直到 recoverer 才可见。
- **开关保护**。`OutboxPoller`/`OutboxRecoverer` 都 `@ConditionalOnProperty(outbox.publisher.enabled)`，
  开发/迁移时可只写 outbox 行不实际发 Kafka。

### 1.4 Kafka adapter 与 envelope

- `IntegrationEvent`（`messaging/event`）= 稳定 envelope `{eventId, eventType, eventVersion, occurredAt, payload(JsonNode)}`。
  envelope 固定、payload 灵活：不为每种事件建一组 Java payload class。
- `KafkaOutboxMessagePublisher`：
  - `ProducerRecord(topicFor(eventType), partitionKey, payload)` + headers
    `eventId/eventType/eventVersion/aggregateType/aggregateId`（consumer 不反序列化 JSON
    就能路由/过滤/观测）。
  - `kafkaTemplate.send(record).get(timeout)` **同步等 ack** 后才让 outbox 标 PUBLISHED；
    fire-and-forget 会在 broker 实际失败时误判已发。
  - `InterruptedException` 恢复 interrupt flag，不吞 shutdown 信号。
  - `topicFor` 按 `eventType` 前缀路由到各 context topic，未知类型 fail-fast。
- `KafkaMessagingConfiguration`：每个 topic 3 partitions / 1 replica（本地单 broker），
  partitionKey 用聚合 id 保证**单聚合内有序**。

### 1.5 Consumer side：契约校验 + 双层幂等

- **每个 bounded context 一个 listener + 独立 consumer group + 独立 DLT**：
  `RepaymentLedgerListener`、`RepaymentNotificationListener`（以及 risk feature、
  card transaction 等）。互不影响、可独立扩缩和重放。
- `IntegrationEventReader.read(...)`：反序列化 + 集中校验 transport contract
  （`eventId/payload` 必填、`eventType` 必填、`eventVersion>=1`、
  **header 的 eventId 必须等于 payload 的 eventId**、eventType header 一致）。
  契约错误抛 `EventContractException`（永久失败）。`requiredText/requiredInstant`
  让坏格式表现为 contract failure，而不是底层 `DateTimeParseException`。
- **双层幂等**（消费侧最关键的部分）：
  1. **Inbox claim**（第一道）：`ConsumerInboxRepository.claim(consumerName, eventId)`，
     底层 `INSERT INTO consumer_inbox`，靠 `PRIMARY KEY(consumer_name, event_id)`
     判定“第一次消费”，`DuplicateKeyException -> false`。
  2. **业务唯一键**（第二道）：`ledger_entries` 对 source event 唯一、notification 唯一。
     挡住“绕过 inbox 的手工 replay / 补偿脚本”。
  - **inbox claim 与业务写在同一个 `@Transactional`**（见 `RecordLedgerEntryService.record`）。
    否则 claim 成功但业务写失败会“假装消费过”，造成丢消费。
- **错误处理与 DLT**（`KafkaMessagingConfiguration.listenerFactory`）：
  `DeadLetterPublishingRecoverer`（保留原 partition 便于排查/重放）+
  `DefaultErrorHandler(FixedBackOff(1000ms, 2次))`；
  **`addNotRetryableExceptions(EventContractException.class)`**——
  永久契约错误直进 DLT 不空转，瞬时错误才退避重试；`concurrency` 对齐 partition 数。

### 1.6 数据模型（已配套索引）

```sql
outbox_events(
  id PK, aggregate_type, aggregate_id, event_type, event_version,
  partition_key, payload JSON, status, attempts, next_attempt_at,
  created_at, published_at, last_error,
  INDEX idx_outbox_publishable (status, next_attempt_at, created_at),  -- 匹配 poller 查询
  CHECK status IN ('PENDING','PROCESSING','PUBLISHED','DEAD'),
  CHECK attempts >= 0
)
consumer_inbox(
  consumer_name, event_id, processed_at,
  PRIMARY KEY (consumer_name, event_id)  -- 去重键即主键
)
```

`idx_outbox_publishable` 的 `status` 做前导列，PENDING 行天然聚簇，PUBLISHED 堆积不拖慢
poller 的 `WHERE status='PENDING' AND next_attempt_at<=now ORDER BY created_at`。

---

## 第二部分：评价——优点与真实 gap

### 2.1 优点（可以照着讲一轮系统设计面）

1. **Transactional Outbox 落地正确**：领域事件 aggregate 缓冲 → 同事务写 outbox →
   幂等重放路径不重复 append。dual-write 问题从根上消除。
2. **投递复用统一并发模型**：claim/publish/finalize/recover 四段，等 ack 不持锁，
   lease + lease-token 重校验 + recoverer 兜底，指数退避 + DEAD。
3. **显式 at-least-once + 双层消费者幂等**：Inbox（通用）+ 业务唯一键（兜底），
   且 claim 与业务写同事务。语义自洽。
4. **envelope/contract 成熟**：稳定 envelope + JsonNode payload + 手工 ObjectNode 解耦 domain；
   header 与 payload 的 eventId 一致性校验是很多人想不到的防御。
5. **Kafka 层职责干净**：同步等 ack、按聚合 partition、per-context topic/group/DLT、
   **可重试 vs 不可重试异常分类**、concurrency 对齐 partition。
6. **schema 配套**：outbox 复合索引、inbox 去重主键、status/attempts CHECK 约束都到位。

### 2.2 真实 gap（只列主要的，不硬凑）

> 注意：以下都不是 bug，是“要上生产还差的工程化部分”。

1. **数据保留 / 清理缺失（最该补）**。
   `outbox_events` 的 PUBLISHED 行、`consumer_inbox` 的所有行**永不删除**。
   索引保证热路径快，但**存储无界增长**，且 inbox 会成为长期只增不减的大表。
   生产需要按 `published_at` / `processed_at` 滚动归档或分区裁剪 job。
   *面试几乎必问“outbox 表会不会爆”。*

2. **producer 侧 DEAD 是死胡同，缺可观测/重放**。
   消费侧有 DLT，但 outbox 事件转 `DEAD` 后只是躺在表里，**没有告警、指标或人工重放入口**。
   至少应有 `DEAD` 计数指标 + 一个把 DEAD 改回 PENDING 的运维路径。

3. **`eventVersion` 有字段但无消费侧版本协商**。
   envelope 带了 `eventVersion`，reader 只校验 `>=1`，**consumer 不按版本分支也不拒绝未知版本**。
   也就是说 schema evolution 目前是“约定”，不是“强制”。一旦发不兼容的 v2，
   字段恰好重叠时会被**静默误读**。要么引入 schema registry，要么 consumer 显式按
   `(eventType, eventVersion)` 路由 / 拒绝。

（顺带一提，跨 topic 无全局有序、polling 的秒级延迟，是**有意接受的取舍**而非 gap，
放到第四部分解释。）

---

## 第三部分：通用领域知识 / 设计模式 / 最佳实践

- **Dual-write problem（双写问题）**：跨“DB + 消息中间件”两个独立系统无法原子写。
  解法谱系：Transactional Outbox（本项目）、Listen-to-yourself、CDC(Debezium) 拖 binlog、
  事件溯源（event store 即真相）。
- **Transactional Outbox pattern**：把“要发的消息”作为业务事务的一部分写进同库的 outbox 表，
  再由独立发布器异步投递。本质是把“发消息的意图”持久化。
- **Polling Publisher vs Transaction Log Tailing(CDC)**：
  - Polling（本项目）：实现简单、自洽、可控开关；代价是轮询延迟 + 对 outbox 表的扫描压力（靠索引化解）。
  - CDC：低延迟、不轮询、不侵入业务表查询；代价是运维 connector、强依赖 binlog/格式、
    仍常需 outbox 表来携带干净的事件语义。
- **Delivery semantics（投递语义）**：
  - 端到端只能做到 **at-least-once**；跨 MySQL→Kafka→consumer 的副作用不存在真正的 exactly-once。
  - Kafka 的 EOS（幂等 producer + 事务）只覆盖**Kafka 内部**（producer→broker→__consumer_offsets），
    不覆盖“消费者去改 MySQL”这种外部副作用。
  - 工程上追求 **effectively-once = at-least-once 投递 + 幂等消费者**。
- **Idempotent Consumer / Inbox pattern**：用持久化去重表（消费者名 + 消息 id）保证重复投递只生效一次；
  关键是**去重写与业务写同事务**。
- **去重分层**：通用 Inbox（第一道）+ 业务自然键唯一约束（第二道）。前者挡正常重投，
  后者挡绕过 Inbox 的 replay/补偿。
- **Competing Consumers on a DB queue**：`SELECT ... FOR UPDATE SKIP LOCKED` 让多实例并发
  领取不同 outbox 行，无需额外队列中间件。
- **Lease / visibility timeout**：PROCESSING 是租约不是所有权；worker 宕机后租约到期，
  recoverer 放回重试。lease-token 防迟到 worker 覆盖新状态（乐观并发的一种）。
- **Poison message & DLT**：退避重试 N 次仍失败 → 死信；永久错误（坏 JSON、不兼容 schema）
  **不重试直接死信**。死信要可观测、可重放。
- **Ordering（有序性）**：Kafka 只保证**同 partition 内有序**；partitionKey=聚合 id ⇒ 单聚合有序。
  跨 partition / 跨 topic 无序；`concurrency>1` 不破坏单 partition 顺序，但 **DLT 会**
  （失败那条被旁路，同 key 后续继续）。
- **Schema evolution & Tolerant Reader**：envelope 稳定 + payload 宽松解析；
  向后兼容加字段、破坏性变更升 `eventVersion` 或新 `eventType`。
- **Choreography vs Orchestration**：本项目是**编排式 choreography**——主事务只发事实，
  下游各 context 自己决定怎么反应（ledger 记账、notification 通知、risk 更新特征），
  没有中心 saga 协调器。优点解耦，代价是流程散落、端到端可观测性更难。
- **Anti-Corruption Layer**：outbox adapter 手工映射 domain→integration event，
  domain 改名不破契约。

---

## 第四部分：面试问答（含硬核追问 + 强补充）

### Q1. 为什么不在业务事务里直接 `kafkaTemplate.send()`？
**答**：dual-write。`MySQL commit 成功 / Kafka 失败 → 业务发生但事件丢`；
`Kafka 成功 / MySQL 回滚 → 下游看到从未发生的决策`。Outbox 把“发消息的意图”和业务状态
放进**同一个 commit**，再异步投递。
- **硬核追问：那我用 `@TransactionalEventListener(AFTER_COMMIT)` 发不就行了？**
  仍是 best-effort：commit 之后、send 之前进程挂掉，事件**永久丢失**，没有持久重试来源。
  outbox 的价值正是“意图落库 + 可重放”。
- **强补充**：Outbox 也避免了在主事务里同步等 broker，主交易延迟与可用性不被 Kafka 绑架。

### Q2. 这套的投递语义是什么？能做到 exactly-once 吗？
**答**：端到端 **at-least-once**。`OutboxEvent` 注释直说“Kafka ack 后、finalize commit 前
宕机会重发”，所以下游必须幂等。
- **硬核追问：Kafka 不是有 exactly-once（EOS）吗？**
  EOS 只保证 Kafka 内部（幂等 producer 防 broker 重复、事务绑定 send 与 offset 提交）。
  一旦消费者要去改 MySQL/发通知这种**外部副作用**，跨系统就回到 at-least-once，
  必须靠**消费者幂等**收口成 effectively-once。
- **强补充**：我们没用 Kafka 事务，因为 outbox 已经解决可靠性，再叠 EOS 收益小、复杂度高。

### Q3. 消费者幂等具体怎么做？有了 Inbox 为什么还要业务唯一键？
**答**：Inbox（`consumer_inbox` 唯一键 INSERT-claim）是**通用第一道**：同一
`(consumer_name, eventId)` 只处理一次。业务唯一键（ledger 分录、notification）是**第二道**，
挡住绕过 Inbox 的手工 replay / 补偿脚本。
- **硬核追问：Inbox claim 和业务写不在一个事务会怎样？**
  claim 成功但业务写失败/回滚 → 下次重投时 Inbox 认为“已消费”直接跳过 → **丢消费**。
  所以两者必须同事务。
- **硬核追问：consumer_inbox 会无限增长，怎么办？**（这正是 gap #1）
  按 `processed_at` 滚动删除/分区裁剪；或只保留一个去重窗口 + 依赖业务键兜底窗口外的重复。
- **强补充**：去重键必须是**业务幂等键**（这里是 `eventId`），不是 Kafka offset——offset 会随
  topic 重建/迁移变化。

### Q4. 为什么 claim 和 publish 要分成两段事务？
**答**：claim 是短事务（`PENDING->PROCESSING` 提交即放锁），publish 等 broker ack 时**不持任何行锁**。
否则 broker latency 会被放大成 MySQL 行锁持有时间，高峰期连接池和锁会一起爆。
- **硬核追问：那 publish 成功但 finalize 事务失败呢？**
  消息已进 Kafka，但 outbox 行没标 PUBLISHED（仍 PROCESSING）→ recoverer 到期后放回 PENDING →
  **重发**。这又落回 at-least-once，消费者幂等兜底。完全自洽。

### Q5. PROCESSING lease 的意义？为什么不用 `next_attempt_at` 当 lease token？
**答**：PROCESSING 是租约不是永久所有权。worker 宕机后 `OutboxRecoverer` 在
`next_attempt_at <= now` 时把行按一次失败处理放回重试。finalize 前
`lockCurrentLease` 校验 `status==PROCESSING && lease_token` 未变，防止**迟到的老 worker**
把别人已推进的状态改回 PUBLISHED。`next_attempt_at` 不再兼任 token，因为 timestamp 有
Java `Instant` vs MySQL `TIMESTAMP(6)` 精度边界。
- **硬核追问：两个 worker 同一 `now` claim 同一行，token 会撞吗？**
  不会。claim 在短事务里 `FOR UPDATE SKIP LOCKED`，对同一行是串行化的，只有一个 winner 写 lease；
  且 token 是**按行**比较，不同行互不影响。
- **硬核追问：lease 到期但老 worker 其实还活着、ack 也回来了，会双发吗？**
  会。recoverer 已放回 PENDING → 另一个 worker 重发 → Kafka 里两条。这是 at-least-once 的正常表现，
  消费者 Inbox 去重。**lease 解决的是“卡死可见性”，不是“绝不重复”。**

### Q6. 顺序性怎么保证？什么情况下会乱序？
**答**：partitionKey = 聚合 id（如 `creditAccountId`），保证**同一聚合的事件进同一 partition、有序**。
- **硬核追问：consumer `concurrency=3` 会破坏顺序吗？**
  不会。concurrency 只是同 group 内多线程**各吃不同 partition**，单 partition 仍单线程有序。
- **硬核追问：那 DLT 呢？**
  会**局部乱序**：某条重试耗尽进 DLT 后，同 key 的后续消息继续处理（跳过了失败那条）。
  本项目 ledger 是 append-only 独立分录，不依赖顺序，所以无害；若是“按序推导余额”的有状态投影，
  就要靠 DLT 重放补偿或暂停该 key。
- **强补充**：跨 topic（ledger 同时消费 transaction-events 和 repayment-events）**无全局有序**，
  这是有意接受的取舍——投影按事实独立记账即可。

### Q7. outbox 表越来越大，poller 会不会变慢？
**答**：`idx_outbox_publishable(status, next_attempt_at, created_at)`，`status` 前导让
PENDING 行聚簇，PUBLISHED 堆积不影响 PENDING 扫描。
- **硬核追问：那存储无限涨呢？**（gap #1）
  需要归档 job：把 PUBLISHED 行按 `published_at` 滚动迁到冷表/删除。这是当前缺的工程化部分。
- **强补充**：也可以给 outbox 表做时间分区（按天），直接 `DROP PARTITION` 比 `DELETE` 更省。

### Q8. 为什么 payload 用 `JsonNode` 而不是强类型 DTO？
**答**：envelope 稳定 + payload 灵活（Tolerant Reader）。避免为每种事件建一组 payload class、
也避免版本演进僵化、避免 consumer 耦合自己不关心的字段。
- **硬核追问：那字段写错/缺失谁来兜？**
  `IntegrationEventReader.requiredText/requiredInstant` 显式校验，缺字段抛 `EventContractException`
  → 直接 DLT（不重试）。把“契约错误”和“瞬时错误”分开处理。
- **强补充**：代价是没有编译期类型安全；规模再大时应上 schema registry（Avro/Protobuf）兼顾灵活与约束。

### Q9. 如何演进 event schema 而不炸下游？
**答**：向后兼容地**加字段**；破坏性变更升 `eventVersion` 或引入新 `eventType`，
让新老 consumer 并存过渡。
- **硬核追问：你这套现在能强制版本兼容吗？**
  **不能**（gap #3）。`eventVersion` 只被校验 `>=1`，consumer 不按版本分支也不拒绝未知版本。
  要补：consumer 端按 `(eventType, eventVersion)` 路由 / 显式拒绝不支持的版本，或上 schema registry。

### Q10. 既然有 CDC（Debezium），为什么还自己写 polling outbox？
**答**：polling outbox 实现简单、行为自洽、带开关可控，秒级延迟对账单/通知足够。
CDC 低延迟、不轮询、不侵入业务表查询，但要运维 connector、强依赖 binlog 配置与格式，
且通常**仍需 outbox 表**来携带干净的事件语义（否则下游要理解业务表结构）。
- **强补充**：演进路径——先 polling outbox 跑通语义，规模/延迟要求上来后，把“轮询发布器”
  换成“CDC 拖 outbox 表”，**消费者侧完全不用改**（envelope/幂等不变）。这就是分层抽象的价值。

### Q11. 为什么 topic / listener 按 source context 拆，而不是一个大 topic？
**答**：每个 bounded context 独立 group + 独立 DLT + 独立 concurrency，**故障与扩缩互不影响**，
也便于“只重放某个投影”。一个大 topic 会让 ledger 的失败和 notification 的扩缩互相牵连。
- **强补充**：路由放在 Kafka adapter（`topicFor` 按 eventType 前缀），outbox 本体保持通用、不知道 topic。

---

## 第五部分：一句话总结

这套 messaging 把 **Transactional Outbox + claim-lease-recover 投递 + at-least-once 与双层幂等 +
envelope 版本化 + per-context DLT 与可重试分类** 全部做对且自洽，是项目里最“可面试”的部分。
真正待补的工程化只有三点：**(1) outbox/inbox 数据保留清理、(2) producer 侧 DEAD 的可观测与重放、
(3) eventVersion 的消费侧强制协商**。把这三块补上即生产级。
