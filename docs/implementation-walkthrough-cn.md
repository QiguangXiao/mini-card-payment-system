# Mini Card Payment System 核心流程中文说明

这份文档按“请求进来 -> 中间关键阶段 -> 数据变化 -> 响应出去”的方式解释当前工程。
它不是泛泛讲 DDD，而是对应现在代码里的类、表和状态。

## 1. 怎么读这个工程

当前工程可以按 5 层理解：

- `api`：HTTP adapter。负责接收 request、校验参数、返回 response。
- `application`：use case orchestration。负责事务边界、调用顺序、幂等、锁、调度、事件写入。
- `domain`：核心业务规则。负责状态转换、金额和额度不变量。
- `repository`：领域接口。application 只依赖接口，不关心 MyBatis 细节。
- `infrastructure`：MyBatis、Outbox、Kafka、scheduler adapter 等技术实现。

关键词：

- `Aggregate`：一组需要保持一致的业务对象。当前重点是 `Authorization`、`CreditAccount`、`CardTransaction` 和 `Statement`。
- `Idempotency`：同一个客户端重试不会重复扣额度。
- `Row lock`：用数据库行锁控制并发，而不是 JVM `synchronized`。
- `Outbox`：主业务事务里只写消息意图，后台 scheduler 再发布 Kafka。
- `DelayJob`：主业务事务里写未来任务计划，后台 scheduler 到时间后执行业务动作。

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

授权阶段增加 `reservedAmount`；presentment posting 阶段把金额从 `reservedAmount` 移到 `postedBalance`。

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
- `paid_amount`：未来还款阶段使用；当前生成账单时为 0。
- `transaction_count`：本期收录的 posted transactions 数量。
- `status`：当前生成后为 `CLOSED`，未来还款/逾期阶段会进入 `PARTIALLY_PAID`、`PAID`、`OVERDUE`。

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

### `delay_jobs`

保存未来需要执行的业务动作。

核心字段：

- `id`：delay job id，由 scheduler adapter 生成 UUID。
- `job_type`：当前只有 `AUTHORIZATION_EXPIRY`，以后可以增加其他延迟任务类型。
- `aggregate_type` / `aggregate_id`：指向业务对象，例如 `Authorization` + authorization id。
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
- `partition_key`：Kafka 分区 key，按事件所属 aggregate 选择；authorization/card transaction 事件用自身 id，statement 事件用 credit account id。
- `payload`：JSON 字符串。
- `status`：`PENDING`、`PROCESSING`、`PUBLISHED`、`DEAD`。
- `next_attempt_at`：下一次可领取时间；`PROCESSING` 时也作为 publisher lease deadline。
- `attempts` / `last_error`：失败重试和排障信息。

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

   查询 card aggregate。
   Card 回答“这张卡是否存在、是否被 blocked、是否过期”。

3. `riskAssessmentService.assess(command.toRiskAssessmentRequest())`

   做风控判断。
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
- Risk listener 仍只处理 `authorization.approved` 和 `authorization.declined`，因为风控投影只关心授权决策历史。
- “不感兴趣的合法事件”不应该被当成坏消息送进 DLT。

为什么不叫 `PaymentNotificationListener`：

- 当前工程没有一个独立的 `payment` bounded context。
- `authorization` 和 `CardTransaction` 是两个不同上游领域，Notification 只是独立消费它们的 integration events。
- 按上游领域拆 inbound adapter，更接近未来 notification 微服务的真实形态。

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
   - `RequestAuthorizationNotificationService` 先写 `consumer_inbox(notification-v1, eventId)` 做 Inbox claim。
   - claim 成功后创建一条 `notifications/PENDING` 请求。
   - duplicate delivery claim 失败后直接返回，避免重复通知用户。

为什么这一阶段不做完整 ledger：

- `CardTransaction` 是用户流水，用于 APP 明细、账单和客服查询。
- `Ledger` 是内部会计账本，通常需要 double-entry、accounting account、journal balance 校验。
- `Reconciliation` 是把内部记录和外部文件/资金清算结果对比，发现 missing/duplicate/amount mismatch。
- 现在先把 posted transaction 和账户余额做对，后续再基于它增加 minimal ledger 和 reconciliation。

PayPay Card 面试提示：

- issuer backend 里 authorization 和 posting 是两段生命周期，不能混成一个状态。
- `networkTransactionId` 是外部幂等键，能防止 presentment retry 造成 double posting。
- `Authorization` row lock 防止同一授权被多次入账；`CreditAccount` row lock 同时保护 posting、statement generation 和额度并发。
- 本阶段把 settlement 留到后面，是因为 settlement 处理资金清算，不是用户账户入账。
- posting 成功通知通过 `CardTransactionPostedDomainEvent -> Outbox -> Kafka -> Notification Inbox` 异步完成，不扩大 posting 的 transaction boundary。

## 5. 核心流程三：Statement 账单生成

当前业务目标：

- 把一个 billing cycle 内已经 `POSTED`、但还没有进入账单的 `CardTransaction` 汇总成 statement。
- 固定账单金额、最低还款额和行项目快照。
- 标记这些交易已经被该 statement 收录，避免下次重复出账。

示例请求：

```bash
curl -X POST http://localhost:8080/api/statements/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "creditAccountId": "11111111-1111-1111-1111-111111111111",
    "periodStart": "2026-06-01",
    "periodEnd": "2026-06-30",
    "dueDate": "2026-07-25"
  }'
```

入口：

- `StatementController.generate(...)`
- `GenerateStatementCommand`
- `StatementService.generate(command)`
- `Statement.close(...)`
- `CardTransaction.assignToStatement(statementId, now)`
- `StatementOutboxAdapter.append(event)`

### 5.1 Controller 和 Command 阶段

`StatementController` 只负责 HTTP request/response mapping：

- 从 body 读取 `creditAccountId`、`periodStart`、`periodEnd`、`dueDate`。
- 创建 `GenerateStatementCommand`。
- 调用 `statementService.generate(command)`。

`GenerateStatementCommand` 会校验：

- `periodEnd` 不能早于 `periodStart`。
- `dueDate` 必须晚于 `periodEnd`。

当前阶段用 API 手动传入 billing cycle，方便学习和本地验证。
未来可以由 scheduler 根据账户账单日自动构造同一个 command。

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

8. `StatementClosedDomainEvent`

   `Statement.close(...)` 在 aggregate 内部记录 `StatementClosedDomainEvent`。
   `StatementService` 保存账单和交易归属后，调用 `statement.pullDomainEvents()`，
   再交给 `StatementOutboxAdapter` 写入 `outbox_events/PENDING`。

### 5.3 本次事务提交后的数据变化

成功生成后：

- `statements` 新增一行，`status = CLOSED`。
- `statement_items` 新增多行，保存账单行项目快照。
- 被收录的 `card_transactions.statement_id` 指向该 statement。
- 被收录的 `card_transactions.statement_assigned_at` 记录归账时间。
- `outbox_events` 新增 `statement.closed/PENDING`。

不会变化：

- `credit_accounts.posted_balance` 不会减少。
- `credit_accounts.reserved_amount` 不会变化。
- `CardTransaction.status` 仍然是 `POSTED`。

原因：

- 账单生成只是把已入账消费固定成账单，不代表用户已经还款。
- 未来 Payment 阶段才会减少 `postedBalance`，并恢复可用额度。

### 5.4 PayPay Card 面试提示

- Statement 是 posted transaction 的账单快照，不是实时查询结果。
- 账单周期用自然唯一键实现 idempotency：同一账户同一周期只能有一张账单。
- 先锁 credit account，再锁待出账交易，是为了和 posting 保持统一锁顺序，避免死锁和漏账。
- `statement_items` 保存历史快照，方便解释 audit trail、客服查询和账单 PDF。
- `statement.closed` 通过 Outbox 异步发布，未来通知、PDF 生成、还款提醒可以独立消费。
- 当前没有做还款，所以 `paid_amount = 0` 且状态为 `CLOSED`；下一阶段 Payment 会继续推进状态。

## 6. 核心流程四：Outbox 发布消息

入口：

- `OutboxPoller.pollPublishableEvents()`
- `OutboxPoller.claimPublishableEvents()`
- `OutboxWorker.publishClaimedEvent(event)`
- `OutboxRecoverer.recoverStuckEvents()`

触发方式：

- Spring `@Scheduled`
- `outboxTaskScheduler` 只负责 poller/recoverer 的定时触发。
- `outboxWorkerExecutor` 负责并发等待 Kafka acknowledgement。

关键步骤：

1. `OutboxPoller.claimPublishableEvents()`

   poller 在短事务内调用 `findPublishableBatchForUpdate(now, batchSize)`。
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

### 6.1 消息可靠性结构说明

当前消息相关代码分成 5 组：

- `messaging/outbox`：可靠发布机制。业务事务只写 `outbox_events`，后台 publisher 再发 Kafka。
- `messaging/outbox/mybatis`：Outbox 的 MyBatis persistence 细节。
- `messaging/kafka`：Kafka 技术 adapter。负责 topic、headers、producer ack、consumer DLT。
- `messaging/inbox`：消费者侧幂等机制。Kafka 是 at-least-once，所以 consumer 需要按 `eventId` 去重。
- `messaging/inbox/mybatis`：Inbox 的 MyBatis persistence 细节。
- 业务 bounded context 的 listener，例如：
  - `notification/infrastructure/messaging/AuthorizationNotificationListener`
  - `notification/infrastructure/messaging/CardTransactionNotificationListener`
  - `risk/infrastructure/messaging/AuthorizationRiskFeatureListener`

为什么不再把 Outbox 拆成 `domain/application/infrastructure`：

- Outbox 是 messaging 的 reliable delivery 机制，不是业务 aggregate。
- `OutboxEvent` 表达的是一条待发布消息的 delivery state，不需要伪装成业务 domain。
- 现在的结构更清楚：共享消息机制放在 `messaging/*`，业务发送/消费入口放回各自 bounded context。
- 面试中可以这样解释：Outbox/Inbox 是可靠性 pattern，Notification/Risk 是业务上下文。

PayPay Card 面试提示：

- Outbox 解决的是“业务事务成功后，消息不能丢”的问题。
- 它不解决“消息绝不重复”的问题，因为 Kafka ack 和 MySQL commit 无法组成单机事务。
- 因此下游必须设计 idempotent consumer，例如 `source_event_id` 唯一索引或 `consumer_inbox` 表。
- `FOR UPDATE SKIP LOCKED` 支持多实例 publisher 横向扩展。
- `PROCESSING lease` 处理 publisher 宕机恢复；`DEAD` 状态用于 poison message 和人工修复。

## 7. 核心流程五：DelayJob 自动过期授权

当前业务目标：

- 7 天内没有 posting 的 approved authorization，需要自动撤销 hold，并恢复额度。

入口：

- `DelayJobPoller.pollDueJobs()`
- `DelayJobClaimer.claimDueJobs()`
- `DelayJobWorker.handleClaimedJob(job)`
- `AuthorizationExpiryDelayJobHandler.handle(job)`
- `AuthorizationExpiryService.expire(authorizationId)`

触发方式：

- Spring `@Scheduled`
- `delayJobTaskScheduler` 只负责 poller/recoverer 的定时触发。
- `delayJobWorkerExecutor` 负责并发执行业务 job。
- 和 Outbox 使用相似 durable queue 模式，但 DelayJob 只保留必要职责：poller、claimer、worker、recoverer。

代码结构：

- `com.minicard.delayjob` 是通用 DelayJob 机制，包含任务状态机、repository port、poller、claimer、worker、recoverer、handler interface。
- `com.minicard.delayjob.mybatis` 是 DelayJob 的 MyBatis persistence 细节。
- `com.minicard.authorization.infrastructure.delayjob` 是 Authorization 使用 DelayJob 的 adapter，只负责写入到期任务。
- `com.minicard.infrastructure.scheduler` 只放 Spring `ThreadPoolTaskScheduler` 配置。
- `com.minicard.infrastructure.async` 只放后台 worker executor 配置。
- `com.minicard.infrastructure.transaction` 放共享 `TransactionOperations` helper。

为什么 DelayJob 不再拆 `domain/application/infrastructure`：

- DelayJob 在这个项目里不是业务 bounded context，而是 database-backed delayed work queue。
- `DelayJob` 的状态机很重要，但它描述的是执行计划的 lease/retry/DONE/DEAD，不是信用卡业务规则。
- 包结构压平后，业务领域仍在 `authorization/card/creditaccount`，共享机制则直接放在 `delayjob`。

### 7.1 Poll and claim batch

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

### 7.2 Worker dispatch handler

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

### 7.3 Business transaction

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

### 7.4 Worker mark done or failed

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

### 7.5 Recover stuck PROCESSING jobs

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

## 8. 核心流程六：查询授权和账单

示例请求：

```bash
curl http://localhost:8080/api/authorizations/{authorizationId}
curl http://localhost:8080/api/statements/{statementId}
```

入口：

- `AuthorizationController.get(id)`
- `AuthorizationService.get(id)`
- `StatementController.get(id)`
- `StatementService.get(id)`

处理：

- `@Transactional(readOnly = true)`
- 通过 authorization id 或 statement id 查询当前状态。
- 不写 outbox。
- 不写 delay job。
- 不修改额度。

这个接口适合观察状态变化：

- 创建后可能是 `APPROVED`。
- presentment posting 后会变成 `POSTED`。
- 7 天过期任务执行后会变成 `EXPIRED`。
- statement 生成后可以看到 `CLOSED` 状态、账单金额、最低还款额和 item 快照。

## 9. Outbox vs DelayJob 对比

| 项目 | Outbox | DelayJob |
| --- | --- | --- |
| 目标 | 可靠发布消息 | 到时间执行业务动作 |
| 表 | `outbox_events` | `delay_jobs` |
| 当前例子 | `authorization.approved`, `authorization.declined`, `authorization.posted`, `authorization.expired`, `card_transaction.posted`, `statement.closed` | `AUTHORIZATION_EXPIRY` |
| 生产者 | 业务事务中的 event publisher | 业务事务中的 job scheduler adapter |
| 消费者 | `OutboxPoller` + `OutboxWorker` | `DelayJobPoller` + `DelayJobWorker` |
| 并发控制 | `FOR UPDATE SKIP LOCKED` | `FOR UPDATE SKIP LOCKED` |
| 失败处理 | retry/backoff/DEAD | retry/backoff/PROCESSING lease/DEAD |
| 幂等重点 | consumer 用 eventId 去重 | handler 读取业务 source of truth |
| 线程池 | `outboxTaskScheduler` + `outboxWorkerExecutor` | `delayJobTaskScheduler` + `delayJobWorkerExecutor` |

一句话区分：

- Outbox 表保存“我要告诉别人发生了什么”。
- DelayJob 表保存“未来我要自己做一件业务动作”。

## 10. 面试容易追问的点

### 为什么不用 Java `synchronized` 控制额度？

因为应用可能有多个 pod。
`synchronized` 只能锁当前 JVM，不能锁其他实例。
这里用 MySQL row lock，让所有 pod 在同一个 `credit_accounts` row 上串行化。

### 为什么先写 idempotency claim？

因为 read-then-insert 有 race condition。
两个并发请求可能都读到不存在，然后都继续执行业务。
现在先 insert，并依赖 unique constraint 决定唯一 winner。

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
- 下一阶段 Payment 才会处理还款、恢复额度和账单结清。

### 为什么 Outbox 和 DelayJob 分开？

两者都是 durable queue，但语义不同：

- Outbox 面向外部消息发布。
- DelayJob 面向内部延迟业务动作。

分开后：

- 表字段更贴合语义。
- scheduler 可以独立配置 worker pool。
- 面试时更容易解释职责边界。

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

## 11. Spring 和 MyBatis 语法速查

### `@RestController`

表示这个类处理 HTTP 请求，返回对象会自动序列化成 JSON。

### `@Valid`

触发 DTO 上的 Bean Validation，例如 `@NotBlank`、`@Positive`。

### `@Transactional`

声明事务边界。
当前最关键的是 `AuthorizationService.authorize()`、`PostingService.post()`、`StatementService.generate()` 和 `AuthorizationExpiryService.expire()`。

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

## 12. 后续学习建议

当前实现已经适合学习面试核心点。
如果继续扩展，建议按这个顺序：

1. 增加 Payment / Repayment，用还款减少 `postedBalance`，推进 statement 状态。
2. 引入 Flyway 或 Liquibase，管理 schema migration，而不是只靠 `schema.sql`。
3. 增加 minimal ledger，记录 posting、statement、payment 对内部会计账本的影响。
4. 增加 reconciliation，对比内部 posted transaction 和外部清算/资金文件。
5. 给 outbox/delay job 增加 metrics，例如 pending 数、dead 数、处理耗时。
6. 增加 dead job/admin replay endpoint，但要加权限控制后再做。
7. 补充少量并发测试，验证同账户多个授权、posting 和 statement generation 不会互相漏算。

这一阶段最应该讲清楚的是：

- 一个请求如何被幂等地处理。
- 额度为什么不会在并发下被超用。
- 为什么 delay job 和 outbox 都写在主事务里。
- 为什么 delay job 和 outbox 分表，但 scheduler 形状保持对称。
- 为什么 statement generation 只固定账单，不恢复额度；恢复额度属于 Payment 阶段。
