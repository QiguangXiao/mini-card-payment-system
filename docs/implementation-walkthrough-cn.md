# Mini Card Payment System 核心流程中文说明

这份文档按“请求进来 -> 中间关键阶段 -> 数据变化 -> 响应出去”的方式解释当前工程。
它不是泛泛讲 DDD，而是对应现在代码里的类、表和状态。

## 1. 怎么读这个工程

当前工程可以按业务模块的 5 层理解：

- `api`：HTTP adapter。负责接收 request、校验参数、返回 response。
- `application`：use case orchestration。负责事务边界、调用顺序、幂等、锁、调度、事件写入。
- `domain`：核心业务规则。负责状态转换、金额和额度不变量。
- `repository`：领域接口。application 只依赖接口，不关心 MyBatis 细节。
- `infrastructure`：某个业务模块自己的技术 adapter，例如 MyBatis repository、Outbox adapter、DelayJob adapter、Kafka listener。

关键词：

- `Aggregate`：一组需要保持一致的业务对象。当前重点是 `Authorization`、`CreditAccount`、`CardTransaction` 和 `Statement`。
- `Idempotency`：同一个客户端重试不会重复扣额度。
- `Row lock`：用数据库行锁控制并发，而不是 JVM `synchronized`。
- `Outbox`：主业务事务里只写消息意图，后台 scheduler 再发布 Kafka。
- `DelayJob`：主业务事务里写未来任务计划，后台 scheduler 到时间后执行业务动作。

### 1.1 包结构和名字怎么理解

当前项目里有三类容易混淆的包名：

| 位置 | 例子 | 含义 | 为什么不合并 |
| --- | --- | --- | --- |
| 业务模块内的 `infrastructure` | `authorization.infrastructure.messaging`, `repayment.infrastructure.delayjob` | 某个 bounded context 的 adapter，把业务意图接到 MyBatis、Outbox、DelayJob、Kafka 等机制上 | 它依赖本模块业务语义，例如 authorization expiry、auto repayment |
| 根 `com.minicard.infrastructure` | `cache`, `async`, `scheduler`, `transaction`, `time`, `web` | shared platform helper，提供线程池、scheduler bean、transaction helper、web error handling、cache 基础设施 | 它不拥有业务状态机，也不决定发布什么事件或执行什么 job |
| 根机制包 | `messaging`, `delayjob` | 一等 reliability mechanism，有自己的表、状态、lease、retry、recoverer | 它们比普通 helper 更重；放在根包可以让 Outbox/Inbox/DelayJob 的可靠性语义更清楚 |

所以 `com.minicard.infrastructure.scheduler` 不是“所有 scheduler 业务逻辑”的地方。
它只创建 Spring `ThreadPoolTaskScheduler`。真正的 poll/claim/worker/recover 流程在
`messaging/outbox` 和 `delayjob`，而 `StatementBatchPoller` 是 statement 模块自己的日历触发器。

`com.minicard.infrastructure.async` 也不是业务异步流程本身，只是 worker pool 配置。
如果以后重命名，`infrastructure.execution` / `WorkerPoolConfiguration` 会更直观；当前代码先保留现状，
文档里用这个心智模型解释即可。

### 1.2 反事实阅读法：如果不做这些额外处理会怎样

读这个项目时，很多代码看起来像“额外麻烦”：claim、row lock、lease、after-commit evict、
Inbox、unique fallback。它们不是装饰，而是在防具体事故。

| 看到的额外处理 | 如果没有它，会发生什么 |
| --- | --- |
| Authorization / Repayment 的 INSERT-first `idempotency` claim | 客户端 retry、网关重放或银行回调重复时，可能重复冻结额度或重复还款入账 |
| `CreditAccountRepository.findByIdForUpdate(...)` | 两笔并发授权可能同时看到同一份 available credit，并一起批准，造成超额授权 |
| 风控放在 account `row lock` 之前 | 外部风控慢调用会拖长账户锁持有时间，同一账户高并发请求在锁上排队超时 |
| Posting 和 Statement batch 统一锁顺序：account -> transactions | 一个流程先锁交易再等账户、另一个先锁账户再等交易时，容易形成死锁 |
| `network_transaction_id` 作为 presentment 幂等键 | 卡组织或测试脚本重放同一 presentment 时，同一笔消费可能被入账两次 |
| Statement cycle natural key：`creditAccountId + periodStart + periodEnd` | batch retry 可能给同一账户同一期生成多张账单 |
| `CardTransaction.assignToStatement(...)` | 同一笔 posted transaction 下轮 batch 还会被当成未出账交易，再次进入账单 |
| Outbox row 和业务状态同事务提交 | 业务状态成功但消息意图丢失，下游 Notification/Risk/Ledger 永远不知道这件事 |
| Kafka publish 放在 worker，且等待 broker ack 后再 finalize | 如果主事务里等待 Kafka，会拉长 money-changing lock；如果不等 ack 就标成功，消息可能丢失 |
| Outbox / DelayJob 的 `PROCESSING lease` 和 recoverer | pod 在领取后宕机时，row 会永久卡在 PROCESSING，消息或未来业务动作不再执行 |
| Worker finalize 前重新 `FOR UPDATE` 并比较 lease token | 旧 worker 迟到返回时，可能覆盖新 worker/recoverer 已经写好的失败、重试或成功状态 |
| Consumer Inbox + 业务唯一键 | Kafka/Outbox at-least-once 重投会重复创建通知、重复写 Ledger、重复推进 Risk feature |
| cache after-commit evict | 事务提交前删缓存时，另一个 GET 可能读旧 DB 值并重新写回 Redis，造成 stale read model |
| 自动扣款 deterministic key：`auto-debit:{statementId}` | DelayJob retry 每次都像一笔新还款，同一张账单可能被自动扣款多次 |

所以这个项目里的很多“多一步”，都可以按这个问题理解：

```text
如果没有这一步，重复请求、并发请求、worker 宕机、Kafka 重投、缓存失效竞态会把哪张表写坏？
```

## 2. 核心数据表和状态

### `authorizations`

保存一次授权请求的状态。

核心字段：

- `id`：authorization id，由 `Authorization.request()` 内部 `UUID.randomUUID()` 生成。
- `idempotency_key`：客户端传入的幂等键，有唯一索引。
- `request_fingerprint`：由 `AuthorizationCommand.requestFingerprint()` 计算，用来判断重试请求是否完全相同。
- `status`：`PENDING`、`APPROVED`、`DECLINED`、`EXPIRED`。
- `expires_at`：授权批准后生成的 7 天后过期时间。
- `posted_at`：presentment 入账后，授权从 hold 变成 posted transaction 的时间。
- `expired_at`：定时任务实际完成过期处理的时间。

### `credit_accounts`

保存信用账户额度。

核心字段：

- `credit_limit`：总额度。
- `reserved_amount`：已预占但尚未 posting 或释放的额度。
- `posted_balance`：已经入账、会进入未来账单的消费余额。
- `status`：账户是否可用。

当前额度公式：

```text
availableCredit = creditLimit - reservedAmount - postedBalance
```

授权阶段增加 `reservedAmount`；presentment posting 阶段把金额从 `reservedAmount` 移到 `postedBalance`；
repayment 阶段减少 `postedBalance`，恢复可用额度。

### `card_transactions`

保存持卡人可见的交易流水。

核心字段：

- `id`：card transaction id，由 `CardTransaction.pending(...)` 内部生成 UUID。
- `network_transaction_id`：外部网络/clearing 侧交易 id，也是 presentment 幂等键。
- `authorization_id`：对应原授权。
- `card_id` / `credit_account_id`：方便查询卡片明细和账户明细。
- `amount` / `currency`：入账金额。
- `status`：`PENDING`、`POSTED`。
- `presentment_received_at`：系统收到 presentment 的时间。
- `posted_at`：完成账户入账的时间。
- `statement_id`：这笔 posted transaction 已经进入哪一期账单，未出账时为空。
- `statement_assigned_at`：交易被账单收录的时间。

这里的 `CardTransaction` 是用户流水，不是完整会计 ledger。

### `statements`

保存一个信用账户某个 billing cycle 的账单快照。

核心字段：

- `id`：statement id，由 `Statement.close(...)` 内部 `UUID.randomUUID()` 生成。
- `credit_account_id`：账单属于哪个信用账户。
- `period_start` / `period_end`：账单周期，当前按 UTC 日期切分。
- `due_date`：还款到期日。
- `total_amount`：本期已入账交易汇总金额。
- `minimum_payment_amount`：当前简化规则计算出的最低还款额。
- `paid_amount`：还款阶段累计已经还入这张账单的金额；生成账单时为 0。
- `transaction_count`：本期收录的 posted transactions 数量。
- `status`：生成后为 `CLOSED`，还款后进入 `PARTIALLY_PAID` 或 `PAID`，未来逾期阶段会使用 `OVERDUE`。

幂等键：

```text
credit_account_id + period_start + period_end
```

同一账户同一账单周期只能生成一张 statement。

### `statement_items`

保存账单生成时对 posted `CardTransaction` 的快照。

核心字段：

- `id`：statement item id，由 `StatementItem.snapshot(...)` 内部生成 UUID。
- `statement_id`：属于哪张账单。
- `card_transaction_id`：来源交易，有唯一约束，防止一笔交易进入两张账单。
- `network_transaction_id` / `authorization_id` / `card_id`：从源交易复制的审计字段。
- `amount` / `currency` / `posted_at`：账单行金额和入账时间快照。

为什么要有 item 快照：

- 账单不是每次查询时临时 SUM 交易表。
- 生成账单后，用户看到的账单金额和明细应该稳定可审计。
- 后续 refund、adjustment、dispute 可以基于“是否已经出账”走不同处理路径。

### `repayments`

保存一次客户还款请求和最终入账结果。

核心字段：

- `id`：repayment id，由 `Repayment.pending(...)` 内部 `UUID.randomUUID()` 生成。
- `idempotency_key`：客户端传入的幂等键，有唯一索引。
- `request_fingerprint`：由 `ReceiveRepaymentCommand.requestFingerprint()` 计算，防止同一个幂等键支付不同账单或金额。
- `statement_id`：本次还款应用到哪张已生成 statement。
- `credit_account_id`：还款成功后回填，用于查询账户还款历史和 Kafka partition key。
- `amount` / `currency`：还款金额。
- `status`：`PENDING`、`RECEIVED`。
- `received_at`：还款真正应用到账户和账单的时间。

当前范围：

- 只支持支付一张已生成 statement。
- 不支持 overpayment，也不做多张账单之间的自动分摊。
- 还款成功后同时减少 `credit_accounts.posted_balance`，推进 `statements.paid_amount/status`。

### `ledger_entries`

保存最小内部账本分录。

核心字段：

- `id`：ledger entry id，由 `LedgerEntry.recordPurchasePosted(...)` 或
  `LedgerEntry.recordRepaymentReceived(...)` 内部 `UUID.randomUUID()` 生成。
- `source_event_id`：来源 integration event id，和 `entry_type` 一起做唯一约束，用于 ledger 侧幂等。
- `entry_type`：当前有 `CARD_TRANSACTION_POSTED` 和 `REPAYMENT_RECEIVED`。
- `direction`：`DEBIT` 或 `CREDIT`。当前项目里消费入账记 `DEBIT`，还款入账记 `CREDIT`。
- `source_type` / `source_id`：来源业务对象，例如 `CARD_TRANSACTION` + card transaction id，
  或 `REPAYMENT` + repayment id。
- `credit_account_id`：账本分录归属的信用账户。
- `amount` / `currency`：正数金额；方向由 `direction` 表达，不用负数金额。
- `occurred_at`：业务事实发生时间，例如 postedAt 或 receivedAt。

当前范围：

- 这是 minimal ledger projection，不是生产级 double-entry general ledger。
- 它异步消费 `card_transaction.posted` 和 `repayment.received`，不扩大 posting/repayment 主事务。
- Authorization 只表示额度 hold，不是真正入账，所以当前不生成 ledger entry。

### `delay_jobs`

保存未来需要执行的业务动作。

核心字段：

- `id`：delay job id，由 scheduler adapter 生成 UUID。
- `job_type`：当前有 `AUTHORIZATION_EXPIRY` 和 `AUTO_REPAYMENT`。
- `aggregate_type` / `aggregate_id`：指向业务对象，例如 `Authorization` + authorization id，或 `Statement` + statement id。
- `status`：`PENDING`、`PROCESSING`、`DONE`、`DEAD`。
- `scheduled_at`：业务上应该开始执行的时间。
- `next_attempt_at`：下一次可尝试执行的时间，也用于 retry/backoff/lease。
- `attempts` / `last_error`：失败重试和排障信息。

### `outbox_events`

保存等待发布的集成事件。

核心字段：

- `id`：event id，由 Outbox adapter 生成 UUID，下游用它做幂等去重。
- `aggregate_type` / `aggregate_id`：事件属于哪个业务对象。
- `event_type` / `event_version`：事件类型和版本。
- `partition_key`：Kafka 分区 key，按事件所属 aggregate 选择；authorization/card transaction 事件用自身 id，statement/repayment 事件用 credit account id。
- `payload`：JSON 字符串。
- `status`：`PENDING`、`PROCESSING`、`PUBLISHED`、`DEAD`。
- `next_attempt_at`：下一次可领取时间；`PROCESSING` 时也作为 publisher lease deadline。
- `attempts` / `last_error`：失败重试和排障信息。

### `notifications`

保存待发送或已发送的客户通知请求。

核心字段：

- `id`：notification id，由 `Notification.requestFromEvent(...)` 内部 `UUID.randomUUID()` 生成。
- `source_event_id`：来源 integration event id，有唯一索引，用于通知侧幂等。
- `subject_type` / `subject_id`：通知关联的业务对象，例如 `AUTHORIZATION`、`CARD_TRANSACTION`、`STATEMENT`、`REPAYMENT`。
- `recipient_key`：当前项目还没有用户/持卡人领域，所以暂时用 cardId 或 creditAccountId 作为通知路由线索。
- `template`：通知模板类型，例如 `AUTHORIZATION_APPROVED`、`CARD_TRANSACTION_POSTED`、`STATEMENT_READY`、`REPAYMENT_RECEIVED`。
- `status`：`PENDING`、`SENT`、`FAILED`。

提醒：

- `recipient_key` 不是生产系统里的真实用户 id。
- 以后加入 Cardholder/User 后，应该在 Notification 侧根据 subject 查 customerId 和 notification preference。
- 不要在 authorization/posting/statement 主事务里直接发 push/email/sms。
- 当前项目已经引入 Liquibase，`0002-sync-known-local-schema-drift.sql` 会把旧的
  `authorization_id` / `card_id` 通知表迁移到 `subject_type` / `subject_id` /
  `recipient_key`；这类字段语义升级不能只靠 `CREATE TABLE IF NOT EXISTS`。

## 3. 核心流程一：创建授权

示例请求：

```bash
curl -X POST http://localhost:8080/api/authorizations \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-auth-001' \
  -d '{
    "cardId": "card-001",
    "amount": 1000.00,
    "currency": "JPY",
    "merchantId": "merchant-001",
    "merchantCountry": "JP",
    "cardholderCountry": "JP"
  }'
```

### 3.1 Controller 阶段

入口：

- `AuthorizationController.authorize(...)`

处理：

- 从 header 读取 `Idempotency-Key`。
- 从 body 读取 `CreateAuthorizationRequest`。
- 创建 `AuthorizationCommand`。
- 调用 `authorizationService.authorize(command)`。

这里不做业务判断。Controller 的职责是 HTTP 到 application command 的转换。

### 3.2 Command 阶段

关键方法：

- `AuthorizationCommand.requestedAmount()`
- `AuthorizationCommand.requestFingerprint()`
- `AuthorizationCommand.toRiskAssessmentRequest()`

处理：

- `requestedAmount()` 把 `amount + currency` 组合成 `Money` value object。
- `requestFingerprint()` 把 card、merchant、country、amount、currency 组合成 canonical request，然后计算 SHA-256。
- `toRiskAssessmentRequest()` 把授权请求转换成风控模块输入。

为什么要有 fingerprint：

- 同一个 `Idempotency-Key` 可以被客户端安全重试。
- 但如果同一个 key 携带了不同金额或商户，就应该报 `IdempotencyConflictException`。

### 3.3 Application Service 事务阶段

入口：

- `AuthorizationService.authorize(command)`

事务：

- 方法上有 `@Transactional`。
- 授权状态、额度预占、delay job、outbox event 在同一个 MySQL transaction 中提交。

关键顺序：

1. `Authorization.request(...)`

   创建 `PENDING` aggregate。
   这里生成 `authorization.id = UUID.randomUUID()`。

2. `authorizationRepository.claim(idempotencyKey, pending)`

   第一笔数据库写入。
   它依赖 `authorizations.idempotency_key` 唯一索引，抢到唯一的 idempotency winner。

3. `findByIdempotencyKeyForUpdate(idempotencyKey)`

   用 `SELECT ... FOR UPDATE` 读取该幂等键对应的 authorization。
   如果两个 pod 同时收到相同重试，loser 会等待 winner 完成事务，然后读取 winner 的最终状态。

4. `assertSameIdempotentRequest(persisted, command)`

   比较 fingerprint。
   同一个 key + 相同请求：允许返回已有结果。
   同一个 key + 不同请求：抛幂等冲突。

5. `decideAndReserve(persisted, command, now)`

   只有 idempotency winner 会进入真实决策。

### 3.4 决策和额度预占阶段

入口：

- `AuthorizationService.decideAndReserve(...)`

关键顺序：

1. `AuthorizationService.checkSingleTransactionLimit(authorization)`

   执行便宜的本地授权限额检查。
   这些检查放在账户锁之前，减少锁持有时间。

2. `cardRepository.findById(...)`

   查询 card aggregate。当前实际 Bean 是 `CachedCardRepository`：
   先查 `card-snapshot-v1`，miss 时再回到 `MyBatisCardRepository` 查询 `cards` 表。

   Card 回答“这张卡是否存在、是否被 blocked、是否过期”。
   这个 cache 只保存 `CardSnapshot(id, creditAccountId, status)`，不缓存额度，也不替代后面的
   `credit_accounts` row lock。

3. `riskAssessmentService.assess(command.toRiskAssessmentRequest())`

   做风控判断。
   本地 velocity 查询通过 `RiskVelocityCounter` port 进入当前 JDBC adapter；
   外部评分通过 `ExternalRiskGateway` port 进入 Feign adapter。
   它放在 credit account row lock 之前，是为了避免外部调用或复杂计算占着账户锁。

4. `creditAccountRepository.findByIdForUpdate(...)`

   对 `credit_accounts` 执行 `SELECT ... FOR UPDATE`。
   这是额度并发控制核心：同一账户的多个授权请求会在这里排队。

5. `account.reserve(authorization.requestedAmount())`

   在 domain 内检查：

   - 账户是否 ACTIVE。
   - 币种是否一致。
   - `availableCredit = creditLimit - reservedAmount` 是否足够。

6. `creditAccountRepository.update(account)`

   保存新的 `reserved_amount`。

7. `authorization.approve(now)`

   状态从 `PENDING` 变成 `APPROVED`。
   同时生成 `expiresAt = now + 7 days`。

如果任一步拒绝，authorization 会变成 `DECLINED`，并保存 decline reason。

### 3.5 写 DelayJob

入口：

- `AuthorizationExpiryDelayJobScheduler.schedule(authorization)`

处理：

- 只在 authorization 是 `APPROVED` 时执行。
- 创建 `DelayJob.pending(...)`。
- `job_type = AUTHORIZATION_EXPIRY`。
- `aggregate_type = Authorization`。
- `aggregate_id = authorization.id`。
- `scheduled_at = authorization.expiresAt`。
- `delay_jobs.id = UUID.randomUUID()`。

为什么和 authorization approval 同事务：

- 如果授权批准成功，就必须有未来释放额度的计划。
- 如果事务 rollback，authorization 和 delay job 都不会留下。

### 3.6 写 Outbox

入口：

- `Authorization.approve(...)` / `Authorization.decline(...)`
- `AuthorizationService.publishDomainEvents(authorization)`
- `AuthorizationDomainEventPublisher.append(event)`
- `AuthorizationOutboxAdapter.append(event)`

处理：

- 如果结果是 `APPROVED`，`Authorization.approve(...)` 在状态变成 `APPROVED` 的同一处记录 `AuthorizationApprovedDomainEvent`。
- 如果结果是 `DECLINED`，`Authorization.decline(...)` 在状态变成 `DECLINED` 的同一处记录 `AuthorizationDeclinedDomainEvent`。
- `AuthorizationService` 保存 authorization 后调用 `authorization.pullDomainEvents()`，把领域事实交给 `AuthorizationDomainEventPublisher`。
- 创建 `IntegrationEvent`，包含 event metadata 和 JsonNode payload。
- `eventId = UUID.randomUUID()`，由 `AuthorizationOutboxAdapter` 生成，因为它是 outbound message id，不是 authorization aggregate id。
- 写入 `outbox_events`，状态为 `PENDING`。

为什么区分 domain event 和 payload：

- Domain event 属于 Authorization bounded context，只表达业务事实。
- payload 是对外 JSON contract，需要稳定 eventType/version/字段名，但当前不为每种消息单独建 Java payload class。
- `AuthorizationOutboxAdapter` 负责生成 eventId、映射 eventType/payload、序列化 envelope、写 Outbox。
- Authorization domain 不知道 Kafka、topic、headers 或 Outbox 表。

为什么不在 service 里 new domain event：

- DDD 的关键是“状态转换在哪里发生，业务事实就在哪里产生”。
- 如果 service 根据 `authorization.status()` 反推事件，service 就在猜 domain 已经发生的事实，边界会变模糊。
- 当前做法是 aggregate 负责产生 `domain event`，application service 负责 transaction boundary、repository 调用顺序和 Outbox 发布意图。

为什么 approved/declined 拆成不同 event type：

- `APPROVED` 和 `DECLINED` 是不同业务事实，不应该靠 `isDeclined` 或 status 字段让 consumer 猜。
- Consumer 可以按 `eventType` 只处理自己关心的事件。
- 未来新增 posted、reversal、reconciliation 时，可以继续增加明确事件类型。
- 不同事件的 payload 可以不同，例如 declined 有 `declineReason`，approved 有 `expiresAt`。

消费者如何处理多种事件：

- `IntegrationEventReader` 是共享 transport reader，只负责读取 JSON envelope、校验 header，并返回 JsonNode payload。
- Notification 侧按上游领域拆 listener：
  - `AuthorizationNotificationListener` 处理 `authorization.approved` 和 `authorization.declined`。
  - `CardTransactionNotificationListener` 处理 `card_transaction.posted`。
  - `StatementNotificationListener` 处理 `statement.closed`。
  - `RepaymentNotificationListener` 处理 `repayment.received`。
- Risk listener 仍只处理 `authorization.approved` 和 `authorization.declined`，因为风控投影只关心授权决策历史。
- “不感兴趣的合法事件”不应该被当成坏消息送进 DLT。

为什么按上游领域拆 listener：

- `authorization`、`CardTransaction`、`Statement`、`Repayment` 是不同上游领域，Notification 只是独立消费它们的 integration events。
- 按上游领域拆 inbound adapter，更接近未来 notification 微服务的真实形态。
- 每个 listener 显式检查 `eventType`，新增事件时不会靠一个大而模糊的 boolean/status 字段让 consumer 猜。

为什么 Notification 用 `NotificationType` enum：

- `approved=true/false` 只能表达两种 decision，无法自然扩展到 posted、refund、reversal。
- enum 让 listener 负责把 `eventType` 翻译成“要发哪一种用户通知”，application service 只负责创建通知请求。
- `consumer_inbox` 是第一道 consumer-side idempotency；`notifications.source_event_id` 是第二道保护，保证 Kafka at-least-once 重复投递不会创建重复通知。

为什么不在这里直接发 Kafka：

- 如果业务事务成功但 Kafka 发布失败，会丢事件。
- 如果 Kafka 发布成功但业务事务 rollback，会发出不存在的业务事实。
- Outbox 的做法是：主事务只写 event intent，后台可靠发布。

### 3.7 响应阶段

返回：

- `AuthorizationResponse.from(authorization)`

典型 APPROVED 响应包含：

- `id`
- `cardId`
- `amount`
- `currency`
- `status = APPROVED`
- `declineReason = null`
- `createdAt`
- `decidedAt`
- `expiresAt`

数据变化总结：

- `authorizations`：新行 `PENDING -> APPROVED` 或 `DECLINED`。
- `credit_accounts`：如果批准，`reserved_amount` 增加。
- `delay_jobs`：如果批准，新增 `AUTHORIZATION_EXPIRY/PENDING`。
- `outbox_events`：新增 `authorization.approved/PENDING` 或 `authorization.declined/PENDING`。

## 4. 核心流程二：Presentment Posting 交易入账

当前业务目标：

- 授权批准只是 hold 额度，真实信用卡后台还需要处理商户/网络提交的 presentment。
- 在 issuer 视角，这一步最准确的名字是 posting：把交易 posted to account。
- 本项目 API 使用 `Presentment` 命名输入，用 `PostingService` 表达发卡行入账用例。

示例请求：

```bash
curl -X POST http://localhost:8080/api/presentments \
  -H 'Content-Type: application/json' \
  -d '{
    "networkTransactionId": "ntx-001",
    "authorizationId": "fb6933e2-20ea-4268-b1c2-21c6705b1884",
    "amount": 1000.00,
    "currency": "JPY"
  }'
```

入口：

- `PresentmentController.postPresentment(...)`
- `PostPresentmentCommand`
- `PostingService.post(command)`
- `CardTransactionRepository.claim(transaction)`
- `Authorization.post(now)`
- `CreditAccount.postAuthorized(amount)`

### 4.1 为什么叫 Presentment + Posting

- `Presentment`：外部网络/商户把已授权交易正式提交给发卡行。
- `Posting`：发卡行把这笔交易计入持卡人账户，形成用户可见的 `CardTransaction`。
- `Capture` 更偏商户/收单侧语言，不适合作为 PayPay Card issuer backend 的主模块名。
- `Settlement` 是资金清算，不等于持卡人账户入账，本阶段不把它混进来。

### 4.2 PostingService transaction boundary

关键顺序：

1. `authorizationRepository.findByIdForUpdate(authorizationId)`

   先锁住 authorization row。
   这样同一笔授权不会被两个不同 presentment 同时 posted。

2. `transactionRepository.findByNetworkTransactionIdForUpdate(networkTransactionId)`

   先检查同一个外部 presentment 是否已经处理过。
   如果已存在并且内容一致、状态是 `POSTED`，说明这是 duplicate retry，直接返回已有 `CardTransaction`。
   这条路径不会重复释放 hold，也不会再次增加 `postedBalance`。

3. 校验 authorization

   当前阶段只支持 full presentment：

   - authorization 必须是 `APPROVED`。
   - presentment amount/currency 必须等于 authorization amount/currency。

   partial presentment 会引入 remaining hold，后续可以扩展，但现在先不提前复杂化。

4. `creditAccountRepository.findByIdForUpdate(accountId)`

   锁住账户 row。
   这让 posting、statement generation 和新的 authorization reserve 在同一个账户上串行化。

   这次改动的重点是：新 presentment 只有拿到账户锁后才创建/claim `CardTransaction`。
   因为 `StatementService` 也先锁账户，再锁待出账交易，统一锁顺序可以避免 posting/statement 死锁，
   同时防止账单生成期间漏掉正在入账的交易。

5. `CardTransaction.pending(...)`

   创建 `PENDING` 交易流水，`id = UUID.randomUUID()`。
   这里的 `networkTransactionId` 是外部网络交易 id，也是 presentment idempotency key。

6. `transactionRepository.claim(transaction)`

   插入 `card_transactions`，依赖 `network_transaction_id` 唯一索引抢占幂等所有权。
   如果 duplicate retry 已经存在同一条 posted transaction，就直接返回已有结果，不重复改余额。

7. `account.postAuthorized(amount)`

   状态变化：

   ```text
   reservedAmount -= amount
   postedBalance += amount
   ```

   注意 available credit 不会因为 full posting 改变，因为它只是从 hold 额度变成已入账余额：

   ```text
   before: creditLimit - reservedAmount - postedBalance
   after : creditLimit - (reservedAmount - amount) - (postedBalance + amount)
   ```

8. `authorization.post(now)`

   状态变化：

   ```text
   APPROVED -> POSTED
   postedAt = now
   ```

   `Authorization` aggregate 自己记录 `AuthorizationPostedDomainEvent`。

9. `transaction.markPosted(now)`

   状态变化：

   ```text
   CardTransaction PENDING -> POSTED
   postedAt = now
   ```

10. 同一事务内提交

   - `credit_accounts.reserved_amount` 减少。
   - `credit_accounts.posted_balance` 增加。
   - `authorizations.status = POSTED`。
   - `card_transactions.status = POSTED`。
   - `outbox_events` 新增 `authorization.posted/PENDING`，表达 authorization lifecycle 已被 presentment 消耗。
   - `outbox_events` 新增 `card_transaction.posted/PENDING`，表达用户可见交易已经入账。

11. Outbox 后续异步发布 `card_transaction.posted`

   - 主 posting transaction 不直接调用 Notification，也不等待 Kafka。
   - Outbox worker 发布 Kafka 后，Notification consumer 收到 `card_transaction.posted`。
   - `CardTransactionNotificationListener` 把 eventType 转成 `NotificationType.CARD_TRANSACTION_POSTED`。
   - `RequestNotificationService` 先写 `consumer_inbox(notification-v1, eventId)` 做 Inbox claim。
   - claim 成功后创建一条 `notifications/PENDING` 请求。
   - duplicate delivery claim 失败后直接返回，避免重复通知用户。

12. Ledger 异步消费 `card_transaction.posted`

   - `CardTransactionLedgerListener` 收到同一个 Kafka event。
   - `RecordLedgerEntryService` 先写 `consumer_inbox(ledger-v1, eventId)` 做 Inbox claim。
   - claim 成功后调用 `LedgerEntry.recordPurchasePosted(...)`，这里生成 `ledger_entries.id`。
   - `ledger_entries` 新增一条 `CARD_TRANSACTION_POSTED/DEBIT` 分录，表达 issuer 对持卡人的应收款增加。
   - 如果 Kafka 重复投递，同一个 eventId 会被 Inbox 或 `source_event_id + entry_type` 唯一约束挡住。

为什么这一阶段只做 minimal ledger：

- `CardTransaction` 是用户流水，用于 APP 明细、账单和客服查询。
- 当前 `LedgerEntry` 是学习用 projection，用来解释账本概念和 consumer idempotency。
- 生产级 `Ledger` 通常需要 double-entry、accounting account、journal balance 校验。
- `Reconciliation` 是把内部记录和外部文件/资金清算结果对比，发现 missing/duplicate/amount mismatch。
- 本项目先记录消费入账和还款入账两类分录，不处理 settlement、fee、interest 或 refund adjustment。

PayPay Card interview提示：

- issuer backend 里 authorization 和 posting 是两段生命周期，不能混成一个状态。
- `networkTransactionId` 是外部幂等键，能防止 presentment retry 造成 double posting。
- `Authorization` row lock 防止同一授权被多次入账；`CreditAccount` row lock 同时保护 posting、statement generation 和额度并发。
- 本阶段把 settlement 留到后面，是因为 settlement 处理资金清算，不是用户账户入账。
- posting 成功通知通过 `CardTransactionPostedDomainEvent -> Outbox -> Kafka -> Notification Inbox` 异步完成，不扩大 posting 的 transaction boundary。
- posting 成功账本分录通过 `card_transaction.posted -> Ledger Inbox -> ledger_entries/DEBIT` 异步完成；它是 learning projection，不是授权主事务的一部分。

## 5. 核心流程三：Statement 月度批处理生成账单

当前业务目标：

- 把一个 billing cycle 内已经 `POSTED`、但还没有进入账单的 `CardTransaction` 汇总成 statement。
- 固定账单金额、最低还款额和行项目快照。
- 标记这些交易已经被该 statement 收录，避免下次重复出账。
- 为账单 `dueDate` 写入 `AUTO_REPAYMENT` DelayJob，到期后自动模拟银行扣款。

真实主路径：

```text
StatementBatchPoller
-> StatementBatchService
-> StatementService.generate(...)
```

当前默认产品规则：

```text
close-day-of-month = 31
payment-base-day-of-month = 27
```

例如 6 月 30 日关账，scheduler 在 7 月 1 日跑批处理：

```text
periodStart = 6 月 1 日
periodEnd   = 6 月 30 日
dueDate     = 7 月 27 日
```

为什么是关账日次日执行：

- 如果 6 月 30 日白天刚开始就关账，当天后续 posting 的交易可能变成迟到交易。
- 当前项目统一用 UTC 日切，先让 6 月 30 日完整结束，再在 7 月 1 日批处理。
- 生产系统通常会把 billing timezone 放在产品或账户配置中。

为什么扣款日用 27 日并顺延到日本营业日：

- 25 日常见为工资日，当天银行入账和资金可用可能有时间差。
- 默认 27 日扣款更保守，给工资入账和用户资金准备留出缓冲。
- `JapaneseBusinessDayCalendar` 会把周末、日本法定节假日、振替休日和国民の休日视为非营业日。
- 如果 27 日不是日本营业日，就顺延到之后第一个营业日。

手动 backfill / 学习入口仍然保留：

```bash
curl -X POST http://localhost:8080/api/statements/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "creditAccountId": "11111111-1111-1111-1111-111111111111",
    "periodStart": "2026-06-01",
    "periodEnd": "2026-06-30",
    "dueDate": "2026-07-27"
  }'
```

入口：

- `StatementBatchPoller.closeDueBillingCycles()`
- `StatementBatchService.runDueBatch()`
- `StatementBatchRepository.findCreditAccountIdsWithUnbilledPostedTransactions(...)`
- `StatementController.generate(...)`
- `GenerateStatementCommand`
- `StatementService.generate(command)`
- `Statement.close(...)`
- `CardTransaction.assignToStatement(statementId, now)`
- `StatementDueJobScheduler.scheduleAutoRepayment(statement)`
- `StatementOutboxAdapter.append(event)`

### 5.1 Batch 和 Command 阶段

`StatementBatchPoller` 是很薄的 scheduler：

- 用 `@Scheduled` 每分钟醒一次。
- 只有“关账日次日”才真正运行 batch。
- 不在 scheduler 方法里持有大事务。
- 不直接拼 SQL 锁业务表。

`StatementBatchService` 负责：

- 根据 `statement.batch.close-day-of-month` 判断昨天是否是关账日。
- 计算 `periodStart / periodEnd / dueDate`。
- 查询有未出账 posted transactions 的 candidate account ids。
- 对每个账户分别调用 `StatementService.generate(...)`。

这里刻意不是一个大事务：

- 一个账户失败不能拖垮整批。
- 每个账户自己的 statement generation 都有清楚的 transaction boundary。
- 行锁持有时间短，便于未来横向扩展 batch worker。

`StatementController` 只负责 HTTP request/response mapping：

- 从 body 读取 `creditAccountId`、`periodStart`、`periodEnd`、`dueDate`。
- 创建 `GenerateStatementCommand`。
- 调用 `statementService.generate(command)`。

`GenerateStatementCommand` 会校验：

- `periodEnd` 不能早于 `periodStart`。
- `dueDate` 必须晚于 `periodEnd`。

HTTP 入口现在不是主业务入口，而是本地学习、测试和运营 backfill 的辅助入口。

### 5.2 StatementService transaction boundary

关键顺序：

1. `creditAccountRepository.findByIdForUpdate(creditAccountId)`

   先锁住 `credit_accounts` row。
   这一步不是为了修改账户金额，而是作为账单生成窗口的 concurrency gate。

   `PostingService` 现在也先锁 account，再创建/claim `CardTransaction`。
   因此同一账户上：

   ```text
   posting
   statement generation
   authorization reserve
   ```

   会围绕同一行账户锁串行化，避免账单漏掉正在入账的交易。

2. `statementRepository.findByCycleForUpdate(...)`

   查询同一账户同一账单周期是否已经生成过 statement。

   幂等键是：

   ```text
   credit_account_id + period_start + period_end
   ```

   如果已经存在，直接返回已有账单。
   这就是 statement generation 的 idempotency，不需要客户端额外传 `Idempotency-Key`。

3. `transactionRepository.findUnbilledPostedByCreditAccountForUpdate(...)`

   查询并锁住本周期内：

   - `status = POSTED`
   - `statement_id IS NULL`
   - `posted_at >= periodStart`
   - `posted_at < periodEnd + 1 day`

   的交易。

   SQL 使用 `FOR UPDATE`，防止两个并发生成流程把同一笔交易收进不同 statement。

4. `Statement.close(...)`

   生成 `statement.id = UUID.randomUUID()`。

   同时为每笔交易生成 `StatementItem.snapshot(...)`：

   - `statement_items.id = UUID.randomUUID()`
   - 复制 `cardTransactionId`
   - 复制 `networkTransactionId`
   - 复制 `authorizationId`
   - 复制 `cardId`
   - 复制 `amount/currency/postedAt`

   这一步产生稳定的账单快照。

5. 计算最低还款额

   当前简化规则在 `statement.policy` 中配置：

   ```text
   minimumPayment = min(totalAmount, max(totalAmount * 10%, currencyFloor))
   ```

   例如：

   ```text
   totalAmount = 1500 JPY
   10% = 150 JPY
   JPY floor = 1000 JPY
   minimumPayment = 1000 JPY
   ```

   真实系统会按产品条款、余额分层、监管要求和历史账龄计算，本项目先保留可解释的简化版本。

6. `statementRepository.insert(statement)`

   同一事务内插入：

   - `statements`
   - `statement_items`

   如果唯一键冲突，service 会回读已有 statement，作为 defensive idempotency fallback。

7. `transaction.assignToStatement(statement.id(), now)`

   对每个被收录的 `CardTransaction` 写入：

   - `statement_id`
   - `statement_assigned_at`

   这不是把交易变成 statement 的子对象，而是记录这笔 posted transaction 已经被哪期账单收录。
   下次生成账单时 `statement_id IS NULL` 条件会跳过它。

8. `StatementDueJobScheduler.scheduleAutoRepayment(statement)`

   这一步写入 `delay_jobs/PENDING`：

   ```text
   job_type = AUTO_REPAYMENT
   aggregate_type = Statement
   aggregate_id = statement.id
   scheduled_at = dueDate 00:00 UTC
   ```

   为什么用 DelayJob：

   - 自动扣款是未来某一天要执行的业务动作，不是“现在发布一条消息”。
   - DelayJob 的 `PROCESSING lease`、retry 和 `DEAD` 状态适合表达到期执行与失败恢复。
   - 这条 job 和 statement/items/transaction assignment 在同一个 MySQL transaction boundary 内提交，
     防止“账单生成了，但没有自动扣款计划”。

9. `StatementClosedDomainEvent`

   `Statement.close(...)` 在 aggregate 内部记录 `StatementClosedDomainEvent`。
   `StatementService` 保存账单和交易归属后，调用 `statement.pullDomainEvents()`，
   再交给 `StatementOutboxAdapter` 写入 `outbox_events/PENDING`。

10. Outbox 后续异步发布 `statement.closed`

   - 主 statement transaction 不直接调用 Notification，也不等待短信/邮件/Push provider。
   - Outbox worker 发布 Kafka 后，`StatementNotificationListener` 收到 `statement.closed`。
   - Listener 把内部事件映射成用户侧 `NotificationType.STATEMENT_READY`。
   - `RequestNotificationService` 先写 `consumer_inbox(notification-v1, eventId)` 做 Inbox claim。
   - claim 成功后创建一条 `notifications/PENDING` 请求。
   - duplicate delivery claim 失败后直接返回，避免重复提醒用户。

   警示：

   - 账单已经生成成功后，通知失败不能 rollback statement。
   - 当前没有 Cardholder/User 表，所以 statement 通知暂时用 `creditAccountId` 做 `recipient_key`。
   - 生产系统应在 Notification 侧查找用户和通知偏好，例如是否允许 push/email、是否静默时段。

### 5.3 本次事务提交后的数据变化

成功生成后：

- `statements` 新增一行，`status = CLOSED`。
- `statement_items` 新增多行，保存账单行项目快照。
- 被收录的 `card_transactions.statement_id` 指向该 statement。
- 被收录的 `card_transactions.statement_assigned_at` 记录归账时间。
- `delay_jobs` 新增 `AUTO_REPAYMENT/PENDING`，计划在 `dueDate` 自动扣款。
- `outbox_events` 新增 `statement.closed/PENDING`。

不会变化：

- `credit_accounts.posted_balance` 不会减少。
- `credit_accounts.reserved_amount` 不会变化。
- `CardTransaction.status` 仍然是 `POSTED`。

原因：

- 账单生成只是把已入账消费固定成账单，不代表用户已经还款。
- Repayment 阶段才会减少 `postedBalance`，并恢复可用额度。

### 5.4 PayPay Card interview提示

- Statement 是 posted transaction 的账单快照，不是实时查询结果。
- 真实主路径是 billing batch，不是用户实时请求；HTTP generate 只是 backfill/学习入口。
- 账单周期用自然唯一键实现 idempotency：同一账户同一周期只能有一张账单。
- 先锁 credit account，再锁待出账交易，是为了和 posting 保持统一锁顺序，避免死锁和漏账。
- `statement_items` 保存历史快照，方便解释 audit trail、客服查询和账单 PDF。
- `AUTO_REPAYMENT` 使用 DelayJob，因为它是 future business action；`statement.closed` 使用 Outbox，因为它是 integration event。
- `statement.closed` 通过 Outbox 异步发布，Notification 已消费它来创建 `STATEMENT_READY` 通知；未来 PDF 生成、还款提醒也可以独立消费。
- 账单生成后 `paid_amount = 0` 且状态为 `CLOSED`；Repayment 会继续推进到 `PARTIALLY_PAID` 或 `PAID`。

## 6. 核心流程四：Repayment 还款入账

当前业务目标：

- 到 `dueDate` 后自动从银行账户扣款，默认模拟成功。
- 也保留客户/API 主动还款入口，方便学习和测试。
- 银行扣款成功后，针对一张已生成 statement 入账。
- 还款成功后减少 `credit_accounts.posted_balance`，恢复可用额度。
- 同时推进 `statements.paid_amount/status`。
- 发布 `repayment.received`，让 Notification 和 Ledger 可以异步消费；未来 Reconciliation 也可以基于它对账。

当前刻意不做：

- 不支持 overpayment。
- 不把一笔还款自动分摊到多张 statement。
- 不新增客户自定义扣款日或 `bank_accounts` 表；`SimulatedBankDebitGateway` 先假设客户已有默认扣款授权。
- 不引入真实银行网络、资金清算文件或生产级 double-entry ledger。

自动扣款主路径：

```text
AUTO_REPAYMENT DelayJob due
-> AutoRepaymentDelayJobHandler
-> AutoRepaymentService.debitStatement(statementId)
-> SimulatedBankDebitGateway
-> RepaymentService.receive(...)
```

银行结果当前有两种预设：

- `SUCCESS`：默认配置，进入 `RepaymentService.receive(...)` 完成入账。
- `FAILED`：不调用 `RepaymentService`，让 DelayJob 记录失败并按 retry/DEAD 策略处理；未来可扩展失败通知、再次扣款或逾期流程。

示例请求：

```bash
curl -X POST http://localhost:8080/api/repayments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-repayment-001' \
  -d '{
    "statementId": "22222222-2222-2222-2222-222222222222",
    "amount": 500.00,
    "currency": "JPY"
  }'
```

### 6.1 AutoRepayment due-date 阶段

入口：

- `DelayJobPoller.pollDueJobs()`
- `DelayJobWorker.handleClaimedJob(job)`
- `AutoRepaymentDelayJobHandler.handle(job)`
- `AutoRepaymentService.debitStatement(statementId)`
- `SimulatedBankDebitGateway.debit(request)`
- `RepaymentService.receive(command)`

关键点：

1. `AutoRepaymentDelayJobHandler`

   `DelayJobWorker` 根据 `job_type = AUTO_REPAYMENT` 找到 handler。
   handler 校验 `aggregate_type = Statement`，再把 `aggregate_id` 转成 statement id。

2. `AutoRepaymentService.debitStatement(...)`

   先读取 statement：

   - 如果已经 `PAID`，说明用户可能提前主动还款或上一次 retry 已经成功，直接返回。
   - 否则用 `statement.remainingAmount()` 作为自动扣款金额。

3. `SimulatedBankDebitGateway`

   当前默认返回：

   ```text
   BankDebitStatus.SUCCESS
   ```

   如果配置成 `FAILED`，service 会抛 `AutoRepaymentFailedException`。
   这时不会写 `repayments`，因为银行资金并没有到账。

4. 自动构造 `ReceiveRepaymentCommand`

   成功时使用确定性幂等键：

   ```text
   auto-debit:{statementId}
   ```

   这让 DelayJob worker 宕机、超时、重复执行时不会重复入账。

5. 复用 `RepaymentService.receive(...)`

   自动扣款成功后的入账，和 API 主动还款走同一套：

   - repayment idempotency claim
   - credit account row lock
   - statement row lock
   - account/statement 状态更新
   - `repayment.received` Outbox

这样做的重点是：自动扣款只是 repayment 的上游资金来源，真正“账单已还款”的一致性逻辑不复制一份。

### 6.2 Controller 和 Command 阶段

入口：

- `RepaymentController.receive(...)`
- `ReceiveRepaymentRequest`
- `ReceiveRepaymentCommand`

处理：

- Controller 校验 `Idempotency-Key` header 和 JSON body。
- `ReceiveRepaymentCommand.money()` 把 `BigDecimal + Currency` 转成 `Money` value object。
- `ReceiveRepaymentCommand.requestFingerprint()` 生成 canonical fingerprint：

```text
statementId | amount | currency
```

为什么要 fingerprint：

- 同一个 `Idempotency-Key` 可以重复提交同一还款请求。
- 但不能第一次给 statement A 还 500，第二次拿同一个 key 给 statement B 还 1000。
- 这种请求会抛 `RepaymentConflictException`，API 返回 `REPAYMENT_CONFLICT`。

### 6.3 RepaymentService transaction boundary

入口：

- `RepaymentService.receive(command)`

关键顺序：

1. 非锁定读取 statement

   `statementRepository.findById(statementId)` 先确认 statement 存在，并拿到 `creditAccountId`。
   这个读取不做余额决策。

   为什么可以这样做：

   - `statement.creditAccountId` 是 statement 的归属字段，生成后不应该变化。
   - 先查存在性可以避免 `repayments.statement_id` 外键错误变成晦涩的数据库异常。
   - 真正的还款金额和状态判断，会在后面 `FOR UPDATE` 锁住 statement 后重新校验。

2. 创建 `Repayment.pending(...)`

   `repayment.id = UUID.randomUUID()` 在 domain factory 内生成。
   新还款先进入 `PENDING`，表达幂等 ownership 已经准备被 claim。

3. `repaymentRepository.claim(pending)`

   MyBatis 插入 `repayments/PENDING`。
   `repayments.idempotency_key` 唯一索引决定并发 winner。

   interview 重点：

   - 这是 INSERT-first idempotency。
   - 不靠 read-then-insert，因为并发下两个线程可能同时读到不存在。
   - loser 不会继续改 account 或 statement。

4. `findByIdempotencyKeyForUpdate(...)`

   用 `SELECT ... FOR UPDATE` 锁定同一个 repayment row。
   duplicate retry 会等待 winner 提交，然后读取最终 `RECEIVED` 结果。

5. 锁定 credit account

   `creditAccountRepository.findByIdForUpdate(creditAccountId)`。

   这里沿用全项目锁顺序：

```text
credit account row lock -> statement row lock
```

   这样 repayment 不会和 `StatementService.generate(...)` 的锁顺序相反，降低死锁风险。

6. 锁定 statement

   `statementRepository.findByIdForUpdate(statementId)`。
   锁住后重新校验：

   - statement 属于刚才锁住的 credit account。
   - 币种和 statement/account 一致。
   - statement 不是 `PAID`。
   - 还款金额不超过 statement remaining amount。
   - 还款金额不超过 account posted balance。

7. 更新两个 aggregate

   - `account.applyRepayment(amount)`：减少 `postedBalance`。
   - `statement.applyRepayment(amount, now)`：增加 `paidAmount`，状态变为 `PARTIALLY_PAID` 或 `PAID`。

   这两个变更在同一个 MySQL transaction boundary 内提交。
   任一步失败都会 rollback，避免“账单显示已还但额度没恢复”或相反的不一致。

8. `Repayment.markReceived(...)`

   `Repayment` 从 `PENDING -> RECEIVED`。
   domain 内部产生 `RepaymentReceivedDomainEvent`。

9. 保存并写 Outbox

   - `creditAccountRepository.update(account)`
   - `statementRepository.updatePayment(statement)`
   - `repaymentRepository.update(repayment)`
   - `repayment.pullDomainEvents()`
   - `RepaymentOutboxAdapter.append(event)`

   `repayment.received` 的 Outbox row 和 account/statement/repayment 状态一起 commit。

10. Outbox 后续异步发布 `repayment.received`

   - `KafkaOutboxMessagePublisher` 看到 `eventType` 以 `repayment.` 开头，路由到 `mini-card.repayment-events.v1`。
   - `RepaymentNotificationListener` 消费 `repayment.received`。
   - Listener 把业务事实映射成 `NotificationType.REPAYMENT_RECEIVED`。
   - `RequestNotificationService` 用 `consumer_inbox` 和 `notifications.source_event_id` 做 consumer-side idempotency。

11. Ledger 异步消费 `repayment.received`

   - `RepaymentLedgerListener` 收到同一个 Kafka event。
   - `RecordLedgerEntryService` 先写 `consumer_inbox(ledger-v1, eventId)` 做 Inbox claim。
   - claim 成功后调用 `LedgerEntry.recordRepaymentReceived(...)`，这里生成 `ledger_entries.id`。
   - `ledger_entries` 新增一条 `REPAYMENT_RECEIVED/CREDIT` 分录，表达 issuer 对持卡人的应收款减少。
   - 这一步失败不会 rollback repayment 主事务；Kafka retry/DLT 和人工重放负责修复 ledger projection。

### 6.4 本次事务提交后的数据变化

成功还款后：

- `repayments`：`PENDING -> RECEIVED`，记录 `received_at` 和 `credit_account_id`。
- `credit_accounts.posted_balance` 减少，还款金额重新释放为可用额度。
- `statements.paid_amount` 增加。
- `statements.status` 变为 `PARTIALLY_PAID` 或 `PAID`。
- `outbox_events` 新增 `repayment.received/PENDING`。
- Outbox 发布后，`ledger_entries` 会异步新增 `REPAYMENT_RECEIVED/CREDIT`。

不会变化：

- `card_transactions` 不会被改写。
- `statement_items` 不会被改写。
- `reserved_amount` 不会变化。

原因：

- 还款改变的是账户余额和账单还款状态，不改变历史交易流水和账单行快照。
- audit trail 里“消费发生过”和“后来还款了”是两类事实。

### 6.5 PayPay Card interview提示

- Repayment 是独立业务领域，不应该塞进 `transaction` 或 `statement` 里当普通 helper。
- 还款 API 同样需要 idempotency，因为客户端重试或支付回调重复很常见。
- 自动扣款使用确定性 `auto-debit:{statementId}` 幂等键，避免 DelayJob retry 造成 double repayment。
- 银行扣款成功/失败和 repayment 入账要分清：失败不能创建 `repayments/RECEIVED`。
- 锁顺序保持 `credit account -> statement`，是为了和账单生成保持一致，避免死锁。
- `Statement` 负责保护 `paidAmount/status`，`CreditAccount` 负责保护 `postedBalance`，service 负责 transaction boundary。
- `repayment.received -> Outbox -> Kafka -> Notification Inbox -> notification row` 让通知失败不影响还款主事务。

## 7. 核心流程五：Outbox 发布消息

入口：

- `OutboxPoller.pollPublishableEvents()`
- `OutboxClaimer.claimPublishableEvents()`
- `OutboxWorker.publishClaimedEvent(event)`
- `OutboxRecoverer.recoverStuckEvents()`

触发方式：

- Spring `@Scheduled`
- `outboxTaskScheduler` 只负责 poller/recoverer 的定时触发。
- `outboxWorkerExecutor` 负责并发等待 Kafka acknowledgement。

关键步骤：

1. `OutboxClaimer.claimPublishableEvents()`

   claimer 在短事务内调用 `findPublishableBatchForUpdate(now, batchSize)`。
   MyBatis XML 使用：

   ```sql
   FOR UPDATE SKIP LOCKED
   ```

   多个 pod 可以同时扫描 outbox，但不会领取同一条可用 row。

   领取后调用：

   - `event.markProcessing(now, processingTimeoutSeconds)`
   - `outboxEventRepository.updateDeliveryState(event)`

   这一步提交后，DB row lock 释放。

2. `OutboxPoller` submit worker

   claim transaction commit 后，poller 把每条 `PROCESSING` event 提交给 `outboxWorkerExecutor`。
   poller 不直接发 Kafka，也不提前 mark published。

3. `OutboxWorker.publishClaimedEvent(event)`

   在事务外把已保存的 JSON payload 发布到 Kafka。
   这样等待 broker acknowledgement 时，不会长时间持有 MySQL row lock。

4. `markPublished(event)`

   重新开启短事务，`findByIdForUpdate(event.id)` 锁住当前 row。
   如果 row 仍然是同一个 `PROCESSING` lease，就调用 `event.markPublished(...)`。

5. `markFailed(event, error, exception)`

   发布失败时也重新开启短事务。
   如果 row 仍然是同一个 `PROCESSING` lease，就调用 `event.markFailed(...)`：

   - 增加 `attempts`
   - 计算 exponential backoff
   - 未达最大次数时回到 `PENDING`
   - 达到最大次数后进入 `DEAD`

6. stale lease 防护

   `PROCESSING` 的 `next_attempt_at` 被当作轻量 lease token。
   如果 publisher 太慢，lease 过期后事件被另一个实例重新领取，旧 publisher 的迟到结果不会覆盖新状态。

7. `OutboxRecoverer.recoverStuckEvents()`

   独立扫描 `status = PROCESSING` 且 lease 已超时的 rows。
   未达最大次数时回到 `PENDING`，达到最大次数后进入 `DEAD`。

重要语义：

- Outbox 是 `at-least-once delivery`。
- Kafka ack 和 MySQL commit 不能原子化，所以消息可能重复。
- 下游 consumer 必须用 `eventId` 做幂等。
- `processingTimeoutSeconds` 应大于正常 Kafka send timeout，避免健康 publisher 被过早认为失联。

### 7.1 消息可靠性结构说明

当前消息相关代码分成 5 组：

- `messaging/outbox`：可靠发布机制。业务事务只写 `outbox_events`，后台 publisher 再发 Kafka。
- `messaging/outbox/mybatis`：Outbox 的 MyBatis persistence 细节。
- `messaging/kafka`：Kafka 技术 adapter。负责 topic、headers、producer ack、consumer DLT。
- `messaging/inbox`：消费者侧幂等机制。Kafka 是 at-least-once，所以 consumer 需要按 `eventId` 去重。
- `messaging/inbox/mybatis`：Inbox 的 MyBatis persistence 细节。
- 业务 bounded context 的 listener，例如：
  - `notification/infrastructure/messaging/AuthorizationNotificationListener`
  - `notification/infrastructure/messaging/CardTransactionNotificationListener`
  - `notification/infrastructure/messaging/StatementNotificationListener`
  - `notification/infrastructure/messaging/RepaymentNotificationListener`
  - `risk/infrastructure/messaging/AuthorizationRiskFeatureListener`

为什么不再把 Outbox 拆成 `domain/application/infrastructure`：

- Outbox 是 messaging 的 reliable delivery 机制，不是业务 aggregate。
- `OutboxEvent` 表达的是一条待发布消息的 delivery state，不需要伪装成业务 domain。
- 现在的结构更清楚：共享消息机制放在 `messaging/*`，业务发送/消费入口放回各自 bounded context。
- interview中可以这样解释：Outbox/Inbox 是可靠性 pattern，Notification/Risk 是业务上下文。

PayPay Card interview提示：

- Outbox 解决的是“业务事务成功后，消息不能丢”的问题。
- 它不解决“消息绝不重复”的问题，因为 Kafka ack 和 MySQL commit 无法组成单机事务。
- 因此下游必须设计 idempotent consumer，例如 `source_event_id` 唯一索引或 `consumer_inbox` 表。
- `FOR UPDATE SKIP LOCKED` 支持多实例 publisher 横向扩展。
- `PROCESSING lease` 处理 publisher 宕机恢复；`DEAD` 状态用于 poison message 和人工修复。

### 7.2 Ledger projection

Ledger 作为独立 bounded context 消费已经发生的业务事实：

```text
card_transaction.posted
-> CardTransactionLedgerListener
-> RecordLedgerEntryService
-> consumer_inbox(ledger-v1, eventId)
-> ledger_entries/CARD_TRANSACTION_POSTED/DEBIT

repayment.received
-> RepaymentLedgerListener
-> RecordLedgerEntryService
-> consumer_inbox(ledger-v1, eventId)
-> ledger_entries/REPAYMENT_RECEIVED/CREDIT
```

为什么不消费 authorization：

- Authorization 是额度 hold，不是真正 posted receivable。
- 真正会进入用户账务和账本的是 presentment posting 后的 `card_transaction.posted`。
- 还款真正到账后，`repayment.received` 才减少 issuer 对持卡人的应收款。

为什么当前异步写 Ledger：

- 这是 minimal learning projection，用来理解 ledger entry 和 consumer idempotency。
- 它不扩大 posting/repayment 的 transaction boundary。
- 生产里如果 Ledger 是 accounting source of truth，通常会在 posting/repayment 的核心账务事务里同步写入，或使用更强的可靠事务设计。

## 8. 核心流程六：DelayJob 未来业务动作

当前业务目标：

- 7 天内没有 posting 的 approved authorization，需要自动撤销 hold，并恢复额度。
- statement 到 `dueDate` 后，需要自动模拟银行扣款，并在成功后进入 repayment 入账。

入口：

- `DelayJobPoller.pollDueJobs()`
- `DelayJobClaimer.claimDueJobs()`
- `DelayJobWorker.handleClaimedJob(job)`
- `AuthorizationExpiryDelayJobHandler.handle(job)`
- `AutoRepaymentDelayJobHandler.handle(job)`
- `AuthorizationExpiryService.expire(authorizationId)`
- `AutoRepaymentService.debitStatement(statementId)`

触发方式：

- Spring `@Scheduled`
- `delayJobTaskScheduler` 只负责 poller/recoverer 的定时触发。
- `delayJobWorkerExecutor` 负责并发执行业务 job。
- 和 Outbox 使用相似 durable queue 模式，但 DelayJob 只保留必要职责：poller、claimer、worker、recoverer。

代码结构：

- `com.minicard.delayjob` 是通用 DelayJob 机制，包含任务状态机、repository port、poller、claimer、worker、recoverer、handler interface。
- `com.minicard.delayjob.mybatis` 是 DelayJob 的 MyBatis persistence 细节。
- `com.minicard.authorization.infrastructure.delayjob` 是 Authorization 使用 DelayJob 的 adapter，只负责写入到期任务。
- `com.minicard.repayment.infrastructure.delayjob` 是 Statement due-date 自动扣款使用 DelayJob 的 adapter。
- `com.minicard.repayment.application.AutoRepaymentDelayJobHandler` 是 `AUTO_REPAYMENT` 的业务 handler。
- `com.minicard.infrastructure.scheduler` 只放 Spring `ThreadPoolTaskScheduler` 配置；它不包含业务 poller 逻辑。
- `com.minicard.infrastructure.async` 只放后台 worker executor 配置；它不代表业务异步流程本身。
- `com.minicard.infrastructure.transaction` 放共享 `TransactionOperations` helper，让 worker 可以显式拆分 handler transaction 和 finalize transaction。

为什么 DelayJob 不再拆 `domain/application/infrastructure`：

- DelayJob 在这个项目里不是业务 bounded context，而是 database-backed delayed work queue。
- `DelayJob` 的状态机很重要，但它描述的是执行计划的 lease/retry/DONE/DEAD，不是信用卡业务规则。
- 包结构压平后，业务领域仍在 `authorization/card/creditaccount`，共享机制则直接放在 `delayjob`。

### 8.1 Poll and claim batch

关键方法：

- `DelayJobPoller.pollDueJobs()`
- `DelayJobClaimer.claimDueJobs()`

处理：

- 查询 `delay_jobs` 中到期的 `PENDING`。
- 使用 `FOR UPDATE SKIP LOCKED`。
- 把 job 标记成 `PROCESSING`。
- 设置 `next_attempt_at = now + processingTimeoutSeconds`。
- claim transaction 立刻 commit。
- commit 后，poller 再把每个 claimed job submit 给 `delayJobWorkerExecutor`。

为什么 `PROCESSING` 是 lease：

- 如果 pod claim job 后宕机，job 不能永久卡住。
- lease 到期后，`DelayJobRecoverer` 会把 job 恢复成可 retry 状态。
- worker finalize 时会重新锁 job row，并检查当前 row 仍然是同一个 `PROCESSING` lease，避免旧 worker 覆盖新 lease。

### 8.2 Worker dispatch handler

关键方法：

- `DelayJobWorker.handleClaimedJob(job)`
- `AuthorizationExpiryDelayJobHandler.jobType()`
- `AuthorizationExpiryDelayJobHandler.handle(job)`

处理：

- worker 根据 `job.jobType` 找 handler。
- `jobType()` 返回 `AUTHORIZATION_EXPIRY`。
- `handle()` 把 `job.aggregateId` 转成 authorization id。
- 调用 `AuthorizationExpiryService.expire(...)`。

这种设计方便以后新增其他 delay job 类型：

- 新增 enum。
- 新增 handler。
- 新增业务 scheduler adapter。

当前已注册的类型：

- `AUTHORIZATION_EXPIRY`：authorization 到期释放 hold。
- `AUTO_REPAYMENT`：statement 到期后模拟银行扣款，成功后调用 repayment 入账。

### 8.3 Business transaction

入口：

- `AuthorizationExpiryService.expire(authorizationId)`

关键步骤：

1. `authorizationRepository.findByIdForUpdate(authorizationId)`

   锁住 authorization row。
   虽然 delay job row 已经被锁，但业务表才是 source of truth。

2. 检查 status

   如果已经不是 `APPROVED`，说明不需要释放额度。
   直接返回，让 job 可以被标记为 DONE。
   这让 retry 和 manual replay 具备幂等性。

3. 检查 `expiresAt`

   如果当前时间还没到 `expiresAt`，抛异常。
   这代表 job 被错误地提前执行。

4. `cardRepository.findById(...)`

   根据 authorization.cardId 找到 card，再找到 creditAccountId。
   这里同样会经过 `CachedCardRepository` 的 Card snapshot cache；但释放额度仍必须以后面的
   `creditAccountRepository.findByIdForUpdate(...)` 为准。

5. `creditAccountRepository.findByIdForUpdate(...)`

   锁住账户 row。
   这会把 expiry release 和新的 authorization reserve 串行化。

6. `account.release(authorization.requestedAmount())`

   释放 reserved amount。

7. `authorization.expire(now)`

   状态从 `APPROVED` 变成 `EXPIRED`。
   设置 `expiredAt = now`，并在 aggregate 内记录 `AuthorizationExpiredDomainEvent`。

8. 保存 account、authorization，并写 expired Outbox event

   三件事同事务提交：

   - `credit_accounts.reserved_amount` 减少。
   - `authorizations.status = EXPIRED`。
   - `AuthorizationExpiryService` 从 aggregate 拉取 domain event，`outbox_events` 新增 `authorization.expired/PENDING`。

### 8.4 Worker mark done or failed

回到：

- `DelayJobWorker.handleClaimedJob(job)`

成功：

- `markDone(job)`
- `delay_jobs.status = DONE`

失败：

- `markFailed(job, error, exception)`
- `attempts + 1`
- 未超过最大次数：`status = PENDING`，设置下一次 retry 时间。
- 超过最大次数：`status = DEAD`。

为什么 claim、handle、mark done/fail 拆成多个 transaction：

- claim 事务短，减少 job row lock 时间。
- poller 只负责 poll + claim + submit，不负责提前标成功。
- handler 业务事务可以独立 rollback。
- 失败记录单独提交，避免错误信息跟业务 rollback 一起消失。
- worker 成功后自己 finalize，保证 `DONE` 一定发生在业务处理之后。

### 8.5 Recover stuck PROCESSING jobs

入口：

- `DelayJobRecoverer.recoverStuckJobs()`

处理：

- 查询 `status = PROCESSING` 且 `next_attempt_at <= now` 的 rows。
- 使用 `FOR UPDATE SKIP LOCKED`，多 pod recoverer 可以并发运行。
- 调用 `DelayJob.markProcessingTimedOut(...)`。
- 未超过最大次数：回到 `PENDING`，等待下一次 poll。
- 超过最大次数：进入 `DEAD`，等待人工检查。

为什么 recovery 单独放：

- 正常 poller 只关心新到期任务。
- recoverer 只关心 worker/pod 宕机或超时留下的 stuck lease。
- 这让正常执行路径和故障恢复路径都更容易解释。

## 9. 核心流程七：查询授权、账单和还款

示例请求：

```bash
curl http://localhost:8080/api/authorizations/{authorizationId}
curl http://localhost:8080/api/statements/{statementId}
curl http://localhost:8080/api/repayments/{repaymentId}
```

入口：

- `AuthorizationController.get(id)`
- `AuthorizationService.get(id)`
- `StatementController.get(id)`
- `StatementReadModelService.get(id)`
- `SnapshotCache<UUID, StatementReadModel>`
- `StatementService.get(id)`
- `RepaymentController.get(id)`
- `RepaymentService.get(id)`

处理：

- `@Transactional(readOnly = true)`
- 通过 authorization id、statement id 或 repayment id 查询当前状态。
- 不写 outbox。
- 不写 delay job。
- 不修改额度。

### 9.1 `GET /api/statements/{id}` 的 L1/L2 snapshot cache

当前只缓存一类低风险读模型：

```bash
curl http://localhost:8080/api/statements/{statementId}
```

真实链路：

```text
StatementController.get(id)
-> StatementReadModelService.get(id)
-> SnapshotCache<UUID, StatementReadModel>
   -> Caffeine L1
   -> Redis L2
   -> StatementService.get(id)
   -> StatementRepository.findById(id)
```

为什么缓存 `StatementReadModel`，而不是缓存 `Statement` aggregate：

- `Statement` aggregate 有还款状态转换和不变量保护，属于写模型。
- `StatementReadModel` 只包含 response 需要的字段，没有 business behavior。
- 这样可以明确表达：cache 只是 read acceleration，不是 source of truth。

当前配置：

- cache name：`statement-read-model-v1`。
- Redis key 形状：`mini-card:cache:statement-read-model-v1:{statementId}`。
- L1：Caffeine，`local-ttl = 30s`，`maximum-size = 1000`。
- L2：Redis，`remote-ttl = 5m`，`remote-ttl-jitter = 30s`。
- 不缓存 404 negative result。`statement not found` 仍然直接来自 DB/source of truth。

读取策略：

1. 先查 Caffeine L1。
2. L1 miss 后查 Redis L2。
3. L2 hit 时回填 L1。
4. L1/L2 都 miss 时调用 `StatementService.get(id)`，从 MySQL 读取 aggregate，再映射成 `StatementReadModel`。
5. Redis 读写失败时降级回 DB，不让低风险缓存影响 GET 可用性。

为什么 Redis TTL 要加 jitter：

- 如果大量 statement 在同一秒写入 Redis，又在同一秒过期，下一波请求会同时打到 DB。
- `remote-ttl-jitter` 把过期时间错开，降低 cache avalanche 风险。

为什么还需要 evict：

- `statement_items` 和 `total_amount` 是稳定账单快照，但 `paid_amount/status` 会被还款更新。
- 只靠 TTL 会让用户在还款成功后短时间看到旧的 `paidAmount/status`。
- `RepaymentService.applyToStatementAndAccount(...)` 在同一 transaction boundary 内更新 account 和 statement 后，
  调用 `StatementSnapshotCacheInvalidator.evictAfterCommit(statement.id())`。
- after-commit 很重要：如果事务提交前 evict，另一个 GET 可能读到尚未提交的旧 DB 值并重新写回 Redis，
  造成 stale read model。

当前 evict 范围：

- 当前 JVM 的 Caffeine L1。
- Redis L2。
- 其他 pod 的 Caffeine L1 不会收到本地内存通知，因此 L1 TTL 必须短。
  如果生产要求“还款提交后所有 pod 立即不可读旧账单”，可以在这个 invalidation port 后面增加 Redis Pub/Sub、
  Kafka invalidation event 或集中式 cache invalidation service。

interview可以这样解释：

- 只缓存可重建的 read model，不缓存写模型和强一致决策。
- DB 仍然是 source of truth；cache miss 或 Redis 故障不会改变业务结果。
- L1 降低同 JVM 热点开销，L2 让多实例共享缓存。
- TTL 负责最终兜底，evict 负责业务更新后的及时失效。
- after-commit evict 避免 transaction boundary 内的 stale reload 竞态。

### 9.2 Card snapshot cache

Card snapshot 不是一个公开 GET API，而是 authorization/posting/expiry 流程里的 reference snapshot：

```text
AuthorizationService.decideAndReserve(...)
-> CardRepository.findById(cardId)
-> CachedCardRepository
-> SnapshotCache<String, CardSnapshot>
   -> Caffeine L1
   -> Redis L2
   -> MyBatisCardRepository.findById(cardId)
   -> cards
```

当前配置：

- cache name：`card-snapshot-v1`。
- Redis key 形状：`mini-card:cache:card-snapshot-v1:{cardId}`。
- L1：Caffeine，`local-ttl = 10s`，`maximum-size = 5000`。
- L2：Redis，`remote-ttl = 1m`，`remote-ttl-jitter = 10s`。
- 不缓存 card-not-found negative result。

为什么 Card 可以缓存，但要比 statement 更保守：

- Card 状态和 account 归属通常变化低频，适合做 reference data cache。
- 但 Card 会影响授权批准/拒绝，stale `ACTIVE` 可能让刚 blocked 的卡短时间继续通过卡状态检查。
- 因此 Card snapshot TTL 比 statement read model 短。
- 当前项目还没有 card block/unblock API；未来加入时，状态变更写路径必须在 transaction commit 后
  通过 `CardSnapshotCacheInvalidator.evictAfterCommit(cardId)` evict `card-snapshot-v1:{cardId}`。

为什么不缓存额度：

- 额度是高并发写模型，必须读 `credit_accounts` 并使用 `SELECT ... FOR UPDATE`。
- 如果缓存 available credit，会绕开 row lock，产生超额授权风险。
- Card snapshot 只让系统少查一张低频变化的 `cards` 表，不改变额度一致性边界。

这个接口适合观察状态变化：

- 创建后可能是 `APPROVED`。
- presentment posting 后会变成 `POSTED`。
- 7 天过期任务执行后会变成 `EXPIRED`。
- statement 生成后可以看到 `CLOSED` 状态、账单金额、最低还款额和 item 快照。
- repayment 成功后可以看到 `RECEIVED`、`statementId`、`creditAccountId` 和 `receivedAt`。

## 10. Outbox vs DelayJob 对比

| 项目 | Outbox | DelayJob |
| --- | --- | --- |
| 目标 | 可靠发布消息 | 到时间执行业务动作 |
| 表 | `outbox_events` | `delay_jobs` |
| 当前例子 | `authorization.approved`, `authorization.declined`, `authorization.posted`, `authorization.expired`, `card_transaction.posted`, `statement.closed`, `repayment.received` | `AUTHORIZATION_EXPIRY`, `AUTO_REPAYMENT` |
| 生产者 | 业务事务中的 event publisher | 业务事务中的 job scheduler adapter |
| 消费者 | `OutboxPoller` + `OutboxClaimer` + `OutboxWorker` | `DelayJobPoller` + `DelayJobClaimer` + `DelayJobWorker` |
| 并发控制 | `FOR UPDATE SKIP LOCKED` | `FOR UPDATE SKIP LOCKED` |
| 失败处理 | retry/backoff/DEAD | retry/backoff/PROCESSING lease/DEAD |
| 幂等重点 | consumer 用 eventId 去重 | handler 读取业务 source of truth |
| 线程池 | `outboxTaskScheduler` + `outboxWorkerExecutor` | `delayJobTaskScheduler` + `delayJobWorkerExecutor` |

一句话区分：

- Outbox 表保存“我要告诉别人发生了什么”。
- DelayJob 表保存“未来我要自己做一件业务动作”。

## 11. interview容易追问的点

### 为什么不用 Java `synchronized` 控制额度？

因为应用可能有多个 pod。
`synchronized` 只能锁当前 JVM，不能锁其他实例。
这里用 MySQL row lock，让所有 pod 在同一个 `credit_accounts` row 上串行化。

### 为什么先写 idempotency claim？

因为 read-then-insert 有 race condition。
两个并发请求可能都读到不存在，然后都继续执行业务。
现在先 insert，并依赖 unique constraint 决定唯一 winner。

### 为什么 repayment 先读 statement，再锁 account 和 statement？

还款请求只带 `statementId`，需要先知道它属于哪个 `creditAccountId`。
第一次 `statementRepository.findById(...)` 只是拿归属信息，不做余额决策。
之后仍然按统一顺序拿锁：

```text
credit account row lock -> statement row lock
```

锁住 statement 后再重新校验 remaining amount 和 status。
这样既能从 statement 找到账户，又不会和账单生成的锁顺序相反。

### 为什么 risk check 放在 account lock 前？

账户锁是高并发热点。
风控可能较慢，如果拿着账户锁做风控，会降低同账户请求吞吐。
所以先做不需要账户锁的检查，再进入 critical section。

### 为什么 delay job 表不能直接代表授权是否过期？

因为 delay job 是执行计划，不是业务事实。
真正的业务事实在 `authorizations.status`、`credit_accounts.reserved_amount` 和 `credit_accounts.posted_balance`。
job 可以失败、重试、被人工修复，但业务表必须始终是 source of truth。

### 流水、ledger、对账是一个东西吗？

不是。

- `CardTransaction` 是用户流水，回答“持卡人看到哪笔消费”。
- `Ledger` 是内部会计账本，回答“财务科目怎样借贷变化”。
- `Reconciliation` 是运营/批处理流程，回答“内部记录和外部文件/资金结果是否一致”。

当前阶段已经有 `CardTransaction` 和 `Statement`：

- `CardTransaction` 提供 posted transaction 基础。
- `Statement` 把 posted transactions 固定成账单快照。
- `Repayment` 处理还款、恢复额度和账单结清。
- `LedgerEntry` 异步记录最小内部账本分录：消费入账是 `DEBIT`，还款入账是 `CREDIT`。

### 为什么 Outbox 和 DelayJob 分开？

两者都是 durable queue，但语义不同：

- Outbox 面向外部消息发布。
- DelayJob 面向内部延迟业务动作。

分开后：

- 表字段更贴合语义。
- scheduler 可以独立配置 worker pool。
- interview时更容易解释职责边界。

### 为什么 eventId 和 authorizationId 不一样？

一个 authorization 可以产生多个事件：

- `authorization.approved`
- `authorization.declined`
- `authorization.posted`
- `authorization.expired`

所以 event id 是事件本身的唯一标识，authorization id 是 aggregate 标识。

`card_transaction.posted` 里还会有 cardTransactionId：

- eventId：这条 integration event 的唯一 id，用于 Outbox/Inbox 幂等。
- cardTransactionId：用户可见交易流水的 aggregate id。
- authorizationId：这笔 posted transaction 消耗的是哪一笔授权。

`statement.closed` 里还会有 statementId：

- eventId：这条 integration event 的唯一 id。
- statementId：账单 aggregate id。
- creditAccountId：Kafka partition key，用来保证同一账户账单事件有序。

### 为什么 statement.closed 映射成 STATEMENT_READY？

`statement.closed` 是系统内部业务事实，意思是 billing cycle 已关闭、账单金额固定。
用户侧通知不应该直接说 “closed”，而应该表达“本期账单已生成，可以查看并还款”。

所以 Notification 侧使用：

```text
statement.closed -> NotificationType.STATEMENT_READY
```

这个区分能体现 event contract 和 customer-facing template 的边界。

### 为什么 notifications 不再只存 authorizationId？

因为 Notification 现在同时消费四类事件：

- `authorization.approved/declined`
- `card_transaction.posted`
- `statement.closed`
- `repayment.received`

Statement 和 Repayment 都没有 authorizationId，强行塞入旧列会让模型变形。
所以现在用 `subject_type + subject_id` 表达“这条通知关联哪个业务对象”，
再用 `recipient_key` 表达“目前怎样找到接收方”。

## 12. Spring 和 MyBatis 语法速查

### `@RestController`

表示这个类处理 HTTP 请求，返回对象会自动序列化成 JSON。

### `@Valid`

触发 DTO 上的 Bean Validation，例如 `@NotBlank`、`@Positive`。

### `@Transactional`

声明事务边界。
当前最关键的是 `AuthorizationService.authorize()`、`PostingService.post()`、`StatementService.generate()`、`RepaymentService.receive()` 和 `AuthorizationExpiryService.expire()`。

### `@Transactional(readOnly = true)`

用于只读查询。
它表达语义，也让底层框架有机会做只读优化。

### `@Scheduled`

声明定时任务。
当前 Outbox 和 DelayJob 都用 scheduler 轮询数据库。

### `scheduler = "delayJobTaskScheduler"`

指定使用哪个 `ThreadPoolTaskScheduler` bean。
这让 Outbox 和 DelayJob 使用不同 scheduler/worker pool。

### `@Bean`

在 configuration class 里声明 Spring 容器管理的对象。
例如 `outboxTaskScheduler`、`delayJobTaskScheduler`、`TransactionOperations`。

### MyBatis mapper XML

XML 里写 SQL，Java mapper interface 调用它。
当前重要 SQL 是：

```sql
SELECT ...
FOR UPDATE
```

以及：

```sql
SELECT ...
FOR UPDATE SKIP LOCKED
```

`FOR UPDATE` 锁住选中的 row。
`SKIP LOCKED` 让并发 worker 跳过别人已经锁住的 row。

## 13. 后续学习建议

当前实现已经适合学习interview核心点。
如果继续扩展，建议按这个顺序：

1. 后续每次表结构变化都追加 Liquibase changeset，并同步更新 walkthrough。
2. 增加 reconciliation，对比内部 posted transaction、repayment 和外部清算/资金文件。
3. 给 outbox/delay job 增加 metrics，例如 pending 数、dead 数、处理耗时。
4. 增加 dead job/admin replay endpoint，但要加权限控制后再做。
5. 补充少量并发测试，验证同账户多个 authorization、posting、statement generation 和 repayment 不会互相漏算。

这一阶段最应该讲清楚的是：

- 一个请求如何被幂等地处理。
- 额度为什么不会在并发下被超用。
- 为什么 delay job 和 outbox 都写在主事务里。
- 为什么 delay job 和 outbox 分表，但 scheduler 形状保持对称。
- 为什么 statement generation 只固定账单，不恢复额度；恢复额度属于 Repayment 阶段。
