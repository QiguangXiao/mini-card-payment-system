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

- `Aggregate`：一组需要保持一致的业务对象。当前重点是 `Authorization` 和 `CreditAccount`。
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
- `expired_at`：定时任务实际完成过期处理的时间。

### `credit_accounts`

保存信用账户额度。

核心字段：

- `credit_limit_amount`：总额度。
- `reserved_amount`：已预占但尚未 capture 或释放的额度。
- `status`：账户是否可用。

当前授权只做 reserve/release，不做 capture。

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
- `partition_key`：Kafka 分区 key，目前用 authorization id。
- `payload`：JSON 字符串。
- `status`：`PENDING`、`PUBLISHED`、`DEAD`。

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

4. `returnIdempotentResult(persisted, command)`

   比较 fingerprint。
   同一个 key + 相同请求：允许返回已有结果。
   同一个 key + 不同请求：抛幂等冲突。

5. `decideAndReserve(persisted, command, now)`

   只有 idempotency winner 会进入真实决策。

### 3.4 决策和额度预占阶段

入口：

- `AuthorizationService.decideAndReserve(...)`

关键顺序：

1. `decisionPolicy.decide(authorization)`

   执行便宜的本地授权策略。
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

- `DelayJobAuthorizationExpiryJobScheduler.schedule(authorization)`

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

- `OutboxAuthorizationDecisionEventPublisher.append(authorization)`

处理：

- 创建 `AuthorizationDecidedEvent` payload。
- 创建 `IntegrationEventEnvelope`，包含 event metadata 和 payload。
- `eventId = UUID.randomUUID()`。
- 写入 `outbox_events`，状态为 `PENDING`。

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
- `outbox_events`：新增 `authorization.decided/PENDING`。

## 4. 核心流程二：Outbox 发布消息

入口：

- `OutboxPublisherScheduler.publishPendingEvents()`
- `OutboxPublisherService.publishBatch()`

触发方式：

- Spring `@Scheduled`
- 使用 `outboxTaskScheduler` worker pool

关键步骤：

1. `findPublishableBatchForUpdate(now, batchSize)`

   MyBatis XML 使用：

   ```sql
   FOR UPDATE SKIP LOCKED
   ```

   多个 pod 可以同时扫描 outbox，但不会选中同一批 row。

2. `messagePublisher.publish(event, timeout)`

   把已保存的 JSON payload 发布到 Kafka。

3. `event.markPublished(...)`

   发布成功后标记 `PUBLISHED`。

4. `event.markFailed(...)`

   发布失败时增加 `attempts`，计算 exponential backoff。
   达到最大次数后进入 `DEAD`。

重要语义：

- Outbox 是 `at-least-once delivery`。
- Kafka ack 和 MySQL commit 不能原子化，所以消息可能重复。
- 下游 consumer 必须用 `eventId` 做幂等。

## 5. 核心流程三：DelayJob 自动过期授权

当前业务目标：

- 7 天内没有 capture 的 approved authorization，需要自动撤销 hold，并恢复额度。

入口：

- `DelayJobScheduler.dispatchDueJobs()`
- `DelayJobService.dispatchNext()`
- `AuthorizationExpiryDelayJobHandler.handle(job)`
- `AuthorizationExpiryService.expire(authorizationId)`

触发方式：

- Spring `@Scheduled`
- 使用 `delayJobTaskScheduler` worker pool
- 和 Outbox 使用相似 scheduler 模式，但线程池隔离。

### 5.1 Claim job

关键方法：

- `DelayJobService.claimNextRunnable()`

处理：

- 查询 `delay_jobs` 中到期的 `PENDING` 或 lease 已过期的 `PROCESSING`。
- 使用 `FOR UPDATE SKIP LOCKED`。
- 把 job 标记成 `PROCESSING`。
- 设置 `next_attempt_at = now + processingTimeoutSeconds`。

为什么 `PROCESSING` 是 lease：

- 如果 pod claim job 后宕机，job 不能永久卡住。
- lease 到期后，其他 pod 可以重新 claim。

### 5.2 Dispatch handler

关键方法：

- `AuthorizationExpiryDelayJobHandler.jobType()`
- `AuthorizationExpiryDelayJobHandler.handle(job)`

处理：

- `jobType()` 返回 `AUTHORIZATION_EXPIRY`。
- `handle()` 把 `job.aggregateId` 转成 authorization id。
- 调用 `AuthorizationExpiryService.expire(...)`。

这种设计方便以后新增其他 delay job 类型：

- 新增 enum。
- 新增 handler。
- 新增业务 scheduler adapter。

### 5.3 Business transaction

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
   设置 `expiredAt = now`。

8. 保存 account、authorization，并写 expired Outbox event

   三件事同事务提交：

   - `credit_accounts.reserved_amount` 减少。
   - `authorizations.status = EXPIRED`。
   - `outbox_events` 新增 `authorization.expired/PENDING`。

### 5.4 Mark done or failed

回到：

- `DelayJobService.dispatchNext()`

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
- handler 业务事务可以独立 rollback。
- 失败记录单独提交，避免错误信息跟业务 rollback 一起消失。

## 6. 核心流程四：查询授权

示例请求：

```bash
curl http://localhost:8080/api/authorizations/{authorizationId}
```

入口：

- `AuthorizationController.get(id)`
- `AuthorizationService.get(id)`

处理：

- `@Transactional(readOnly = true)`
- 通过 authorization id 查询当前状态。
- 不写 outbox。
- 不写 delay job。
- 不修改额度。

这个接口适合观察状态变化：

- 创建后可能是 `APPROVED`。
- 7 天过期任务执行后会变成 `EXPIRED`。

## 7. Outbox vs DelayJob 对比

| 项目 | Outbox | DelayJob |
| --- | --- | --- |
| 目标 | 可靠发布消息 | 到时间执行业务动作 |
| 表 | `outbox_events` | `delay_jobs` |
| 当前例子 | `authorization.decided`, `authorization.expired` | `AUTHORIZATION_EXPIRY` |
| 生产者 | 业务事务中的 event publisher | 业务事务中的 job scheduler adapter |
| 消费者 | `OutboxPublisherScheduler` | `DelayJobScheduler` |
| 并发控制 | `FOR UPDATE SKIP LOCKED` | `FOR UPDATE SKIP LOCKED` |
| 失败处理 | retry/backoff/DEAD | retry/backoff/PROCESSING lease/DEAD |
| 幂等重点 | consumer 用 eventId 去重 | handler 读取业务 source of truth |
| 线程池 | `outboxTaskScheduler` | `delayJobTaskScheduler` |

一句话区分：

- Outbox 表保存“我要告诉别人发生了什么”。
- DelayJob 表保存“未来我要自己做一件业务动作”。

## 8. 面试容易追问的点

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
真正的业务事实在 `authorizations.status` 和 `credit_accounts.reserved_amount`。
job 可以失败、重试、被人工修复，但业务表必须始终是 source of truth。

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

- `authorization.decided`
- `authorization.expired`

所以 event id 是事件本身的唯一标识，authorization id 是 aggregate 标识。

## 9. Spring 和 MyBatis 语法速查

### `@RestController`

表示这个类处理 HTTP 请求，返回对象会自动序列化成 JSON。

### `@Valid`

触发 DTO 上的 Bean Validation，例如 `@NotBlank`、`@Positive`。

### `@Transactional`

声明事务边界。
当前最关键的是 `AuthorizationService.authorize()` 和 `AuthorizationExpiryService.expire()`。

### `@Transactional(readOnly = true)`

用于只读查询。
它表达语义，也让底层框架有机会做只读优化。

### `@Scheduled`

声明定时任务。
当前 Outbox 和 DelayJob 都用 scheduler 轮询数据库。

### `scheduler = "delayJobTaskScheduler"`

指定使用哪个 `ThreadPoolTaskScheduler` bean。
这让 Outbox 和 DelayJob 使用不同 worker pool。

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

## 10. 后续学习建议

当前实现已经适合学习面试核心点。
如果继续扩展，建议按这个顺序：

1. 引入 Flyway 或 Liquibase，管理 schema migration，而不是只靠 `schema.sql`。
2. 增加 capture 流程，让 approved authorization 可以被正式入账。
3. 增加 reservation ledger，记录每次 reserve/release/capture 的明细。
4. 给 outbox/delay job 增加 metrics，例如 pending 数、dead 数、处理耗时。
5. 增加 dead job/admin replay endpoint，但要加权限控制后再做。
6. 补充少量并发测试，验证同账户多个授权不会超额。

这一阶段最应该讲清楚的是：

- 一个请求如何被幂等地处理。
- 额度为什么不会在并发下被超用。
- 为什么 delay job 和 outbox 都写在主事务里。
- 为什么 delay job 和 outbox 分表，但 scheduler 形状保持对称。
