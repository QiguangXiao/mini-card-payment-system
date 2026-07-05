# 从 Authorization 到 Repayment 的领域状态流转与锁说明

这份文档专门解释一条完整主链路：

```text
Authorization -> Presentment Posting -> Statement Batch -> Auto/Manual Repayment
```

重点不是 API 用法本身，而是你在interview里要讲清楚的几个问题：

- 每个领域对象有哪些状态，谁触发状态转换。
- 状态转换在哪个类里执行，哪些表的哪些行会变化。
- 为什么要加 `row lock`，锁住多大范围，锁多久，何时释放。
- 一个领域的变化怎样影响其他领域。
- 用一串具体请求、具体数字、具体 ID 和具体时间，把整个过程串起来。

本文基于当前代码，不是泛泛讲信用卡系统：

- `AuthorizationService.authorize(...)`
- `PostingService.post(...)`
- `StatementCycleService.createDueJobs()` / `StatementJobDispatcher` / `StatementJobHandler`（账单批处理已扁平化为 claimable job）
- `StatementGenerationService.generate(...)`（逐账户出账）
- `AutoRepaymentService.debitStatement(...)`
- `RepaymentService.receive(...)`
- `AuthorizationExpiryService.expire(...)`
- Liquibase changelog: `src/main/resources/db/changelog/db.changelog-master.yaml`
- MyBatis mapper XML 中的 `SELECT ... FOR UPDATE`

## 1. 一句话总图

```text
刷卡授权
  Authorization: PENDING -> APPROVED
  CreditAccount: reserved_amount 增加

商户请款 / clearing presentment
  Authorization: APPROVED -> POSTED
  CardTransaction: PENDING -> POSTED
  CreditAccount: reserved_amount 减少, posted_balance 增加

月度账单批处理
  Statement: 新建 CLOSED
  CardTransaction: statement_id 从 NULL 变成 statementId
  DelayJob: 新建 AUTO_REPAYMENT/PENDING

客户主动还款 / 到期自动扣款
  Repayment: PENDING -> RECEIVED
  Statement: CLOSED -> PARTIALLY_PAID -> PAID
  CreditAccount: posted_balance 减少
```

最关键的共享不变量：

```text
availableCredit = creditLimit - reservedAmount - postedBalance
```

所以凡是会改变 `reservedAmount` 或 `postedBalance` 的用例，都必须在同一个
`credit_accounts` row 上串行化。

## 2. 本文使用的固定示例

为了让状态变化可追踪，下面所有请求都用同一个账户和同一笔消费。

初始时间：

```text
T0 = 2026-07-01T10:00:00Z
```

初始数据库：

```text
credit_accounts
id = 11111111-1111-1111-1111-111111111111
credit_limit = 100000.00
reserved_amount = 0.00
posted_balance = 0.00
currency = JPY
status = ACTIVE

cards
id = card-123
credit_account_id = 11111111-1111-1111-1111-111111111111
status = ACTIVE
```

下面假设代码里的 `UUID.randomUUID()` 生成这些 ID，方便说明：

```text
authorizationId = aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1
authorizationApprovedEventId = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1
authorizationPostedEventId = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2
cardTransactionId = cccccccc-cccc-cccc-cccc-ccccccccccc1
cardTransactionPostedEventId = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee3
statementId = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
statementItemId = dddddddd-dddd-dddd-dddd-dddddddddd01
statementClosedEventId = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee4
autoRepaymentDelayJobId = ffffffff-ffff-ffff-ffff-fffffffffff1
repaymentId1 = 99999999-9999-9999-9999-999999999991
repaymentReceivedEventId1 = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee5
repaymentId2 = 99999999-9999-9999-9999-999999999992
repaymentReceivedEventId2 = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee6
```

这些 ID 是解释用的示例值。真实运行时由 domain factory 或 Outbox adapter 生成。

## 3. 状态机总览

| 领域 / 表 | 状态字段 | 本链路状态变化 | 触发者 |
| --- | --- | --- | --- |
| `Authorization` / `authorizations.status` | `PENDING`, `APPROVED`, `DECLINED`, `POSTED`, `EXPIRED` | `PENDING -> APPROVED -> POSTED` | 授权 API、presentment API、expiry job |
| `CreditAccount` / `credit_accounts` | `status`, `reserved_amount`, `posted_balance` | `reserved 0 -> 1500 -> 0`, `posted 0 -> 1500 -> 1000 -> 0` | 授权、posting、repayment、expiry |
| `CardTransaction` / `card_transactions.status` | `PENDING`, `POSTED` | `PENDING -> POSTED`，之后被 statement 收录 | presentment API、statement generation |
| `Statement` / `statements.status` | `CLOSED`, `PARTIALLY_PAID`, `PAID`, `OVERDUE` | 新建 `CLOSED -> PARTIALLY_PAID -> PAID` | statement batch、repayment |
| `Repayment` / `repayments.status` | `PENDING`, `RECEIVED` | 每次还款 `PENDING -> RECEIVED` | repayment API、auto debit |
| `DelayJob` / `delay_jobs.status` | `PENDING`, `PROCESSING`, `DONE`, `DEAD` | `AUTO_REPAYMENT` 从 `PENDING -> DONE` | due-date auto repayment |
| `OutboxEvent` / `outbox_events.status` | `PENDING`, `PROCESSING`, `PUBLISHED`, `DEAD` | 每个业务事件先 `PENDING`，后台发布后 `PUBLISHED` | 各业务 service、Outbox worker |
| `Notification` / `notifications.status` | `PENDING`, `SENT`, `FAILED` | Kafka consumer 创建 `PENDING` 通知 | Notification listener |

注意：`CreditAccount.status` 在本文示例中一直是 `ACTIVE`，真正变化的是金额字段。
金融系统里，金额字段变化也是核心状态变化。

## 4. 锁规则先讲清楚

### 4.1 锁不是 Java `synchronized`

本项目用 MySQL `SELECT ... FOR UPDATE` 做 `row lock`。

原因：

- 服务可能有多个 pod / 多个 JVM。
- Java `synchronized` 只能锁当前 JVM，不能锁其他实例。
- MySQL row lock 才能让所有实例在同一条业务数据上串行化。

### 4.2 锁多大

本文主链路中，锁的粒度都是行级：

| SQL | 锁住的范围 | 用途 |
| --- | --- | --- |
| `authorizations WHERE idempotency_key = ? FOR UPDATE` | 一个 authorization row | 同一个授权幂等键只能有一个最终结果 |
| `authorizations WHERE id = ? FOR UPDATE` | 一个 authorization row | posting 和 expiry 不能同时改同一授权 |
| `credit_accounts WHERE id = ? FOR UPDATE` | 一个 account row | 串行化额度计算和余额变化 |
| `card_transactions WHERE network_transaction_id = ? FOR UPDATE` | 一个 card transaction row | 同一个 presentment 不能重复入账 |
| `card_transactions` statement candidate query `FOR UPDATE` | 本账户、本周期内未出账的 posted transaction rows | 防止同一交易进入两张账单 |
| `statements WHERE credit_account_id + period FOR UPDATE` | 同一账户同一周期 statement row | 同一周期只能生成一张账单 |
| `statements WHERE id = ? FOR UPDATE` | 一个 statement row | 并发 repayment 不能把 `paid_amount` 改乱 |
| `repayments WHERE idempotency_key = ? FOR UPDATE` | 一个 repayment row | 同一个还款幂等键只能有一个结果 |

唯一索引也很重要：

- `authorizations.idempotency_key`
- `card_transactions.network_transaction_id`
- `statements.credit_account_id + period_start + period_end`
- `repayments.idempotency_key`
- `statement_lines.card_transaction_id`

这些唯一约束是 `idempotency` 和防重复归账的最后防线。

### 4.3 锁多久

这些锁都在 Spring `@Transactional` 方法内获取。

释放时间：

```text
transaction commit 或 rollback 时释放
```

也就是说：

- Controller 参数校验阶段不持有锁。
- Kafka publish 不在主事务里执行，不持有业务锁。
- Notification 发送不在主事务里执行，不持有业务锁。
- 真正持锁的是 service 里从 `SELECT ... FOR UPDATE` 开始，到方法成功返回并 commit 为止。

### 4.4 全链路最重要的锁顺序

本项目尽量保持同一账户相关操作的顺序：

```text
credit account row lock -> 其他业务 row lock
```

典型例子：

- `StatementGenerationService.generate(...)`：先锁 `credit_accounts`，再锁待出账 `card_transactions`。
- `RepaymentService.receive(...)`：先锁 `credit_accounts`，再锁 `statements`。
- `PostingService.post(...)`：会锁 authorization，然后锁 account，再 claim card transaction。

这个顺序的目标是减少死锁和漏账：

- posting 和 statement generation 不会一个先锁 transaction、一个先锁 account 后互相等待。
- repayment 和 statement generation 都围绕同一个 account row 串行化。

### 4.5 如果不加这些锁和唯一约束会怎样

| 省掉的保护 | 可能出的问题 |
| --- | --- |
| 不用 `credit_accounts ... FOR UPDATE` | 两笔并发授权都看到同一份额度，分别批准，最后超过 credit limit |
| 不用 idempotency unique key | 客户端 retry 会被当成新请求，重复 hold 或重复 repayment |
| 不锁 presentment 的 `network_transaction_id` | 卡组织重放同一请款时，同一笔交易可能被 posted 两次 |
| 不锁 statement cycle natural key | batch retry 或多实例同时跑时，同一账户同一期出现多张账单 |
| 不锁未出账 transactions | statement batch 可能和 posting 交错，漏掉刚 posted 的交易或把交易归入两张账单 |
| 不保持统一锁顺序 | 一个事务拿 account 等 transaction，另一个拿 transaction 等 account，形成 deadlock |
| 只依赖 Java `synchronized` | 只能保护当前 JVM；多个 pod 下其他实例仍会并发修改同一账户 |

所以这里的锁不是“为了代码看起来严谨”，而是把并发写入压到同一个 MySQL source of truth 上。
读代码时可以反复问：如果这条 `FOR UPDATE` 拿掉，哪两个请求会同时改哪一行？

## 5. 请求一：刷卡授权 Authorization

### 5.1 触发者

触发者是商户/收单网络发来的授权请求，在项目里表现为客户端调用：

```bash
curl -X POST http://localhost:8080/api/authorizations \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-auth-1500-001' \
  -d '{
    "cardId": "card-123",
    "amount": 1500.00,
    "currency": "JPY",
    "merchantId": "merchant-001",
    "merchantCountry": "JP",
    "cardholderCountry": "JP"
  }'
```

时间：

```text
T1 = 2026-07-01T10:00:00Z
```

入口：

```text
AuthorizationController.authorize(...)
-> AuthorizationCommand
-> AuthorizationService.authorize(...)
```

### 5.2 执行过程

1. Controller 转 command。

   `AuthorizationCommand.requestFingerprint()` 计算请求指纹。

   作用：

   - 同一个 `Idempotency-Key` 重试同一请求可以返回同一个结果。
   - 同一个 key 但 body 不同会触发 `IdempotencyConflictException`。

2. `Authorization.request(...)` 创建新 aggregate。

   示例生成：

   ```text
   authorizations.id = aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1
   status = PENDING
   created_at = 2026-07-01T10:00:00Z
   ```

   状态转换：

   ```text
   无 row -> PENDING
   ```

3. `authorizationRepository.claim(...)` 插入 `authorizations/PENDING`。

   数据库行：

   ```text
   authorizations
   id = aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1
   idempotency_key = demo-auth-1500-001
   card_id = card-123
   amount = 1500.00
   currency = JPY
   status = PENDING
   ```

   冲突防护：

   - `uk_authorizations_idempotency_key` 让同一个 key 只有一个 winner。
   - 并发 duplicate loser 不会继续 reserve credit。

4. `findByIdempotencyKeyForUpdate(...)` 锁 authorization row。

   SQL：

   ```sql
   SELECT ...
   FROM authorizations
   WHERE idempotency_key = 'demo-auth-1500-001'
   FOR UPDATE;
   ```

   锁大小：

   - `authorizations` 里这一条 row。

   锁时间：

   - 从这条 SQL 执行后，到 `AuthorizationService.authorize(...)` 事务 commit/rollback。

   防什么冲突：

   - 同一个 idempotency key 的并发 retry 必须等 winner 完成。
   - retry 读到的是最终 `APPROVED` / `DECLINED`，不会读到半成品 `PENDING` 后自己再执行一次额度预占。

5. 做本地 policy、card 状态和 risk check。

   注意：risk check 在 account lock 之前。

   目的：

   - 风控可能慢，不应该拿着热点账户锁做慢操作。
   - 尽量缩短 `credit_accounts` row lock 的持有时间。

6. `creditAccountRepository.findByIdForUpdate(...)` 锁 credit account row。

   SQL：

   ```sql
   SELECT id, credit_limit, reserved_amount, posted_balance, currency, status
   FROM credit_accounts
   WHERE id = '11111111-1111-1111-1111-111111111111'
   FOR UPDATE;
   ```

   锁大小：

   - `credit_accounts` 的一条账户 row。

   锁时间：

   - 从这条 SQL 执行后，到 authorization 事务 commit/rollback。

   防什么冲突：

   - 两个不同 authorization 同时检查 available credit，导致超额批准。
   - repayment 和 authorization 同时修改 `posted_balance` / 读取 available credit。
   - expiry release 和 authorization reserve 同时基于旧 `reserved_amount` 计算。

7. `CreditAccount.reserve(1500)`。

   变化：

   ```text
   reserved_amount: 0.00 -> 1500.00
   posted_balance: 0.00 -> 0.00
   availableCredit: 100000.00 -> 98500.00
   ```

8. `Authorization.approve(T1)`。

   状态转换：

   ```text
   Authorization: PENDING -> APPROVED
   decided_at = 2026-07-01T10:00:00Z
   expires_at = 2026-07-08T10:00:00Z
   ```

   同时 aggregate 产生：

   ```text
   AuthorizationApprovedDomainEvent
   ```

9. 同事务写 DelayJob 和 Outbox。

   `delay_jobs`：

   ```text
   job_type = AUTHORIZATION_EXPIRY
   aggregate_type = Authorization
   aggregate_id = aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1
   status = PENDING
   scheduled_at = 2026-07-08T10:00:00Z
   ```

   `outbox_events`：

   ```text
   id = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1
   aggregate_type = Authorization
   aggregate_id = aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1
   event_type = authorization.approved
   status = PENDING
   ```

10. 事务 commit。

   释放锁：

   - `authorizations` row lock 释放。
   - `credit_accounts` row lock 释放。

### 5.3 请求一结束后的状态

```text
authorizations
id = aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1
status = APPROVED
amount = 1500.00
expires_at = 2026-07-08T10:00:00Z

credit_accounts
id = 11111111-1111-1111-1111-111111111111
reserved_amount = 1500.00
posted_balance = 0.00
availableCredit = 98500.00

delay_jobs
AUTHORIZATION_EXPIRY / PENDING

outbox_events
authorization.approved / PENDING
```

对其他领域的影响：

- `CardTransaction` 还不存在。
- `Statement` 还不存在。
- `Repayment` 还不存在。
- Notification 和 Risk 是异步消费 `authorization.approved`，不影响主事务。

## 6. 请求二：Presentment Posting 入账

### 6.1 触发者

触发者是外部卡组织/清算网络提交 presentment，表示商户正式请款。

项目里调用：

```bash
curl -X POST http://localhost:8080/api/presentments \
  -H 'Content-Type: application/json' \
  -d '{
    "networkTransactionId": "ntx-20260701-0001",
    "authorizationId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1",
    "amount": 1500.00,
    "currency": "JPY"
  }'
```

时间：

```text
T2 = 2026-07-01T10:05:00Z
```

入口：

```text
PresentmentController.postPresentment(...)
-> PostPresentmentCommand
-> PostingService.post(...)
```

### 6.2 执行过程

1. 锁 authorization row。

   SQL：

   ```sql
   SELECT ...
   FROM authorizations
   WHERE id = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1'
   FOR UPDATE;
   ```

   锁大小：

   - 一条 `authorizations` row。

   锁时间：

   - 从 SQL 执行后，到 `PostingService.post(...)` commit/rollback。

   防什么冲突：

   - posting 和 expiry job 同时处理同一授权。
   - 两个 posting 请求同时把同一授权从 `APPROVED` 改成 `POSTED`。

   如果 expiry 先拿到锁并把授权改成 `EXPIRED`，posting 后拿锁时会发现状态不是 `APPROVED`，拒绝入账。
   如果 posting 先拿到锁并改成 `POSTED`，expiry job 之后看到不是 `APPROVED`，会跳过。

2. 读取 card。

   `cards` 这里只读，不加锁。

   用途：

   - 通过 `card_id = card-123` 找到 `credit_account_id = 11111111-1111-1111-1111-111111111111`。

3. 检查 `networkTransactionId` 是否已存在。

   SQL：

   ```sql
   SELECT ...
   FROM card_transactions
   WHERE network_transaction_id = 'ntx-20260701-0001'
   FOR UPDATE;
   ```

   如果已存在：

   - 相同 presentment 且已经 `POSTED`，直接返回已有结果。
   - 内容不同，抛 `PresentmentConflictException`。

   如果不存在：

   - 继续走新入账流程。

4. 校验 authorization 可入账。

   代码检查：

   - `authorization.status == APPROVED`
   - `T2 <= expires_at`
   - presentment amount 等于 authorization amount

5. 锁 credit account row。

   SQL：

   ```sql
   SELECT id, credit_limit, reserved_amount, posted_balance, currency, status
   FROM credit_accounts
   WHERE id = '11111111-1111-1111-1111-111111111111'
   FOR UPDATE;
   ```

   锁大小：

   - 一条 `credit_accounts` row。

   锁时间：

   - 从 SQL 执行后，到 posting 事务 commit/rollback。

   防什么冲突：

   - posting 和 statement generation 并发，导致账单漏掉刚入账交易。
   - posting 和 repayment 并发，同时改 `posted_balance`。
   - posting 和 authorization/expiry 并发，同时改 `reserved_amount`。

6. 创建 `CardTransaction.pending(...)`。

   示例生成：

   ```text
   card_transactions.id = cccccccc-cccc-cccc-cccc-ccccccccccc1
   network_transaction_id = ntx-20260701-0001
   status = PENDING
   presentment_received_at = 2026-07-01T10:05:00Z
   statement_id = NULL
   ```

7. `transactionRepository.claim(pending)` 插入 card transaction。

   防什么冲突：

   - `uk_card_transactions_network_transaction` 保证同一个 presentment 不会插入两次。
   - 即使两个线程都先查到不存在，最终也只有一个 insert 成功。

8. 再次按 `network_transaction_id` 锁定 card transaction row。

   目的：

   - 让 duplicate retry 等 winner 完成后读取最终状态。
   - 后续更新 `PENDING -> POSTED` 在同一 row lock 下执行。

9. 三个领域状态一起变化。

   `CreditAccount.postAuthorized(1500)`：

   ```text
   reserved_amount: 1500.00 -> 0.00
   posted_balance: 0.00 -> 1500.00
   availableCredit: 98500.00 -> 98500.00
   ```

   `Authorization.post(T2)`：

   ```text
   Authorization: APPROVED -> POSTED
   posted_at = 2026-07-01T10:05:00Z
   ```

   `CardTransaction.markPosted(T2)`：

   ```text
   CardTransaction: PENDING -> POSTED
   posted_at = 2026-07-01T10:05:00Z
   ```

   注意：available credit 没变，因为 1500 从 hold 变成 posted balance。

10. 同事务写 Outbox。

   `authorization.posted`：

   ```text
   outbox_events.id = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2
   aggregate_type = Authorization
   event_type = authorization.posted
   status = PENDING
   ```

   `card_transaction.posted`：

   ```text
   outbox_events.id = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee3
   aggregate_type = CardTransaction
   event_type = card_transaction.posted
   status = PENDING
   ```

11. 事务 commit。

   释放锁：

   - `authorizations` row lock 释放。
   - `credit_accounts` row lock 释放。
   - `card_transactions` row lock 释放。

### 6.3 请求二结束后的状态

```text
authorizations
id = aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1
status = POSTED
posted_at = 2026-07-01T10:05:00Z

credit_accounts
reserved_amount = 0.00
posted_balance = 1500.00
availableCredit = 98500.00

card_transactions
id = cccccccc-cccc-cccc-cccc-ccccccccccc1
status = POSTED
statement_id = NULL
posted_at = 2026-07-01T10:05:00Z
```

对其他领域的影响：

- `Statement` 还未生成，但这笔 `POSTED` 且 `statement_id IS NULL` 的交易会成为账单候选。
- `AuthorizationExpiryService` 未来执行时会看到 authorization 已经 `POSTED`，不会释放额度。
- Notification 会异步消费 `card_transaction.posted` 创建入账通知。

## 7. 请求三：Statement 月度批处理生成账单

### 7.1 触发者

真实主路径是月度账单批处理（现已扁平化为 claimable job，详见 `claimable-jobs-cn.md`）：

```text
BillingCycleScheduler（每天 JST cron，触发 reconciliation 心跳）
-> StatementCycleService.createDueJobs（扫描最近已过去 close cycles，缺失周期才算 cycle + 按账户数建分片 statement_jobs）
-> StatementJobDispatcher（claim 分片）
-> StatementJobHandler（取本片账户：CRC32(account)%shardCount）
-> StatementGenerationService.generate(...)（逐账户独立小事务出账）
```

当前默认产品规则：

```text
close-day-of-month = 31
payment-base-day-of-month = 27
```

本例中：

```text
periodStart = 2026-07-01
periodEnd   = 2026-07-31
dueDate     = 2026-08-27
```

由于 2026-08-27 是日本营业日，本例 dueDate 不需要顺延。
如果 27 日遇到周末、日本法定节假日、振替休日或国民の休日，当前代码会顺延到之后第一个营业日。

HTTP API 仍保留为本地学习和运营 backfill 入口：

```bash
curl -X POST http://localhost:8080/api/statements/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "creditAccountId": "11111111-1111-1111-1111-111111111111",
    "periodStart": "2026-07-01",
    "periodEnd": "2026-07-31",
    "dueDate": "2026-08-27"
  }'
```

时间：

```text
T3 = 2026-08-01T00:00:00Z
```

入口：

```text
Batch path（claimable job）:
BillingCycleScheduler.<daily cron>
-> StatementCycleService.createDueJobs（按账户数建分片 statement_jobs）
-> StatementJobDispatcher claim 分片 -> StatementJobHandler 取本片账户
-> StatementGenerationService.generate(...)（逐账户独立小事务）

Backfill/API path:
StatementController.generate(...)
-> GenerateStatementCommand
-> StatementGenerationService.generate(...)
```

### 7.2 执行过程

1. 锁 credit account row。

   SQL：

   ```sql
   SELECT id, credit_limit, reserved_amount, posted_balance, currency, status
   FROM credit_accounts
   WHERE id = '11111111-1111-1111-1111-111111111111'
   FOR UPDATE;
   ```

   锁大小：

   - 一条 `credit_accounts` row。

   锁时间：

   - 从 SQL 执行后，到 statement generation 事务 commit/rollback。

   防什么冲突：

   - posting 正在把交易入账时，statement 不应该在中间状态扫描候选交易。
   - repayment 正在减少 `posted_balance`、更新 statement 状态时，账单生成和还款不能拿相反锁顺序造成死锁。

2. 锁同账户同周期 statement row。

   SQL：

   ```sql
   SELECT ...
   FROM statements
   WHERE credit_account_id = '11111111-1111-1111-1111-111111111111'
     AND period_start = '2026-07-01'
     AND period_end = '2026-07-31'
   FOR UPDATE;
   ```

   如果已经存在：

   - 直接返回已有 statement。

   如果不存在：

   - 继续创建。

   防什么冲突：

   - 两次并发生成同一账户同一周期账单。
   - `uk_statements_cycle` 是最后防线。

3. 锁待出账 card transaction rows。

   SQL：

   ```sql
   SELECT ...
   FROM card_transactions
   WHERE credit_account_id = '11111111-1111-1111-1111-111111111111'
     AND status = 'POSTED'
     AND statement_id IS NULL
     AND posted_at >= '2026-07-01T00:00:00Z'
     AND posted_at < '2026-08-01T00:00:00Z'
   ORDER BY posted_at, id
   FOR UPDATE;
   ```

   本例锁住：

   ```text
   card_transactions.id = cccccccc-cccc-cccc-cccc-ccccccccccc1
   ```

   锁大小：

   - 本账户、本账单周期、未出账、已入账的交易 rows。

   锁时间：

   - 从这条 SQL 执行后，到 statement generation 事务 commit/rollback。

   防什么冲突：

   - 两个 statement 同时把同一笔 card transaction 收进不同账单。
   - 正在归账时，其他生成流程看到 `statement_id IS NULL` 又重复处理。

4. `Statement.close(...)` 创建账单 aggregate。

   示例生成：

   ```text
   statements.id = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
   status = CLOSED
   total_amount = 1500.00
   minimum_payment_amount = 1000.00
   paid_amount = 0.00
   generated_at = 2026-08-01T00:00:00Z
   ```

   状态转换：

   ```text
   无 statement row -> CLOSED
   ```

   同时生成 `StatementClosedDomainEvent`。

5. `StatementLine.snapshot(...)` 创建账单 line。

   示例：

   ```text
   statement_lines.id = dddddddd-dddd-dddd-dddd-dddddddddd01
   statement_id = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
   card_transaction_id = cccccccc-cccc-cccc-cccc-ccccccccccc1
   amount = 1500.00
   posted_at = 2026-07-01T10:05:00Z
   ```

   为什么要 snapshot：

   - 账单不是每次查询动态 sum。
   - 生成后用户看到的账单金额和明细要稳定可审计。

6. `StatementBillingRepository.markCardTransactionsBilled(...)`。

   变化：

   ```text
   card_transactions.id = cccccccc-cccc-cccc-cccc-ccccccccccc1
   billing_status: UNBILLED -> BILLED
   statement_id: NULL -> bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
   statement_assigned_at = 2026-08-01T00:00:00Z
   status = POSTED 不变
   ```

   注意：

   - 这不是 `CardTransaction` 新状态。
   - 它是归属关系变化：这笔交易已经被某期 statement 收录。

7. 同事务写 `AUTO_REPAYMENT` DelayJob。

   ```text
   delay_jobs.id = ffffffff-ffff-ffff-ffff-fffffffffff1
   job_type = AUTO_REPAYMENT
   aggregate_type = Statement
   aggregate_id = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
   status = PENDING
   scheduled_at = 2026-08-27T00:00:00Z
   ```

   这里用 DelayJob，因为自动扣款是 dueDate 未来业务动作；
   `statement.closed` 才是要发布给下游的 integration event。

8. 同事务写 Outbox。

   ```text
   outbox_events.id = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee4
   aggregate_type = Statement
   aggregate_id = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
   event_type = statement.closed
   partition_key = 11111111-1111-1111-1111-111111111111
   status = PENDING
   ```

9. 事务 commit。

   释放锁：

   - `credit_accounts` row lock 释放。
   - 同周期 `statements` row / unique key 相关锁释放。
   - 候选 `card_transactions` row locks 释放。

### 7.3 请求三结束后的状态

```text
credit_accounts
reserved_amount = 0.00
posted_balance = 1500.00
availableCredit = 98500.00

card_transactions
id = cccccccc-cccc-cccc-cccc-ccccccccccc1
status = POSTED
statement_id = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1

statements
id = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
status = CLOSED
total_amount = 1500.00
paid_amount = 0.00
minimum_payment_amount = 1000.00
```

对其他领域的影响：

- `CreditAccount.posted_balance` 不变，因为账单生成不是还款。
- `CardTransaction` 不再是未出账候选。
- `DelayJob` 后续到 `dueDate` 会触发 `AUTO_REPAYMENT`。
- Notification 异步消费 `statement.closed` 创建 `STATEMENT_CLOSED` 通知。
- 现在 `Repayment` 可以针对这张 statement 入账。

## 8. 请求四：客户提前主动部分还款 500 JPY

### 8.1 触发者

触发者是客户在 dueDate 之前主动还款。
这个例子故意保留，是为了展示 `Statement.CLOSED -> PARTIALLY_PAID` 的中间状态。

当前项目用 API 表达：

```bash
curl -X POST http://localhost:8080/api/repayments \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-repayment-500-001' \
  -d '{
    "statementId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1",
    "amount": 500.00,
    "currency": "JPY"
  }'
```

时间：

```text
T4 = 2026-08-10T09:00:00Z
```

入口：

```text
RepaymentController.receive(...)
-> ReceiveRepaymentCommand
-> RepaymentService.receive(...)
```

### 8.2 执行过程

1. 非锁定读取 statement。

   SQL：

   ```sql
   SELECT ...
   FROM statements
   WHERE id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1';
   ```

   不加锁的原因：

   - 这里只是确认 statement 存在，并拿 `credit_account_id`。
   - 真正的金额和状态判断后面会在 `FOR UPDATE` 锁住 statement 后重做。
   - 先查存在性可以避免 repayment claim 先撞 FK，导致数据库异常不清晰。

2. `Repayment.pending(...)` 创建新 repayment aggregate。

   示例生成：

   ```text
   repayments.id = 99999999-9999-9999-9999-999999999991
   idempotency_key = demo-repayment-500-001
   statement_id = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
   amount = 500.00
   status = PENDING
   created_at = 2026-08-10T09:00:00Z
   ```

   状态转换：

   ```text
   无 repayment row -> PENDING
   ```

3. `repaymentRepository.claim(...)` 插入 repayment row。

   防什么冲突：

   - `uk_repayments_idempotency_key` 保证同一个还款 key 只有一个 winner。
   - duplicate callback / retry 不会重复减少 `posted_balance`。

4. 锁 repayment row。

   SQL：

   ```sql
   SELECT ...
   FROM repayments
   WHERE idempotency_key = 'demo-repayment-500-001'
   FOR UPDATE;
   ```

   锁大小：

   - 一条 `repayments` row。

   锁时间：

   - 从 SQL 执行后，到 repayment 事务 commit/rollback。

   防什么冲突：

   - 同一个还款请求重复进来时，duplicate 等 winner 完成后读取 `RECEIVED`。
   - 相同 key 但不同 amount / statement 会被 fingerprint 拒绝。

5. 锁 credit account row。

   SQL：

   ```sql
   SELECT id, credit_limit, reserved_amount, posted_balance, currency, status
   FROM credit_accounts
   WHERE id = '11111111-1111-1111-1111-111111111111'
   FOR UPDATE;
   ```

   锁大小：

   - 一条 `credit_accounts` row。

   锁时间：

   - 从 SQL 执行后，到 repayment 事务 commit/rollback。

   防什么冲突：

   - repayment 和 authorization 并发：还款释放额度，授权读取 available credit，必须串行。
   - repayment 和 posting 并发：都改 `posted_balance`，必须串行。
   - repayment 和 statement generation 并发：保持 account-first 锁顺序，避免死锁。

6. 锁 statement row。

   SQL：

   ```sql
   SELECT ...
   FROM statements
   WHERE id = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1'
   FOR UPDATE;
   ```

   锁大小：

   - 一条 `statements` row。

   锁时间：

   - 从 SQL 执行后，到 repayment 事务 commit/rollback。

   防什么冲突：

   - 两笔不同 repayment 同时给同一张 statement 还款，导致 `paid_amount` 超过 `total_amount`。
   - repayment 和未来 overdue scheduler 同时改 statement status。

7. 在锁内重新校验。

   当前锁内看到：

   ```text
   statement.total_amount = 1500.00
   statement.paid_amount = 0.00
   statement.remainingAmount = 1500.00
   statement.status = CLOSED

   account.posted_balance = 1500.00
   ```

   校验：

   - repayment currency 必须等于 statement/account currency。
   - statement 不能已经 `PAID`。
   - repayment amount `500.00 <= statement.remainingAmount 1500.00`。
   - repayment amount `500.00 <= account.posted_balance 1500.00`。

8. 更新 `CreditAccount`。

   `account.applyRepayment(500)`：

   ```text
   posted_balance: 1500.00 -> 1000.00
   reserved_amount: 0.00 -> 0.00
   availableCredit: 98500.00 -> 99000.00
   ```

9. 更新 `Statement`。

   `statement.applyRepayment(500, T4)`：

   ```text
   paid_amount: 0.00 -> 500.00
   status: CLOSED -> PARTIALLY_PAID
   updated_at = 2026-08-10T09:00:00Z
   remainingAmount: 1000.00
   ```

10. 更新 `Repayment`。

   `repayment.markReceived(...)`：

   ```text
   status: PENDING -> RECEIVED
   credit_account_id = 11111111-1111-1111-1111-111111111111
   received_at = 2026-08-10T09:00:00Z
   ```

   同时产生：

   ```text
   RepaymentReceivedDomainEvent
   ```

11. 同事务写 Outbox。

   ```text
   outbox_events.id = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee5
   aggregate_type = Repayment
   aggregate_id = 99999999-9999-9999-9999-999999999991
   event_type = repayment.received
   partition_key = 11111111-1111-1111-1111-111111111111
   status = PENDING
   ```

12. 事务 commit。

   释放锁：

   - `repayments` row lock 释放。
   - `credit_accounts` row lock 释放。
   - `statements` row lock 释放。

### 8.3 请求四结束后的状态

```text
repayments
id = 99999999-9999-9999-9999-999999999991
status = RECEIVED
amount = 500.00

statements
id = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
status = PARTIALLY_PAID
paid_amount = 500.00
remainingAmount = 1000.00

credit_accounts
reserved_amount = 0.00
posted_balance = 1000.00
availableCredit = 99000.00
```

对其他领域的影响：

- `Authorization` 仍是 `POSTED`，不会因为还款变回 `APPROVED`。
- `CardTransaction` 仍是 `POSTED`，历史消费流水不会被还款删除。
- `StatementLine` 不变，账单明细快照不被还款改写。
- Notification 异步消费 `repayment.received` 创建 `REPAYMENT_RECEIVED` 通知。

## 9. 请求五：dueDate 自动扣款还清剩余 1000 JPY

### 9.1 触发者

到 `dueDate = 2026-08-27`，`AUTO_REPAYMENT` DelayJob 到期。
当前模拟银行扣款默认成功，所以系统自动扣剩余 1000 JPY。

入口：

```text
DelayJobPoller.pollDueJobs()
-> DelayJobWorker.handleClaimedJob(job)
-> AutoRepaymentDelayJobHandler.handle(job)
-> AutoRepaymentService.debitStatement(statementId)
-> SimulatedBankDebitGateway.debit(request)
-> RepaymentService.receive(command)
```

时间：

```text
T5 = 2026-08-27T00:00:00Z
```

自动构造的 repayment command：

```text
idempotencyKey = auto-debit:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
statementId = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
amount = 1000.00
currency = JPY
```

为什么不是直接改账单：

- bank debit result = `SUCCESS` 之后，才可以入账。
- `FAILED` 时不能创建 `RECEIVED` repayment，否则会把没到账的资金记成已还。
- 自动扣款成功后仍复用 `RepaymentService.receive(...)`，避免复制一套余额和账单状态更新逻辑。

### 9.2 与第一次还款相同的锁顺序

锁顺序仍然是：

```text
repayments row by idempotency_key
-> credit_accounts row by account id
-> statements row by statement id
```

锁内重新读取到：

```text
statement.paid_amount = 500.00
statement.remainingAmount = 1000.00
statement.status = PARTIALLY_PAID
account.posted_balance = 1000.00
```

### 9.3 状态变化

`CreditAccount.applyRepayment(1000)`：

```text
posted_balance: 1000.00 -> 0.00
availableCredit: 99000.00 -> 100000.00
```

`Statement.applyRepayment(1000, T5)`：

```text
paid_amount: 500.00 -> 1500.00
status: PARTIALLY_PAID -> PAID
remainingAmount: 0.00
```

`Repayment.markReceived(...)`：

```text
repayments.id = 99999999-9999-9999-9999-999999999992
idempotency_key = auto-debit:bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
status: PENDING -> RECEIVED
amount = 1000.00
```

同事务写：

```text
outbox_events
id = eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee6
event_type = repayment.received
status = PENDING
```

### 9.4 请求五结束后的最终状态

```text
authorizations
id = aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1
status = POSTED

card_transactions
id = cccccccc-cccc-cccc-cccc-ccccccccccc1
status = POSTED
statement_id = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1

statements
id = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
status = PAID
total_amount = 1500.00
paid_amount = 1500.00

credit_accounts
credit_limit = 100000.00
reserved_amount = 0.00
posted_balance = 0.00
availableCredit = 100000.00

repayments
99999999-9999-9999-9999-999999999991 / RECEIVED / 500.00
99999999-9999-9999-9999-999999999992 / RECEIVED / 1000.00
```

## 10. 异步事件怎样影响其他领域

### 10.1 Outbox 是主事务的一部分

在每个主业务事务里，service 不直接发 Kafka，而是写 `outbox_events`：

| 触发点 | Domain event | Outbox event_type | 下游影响 |
| --- | --- | --- | --- |
| `Authorization.approve(...)` | `AuthorizationApprovedDomainEvent` | `authorization.approved` | Notification、Risk projection |
| `Authorization.post(...)` | `AuthorizationPostedDomainEvent` | `authorization.posted` | 其他授权生命周期消费者 |
| `CardTransaction.markPosted(...)` | `CardTransactionPostedDomainEvent` | `card_transaction.posted` | Notification、Ledger |
| `Statement.close(...)` | `StatementClosedDomainEvent` | `statement.closed` | Notification |
| `Repayment.markReceived(...)` | `RepaymentReceivedDomainEvent` | `repayment.received` | Notification、Ledger |

这些 Outbox row 和业务状态一起 commit。

这样避免两种不一致：

- 业务 commit 成功但 Kafka 发送失败，事件丢失。
- Kafka 发送成功但业务 rollback，下游看到不存在的业务事实。

### 10.2 Kafka publish 不持有业务锁

`OutboxWorker` 后续把 `outbox_events/PENDING` 发布到 Kafka：

```text
PENDING -> PROCESSING -> PUBLISHED
```

这发生在业务事务之后。

所以：

- 不锁 `credit_accounts`。
- 不锁 `authorizations`。
- 不锁 `statements`。
- 不锁 `repayments`。

如果 Kafka 慢或挂了，只影响 Outbox 重试，不会延长授权/入账/还款的业务锁时间。

### 10.3 Notification 是消费结果，不影响主流程

例如 `repayment.received`：

```text
RepaymentService commit
-> Outbox worker publish Kafka
-> RepaymentNotificationListener
-> RequestNotificationService
-> consumer_inbox claim
-> notifications/PENDING
```

Notification 自己用两层幂等：

- `consumer_inbox(consumer_name, event_id)`
- `notifications.source_event_id` unique constraint

如果 Notification 失败：

- 不 rollback repayment。
- Kafka listener retry / DLT 处理。

### 10.4 Ledger 是消费结果，不影响主流程

例如 `repayment.received`：

```text
RepaymentService commit
-> Outbox worker publish Kafka
-> RepaymentLedgerListener
-> RecordLedgerEntryService
-> consumer_inbox claim
-> ledger_entries/REPAYMENT_RECEIVED/CREDIT
```

Ledger projection 自己用两层幂等：

- `consumer_inbox(consumer_name, event_id)`
- `ledger_entries(source_event_id, entry_type)` unique constraint

它记录内部账本视角：

- `card_transaction.posted` -> `CARD_TRANSACTION_POSTED/DEBIT`
- `repayment.received` -> `REPAYMENT_RECEIVED/CREDIT`

当前 Ledger 是学习用 minimal projection，不是生产级 double-entry general ledger。

### 10.5 DelayJob 是未来业务动作

Statement 生成时，同事务写入：

```text
delay_jobs
job_type = AUTO_REPAYMENT
aggregate_type = Statement
aggregate_id = bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1
scheduled_at = 2026-08-27T00:00:00Z
status = PENDING
```

到 `dueDate` 后：

```text
DelayJob PENDING -> PROCESSING
-> AutoRepaymentService
-> bank debit SUCCESS
-> RepaymentService.receive(...)
-> DelayJob DONE
```

如果 bank debit result = `FAILED`：

- 不会创建 `RECEIVED` repayment。
- DelayJob 会按 retry/backoff/DEAD 记录失败路径。
- 未来可以在这个分支接失败通知、再次扣款、逾期标记。

## 11. 常见并发冲突怎么被挡住

### 11.1 同一个授权请求重复提交

场景：

```text
Client A 和 Client B 同时用 Idempotency-Key demo-auth-1500-001 请求授权。
```

保护：

- `authorizations.idempotency_key` unique constraint 选出唯一 winner。
- `findByIdempotencyKeyForUpdate` 让 loser 等 winner 完成。

结果：

- 只会 reserve 一次 `1500 JPY`。
- duplicate 返回同一个 authorization 结果。

### 11.2 两个不同授权同时刷同一个账户

场景：

```text
授权 A = 90000 JPY
授权 B = 20000 JPY
同一个 credit account 剩余 100000 JPY
```

保护：

- 两个请求最终都要锁同一条 `credit_accounts` row。
- 谁先拿锁谁先计算 available credit 并更新 `reserved_amount`。
- 第二个请求拿锁时会看到最新余额。

结果：

- 不会两个都基于旧余额批准导致超额。

### 11.3 Presentment 和 expiry job 同时发生

场景：

```text
授权快过期时，presentment 到达；expiry job 也醒来。
```

保护：

- 两边都先锁同一条 `authorizations` row。

结果：

- posting 先成功：authorization 变 `POSTED`，expiry 后续跳过。
- expiry 先成功：authorization 变 `EXPIRED`，posting 后续拒绝。

### 11.4 Presentment 和 statement generation 并发

场景：

```text
账单生成正在扫描未出账交易，同时一笔 presentment 正在入账。
```

保护：

- 两边都锁同一条 `credit_accounts` row。
- `PostingService` 在 account lock 后插入并更新 `card_transactions`。
- `StatementGenerationService` 在 account lock 后扫描 `POSTED AND statement_id IS NULL` 的交易。

结果：

- 如果 posting 先 commit，statement 会看见这笔 posted transaction。
- 如果 statement 先 commit，posting 会在下一期账单候选里。
- 不会出现 statement 扫描时漏掉一个已经算进 `posted_balance` 的中间状态。

### 11.5 两笔还款同时还同一张 statement

场景：

```text
statement remaining = 1000 JPY
两个 repayment 各还 800 JPY
```

保护：

- 两边都锁 `credit_accounts` row。
- 两边都锁同一条 `statements` row。
- 第二个 repayment 拿锁时会看到第一个已经更新后的 `paid_amount`。

结果：

- 第一笔 800 成功后，remaining 变 200。
- 第二笔 800 会被拒绝：`repayment amount exceeds statement remaining amount`。

### 11.6 Repayment 和 Authorization 并发

场景：

```text
账户 posted_balance = 1500
客户还款 1500，同时又刷卡授权 100000
```

保护：

- repayment 和 authorization 都会锁同一条 `credit_accounts` row。

结果：

- 如果 repayment 先 commit，authorization 看到 available credit 已恢复。
- 如果 authorization 先 commit，repayment 之后再恢复额度。
- 不会出现两个操作同时基于旧 `posted_balance` 计算可用额度。

## 12. 你可以这样在interview里讲

> 这个项目把状态变化放在 aggregate 里：Authorization 负责 PENDING/APPROVED/POSTED/EXPIRED，CardTransaction 负责 PENDING/POSTED 和 statement assignment，Statement 负责 CLOSED/PARTIALLY_PAID/PAID，Repayment 负责 PENDING/RECEIVED。Application service 负责 transaction boundary 和跨 aggregate 编排。Statement batch 用固定关账日生成账单，并写 `AUTO_REPAYMENT` DelayJob 作为 due-date 未来业务动作。所有会改变额度的操作都要锁同一条 credit account row，用 MySQL `SELECT ... FOR UPDATE` 串行化，而不是用 JVM lock。Outbox row 和业务状态同事务提交，Kafka/Notification 在事务后异步处理，所以消息失败不会扩大主交易锁时间。

更短一点：

> Authorization 先 hold 额度，Posting 把 hold 转成 posted balance，Statement batch 固定账单快照并计划 due-date 自动扣款，Repayment 减少 posted balance 并推进 statement paid status。每一步都有明确 idempotency key、row lock 和 domain state transition。
