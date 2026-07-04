# Notification 机制分层梳理与类缩减评估

> 关键词：Notification, notification intent, per-channel delivery, Inbox idempotency,
> claimable job, `FOR UPDATE SKIP LOCKED`, `PROCESSING` lease, retry/backoff,
> `DEAD`, Resilience4j, provider idempotency, effectively-once, 配信(はいしん),
> 通知(つうち)。

> [!NOTE]
> 这份文档保留为“重构前的分层 review 与取舍记录”。当前代码已经采纳更激进的精简方案：
> `NotificationDeliveryWorker -> NotificationDeliverySender(push/email) -> NotificationProviderClient(Feign)
> -> SimulatedNotificationProviderController`，
> 并删除了 `NotificationRecipientResolver`、`NotificationTemplateRenderer`、`ResilientNotificationSender`、
> `NotificationDispatch`、`ProviderReceipt` 和通知专用 `TimeLimiter` 线程池。当前实现细节以
> [notification-delivery-design-cn.md](notification-delivery-design-cn.md) 为准。

这份文档回答三个问题：

1. 当前 `notification` 模块到底怎么跑？
2. 哪些类是为了表达可靠性边界而值得保留，哪些只是学习项目里的包装？
3. 如果要缩减类，缩减后会变成什么样，代价是什么？

> [!IMPORTANT]
> 本文原按当时代码事实写，不按更早历史设计或未来理想图写。后续压平 sender 层后，当前实现以
> `notification-delivery-design-cn.md` 和代码为准；本段以下仍可作为理解旧分层取舍的 review 记录。
> 当前实现里
> `NotificationDelivery` 已经有独立 `lease_token` 字段。`nextAttemptAt`
> 表达 PENDING 的下次可投递时间、PROCESSING 的 lease deadline；
> `leaseToken` 表达本轮 worker ownership。worker finalize 时比较
> `status == PROCESSING && leaseToken 未变`，防止迟到 worker 覆盖新 worker 或 recoverer 的结果。

---

## 1. 一句话总览

当前 Notification 不是同步发消息，也不是在业务事务里直接调 push/email。

它是两段异步：

```text
业务事实
  -> Outbox
  -> Kafka
  -> XxxNotificationListener
  -> RequestNotificationService
  -> notifications intent + notification_deliveries work rows
  -> NotificationDeliveryPoller/Worker/Recoverer
  -> simulated push/email provider
```

核心思想：

- `Notification` 只表达“应该通知谁、因为什么业务事实、发哪种通知”。
- `NotificationDelivery` 才表达“某一个渠道的投递生命周期”。
- Kafka 到 Notification 的入口靠 Inbox 做 consumer-side `idempotency`。
- Provider 调用在 DB 事务外；结果 finalize 时再开短事务并重新校验 lease。
- 对外部 provider 只能做到 `at-least-once + provider idempotency key`，也就是 practical
  `effectively-once`，不是严格 exactly-once。

---

## 2. 当前包结构怎么读

### 2.1 `notification/infrastructure/messaging`

这一层是 Kafka inbound adapter。

当前有四个 listener：

| Listener | Topic | 关心的 event | 生成的 NotificationType |
| --- | --- | --- | --- |
| `AuthorizationNotificationListener` | `authorization-events` | `authorization.approved`, `authorization.declined` | `AUTHORIZATION_APPROVED`, `AUTHORIZATION_DECLINED` |
| `CardTransactionNotificationListener` | `transaction-events` | `card_transaction.posted` | `CARD_TRANSACTION_POSTED` |
| `StatementNotificationListener` | `statement-events` | `statement.closed` | `STATEMENT_CLOSED` |
| `RepaymentNotificationListener` | `repayment-events` | `repayment.received` | `REPAYMENT_RECEIVED` |

这些类不做投递，不做模板，不查收件人。它们只做一件事：

```text
IntegrationEvent payload
  -> RequestNotificationCommand(sourceEventId, subjectType, subjectId, recipientKey, type)
```

为什么分成四个 listener 而不是一个万能 listener：

- 好处：source context 清楚，`card_transaction.posted` 不会和 `authorization.posted` 混淆。
- 好处：每个 listener 先判断 `eventType`，合法但无关的 future event 会被跳过，不会误入 DLT。
- 代价：类数量增加，四个类有重复样板。

### 2.2 `notification/application`

这一层是 use case 和 durable worker 编排。

| 类 | 当前职责 | 是否核心 |
| --- | --- | --- |
| `RequestNotificationService` | Inbox claim、创建 `Notification` intent、按渠道扇出 `NotificationDelivery` | 核心 |
| `RequestNotificationCommand` | listener 到 service 的 transport-neutral command | 可保留 |
| `NotificationDeliveryClaimer` | 短事务领取 PENDING delivery，打 PROCESSING lease | 核心 |
| `NotificationDeliveryWorker` | 事务外调 provider，短事务 finalize | 核心 |
| `NotificationDeliveryProperties` | typed config + fail-fast 校验 | 可保留 |

### 2.3 `notification/domain`

这一层表达业务概念。

| 类 | 当前职责 | 判断 |
| --- | --- | --- |
| `Notification` | 不可变 intent；由 integration event 创建 | 合理 |
| `NotificationType` | 用户可见通知类型 | 合理 |
| `NotificationSubjectType` | 被通知的业务对象类型 | 合理 |
| `NotificationRepository` | intent 持久化端口 | 合理 |

`Notification` 没有 `status`、`attempts`、`sentAt`。这是有意为之：一条通知可能同时扇出 push/email，
push 成功但 email 失败时，单个 status 无法表达。

### 2.4 `notification/domain/delivery`

这一层表达 per-channel delivery。

重要类：

| 类 | 当前职责 | 判断 |
| --- | --- | --- |
| `NotificationDelivery` | 每个 channel 一条 work row，状态机 owner | 核心 |
| `NotificationDeliveryStatus` | `PENDING/PROCESSING/SENT/DEAD` | 核心 |
| `NotificationDeliveryRepository` | delivery 持久化端口 | 核心 |
| `NotificationChannel` | `APP_PUSH/EMAIL` | 合理 |
| `NotificationRecipientResolver` | recipientKey -> 联系方式和渠道偏好 | 可保留，未来 User 域接缝 |
| `NotificationTemplateRenderer` | `(type, channel, subjectId)` -> 标题正文 | 可缩减 |
| `NotificationSender` | worker 依赖的发送门面 | 可缩减但不建议优先 |
| `NotificationChannelSender` | 单渠道 provider 调用端口 | 合理 |
| `NotificationDispatch` / `NotificationContent` / `ProviderReceipt` | provider 调用 DTO | 可缩减 |

### 2.5 `notification/infrastructure/delivery`

这一层是技术机制和模拟 provider。

| 类 | 当前职责 | 判断 |
| --- | --- | --- |
| `NotificationDeliveryPoller` | `@Scheduled` poll + submit worker pool | 核心 |
| `NotificationDeliveryRecoverer` | 扫描 lease 超时的 PROCESSING rows | 核心 |
| `ResilientNotificationSender` | 按 channel 路由并套 TimeLimiter/CircuitBreaker/Retry | 偏重但有学习价值 |
| `DefaultNotificationTemplateRenderer` | switch 写死文案 | 可缩减 |
| `StubNotificationRecipientResolver` | 无 User 域时合成 email/push token | 可保留 |
| `SimulatedChannelSender` | 模拟 provider latency/failure/idempotency | 学习价值高 |
| `SimulatedPushNotificationSender` / `SimulatedEmailNotificationSender` | 两个薄 wrapper | 可缩减 |
| `NotificationDeliveryConfiguration` | sender executor 配置 | 可保留 |

### 2.6 `notification/infrastructure/mybatis`

这一层是 MyBatis persistence adapter。

| 类 | 当前职责 |
| --- | --- |
| `MyBatisNotificationRepository` / `NotificationMapper` / `NotificationRow` | `notifications` intent 写入 |
| `MyBatisNotificationDeliveryRepository` / `NotificationDeliveryMapper` / `NotificationDeliveryRow` | delivery 查询、claim、recover、状态更新 |

这层看起来文件多，但符合项目里 MyBatis 的一贯风格：domain object、row DTO、mapper interface、XML SQL 分开。
如果强行压缩，会省文件，但 SQL/row/domain 转换会混在一起。

---

## 3. 端到端链路

### 3.1 从业务事件到通知意图

```text
Outbox worker 发布 Kafka event
  -> XxxNotificationListener.on...(ConsumerRecord)
  -> IntegrationEventReader.read(record)
  -> 判断 eventType
  -> new RequestNotificationCommand(...)
  -> RequestNotificationService.request(command)
```

`RequestNotificationService.request()` 的事务边界很关键：

```text
@Transactional
1. consumer_inbox.claim("notification-v1", sourceEventId)
2. Notification.requestFromEvent(...)
3. notifications.insertIfAbsent(source_event_id unique)
4. recipientResolver.resolve(recipientKey)
5. 每个 channel 创建 NotificationDelivery.pendingFor(...)
6. notification_deliveries.insertAll(deliveries)
```

如果第 1 步失败，说明 Kafka redelivery 或 offset replay 重复投递，直接返回。

如果第 3 步重复，说明 source_event_id 唯一键兜底命中，也直接返回，不创建第二批 delivery。

### 3.2 从 delivery 到 provider

```text
NotificationDeliveryPoller
  -> NotificationDeliveryClaimer.claimDispatchableDeliveries()
       SELECT PENDING WHERE next_attempt_at <= now
       FOR UPDATE SKIP LOCKED
       markProcessing(now, timeout, leaseToken)
       update row
       commit
  -> worker pool execute(NotificationDeliveryWorker.handleClaimedDelivery)
```

Worker 处理：

```text
1. resolver.resolve(recipientKey)
2. addressFor(channel)
3. renderer.render(notificationType, channel, subjectId)
4. resilientSender.send(NotificationDispatch)
5. 短事务 findByIdForUpdate(deliveryId)
6. 校验 status == PROCESSING && leaseToken == claimed.leaseToken
7. 成功 markSent；失败 markFailed；update row
```

如果 worker pool queue 满，poller 捕获 `TaskRejectedException`，调用 `markRejectedForRetry`，把 row 放回 retry/DEAD，
避免一直卡到 lease timeout。

### 3.3 Recoverer 做什么

```text
NotificationDeliveryRecoverer
  -> SELECT PROCESSING WHERE next_attempt_at <= now
     FOR UPDATE SKIP LOCKED
  -> markProcessingTimedOut(...)
  -> PENDING retry or DEAD
```

没有 recoverer 的话，pod 在 provider 调用中途宕机，delivery 会永久停在 `PROCESSING`。

---

## 4. 当前状态机

```text
PENDING
  -- claim / short tx -->
PROCESSING
  -- provider receipt + lease recheck -->
SENT

PROCESSING
  -- provider error / timeout / circuit open -->
PENDING(nextAttemptAt = now + exponential backoff)

PROCESSING
  -- attempts >= maxAttempts -->
DEAD

PROCESSING
  -- worker crashed and lease deadline passed -->
PENDING or DEAD by recoverer
```

当前默认配置：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `notification.delivery.enabled` | `true` | 开启 delivery poller/recoverer |
| `fixed-delay-ms` | `1000` | poller 扫描间隔 |
| `recovery-fixed-delay-ms` | `5000` | recoverer 扫描间隔 |
| `batch-size` | `50` | 单轮 claim 数 |
| `processing-timeout-seconds` | `30` | PROCESSING lease deadline |
| `max-attempts` | `8` | 超过后 DEAD |
| `worker-pool-size` | `4` | delivery worker pool |
| `sender-threads` | `4` | provider call executor |

---

## 5. 幂等边界

### 5.1 Kafka event -> Notification intent

强幂等，靠两道门：

1. `consumer_inbox`：`notification-v1 + sourceEventId` 只处理一次。
2. `notifications.source_event_id` unique：即使 Inbox 失效或迁移异常，也挡住重复 intent。

### 5.2 Notification intent -> per-channel delivery

强幂等，靠同事务 fan-out 和 `UNIQUE(notification_id, channel)`。

也就是说，一条通知对一个渠道最多一条 delivery。

### 5.3 Delivery -> external provider

不能严格 exactly-once，只能 practical `effectively-once`。

崩溃窗口：

```text
worker 调 provider 成功，provider 已经发出 push/email
worker 在 markSent 事务提交前宕机
recoverer 之后把 delivery 放回 PENDING
新 worker 再次调 provider
```

这个窗口无法用本地 MySQL transaction 关闭，因为 provider side effect 不属于 MySQL。

当前做法：

- `NotificationDelivery.idempotencyKey()` 返回 delivery id。
- `NotificationDispatch` 把 idempotency key 传给 sender。
- `SimulatedChannelSender` 用内存 map 模拟 provider 按 idempotency key 去重。

生产含义：真实 provider 要支持 idempotency key；不支持时只能接受极低概率重复，或自己做 provider-call ledger，
但 provider-call ledger 也不能彻底消灭“provider 成功但本地未提交”的窗口。

---

## 6. 当前设计的优点

### 6.1 意图和投递拆开是对的

`Notification` 是 intent，`NotificationDelivery` 是 per-channel work row。

这能表达：

```text
同一条 statement.closed 通知：
  APP_PUSH -> SENT
  EMAIL    -> PENDING / DEAD
```

如果只有一个 `notifications.status`，这个状态无法表达。

### 6.2 和 Outbox/DelayJob 的 mental model 一致

Notification delivery 复用了项目里的 claimable-job 心智模型：

```text
poller -> claimer(short tx + SKIP LOCKED) -> worker(side effect outside tx)
       -> finalize(short tx + lease recheck) -> recoverer
```

这是很好的 interview 解释素材。

### 6.3 Provider 调用没有放进 DB 事务

这是正确性和性能都很重要的边界。

如果把 provider HTTP 调用放进 `@Transactional`，慢 provider 会把 DB connection 和 row lock 长时间占住，
最后可能拖垮 Hikari pool。

### 6.4 Resilience4j 和 durable retry 分工清楚

`ResilientNotificationSender` 做一次投递尝试内的快速 timeout/retry/circuit breaker。

`NotificationDelivery` 状态机做跨 JVM、跨重启的 durable retry/backoff/DEAD。

这两层不是重复：

| 层 | 生命周期 | 解决的问题 |
| --- | --- | --- |
| Resilience4j Retry | 单次 worker 调用内 | provider 瞬时抖动 |
| Delivery retry | DB 持久化，跨重启 | pod 宕机、持续故障、人工可见 DEAD |

---

## 7. 当前设计的偏重点和风险

### 7.1 类数量偏多

只为模拟 push/email，当前已经有：

- recipient port + recipient record + stub resolver
- template port + content record + default renderer
- sender facade + channel sender port + dispatch record + receipt record
- resilient sender + simulated sender base + push/email wrappers

这对生产演进友好，但对学习者第一眼会显得“通知还没接真实 provider，类却很多”。

### 7.2 lease ownership 已经显式化

当前代码已经把 lease 的两个维度拆开：

- `nextAttemptAt`：PENDING 的 next retry time；PROCESSING 的 lease deadline
- `leaseToken`：本轮 claim 的 owner identity，worker finalize 只认这个 token

这比早期“用 timestamp 兼职 token”的写法更干净：

```text
PENDING:    next_attempt_at = retry time, lease_token = null
PROCESSING: next_attempt_at = lease deadline, lease_token = UUID
finalize:   WHERE id = ? AND status = PROCESSING AND lease_token = ?
```

Outbox、DelayJob、NotificationDelivery 共享这个 lightweight claimable job 模型。
StatementJob 更重一些：保留 `claimed_by / claimed_at / claim_until`，并额外用
`claim_token` 做 finalize ownership check。

### 7.3 `StatementNotificationListener` 是否必要，要和 statement event 路径一起判断

当前代码存在 `statement.closed -> StatementNotificationListener -> STATEMENT_CLOSED`。

如果 statement event path 保留，它合理；如果未来再次简化 statement，不发 `statement.closed` 了，
就应该连 listener、topic wiring、测试一起清掉，避免死 consumer。

---

## 8. 类缩减建议

### 8.1 不建议缩减的核心类

这些类承载关键边界，缩掉会让机制变糊：

| 类 | 为什么保留 |
| --- | --- |
| `Notification` | intent 和 delivery 分离的核心 |
| `NotificationDelivery` | per-channel 状态机核心 |
| `RequestNotificationService` | Inbox + intent + fan-out 的 transaction boundary |
| `NotificationDeliveryClaimer` | claim 短事务边界清楚 |
| `NotificationDeliveryWorker` | provider side effect outside tx + finalize 短事务 |
| `NotificationDeliveryPoller` | scheduler 只 poll/submit，不做业务 |
| `NotificationDeliveryRecoverer` | 可靠性闭环，防 PROCESSING 永久卡死 |
| MyBatis mapper/XML/row/repository | 项目风格一致，SQL 明确 |

### 8.2 可以缩减：四个 listener 的重复样板

当前：

```text
AuthorizationNotificationListener
CardTransactionNotificationListener
StatementNotificationListener
RepaymentNotificationListener
```

可缩减方案：

```text
NotificationEventMapping
  eventType
  subjectType
  subjectIdField
  recipientKeyField
  notificationType

GenericNotificationListener
  每个 topic 一个 @KafkaListener 方法
  调 common handle(record, mappings)
```

缩减后：

- 少一些重复 `eventReader.requiredText(...)`。
- 新增 event 只加 mapping。

代价：

- 当前 source-specific listener 名字很直观，能直接看出 bounded context。
- mapping 配错时不如显式代码容易 review。
- `authorization.approved/declined` 共享 helper、`statement/repayment` 的 recipientKey caveat 会藏进配置式映射里。

建议：**暂时不缩。**
如果 listener 增加到 8 个以上，再抽 common helper，不急着 generic。

### 8.3 可以缩减：模板端口

当前：

```text
NotificationTemplateRenderer interface
DefaultNotificationTemplateRenderer implementation
NotificationContent record
```

可缩减方案：

```text
NotificationDeliveryWorker
  private NotificationContent render(...)
```

或：

```text
DefaultNotificationTemplateRenderer
  不要 interface，worker 直接依赖 concrete class
```

缩减后：

- 少一个 port。
- 对当前硬编码英文文案足够。

代价：

- 未来接模板引擎、多语言、AB 文案时要重新抽。
- Worker 会同时承担 delivery state machine 和 copywriting/template 责任。

建议：**如果目标是降低类数，可以缩这个；如果目标是保留未来模板扩展接缝，可以不动。**
它不是核心可靠性类，缩不缩都不影响机制正确性。

### 8.4 可以缩减：`NotificationSender` facade

当前：

```text
NotificationSender
NotificationChannelSender
ResilientNotificationSender implements NotificationSender
```

可缩减方案：

```text
NotificationDeliveryWorker -> ResilientNotificationSender
ResilientNotificationSender -> List<NotificationChannelSender>
```

缩减后：

- 少一个 interface。
- 当前只有一个 facade 实现，测试也可直接 mock concrete class。

代价：

- Worker 对 infrastructure class 命名更敏感。
- 和 `OutboxWorker -> OutboxMessagePublisher` 的端口形状不再对称。

建议：**可以缩，但优先级不高。**
它是“为了对称和测试边界”的轻包装，不是明显坏味道。

### 8.5 可以缩减：`NotificationDispatch` / `ProviderReceipt`

当前 sender 入参和出参是小 record：

```text
NotificationDispatch(channel, recipientAddress, content, idempotencyKey)
ProviderReceipt(providerMessageId)
```

可缩减方案：

```text
send(channel, address, content, idempotencyKey) -> String providerMessageId
```

缩减后：

- 少两个 record。
- 代码更短。

代价：

- 参数顺序更容易错。
- `ProviderReceipt` 这个名字强调“拿到 provider ack 后才能 mark SENT”，学习表达更清楚。
- 未来 provider 返回更多字段时又要加对象。

建议：**不急着缩。**
这两个 record 很小，但语义很干净。

### 8.6 可以缩减：push/email 两个模拟 sender wrapper

当前：

```text
SimulatedChannelSender
SimulatedPushNotificationSender
SimulatedEmailNotificationSender
```

可缩减方案：

```text
@Configuration
NotificationChannelSender pushSender(...) { return new SimulatedChannelSender(APP_PUSH, "push", properties); }
NotificationChannelSender emailSender(...) { return new SimulatedChannelSender(EMAIL, "email", properties); }
```

缩减后：

- 少两个很薄的 class。

代价：

- 具体 sender 从 class 变成 bean factory，初学者不一定更容易找。
- 以后 push/email 真实实现差异变大时，又要拆回 class。

建议：**可以缩，但收益很小。**
如果你想降低文件数量，这是最安全的一刀。

### 8.7 可以缩减：`NotificationRecipientResolver` 是否要 port

当前：

```text
NotificationRecipientResolver interface
StubNotificationRecipientResolver implementation
NotificationRecipient record
```

可缩减方案：

```text
RequestNotificationService 或 Worker 内硬编码：
recipientKey -> push-token/email
```

缩减后：

- 少一个 port 和 stub 实现。

代价：

- 当前项目没有 User/Customer 聚合，recipient 解析正是一个真实缺口。
- 把缺口藏进 service/worker，会让未来接 customerId、渠道偏好、退订设置时更难改。

建议：**不缩。**
这是少数“虽然当前是假实现，但边界非常合理”的接口。

---

## 9. 如果按“最小但还正确”来缩，最终形态是什么

一个克制版可以长这样：

```text
notification/
  application/
    RequestNotificationService
    RequestNotificationCommand
    NotificationDeliveryWorker
    NotificationDeliveryClaimer
    NotificationDeliveryProperties

  domain/
    Notification
    NotificationType
    NotificationSubjectType
    NotificationRepository
    delivery/
      NotificationDelivery
      NotificationDeliveryStatus
      NotificationDeliveryRepository
      NotificationChannel
      NotificationRecipientResolver
      NotificationRecipient
      NotificationChannelSender

  infrastructure/
    messaging/
      四个 source-specific listeners 先保留
    delivery/
      NotificationDeliveryPoller
      NotificationDeliveryRecoverer
      ResilientNotificationSender
      StubNotificationRecipientResolver
      SimulatedChannelSender
      NotificationDeliveryConfiguration
      DefaultNotificationTemplateRenderer  # 可 concrete，不必 interface
    mybatis/
      保持现状
```

具体可删/合并：

- 删除 `NotificationSender` interface，让 worker 直接依赖 `ResilientNotificationSender`。
- 删除 `NotificationTemplateRenderer` interface，让 worker 或 concrete renderer 直接使用。
- 合并 `SimulatedPushNotificationSender` / `SimulatedEmailNotificationSender` 到 config factory。

这会减少约 3-4 个小类。

但不会动：

- intent/delivery 分离
- Inbox idempotency
- poller/claimer/worker/recoverer
- per-channel delivery row
- provider idempotency key

也就是说，**缩外围，不缩可靠性骨架**。

---

## 10. 我建议怎么取舍

### 当前最推荐：暂时不动代码，先消化

理由：

- Notification 机制已经能作为“外部副作用可靠投递”的学习样本。
- 类多主要多在扩展接缝，不是在核心链路里乱套。
- 真正值得你先搞清楚的是状态机和事务边界，而不是马上删文件。

### 如果你想瘦身，第一批只动外围

优先顺序：

1. 合并 `SimulatedPushNotificationSender` / `SimulatedEmailNotificationSender`。
2. 去掉 `NotificationTemplateRenderer` interface，保留 concrete renderer。
3. 评估去掉 `NotificationSender` interface。

不要第一批就动：

- `NotificationDeliveryClaimer`
- `NotificationDeliveryWorker`
- `NotificationDeliveryRecoverer`
- `NotificationDeliveryRepository`
- `NotificationRecipientResolver`

### 可靠性表达已经到位，不建议继续加机制

`lease_token` 已经把 ownership 说清楚了：claim 时生成 UUID，finalize 时比较 token，
recoverer 仍按 `next_attempt_at <= now` 扫描超时 PROCESSING row。此时再往 Notification 加更多
调度字段、分布式锁或二级队列，收益会很低。

后续如果要动，优先是删外围包装或补真实 provider adapter；不要再扩展可靠性骨架。

---

## 11. interview 口径

可以这样讲：

> Notification 在这个项目里不是同步副作用。上游业务只产生 integration event，经 Outbox/Kafka 到
> Notification listener；listener 只翻译成 `RequestNotificationCommand`。`RequestNotificationService`
> 用 Inbox 保证 consumer idempotency，然后在一个本地事务里创建 notification intent 和 per-channel
> delivery rows。真正调 push/email provider 是后台 claimable job：短事务 claim，事务外调用 provider，
> finalize 时重新锁 row 并校验 lease，失败退避重试，超过次数进 DEAD。Provider side effect 不能和 MySQL
> 做一个本地事务，所以端到端不是严格 exactly-once，而是 at-least-once 加 provider idempotency key 去重，
> 逼近 effectively-once。

常见追问：

| 追问 | 回答要点 |
| --- | --- |
| 为什么不在业务事务里直接发通知？ | 避免 provider 失败/慢调用 rollback 或拖住核心金融事务；通知失败可异步 retry/DLT |
| 为什么 `Notification` 不直接有 `SENT/FAILED`？ | 多渠道投递状态不同，一条 intent 对多条 delivery |
| 为什么需要 Inbox？ | Kafka at-least-once，offset replay 会重复投递；Inbox 让同 eventId 只创建一次 intent |
| 为什么 provider 仍可能重复？ | provider side effect 和 MySQL 状态更新不在同一事务；崩溃窗口不可消灭 |
| 怎么降低重复影响？ | delivery id 作为 provider idempotency key，下游按键去重 |
| 为什么要 recoverer？ | worker/pod 在 PROCESSING 后宕机，否则 row 永远不可见 |
| 现在最可缩的地方？ | 模板接口、sender facade、模拟 push/email wrapper；不要缩 claim/worker/recoverer 骨架 |

---

## 12. 最终判断

当前 Notification 机制整体是**偏完整、偏重，但有学习价值**。

它适合学习：

- `Outbox -> Kafka -> Inbox` 的异步边界
- external side effect 不进 DB transaction
- claimable job 的短事务 claim / worker / recoverer
- `at-least-once` 和 provider idempotency 的真实 trade-off
- 为什么 intent 和 per-channel delivery 要拆开

它不适合继续无限加功能。

当前最好的路线是：

```text
先消化当前机制
  -> 再决定是否缩外围类
  -> 不要动可靠性骨架
  -> lease_token 已经补齐，后续重点是少加功能、讲清 trade-off
```
