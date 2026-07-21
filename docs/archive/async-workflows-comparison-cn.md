# 异步流程横向对比：定时任务、Outbox、Kafka Consumer

> **归档对齐说明（2026-07）**：本文正文已经按当前类、表和配置逐段更新。
> Statement 不再是“无 durable queue 的日历扫描”，而是 reconciliation planner + sharded
> claimable jobs；Notification 也有独立的 per-channel delivery queue。Kafka 当前只有
> authorization/transaction 两个业务 topic 和 notification consumer group。精简入口见
> [claimable-jobs-cn.md](../claimable-jobs-cn.md) 与
> [events-outbox-inbox-kafka-cn.md](../events-outbox-inbox-kafka-cn.md)。

这份文档用于横向对比当前项目里的后台调度和 Kafka 消息上下文。它不是替代
`docs/kafka-learning-cn.md`，而是回答一个更工程化的问题：

> 业务主事务之后，哪些事情通过 scheduler 发生，哪些事情通过 Outbox/Kafka 发生，
> 各自的参与方是谁，失败和重复怎么处理，命名为什么要这样拆。

## 0. 文档拆分建议

我建议做一份横向对比 doc，也就是当前这份，而不是拆成两个完全独立的 doc。

理由：

| 方案 | 优点 | 缺点 | 当前建议 |
| --- | --- | --- | --- |
| 拆成 `scheduler` 和 `Kafka` 两份 | 每份主题更窄 | 会把 Outbox 夹在中间，读者很难看到 `业务事务 -> outbox_events -> Kafka -> consumer` 的完整链路 | 不推荐作为第一入口 |
| 合并成一份横向对比 | 能把 Outbox、DelayJob、Statement batch、Kafka consumer 放在同一张图里比较 | 文档会稍长 | 推荐 |

保留两层文档比较清楚：

- 本文：看代码流程、参与方、命名差异、interview解释。
- `docs/kafka-learning-cn.md`：深入 Kafka 配置、producer/consumer 参数、DLT、partition、consumer group。

## 1. 总览：项目里有四类 durable async work

| 机制 | 解决的问题 | 触发方式 | durable state | 典型参与类 |
| --- | --- | --- | --- | --- |
| Outbox reliable publication | 已经发生的业务事实要可靠发布给下游 | `OutboxPoller` 定时扫描 | `outbox_events` | `*OutboxAdapter`, `OutboxClaimer`, `OutboxWorker`, `KafkaOutboxMessagePublisher`, `OutboxRecoverer` |
| DelayJob future business action | 未来某个时间要执行业务动作 | `DelayJobPoller` 定时扫描 | `delay_jobs` | `*DelayJobScheduler`, `DelayJobClaimer`, `DelayJobWorker`, `DelayJobHandler`, `DelayJobRecoverer` |
| Statement sharded jobs | 某账期要为大量账户生成账单，并能补漏、分片和恢复 | daily `BillingCycleScheduler` 规划；`StatementJobDispatcher` 高频 claim | `statement_jobs` + `statements` / `statement_lines` | `StatementCycleService`, `StatementJobDispatcher`, `StatementJobHandler`, `StatementGenerationService` |
| Notification delivery | Notification intent 要 fan-out 到 APP_PUSH / EMAIL，并按渠道独立 retry | `NotificationDeliveryPoller` | `notifications` + `notification_deliveries` | `RequestNotificationService`, `NotificationDeliveryClaimer`, `NotificationDeliveryWorker`, `NotificationDeliveryRecoverer` |

核心区别：

- Outbox 表达的是“事实已经发生，要可靠告诉别人”。例如 `authorization.approved`、`card_transaction.posted`。
- DelayJob 表达的是“未来要执行一个业务动作”。例如授权到期释放额度、账单到期自动扣款。
- Statement 是两层职责：daily scheduler 做“缺哪个账期就补哪个”的 desired-state reconciliation，
  生成 durable sharded `statement_jobs`；dispatcher 再用 claim/lease/recover 驱动执行。
- Notification intent 和 delivery 分开：Kafka consumer 事务只创建通知意图及两条渠道 delivery；
  provider HTTP 调用由 durable delivery worker 在事务外执行，成功/失败再用短事务 finalize。

### 1.1 为什么根 `infrastructure` 里也有 async / scheduler / transaction

这几个名字不是业务机制，而是 platform execution resources：

| 包或类 | 实际含义 | 不应该误解成 |
| --- | --- | --- |
| `infrastructure.scheduler.PollingSchedulerConfiguration` | 创建 `outboxTaskScheduler`、`delayJobTaskScheduler`、`billingCycleTaskScheduler`、`statementJobTaskScheduler`、`notificationDeliveryTaskScheduler` | Outbox/DelayJob/Statement/Notification 的业务逻辑 |
| `infrastructure.async.WorkerExecutorConfiguration` | 创建 Outbox、普通 DelayJob、AUTO_REPAYMENT、StatementJob、NotificationDelivery 五个 bounded worker pool | 所有异步业务流程的入口 |
| `infrastructure.transaction.TransactionOperationsConfiguration` | 把 Spring transaction manager 包装成 `TransactionOperations`，给 worker 显式开短事务 | application service 的业务事务规则 |
| `messaging/outbox` | Outbox reliable publication 机制，有表、状态、lease、worker、recoverer | 普通 Kafka helper |
| `delayjob` | future business action 机制，有表、状态、lease、handler dispatch、recoverer | 普通 `@Scheduled` 方法 |
| `statement.infrastructure.scheduler` | statement 模块的 daily reconciliation 触发器 | 真正生成账单的 worker |

所以当前分法可以解释为：根 `infrastructure` 管线程、事务 helper、cache 等 platform wiring；
`messaging/outbox` 和 `delayjob` 管可靠性状态机；业务模块自己的 `infrastructure.*` 负责把业务意图接到这些机制上。

## 2. 定时任务横向对比

### 2.1 Outbox 发布任务

代码路径：

```text
业务 service transaction
-> domain event
-> *OutboxAdapter
-> outbox_events(PENDING)
-> OutboxPoller
-> OutboxClaimer(PENDING -> PROCESSING lease)
-> OutboxWorker
-> KafkaOutboxMessagePublisher
-> Kafka broker ack
-> OutboxWorker finalize(PUBLISHED / PENDING retry / DEAD)
```

关键类：

| 角色 | 类 | 责任 |
| --- | --- | --- |
| outbound adapter | `AuthorizationOutboxAdapter`, `CardTransactionOutboxAdapter` | 把 domain event 映射成 integration event envelope，并写 `outbox_events` |
| poller | `OutboxPoller` | `@Scheduled` 醒来，调用 claimer，把 claimed event 提交给 worker pool |
| claimer | `OutboxClaimer` | 短事务内 `FOR UPDATE SKIP LOCKED` 领取 due rows，写 `PROCESSING lease` |
| worker | `OutboxWorker` | 等 Kafka acknowledgement，然后重新锁 row finalize |
| publisher adapter | `KafkaOutboxMessagePublisher` | 根据 `eventType` 路由 topic，写 Kafka headers，等待 broker ack |
| recoverer | `OutboxRecoverer` | 扫描超时 `PROCESSING` rows，按失败进入 retry 或 `DEAD` |

状态机：

```text
PENDING
  -> PROCESSING lease
       -> PUBLISHED
       -> PENDING retry
       -> DEAD

PROCESSING lease expired
  -> OutboxRecoverer
  -> PENDING retry 或 DEAD
```

interview 重点：

- 主业务事务只写 MySQL，不直接发 Kafka，避免 MySQL commit 和 Kafka ack 的 dual-write 风险。
- Kafka publish 成功后、`markPublished` 前宕机会重复发布，所以整体是 at-least-once。
- Consumer 必须通过 Inbox 或业务唯一键处理 idempotency。
- `PROCESSING` 不是最终状态，而是 lease。lease 到期后 recoverer 可以恢复。

### 2.2 DelayJob 延迟业务动作

代码路径：

```text
业务 service transaction
-> *DelayJobScheduler
-> delay_jobs(PENDING, scheduled_at)
-> DelayJobPoller
-> DelayJobClaimer(PENDING -> PROCESSING lease)
-> DelayJobWorker
-> DelayJobHandler
-> 具体业务 service
-> DelayJobWorker finalize(DONE / PENDING retry / DEAD)
```

关键类：

| 角色 | 类 | 责任 |
| --- | --- | --- |
| business scheduler port | `AuthorizationExpiryJobScheduler`, `StatementDueJobScheduler` | 业务层只表达“未来要做什么”，不依赖 delay_jobs 表；这里的 scheduler 是业务计划 port，不是 Spring `@Scheduled` |
| delay job adapter | `AuthorizationExpiryDelayJobScheduler`, `AutoRepaymentDelayJobScheduler` | 把业务计划写成 `DelayJob.pending(...)` |
| poller | `DelayJobPoller` | `@Scheduled` 醒来，调用 claimer，把 claimed job 提交给 worker pool |
| claimer | `DelayJobClaimer` | 短事务内 `FOR UPDATE SKIP LOCKED` 领取 due jobs，写 `PROCESSING lease` |
| worker | `DelayJobWorker` | 根据 `jobType` dispatch 到 handler，并 finalize job 状态 |
| handler | `AuthorizationExpiryDelayJobHandler`, `AutoRepaymentDelayJobHandler` | 把通用 `DelayJob` 翻译回具体业务用例 |
| recoverer | `DelayJobRecoverer` | 扫描超时 `PROCESSING` jobs，按失败进入 retry 或 `DEAD` |

状态机：

```text
PENDING
  -> PROCESSING lease
       -> DONE
       -> PENDING retry
       -> DEAD

PROCESSING lease expired
  -> DelayJobRecoverer
  -> PENDING retry 或 DEAD
```

interview 重点：

- DelayJob 和 Outbox 都是 database-backed queue，但业务语义不同。
- DelayJob handler 必须幂等，因为 worker 宕机、lease 恢复、manual replay 都可能导致重复执行。
- `delay_jobs` 有 `UNIQUE(job_type, aggregate_type, aggregate_id)`，防止同一业务动作重复计划。
- Handler 要校验 `aggregate_type`，避免把错误聚合的 id 当成业务 id 执行。

### 2.3 Statement reconciliation + sharded claimable jobs

代码路径：

```text
BillingCycleScheduler（每天 01:00, Asia/Tokyo）
-> StatementCycleService.createDueJobs()
   -> 回看最近 2 个已结束 close cycles
   -> 缺失 cycle 才统计 POSTED + UNBILLED 账户
   -> 按 1000 accounts/job 创建 statement_jobs(PENDING)

StatementJobDispatcher（每 1s dispatch；每 10s recover）
-> FOR UPDATE SKIP LOCKED claim 最多 8 个 shard
-> PENDING -> PROCESSING + claimed_by/at/until + claim_token
-> commit claim transaction
-> statementJobWorkerExecutor（4 threads / queue 100）
-> StatementJobHandler.handle(...)
-> per account: StatementGenerationService.generate(...)
-> finalize DONE / PENDING retry / DEAD（校验 claim_token）
```

关键类：

| 角色 | 类 | 责任 |
| --- | --- | --- |
| reconciliation scheduler | `BillingCycleScheduler` | daily 触发，不执行全量出账，只要求 planner 收敛到“最近 cycle 都有 jobs” |
| cycle planner | `StatementCycleService` | 计算 billing cycle / due date / shardCount，同事务幂等插入整套 jobs |
| dispatcher | `StatementJobDispatcher` | claim、提交 worker、recover、finalize；job row lock 不跨账户业务处理 |
| shard handler | `StatementJobHandler` | 稳定分片查询账户，逐账户调用生成用例，并汇总 generated/skipped/failed |
| statement use case | `StatementGenerationService` | 每账户独立事务，按 account → transactions → statement 锁顺序生成账单和 DelayJob |

为什么没有拆出 `StatementJobClaimer` / `StatementJobRecoverer` 四件套：

- 它**确实**从 `statement_jobs` 领取 durable jobs，只是当前把 claim/dispatch/recover/finalize
  合在一个 `StatementJobDispatcher`，作为更紧凑的 reference implementation。
- Job 级幂等由 `(period_start, period_end, shard_no)` 唯一键保护；账户级幂等由
  `statements(credit_account_id, period_start, period_end)` 自然键保护。
- PROCESSING 用四列 lease metadata，`claim_until` 只回答“何时可回收”，随机
  `claim_token` 才回答“本轮 owner 是谁”。迟到 worker finalize 时 token 不符会被丢弃。
- 每账户独立事务提供 fault isolation，但只要一个账户是 retryable failure，该 shard 仍会整体
  retry/最终 DEAD；账户级自然键让重复扫描已成功账户时安全跳过。

### 2.4 Notification per-channel delivery

代码路径：

```text
Kafka listener transaction
-> RequestNotificationService
-> notifications immutable intent
-> notification_deliveries(APP_PUSH + EMAIL, PENDING)

NotificationDeliveryPoller（1s，batch 40）
-> NotificationDeliveryClaimer(PROCESSING, lease_token, 30s deadline)
-> notificationDeliveryWorkerExecutor（4 threads / queue 100）
-> channel sender -> provider HTTP（事务外）
-> finalize SENT / PENDING backoff / DEAD（max 8 durable attempts）
-> NotificationDeliveryRecoverer 回收超时 PROCESSING
```

这里有三层失败治理，不能混为一个 retry：

- Resilience4j `notificationDelivery` Retry 在一次 worker attempt 内最多调用 provider 3 次，
  处理瞬时网络错误；Push/Email 各自有 20 permits/s 的本地 RateLimiter 和独立 CircuitBreaker。
- durable delivery attempts 跨进程重启存在 MySQL 中，最多 8 次并指数退避；它解决 provider 长时间故障。
- 本地 RateLimiter 没 permit、或 worker queue 满时 provider 根本没被调用，因此只
  `rescheduleWithoutAttempt`，不消耗 durable attempts。否则健康消息可能仅因本 pod 容量不足被推入 DEAD。

Provider 调用不能包在 DB transaction 里：网络 timeout 无法随 MySQL rollback 撤销，反而会把连接和
row lock 长时间钉住。Worker 先短事务 claim，事务外调用 provider，再短事务按 lease token finalize。

## 3. Outbox 的 producer side：谁产生了哪些事件

| Source context | 产生事件的 service | Outbox adapter | eventType | topic | partition key |
| --- | --- | --- | --- | --- | --- |
| Authorization | `AuthorizationService`, `AuthorizationExpiryService`, `PostingService` | `AuthorizationOutboxAdapter` | `authorization.approved`, `authorization.declined`, `authorization.expired`, `authorization.posted` | `mini-card.authorization-events.v1` | `authorizationId` |
| CardTransaction | `PostingService` | `CardTransactionOutboxAdapter` | `card_transaction.posted` | `mini-card.transaction-events.v1` | `cardTransactionId` |

这里的命名按 source context 切，而不是按下游用途切。

例如 `PostingService` 会产生两个事实：

- `authorization.posted`：授权生命周期结束，表达 hold 已被正式入账消费。
- `card_transaction.posted`：用户可见交易流水已经入账，Notification 更关心这个事实。

所以项目不应该把它们合成一个泛泛的 `payment.posted`。这样会模糊 issuer-side lifecycle：Authorization 和 CardTransaction 是不同业务概念。

## 4. Kafka consumer contexts 横向对比

Kafka inbound 的公共入口是 `IntegrationEventReader`：

- 反序列化 `IntegrationEvent` envelope。
- 校验 `eventId`、`eventType`、`eventVersion`、`payload`。
- 只用 body envelope 做 correctness 和 dispatch；Kafka headers 只用于日志、kcat、DLT 排障，
  reader 不读取也不校验 header/body 一致性，因此缺 header 的手工 replay 仍可消费。
- 提供 `requiredText(...)`，让 listener 做字段级 contract check。

Consumer 侧的共同规则：

- Listener 是 inbound adapter，只做 eventType 过滤和 payload -> command 映射。
- 真正 side effect 放在 application service。
- application service 使用 `ConsumerInboxRepository.claim(consumerName, eventId, now)` 做 consumer-side idempotency。
- 无法恢复的 contract failure 进入 DLT，不要误当业务失败。

| Consumer context | Listener | Source topic | 关心的 eventType | Side effect | 幂等保护 | DLT |
| --- | --- | --- | --- | --- | --- | --- |
| Notification | `AuthorizationNotificationListener` | authorization topic | `authorization.approved`, `authorization.declined` | 创建授权结果通知 | `consumer_inbox(notification-v1, eventId)` + `notifications.source_event_id` unique | `mini-card.notification.dlt.v1` |
| Notification | `CardTransactionNotificationListener` | transaction topic | `card_transaction.posted` | 创建交易入账通知 | 同上 | 同上 |

为什么 Notification 要拆成多个 source-specific listener：

- Authorization decision 与 CardTransaction posting 是不同业务事实。
- 每个 listener 的 payload 字段不同，`recipientKey` 的来源也不同。
- 如果合成一个泛泛的 `GenericNotificationListener`，代码会把 source context 混在一起，后续很难解释为什么 `authorization.posted` 不等于用户可见交易入账。

## 5. 五条实际业务流程

### 5.1 授权批准：同步授权 + Outbox + DelayJob

```text
POST /api/authorizations
-> AuthorizationController
-> AuthorizationService.authorize(...)
   -> authorizationRepository.claim(idempotency key)
   -> card status check
   -> riskAssessmentService.assess(...)
   -> creditAccountRepository.findByIdForUpdate(row lock)
   -> account.reserve(...)
   -> authorization.approve(...)
   -> authorizationRepository.update(...)
   -> AuthorizationExpiryDelayJobScheduler.schedule(...)
   -> AuthorizationOutboxAdapter.append(authorization.approved)
-> HTTP response

later:
OutboxClaimer/OutboxWorker
-> Kafka authorization topic
-> AuthorizationNotificationListener
```

参与方：

- 主事务：`AuthorizationService`。
- row lock：`credit_accounts`。
- API idempotency：`authorizations.idempotency_key`。
- 未来动作：`AUTHORIZATION_EXPIRY` DelayJob。
- 事实发布：`authorization.approved` Outbox event。
- 下游：Notification 创建批准通知。

为什么 DelayJob 和 Outbox 都要写：

- DelayJob：7 天后如果没有 presentment，要释放 reserved credit。
- Outbox：授权批准这个事实已经发生，要让 Notification 知道。

### 5.2 授权过期：DelayJob 执行业务动作，再产生 Outbox

```text
delay_jobs(AUTHORIZATION_EXPIRY due)
-> DelayJobPoller
-> DelayJobClaimer(PENDING -> PROCESSING)
-> DelayJobWorker
-> AuthorizationExpiryDelayJobHandler
-> AuthorizationExpiryService.expire(...)
   -> authorizationRepository.findByIdForUpdate(...)
   -> if not APPROVED: idempotent skip
   -> creditAccountRepository.findByIdForUpdate(row lock)
   -> account.release(...)
   -> authorization.expire(...)
   -> AuthorizationOutboxAdapter.append(authorization.expired)
-> DelayJobWorker mark DONE
```

这里 DelayJob 是触发器，但真正 source of truth 仍然是 `authorizations` 和 `credit_accounts`。
如果 job 重复执行，`AuthorizationExpiryService` 会看到 authorization 已经不是 `APPROVED`，直接跳过。

### 5.3 Presentment posting：一个主事务产生两个不同事实

```text
POST /api/presentments
-> PresentmentController
-> PostingService.post(...)
   -> authorizationRepository.findByIdForUpdate(...)
   -> transactionRepository.findByNetworkTransactionIdForUpdate(...)
   -> creditAccountRepository.findByIdForUpdate(row lock)
   -> transactionRepository.claim(networkTransactionId)
   -> account.postAuthorized(...)
   -> authorization.post(...)
   -> cardTransaction.markPosted(...)
   -> update account / authorization / cardTransaction
   -> AuthorizationOutboxAdapter.append(authorization.posted)
   -> CardTransactionOutboxAdapter.append(card_transaction.posted)
-> HTTP response

later:
OutboxWorker -> Kafka authorization topic
OutboxWorker -> Kafka transaction topic
transaction topic -> CardTransactionNotificationListener
```

为什么 `authorization.posted` 和 `card_transaction.posted` 都存在：

- `authorization.posted` 是授权生命周期事实。
- `card_transaction.posted` 是用户可见交易流水事实。
- Notification 使用 `card_transaction.posted`，避免把授权内部状态当成用户交易。

### 5.4 Statement：reconciliation + claimable job + DelayJob

```text
BillingCycleScheduler
-> StatementCycleService.createDueJobs()
   -> reconcile recent closed cycles
   -> statement_jobs(PENDING shards; cycle/shard unique)
-> StatementJobDispatcher claim PROCESSING lease
-> StatementJobHandler（CRC32 account sharding）
-> per account: StatementGenerationService.generate(...)
   -> creditAccountRepository.findByIdForUpdate(row lock)
   -> statementRepository.findByCycleForUpdate(account idempotency)
   -> statementBillingRepository.findBillableLineSourcesForUpdate(...)
   -> Statement.generate(...)
   -> statementRepository.insert(...)
   -> statementBillingRepository.markBilled(...)
   -> StatementDueJobScheduler.schedule(...)
-> StatementJobDispatcher finalize（claim_token owner check）
```

为什么自动扣款是 DelayJob：

- 自动扣款是 due date 的未来业务动作，与账单生成同事务写入 `delay_jobs`，不依赖 Kafka。
- 当前 Statement 不产生 Outbox/Kafka event；过去的 `statement.closed` 通知路径已经删除。

### 5.5 到期自动扣款：DelayJob -> Repayment

```text
delay_jobs(AUTO_REPAYMENT due)
-> DelayJobPoller
-> DelayJobWorker
-> AutoRepaymentDelayJobHandler
-> AutoRepaymentService.debitStatement(...)
   -> statementRepository.findById(...)
   -> BankDebitGateway.debit(...)
   -> if SUCCESS: RepaymentService.receive(...)
      -> repaymentRepository.claim(deterministic idempotency key)
      -> creditAccountRepository.findByIdForUpdate(row lock)
      -> statementRepository.findByIdForUpdate(...)
      -> account.applyRepayment(...)
      -> statement.applyRepayment(...)
      -> repayment.markReceived(...)
-> DelayJobWorker mark DONE
```

自动扣款的 idempotency key 是 `auto-debit:{statementId}`。这让 DelayJob retry 不会重复入账。

## 6. 这次保持一致的地方

目前 Outbox 和 DelayJob 的命名已经收敛成一组对称结构：

| 统一角色 | Outbox | DelayJob | 共同点 |
| --- | --- | --- | --- |
| Poller | `OutboxPoller` | `DelayJobPoller` | `@Scheduled` 入口，只 poll/submit，不做长业务 |
| Claimer | `OutboxClaimer` | `DelayJobClaimer` | 短事务 claim due rows，写 `PROCESSING lease` |
| Worker | `OutboxWorker` | `DelayJobWorker` | 执行长动作，然后重新锁 row finalize |
| Recoverer | `OutboxRecoverer` | `DelayJobRecoverer` | 恢复 lease 超时的 `PROCESSING` rows |
| State row | `OutboxEvent` | `DelayJob` | 都有 attempts、nextAttemptAt、lastError、retry/DEAD |


```text
poller 只负责醒来和提交任务
claimer 只负责短事务领取
worker 负责真正执行和 finalize
recoverer 负责宕机/超时恢复
```

## 7. 仍然应该保持不同的地方

| 不同点 | 为什么不强行统一 |
| --- | --- |
| `OutboxWorker` 没有 handler dispatch map，`DelayJobWorker` 有 | Outbox worker 的动作永远是 publish message；DelayJob worker 执行的是多种业务动作，需要按 `jobType` dispatch |
| Outbox final state 是 `PUBLISHED`，DelayJob final state 是 `DONE` | Outbox 完成的是消息发布；DelayJob 完成的是业务动作执行 |
| Outbox 使用 `eventType` 路由 topic，DelayJob 使用 `jobType` 找 handler | 一个是 integration event contract，一个是内部 delayed business action contract |
| StatementJob 不拆四个类 | 它仍是 queue row + lease 模型，只是集中在 `StatementJobDispatcher`，避免机械复制四类结构 |
| Notification 拆多个 source listener | payload 和业务含义不同，source-specific listener 比一个大 listener 更能表达 bounded context |

这些不同不是命名不统一，而是业务语义不同。

## 8. 高频interview解释模板

### 8.1 为什么 Outbox 和 DelayJob 都要有 `PROCESSING lease`

`PROCESSING` 不是终态，而是 worker 当前持有的租约。Worker 宕机时，row 不会永远卡住；
`nextAttemptAt` 到期后 recoverer 会把它按一次失败处理，回到 retry 或进入 `DEAD`。

如果没有 `PROCESSING lease`，通常会在两个坏结果里选一个：

- 不标记领取状态：多个 pod 同时拿到同一行，重复发 Kafka 或重复执行业务动作。
- 只标记“处理中”但没有 lease/recoverer：pod 宕机后这行永远不可见，事件或 job 丢在半路。

所以 lease 的本质是 database-backed ownership：短时间内只有一个 worker 拥有执行权；
超过 deadline 后，系统认为这个 ownership 失效，可以安全恢复。

### 8.2 为什么不能在主事务里直接发 Kafka

MySQL commit 和 Kafka ack 不能放进同一个本地原子事务。主事务直接发 Kafka 会有两类风险：

- DB commit 成功但 Kafka publish 失败：业务发生了，下游不知道。
- Kafka publish 成功但 DB rollback：下游看到了不存在的业务事实。

Outbox 的选择是：主事务只提交业务状态和 outbox row，后台再可靠发布。代价是可能重复，所以 consumer 必须幂等。

如果没有 Outbox，把 Kafka publish 直接放进业务事务附近，会遇到 dual-write 问题：

| 省掉 Outbox 后的情况 | 结果 |
| --- | --- |
| MySQL commit 成功，Kafka publish 失败 | 用户授权/入账已经成功，但 Notification 收不到事件 |
| Kafka publish 成功，MySQL rollback | 下游消费了一个业务上不存在的事实 |
| 为了等 Kafka ack 拉长 DB transaction | account row lock 持有更久，高并发下锁等待和超时增加 |

### 8.3 为什么 consumer 不能只靠 Kafka exactly-once

本项目 consumer 的 side effect 在 MySQL。Kafka offset commit 和 MySQL transaction 不能原子提交。
即使 Kafka producer 开了 idempotence，consumer 仍可能在处理成功后、offset commit 前宕机，导致消息重投。

所以 consumer side 使用：

- `consumer_inbox(consumer_name, event_id)` 防重复处理。
- 业务表 unique constraint 作为第二道保护，例如 `notifications.source_event_id`。

如果没有 consumer-side idempotency，Outbox 重发、Kafka offset 回放、人工 replay 都会变成真实 side effect：

- 通知会重复发送。

### 8.4 为什么 topic/listener 按 source context 拆

事件名应该表达“哪个业务事实发生了”，而不是“哪个下游想用它”。

例如：

- `card_transaction.posted` 是 integration event（交易入账事实）。
- `CARD_TRANSACTION_POSTED` 是 Notification context 里的通知类型；与事件同名，保持通知与事件 1:1 命名一致。

虽然同名，两者仍属于不同层：前者是 integration event contract，后者是下游通知分类。
真正面向用户的友好文案在 delivery 阶段按 NotificationType 选模板渲染，不需要给内部类型改名。

### 8.5 怎么解释 `scheduler`、`poller`、`worker`、`planner`

当前代码还没有统一把业务计划 port 改名成 `Planner`，所以你会看到：

- `AuthorizationExpiryJobScheduler`：业务层 port，意思是“计划一个授权过期动作”。
- `AuthorizationExpiryDelayJobScheduler`：DelayJob adapter，意思是“把这个计划写成 delay_jobs row”。
- `DelayJobPoller`：真正的 `@Scheduled` 入口，意思是“周期性扫描 due jobs”。
- `PollingSchedulerConfiguration`：Spring scheduler thread pool 配置，意思是“给 poller/recoverer 提供线程”。

interview 里不用强行说这些名字完美，可以这样解释：

> 这个项目把“业务计划”“周期扫描”“后台执行资源”拆开。业务 service 只依赖 scheduler port 表达未来动作；
> DelayJob adapter 把计划落到 `delay_jobs`；Poller/Claimer/Worker/Recoverer 负责 lease、retry 和并发；
> 根 infrastructure 只提供线程池和 transaction helper。这样即使名字都包含 scheduler，也不会混淆 transaction boundary。

## 9. 读代码建议顺序

如果你想按一次完整链路学习，建议这样读：

1. `AuthorizationService.authorize(...)`
2. `AuthorizationOutboxAdapter`
3. `OutboxEvent` / `OutboxClaimer` / `OutboxWorker` / `OutboxRecoverer`
4. `KafkaOutboxMessagePublisher`
5. `IntegrationEventReader`
6. `AuthorizationNotificationListener`
7. `RequestNotificationService`

如果你想对比 DelayJob，则读：

1. `AuthorizationExpiryDelayJobScheduler`
2. `DelayJob`
3. `DelayJobClaimer` / `DelayJobWorker` / `DelayJobRecoverer`
4. `AuthorizationExpiryDelayJobHandler`
5. `AuthorizationExpiryService.expire(...)`

如果你想对比 Statement 和 repayment，则读：

1. `BillingCycleScheduler`
2. `StatementCycleService.createDueJobs(...)`
3. `StatementJobDispatcher`
4. `StatementJobHandler`
5. `StatementGenerationService.generate(...)`
6. `AutoRepaymentDelayJobScheduler`
7. `AutoRepaymentDelayJobHandler`
8. `AutoRepaymentService.debitStatement(...)`
9. `RepaymentService.receive(...)`
