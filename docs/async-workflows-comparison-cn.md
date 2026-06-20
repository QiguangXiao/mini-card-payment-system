# 异步流程横向对比：定时任务、Outbox、Kafka Consumer

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

## 1. 总览：项目里有三类异步机制

| 机制 | 解决的问题 | 触发方式 | durable state | 典型参与类 |
| --- | --- | --- | --- | --- |
| Outbox reliable publication | 已经发生的业务事实要可靠发布给下游 | `OutboxPoller` 定时扫描 | `outbox_events` | `*OutboxAdapter`, `OutboxClaimer`, `OutboxWorker`, `KafkaOutboxMessagePublisher`, `OutboxRecoverer` |
| DelayJob future business action | 未来某个时间要执行业务动作 | `DelayJobPoller` 定时扫描 | `delay_jobs` | `*DelayJobScheduler`, `DelayJobClaimer`, `DelayJobWorker`, `DelayJobHandler`, `DelayJobRecoverer` |
| Statement batch scheduler | 到账单日次日批量关账 | `StatementBatchPoller` 定时触发 | 业务表本身，主要是 `statements` / `card_transactions` | `StatementBatchPoller`, `StatementBatchService`, `StatementService` |

核心区别：

- Outbox 表达的是“事实已经发生，要可靠告诉别人”。例如 `authorization.approved`、`statement.closed`。
- DelayJob 表达的是“未来要执行一个业务动作”。例如授权到期释放额度、账单到期自动扣款。
- Statement batch 是日历驱动的批处理入口，不是每条业务动作的 durable queue。它每轮找候选账户，再让 `StatementService` 对每个账户开自己的 transaction boundary。

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
| outbound adapter | `AuthorizationOutboxAdapter`, `CardTransactionOutboxAdapter`, `StatementOutboxAdapter`, `RepaymentOutboxAdapter` | 把 domain event 映射成 integration event envelope，并写 `outbox_events` |
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

interview重点：

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
| business scheduler port | `AuthorizationExpiryJobScheduler`, `StatementDueJobScheduler` | 业务层只表达“未来要做什么”，不依赖 delay_jobs 表 |
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

interview重点：

- DelayJob 和 Outbox 都是 database-backed queue，但业务语义不同。
- DelayJob handler 必须幂等，因为 worker 宕机、lease 恢复、manual replay 都可能导致重复执行。
- `delay_jobs` 有 `UNIQUE(job_type, aggregate_type, aggregate_id)`，防止同一业务动作重复计划。
- Handler 要校验 `aggregate_type`，避免把错误聚合的 id 当成业务 id 执行。

### 2.3 Statement batch scheduler

代码路径：

```text
StatementBatchPoller
-> StatementBatchService.runDueBatch()
-> 判断今天是否是 close day 次日
-> 查询有未出账 posted transactions 的 credit accounts
-> 每个 account 调 StatementService.generate(...)
```

关键类：

| 角色 | 类 | 责任 |
| --- | --- | --- |
| scheduler | `StatementBatchPoller` | 每分钟轻量触发，记录 batch 结果 |
| batch service | `StatementBatchService` | 计算 billing cycle / due date，查询候选账户，逐个处理 |
| statement use case | `StatementService` | 对单个账户开事务，锁 account 和待出账交易，生成 statement |

为什么它没有 `StatementBatchClaimer`：

- 它不是从一张 `statement_jobs` 表领取 durable jobs。
- 它是日历驱动的 batch，每轮根据业务表现状查询候选账户。
- 单账户的 idempotency 由 `StatementService.generate(...)` 的自然键保障：同一 `creditAccountId + periodStart + periodEnd` 只能生成一张 statement。
- 单账户失败不应中断整批。当前用 structured log 记录失败，下一轮 scheduler 仍可重试。

## 3. Outbox 的 producer side：谁产生了哪些事件

| Source context | 产生事件的 service | Outbox adapter | eventType | topic | partition key |
| --- | --- | --- | --- | --- | --- |
| Authorization | `AuthorizationService`, `AuthorizationExpiryService`, `PostingService` | `AuthorizationOutboxAdapter` | `authorization.approved`, `authorization.declined`, `authorization.expired`, `authorization.posted` | `mini-card.authorization-events.v1` | `authorizationId` |
| CardTransaction | `PostingService` | `CardTransactionOutboxAdapter` | `card_transaction.posted` | `mini-card.transaction-events.v1` | `cardTransactionId` |
| Statement | `StatementService` | `StatementOutboxAdapter` | `statement.closed` | `mini-card.statement-events.v1` | `creditAccountId` |
| Repayment | `RepaymentService` | `RepaymentOutboxAdapter` | `repayment.received` | `mini-card.repayment-events.v1` | `creditAccountId` |

这里的命名按 source context 切，而不是按下游用途切。

例如 `PostingService` 会产生两个事实：

- `authorization.posted`：授权生命周期结束，表达 hold 已被正式入账消费。
- `card_transaction.posted`：用户可见交易流水已经入账，Notification 和 Ledger 更关心这个事实。

所以项目不应该把它们合成一个泛泛的 `payment.posted`。这样会模糊 issuer-side lifecycle：Authorization 和 CardTransaction 是不同业务概念。

## 4. Kafka consumer contexts 横向对比

Kafka inbound 的公共入口是 `IntegrationEventReader`：

- 反序列化 `IntegrationEvent` envelope。
- 校验 `eventId`、`eventType`、`eventVersion`、`payload`。
- 校验 Kafka header 和 payload 的 `eventId/eventType` 一致。
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
| Notification | `StatementNotificationListener` | statement topic | `statement.closed` | 创建 `STATEMENT_READY` 通知 | 同上 | 同上 |
| Notification | `RepaymentNotificationListener` | repayment topic | `repayment.received` | 创建还款成功通知 | 同上 | 同上 |
| Risk | `AuthorizationRiskFeatureListener` -> `ProjectRiskFeatureCommand` | authorization topic | `authorization.approved`, `authorization.declined` | 更新卡片风控特征投影 | `consumer_inbox(risk-feature-v1, eventId)` | `mini-card.authorization-risk-feature.dlt.v1` |
| Ledger | `CardTransactionLedgerListener` | transaction topic | `card_transaction.posted` | 记录消费入账 DEBIT 分录 | `consumer_inbox(ledger-v1, eventId)` + `ledger_entries(source_event_id, entry_type)` unique | `mini-card.ledger.dlt.v1` |
| Ledger | `RepaymentLedgerListener` | repayment topic | `repayment.received` | 记录还款 CREDIT 分录 | 同上 | 同上 |

为什么 Notification 要拆成多个 source-specific listener：

- Authorization decision、CardTransaction posting、Statement closing、Repayment received 是不同业务事实。
- 每个 listener 的 payload 字段不同，`recipientKey` 的来源也不同。
- 如果合成一个 `PaymentNotificationListener`，代码会把 source context 混在一起，后续很难解释为什么 `authorization.posted` 不等于用户可见交易入账。

## 5. 四条实际业务流程

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
-> AuthorizationRiskFeatureListener
```

参与方：

- 主事务：`AuthorizationService`。
- row lock：`credit_accounts`。
- API idempotency：`authorizations.idempotency_key`。
- 未来动作：`AUTHORIZATION_EXPIRY` DelayJob。
- 事实发布：`authorization.approved` Outbox event。
- 下游：Notification 创建批准通知，Risk 更新特征投影。

为什么 DelayJob 和 Outbox 都要写：

- DelayJob：7 天后如果没有 presentment，要释放 reserved credit。
- Outbox：授权批准这个事实已经发生，要让 Notification/Risk 知道。

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
transaction topic -> CardTransactionLedgerListener
```

为什么 `authorization.posted` 和 `card_transaction.posted` 都存在：

- `authorization.posted` 是授权生命周期事实。
- `card_transaction.posted` 是用户可见交易流水事实。
- Notification 和 Ledger 使用 `card_transaction.posted`，避免把授权内部状态当成用户交易。

### 5.4 Statement batch：日历批处理 + DelayJob + Outbox

```text
StatementBatchPoller
-> StatementBatchService.runDueBatch()
   -> 判断 close day 次日
   -> 查询有 unbilled posted transactions 的 accounts
   -> per account: StatementService.generate(...)
      -> creditAccountRepository.findByIdForUpdate(row lock)
      -> statementRepository.findByCycleForUpdate(idempotency)
      -> transactionRepository.findUnbilledPostedByCreditAccountForUpdate(...)
      -> Statement.close(...)
      -> statementRepository.insert(...)
      -> transactionRepository.assignStatement(...)
      -> AutoRepaymentDelayJobScheduler.scheduleAutoRepayment(...)
      -> StatementOutboxAdapter.append(statement.closed)
```

随后：

```text
statement.closed
-> OutboxWorker
-> Kafka statement topic
-> StatementNotificationListener
-> RequestNotificationService
-> notifications(STATEMENT_READY)
```

为什么自动扣款是 DelayJob，而通知是 Outbox/Kafka：

- 自动扣款是 due date 的未来业务动作。
- `statement.closed` 通知是账单已生成后的异步副作用，不应该放大 `StatementService` 的 transaction boundary。

### 5.5 到期自动扣款：DelayJob -> Repayment -> Outbox -> Consumer

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
      -> RepaymentOutboxAdapter.append(repayment.received)
-> DelayJobWorker mark DONE

later:
repayment.received
-> RepaymentNotificationListener
-> RepaymentLedgerListener
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
| Statement batch 没有 Claimer/Recoverer | 它不是 queue row 模型，而是日历驱动的候选账户扫描 |
| Notification 拆多个 source listener | payload 和业务含义不同，source-specific listener 比一个大 listener 更能表达 bounded context |
| Risk/Ledger/Notification 使用不同 consumer group 和 DLT | 下游失败应该隔离，Notification 失败不能阻塞 Risk projection 或 Ledger projection |

这些不同不是命名不统一，而是业务语义不同。

## 8. 高频interview解释模板

### 8.1 为什么 Outbox 和 DelayJob 都要有 `PROCESSING lease`

`PROCESSING` 不是终态，而是 worker 当前持有的租约。Worker 宕机时，row 不会永远卡住；
`nextAttemptAt` 到期后 recoverer 会把它按一次失败处理，回到 retry 或进入 `DEAD`。

### 8.2 为什么不能在主事务里直接发 Kafka

MySQL commit 和 Kafka ack 不能放进同一个本地原子事务。主事务直接发 Kafka 会有两类风险：

- DB commit 成功但 Kafka publish 失败：业务发生了，下游不知道。
- Kafka publish 成功但 DB rollback：下游看到了不存在的业务事实。

Outbox 的选择是：主事务只提交业务状态和 outbox row，后台再可靠发布。代价是可能重复，所以 consumer 必须幂等。

### 8.3 为什么 consumer 不能只靠 Kafka exactly-once

本项目 consumer 的 side effect 在 MySQL。Kafka offset commit 和 MySQL transaction 不能原子提交。
即使 Kafka producer 开了 idempotence，consumer 仍可能在处理成功后、offset commit 前宕机，导致消息重投。

所以 consumer side 使用：

- `consumer_inbox(consumer_name, event_id)` 防重复处理。
- 业务表 unique constraint 作为第二道保护，例如 `notifications.source_event_id`、`ledger_entries(source_event_id, entry_type)`。

### 8.4 为什么 topic/listener 按 source context 拆

事件名应该表达“哪个业务事实发生了”，而不是“哪个下游想用它”。

例如：

- `statement.closed` 是账单事实。
- `STATEMENT_READY` 是 Notification context 里的通知模板。

这两个名字不能混。前者是 integration event contract，后者是下游用户沟通方式。

## 9. 读代码建议顺序

如果你想按一次完整链路学习，建议这样读：

1. `AuthorizationService.authorize(...)`
2. `AuthorizationOutboxAdapter`
3. `OutboxEvent` / `OutboxClaimer` / `OutboxWorker` / `OutboxRecoverer`
4. `KafkaOutboxMessagePublisher`
5. `IntegrationEventReader`
6. `AuthorizationNotificationListener` 和 `AuthorizationRiskFeatureListener`
7. `RequestNotificationService` / `RiskFeatureProjectionService`

如果你想对比 DelayJob，则读：

1. `AuthorizationExpiryDelayJobScheduler`
2. `DelayJob`
3. `DelayJobClaimer` / `DelayJobWorker` / `DelayJobRecoverer`
4. `AuthorizationExpiryDelayJobHandler`
5. `AuthorizationExpiryService.expire(...)`

如果你想对比 Statement 和 repayment，则读：

1. `StatementBatchPoller`
2. `StatementBatchService`
3. `StatementService.generate(...)`
4. `AutoRepaymentDelayJobScheduler`
5. `AutoRepaymentDelayJobHandler`
6. `AutoRepaymentService.debitStatement(...)`
7. `RepaymentService.receive(...)`
8. `RepaymentOutboxAdapter`
9. `RepaymentNotificationListener` / `RepaymentLedgerListener`
