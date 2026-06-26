# High Traffic System Design 高流量系统设计手册

这份文档专门回答 PayPay Card Backend Engineer interview 里很容易被深挖的主题：

```text
如果 authorization QPS 增加 10 倍，你会怎么设计、压测、扩容和排障？
```

它覆盖当前 mini-card-payment-system 的真实结构，但不局限于这个项目。目标是让你能把
`idempotency`、`row lock`、`transaction boundary`、Kafka、cache、线程池、连接池、限流、
backpressure、observability 和 production failure mode 放在同一套回答里。

> [!IMPORTANT]
> 高流量系统设计不是“加 Redis、加机器、拆微服务”。  
> 金融后端的高分答案通常是：先守住 correctness boundary，再定位瓶颈，再做容量和降级设计。

## 1. 一句话总论

可以先背这个版本：

> 对 credit card authorization 这种 money-changing path，我不会先追求最大 QPS，而是先保证同一账户额度不会被并发超用、同一请求不会重复占额度、业务状态和 Outbox publish intent 在同一个 `transaction boundary` 提交。  
> 高流量下我会缩短 account `row lock` 的 critical section，把外部风控、Kafka publish、Notification、Ledger、Risk projection 移出主事务；读扩展只缓存可重建 snapshot；后台 worker 使用 bounded pool、lease 和 retry；观测上看 p95/p99、DB lock wait、Hikari pending、Kafka lag、Outbox/DelayJob backlog、Redis error 和 JVM GC。

这段话命中四个核心点：

| 核心点 | 为什么重要 |
| --- | --- |
| correctness first | 金融系统不能用吞吐换错误扣款或超额授权 |
| locate bottleneck | 不定位瓶颈就扩容，可能只是把 DB 打得更满 |
| reduce critical section | 同账户写入必须串行，所以锁内逻辑越短越好 |
| degrade intentionally | Kafka、Redis、Risk、DB 不同依赖的降级策略不同 |

## 2. 当前项目的高流量证据地图

当前项目已经具备一些 high traffic 设计点，但也有还没实现的生产级能力。

| 主题 | 当前项目证据 | high traffic 意义 |
| --- | --- | --- |
| API validation | `AuthorizationController`, `CreateAuthorizationRequest`, Bean Validation | 无效请求尽早失败，不进入热事务 |
| API idempotency | `Idempotency-Key`, `authorizations.idempotency_key` unique | timeout retry 不重复 hold credit |
| 请求 fingerprint | `AuthorizationCommand.requestFingerprint()` | 同 key 不同 body 返回 conflict |
| 便宜规则前置 | `AuthorizationService.checkSingleTransactionLimit(...)` | 先拒绝明显无效请求，减少 DB 锁竞争 |
| 风控在锁前 | `RiskAssessmentService.assess(...)` 在 account lock 前 | 外部 I/O 不持有 account row lock |
| 账户行锁 | `CreditAccountRepository.findByIdForUpdate(...)` | 同 account 额度变化串行 |
| Kafka publish 异步化 | `AuthorizationOutboxAdapter`, `OutboxWorker` | 主请求不等 broker ack |
| 后台 worker 有界 | `outbox.worker-pool-size=4`, queue `100` | 下游慢时不无限堆线程和内存 |
| Worker ownership | `FOR UPDATE SKIP LOCKED`, `PROCESSING lease` | 多实例不重复 claim 同一 row |
| Redis velocity | `RedisRiskVelocityCounter` | 把每笔授权的近期计数 workload 从主库搬到 Redis |
| Statement GET cache | `StatementReadService`, `docs/cache-snapshot-design-cn.md` | Caffeine L1 + Redis L2 cache-aside，学习“不缓存额度”和 after-commit eviction |
| Redis timeout | `spring.data.redis.timeout=1s` | Redis 不应拖垮主请求；velocity 计数 fail-open |
| Kafka partition | source topics 3 partitions, listener concurrency 2/3 | 提供局部顺序和 consumer 并行度 |
| Actuator | `/actuator/health`, `/actuator/metrics` | 提供 JVM、HTTP、Hikari、health 基础指标 |

当前还缺的生产证据：

| 缺口 | 为什么 interview 会问 |
| --- | --- |
| 压测脚本 | 你说能扛高流量，怎么证明 |
| 容量模型 | 线程、连接、QPS、latency 的关系怎么估算 |
| 限流策略 | 热点账户、恶意 client、重试风暴怎么保护 |
| 指标 dashboard | p99 慢时如何区分 app、DB、Kafka、Redis、Risk |
| AWS/ECS 容量映射 | 上云后怎么扩 task、RDS、MSK、ElastiCache |

这份文档先补“设计和回答能力”，后续可以再补 k6/Gatling 脚本。

## 3. High traffic 不是单一问题

interview 官问“高流量怎么办”，通常不是只问吞吐。他在验证你能不能把系统拆成几个可定位的瓶颈。

| 层次 | 典型瓶颈 | 当前项目锚点 |
| --- | --- | --- |
| Client/API | retry storm, invalid request, timeout | `Idempotency-Key`, Bean Validation |
| Tomcat | request threads busy, queueing | servlet request path, Actuator HTTP metrics |
| Application service | 长事务、外部调用、锁顺序 | `AuthorizationService.authorize(...)` |
| DB connection pool | Hikari pending, connection timeout | datasource/Hikari metrics |
| MySQL SQL | slow query, missing index, lock wait | MyBatis XML, `FOR UPDATE` |
| MySQL row lock | hotspot account, deadlock | `credit_accounts` row lock |
| External dependency | risk latency, timeout, circuit open | Feign + Resilience4j `externalRisk` |
| Kafka producer | broker ack slow, send timeout | `KafkaOutboxMessagePublisher`, Outbox backlog |
| Kafka consumer | consumer lag, DLT, slow side effect | Notification/Risk/Ledger listeners |
| Redis/cache | timeout, limiter degradation, cache miss storm/stale data | `RedisRiskVelocityCounter`, `StatementReadService`, cache design docs |
| JVM/runtime | GC pause, heap pressure, thread count | JVM/thread docs and Actuator |

> [!WARNING]
> “QPS 不够就加 pod”是危险回答。  
> 如果瓶颈是同一 `credit_accounts` row lock，加再多 app instance，同一个账户的写入仍然要串行。

## 4. Authorization 热路径拆解

当前最重要的 high traffic 讨论对象是 authorization。

```text
POST /api/authorizations
-> AuthorizationController
-> AuthorizationCommand
-> AuthorizationService.authorize(...)
-> claim idempotency row
-> find authorization by idempotency key FOR UPDATE
-> cheap local checks
-> cardRepository.findById(cardId)
-> riskAssessmentService.assess(...)
-> creditAccountRepository.findByIdForUpdate(accountId)
-> CreditAccount.reserve(...)
-> authorization.approve/decline
-> update account
-> update authorization
-> insert DelayJob
-> insert Outbox event
-> commit
-> response
```

### 4.1 锁前做什么

锁前适合做：

- DTO validation。
- `Idempotency-Key` claim。
- request fingerprint check。
- 单笔金额上限检查。
- card reference data check。
- local risk rule。
- external risk call。

原因：

> account `row lock` 是同账户授权请求的串行点。锁前多做便宜检查，可以让明显拒绝的请求不进入锁；把慢外部调用放锁前，可以避免一个慢 risk call 拖住同账户所有请求。

### 4.2 锁内做什么

锁内只做必须基于最新账户状态的事情：

- 读取 `credit_accounts` 当前行。
- 计算 available credit。
- `CreditAccount.reserve(...)`。
- 更新 `reserved_amount`。
- 把 authorization 推进到 APPROVED/DECLINED。
- 写与主状态强绑定的 DelayJob 和 Outbox row。

锁内不应该做：

- 等 Kafka broker ack。
- 发送 Notification。
- 写 Ledger projection。
- 做慢 HTTP 调用。
- 扫描大量历史交易。
- 生成复杂报表。

### 4.3 锁后做什么

commit 后或异步做：

- Outbox worker publish Kafka。
- Notification consumer 创建通知。
- Risk feature consumer 更新 projection。
- Ledger consumer 写 minimal ledger entries。
- cache evict after commit。
- metrics/logging/tracing。

> [!TIP]
> 一个高分表达：  
> “我不是避免锁，而是让锁只保护必须串行的最小业务事实。”

### 4.4 具体并发剧本：请求 A/B 和线程 A/B

这一节把上面的原则变成具体场景。interview 里不要只说“用了锁”“用了幂等”，要能讲清两个请求真的同时进来时，代码怎么执行。

#### 剧本 1：两个不同授权同时刷同一个账户

初始数据：

```text
creditAccountId = account-123
creditLimit = 100000 JPY
reservedAmount = 0
postedBalance = 0
availableCredit = 100000 JPY

card-123 -> account-123
card-secondary -> account-123
```

请求 A：

```http
POST /api/authorizations
Idempotency-Key: auth-A-001
```

```json
{
  "cardId": "card-123",
  "amount": 80000,
  "currency": "JPY",
  "merchantId": "merchant-a",
  "merchantCountry": "JP",
  "cardholderCountry": "JP"
}
```

请求 B：

```http
POST /api/authorizations
Idempotency-Key: auth-B-001
```

```json
{
  "cardId": "card-secondary",
  "amount": 30000,
  "currency": "JPY",
  "merchantId": "merchant-b",
  "merchantCountry": "JP",
  "cardholderCountry": "JP"
}
```

执行过程：

```text
Thread A -> AuthorizationService.authorize(A)
Thread B -> AuthorizationService.authorize(B)

A claim idempotency succeeds
B claim idempotency succeeds

A passes card/risk checks
B passes card/risk checks

A calls creditAccountRepository.findByIdForUpdate(account-123)
  -> locks credit_accounts row
  -> availableCredit = 100000
  -> reserve 80000
  -> update reservedAmount = 80000

B calls creditAccountRepository.findByIdForUpdate(account-123)
  -> waits for A commit

A commits authorization APPROVED + account update + DelayJob + Outbox

B lock is released and reads latest account row
  -> availableCredit = 20000
  -> reserve 30000 fails
  -> authorization DECLINED with INSUFFICIENT_AVAILABLE_CREDIT
```

如果没有 `FOR UPDATE`：

```text
A reads availableCredit = 100000
B reads availableCredit = 100000
A approves 80000
B approves 30000
total reserved = 110000 > creditLimit
```

这就是 over-approve。这个 bug 在单线程测试里不容易出现，但在真实高流量下会直接变成金融事故。

当前项目的解决点：

- `AuthorizationService.decideAndReserve(...)`
- `creditAccountRepository.findByIdForUpdate(card.creditAccountId())`
- `CreditAccount.reserve(...)`
- `creditAccountRepository.update(account)`

高分复述：

> 两个请求可以并发通过 validation、card check 和 risk check，但到了同一个 account 的额度修改时必须串行。A commit 后，B 必须基于最新 `reservedAmount` 再算 available credit，所以 B 会 decline，而不是基于旧额度 approve。

#### 剧本 2：同一个请求 timeout 后重试

请求 A：

```http
POST /api/authorizations
Idempotency-Key: checkout-777
```

```json
{
  "cardId": "card-123",
  "amount": 1000,
  "currency": "JPY",
  "merchantId": "merchant-123",
  "merchantCountry": "JP",
  "cardholderCountry": "JP"
}
```

请求 B 是客户端 timeout 后的 retry，header 和 body 完全相同：

```http
POST /api/authorizations
Idempotency-Key: checkout-777
```

执行过程：

```text
Thread A -> authorizationRepository.claim("checkout-777", pending)
  -> INSERT succeeds
  -> A is idempotency winner

Thread B -> authorizationRepository.claim("checkout-777", pending)
  -> duplicate key
  -> claimed = false

A -> findByIdempotencyKeyForUpdate("checkout-777")
  -> locks authorization row
  -> does card/risk/account reserve
  -> updates authorization APPROVED
  -> commits

B -> findByIdempotencyKeyForUpdate("checkout-777")
  -> waits until A commits
  -> reads final APPROVED row
  -> assertSameIdempotentRequest passes
  -> returns A's result
```

如果没有 insert-first claim：

```text
A SELECT no row
B SELECT no row
A INSERT authorization
B INSERT authorization
A reserves credit
B reserves credit
```

这就是 classic check-then-insert race。

当前项目的解决点：

- `AuthorizationService.authorize(...)`
- `authorizationRepository.claim(...)`
- `AuthorizationMapper.insertClaim`
- `uk_authorizations_idempotency_key`
- `findByIdempotencyKeyForUpdate(...)`
- `assertSameIdempotentRequest(...)`

高分复述：

> Idempotency 的 winner 不是 JVM 决定的，而是数据库 unique constraint 决定的。duplicate request 不会再次走额度预占，而是等 winner 的最终状态。

#### 剧本 3：同一个 idempotency key，不同 body

请求 A：

```text
Idempotency-Key = checkout-888
amount = 1000 JPY
merchantId = merchant-123
```

请求 B：

```text
Idempotency-Key = checkout-888
amount = 9000 JPY
merchantId = merchant-999
```

执行过程：

```text
A claim succeeds and commits APPROVED
B claim fails because idempotency_key already exists
B reads existing authorization FOR UPDATE
B calls assertSameIdempotentRequest(...)
B requestFingerprint != existing.requestFingerprint
B throws IdempotencyConflictException
API returns 409 Conflict
```

如果只按 key 返回旧结果：

```text
client bug sends different payment under old key
server returns old APPROVED result
caller may believe 9000 JPY was approved
actual authorization is 1000 JPY
```

当前项目的解决点：

- `AuthorizationCommand.requestFingerprint()`
- `AuthorizationCommand.matches(existing)`
- `IdempotencyConflictException`

高分复述：

> 幂等不只是“同 key 返回同结果”，还要防止“同 key 不同请求”。fingerprint 是 idempotency 的安全边界。

#### 剧本 4：如果外部风控放在 account lock 内，会发生什么

假设错误实现是：

```text
lock account row
-> call external risk
-> reserve credit
-> commit
```

请求 A：

```text
cardId = card-123
amount = 1000 JPY
external risk latency = 2s
```

请求 B：

```text
cardId = card-secondary
amount = 1000 JPY
same account-123
external risk latency = 100ms
```

错误执行过程：

```text
Thread A locks account-123
Thread A waits 2s for external risk

Thread B reaches account lock
Thread B waits behind A, even though B's risk call would be fast

A finishes risk and commits
B finally starts
```

问题：

- 同账户所有请求被慢外部依赖拖住。
- account lock wait 上升。
- Hikari connection 被占用更久。
- Tomcat request threads 被占住。
- client timeout 后 retry，形成更大压力。

当前项目的正确执行：

```text
A/B both run riskAssessmentService.assess(...) before account row lock
only after risk approved do they call findByIdForUpdate(accountId)
account lock only protects reserve/update
```

当前项目的解决点：

- `AuthorizationService.decideAndReserve(...)`
- `RiskAssessmentService.assess(...)`
- `ExternalRiskGatewayAdapter` + `@CircuitBreaker`
- `creditAccountRepository.findByIdForUpdate(...)` 在 risk 之后

高分复述：

> 风控慢不应该扩大 account row lock critical section。risk check 和额度检查是两个问题：risk 先回答“这笔交易风险是否可接受”，account lock 内再回答“此刻额度是否足够”。

#### 剧本 5：Outbox worker publish 成功后，markPublished 前宕机

初始 row：

```text
outbox_events.id = evt-001
status = PENDING
event_type = authorization.approved
aggregate_id = auth-001
```

执行过程：

```text
OutboxPoller -> OutboxClaimer
  -> SELECT ... FOR UPDATE SKIP LOCKED
  -> status PENDING -> PROCESSING
  -> lease token encoded by nextAttemptAt
  -> commit

Worker A -> OutboxWorker.publishClaimedEvent(evt-001)
  -> Kafka publish succeeds
  -> process crashes before markPublished(...)

Row remains PROCESSING

OutboxRecoverer later sees PROCESSING lease timed out
  -> puts row back to retryable state

Worker B claims evt-001
  -> publishes same event again
```

看起来的问题：

```text
Kafka may receive duplicate authorization.approved event
```

当前项目的解决方式：

- 接受 Kafka at-least-once。
- Consumer side 用 Inbox/unique constraint 幂等。
- `OutboxWorker.lockCurrentLease(...)` finalize 前重新 `FOR UPDATE` 并校验 lease。

如果下游不幂等：

```text
Notification may send twice
Ledger may write duplicate entry
Risk projection may double count
```

高分复述：

> Outbox 解决的是业务状态和 publish intent 的 dual-write，不承诺端到端 exactly-once。publish 成功但 markPublished 前宕机时可能重复发，所以 consumer 必须按 eventId 幂等。

#### 剧本 6：Outbox worker queue 满了

当前配置：

```yaml
outbox.publisher.worker-pool-size: 4
outbox.publisher.worker-queue-capacity: 100
```

假设 Kafka broker ack 很慢：

```text
4 个 outbox-worker-* 都在等待 Kafka send ack
queue 里已经排了 100 个 event
OutboxPoller 下一轮又 claim 50 个 PENDING events
```

执行过程：

```text
OutboxPoller submits event evt-151
outboxWorkerExecutor throws TaskRejectedException
OutboxPoller calls worker.markRejectedForRetry(evt-151, exception)
OutboxWorker marks event failed/retry in its own transaction
```

如果没有 bounded queue：

```text
poller keeps submitting
heap grows
GC pressure rises
event latency increases
eventually OOM or full service degradation
```

如果 queue 满了但不 mark failed：

```text
event stays PROCESSING until recoverer timeout
downstream waits longer
backlog diagnosis becomes harder
```

当前项目的解决点：

- `WorkerExecutorConfiguration.outboxWorkerExecutor(...)`
- `OutboxPoller.pollPublishableEvents()`
- `TaskRejectedException`
- `OutboxWorker.markRejectedForRetry(...)`

高分复述：

> bounded worker pool 不是为了提高吞吐，而是为了让压力显式化。queue 满时宁可进入 retry/DEAD，也不要把压力藏在无限内存队列里。

#### 剧本 7：Statement GET 热点导致 cache stampede

初始情况：

```text
statementId = stmt-001
L1 Caffeine miss
Redis L2 miss
100 个线程同时 GET /api/statements/stmt-001
```

如果没有 per-key single-flight：

```text
Thread 1 -> DB load statement
Thread 2 -> DB load statement
...
Thread 100 -> DB load statement
```

问题：

- 同一个 key 打 100 次 DB。
- MySQL 读压力上升。
- 连接池被 GET 占住，写请求也可能受影响。

当前项目执行：

```text
Thread 1 enters StatementReadService.get(stmt-001)
  -> Caffeine Cache.get starts per-key loader for stmt-001
  -> reads Redis miss
  -> loader calls StatementGenerationService.get(stmt-001)
  -> writes L1 and Redis

Thread 2..100 wait on same local lock
  -> after Thread 1 completes, read from L1
```

当前项目的解决点：

- `StatementReadService`
- Caffeine `Cache.get(key, loader)` 的同 JVM per-key single-flight
- L1 Caffeine + Redis L2
- TTL jitter

局限：

> 这把锁只在单 JVM 内有效。如果 10 个 pod 同时 Redis miss，仍可能有 10 次 DB load。生产超热点可以再加 Redis mutex 或 stale-while-revalidate。

#### 剧本 8：如果恢复 Card cache，blocked 后某个 pod 的 L1 仍是 ACTIVE

这是已删除设计的风险复盘。假设未来又恢复 `card-snapshot-v1`：

```text
cardId = card-123
Pod A L1 cache: CardSnapshot(status=ACTIVE)
Pod B receives future block-card request and updates DB to BLOCKED
Pod B evicts Redis L2
Pod A L1 has not expired yet
```

请求 A：

```text
POST /api/authorizations
cardId = card-123
amount = 1000 JPY
hits Pod A
```

风险执行：

```text
Pod A cached CardRepository reads local L1 ACTIVE
AuthorizationService passes card.checkAuthorizationEligibility()
Risk passes
Account row lock passes
Authorization may approve during stale window
```

为什么当前项目不恢复这个 cache：

- 现在还没有 block/unblock API。
- Card status stale 会直接影响 authorization approve/decline。
- 短 TTL 才安全，但短 TTL 又让单卡命中率变差。
- 真正热瓶颈仍然是 `credit_accounts` row lock，不是 card 主键读取。

生产增强：

```text
block/unblock transaction commit
-> after-commit evict Redis
-> publish cache invalidation event
-> all pods evict L1
-> high-risk card status check can bypass cache
```

高分复述：

> Cache invalidation 要和业务风险绑定。Statement read model stale 通常是展示问题；Card status stale 可能影响授权决策，所以 block/unblock 必须有跨 pod invalidation 或 bypass 策略。

#### 剧本 9：Hikari pending 高，但 CPU 不高

现象：

```text
authorization p99: 3s
app CPU: 35%
MySQL CPU: 40%
Hikari active: max
Hikari pending: high
Thread dump: many http-nio threads waiting in JDBC/socket
```

可能不是 CPU 瓶颈，而是：

- DB connection pool 太小。
- 某些 transaction 持有 connection 太久。
- account row lock wait。
- external risk 或 Kafka publish 被错误放在 transaction 内。
- slow query 没走索引。

排查步骤：

```text
1. 看 Hikari connection acquire time
2. 看 MySQL processlist / InnoDB lock wait
3. 看 slow query
4. 看 thread dump 中 http-nio-* 栈
5. 看是否新代码扩大 transaction boundary
```

高分复述：

> Hikari pending 高时，不要只把 pool 调大。先看连接为什么被占住：是 SQL 慢、锁等待、事务太长，还是外部 I/O 被包进 transaction。调大 pool 可能只是把更多并发压力推给 MySQL。

## 5. 容量模型：先做粗估，不要拍脑袋

容量估算可以先用 Little's Law：

```text
concurrency ~= throughput * latency
```

例如：

```text
100 RPS authorization
average latency 100ms
in-flight requests ~= 100 * 0.1 = 10
```

如果 p99 是 800ms：

```text
100 RPS * 0.8s = 80 in-flight requests at tail
```

这说明 p99 延迟上升会迅速吃掉线程和连接。

### 5.1 Tomcat threads 和 DB connections

假设：

```text
Tomcat max threads = 200
Hikari max pool = 20
```

如果每个 authorization 都需要 DB connection，而请求同时到达 100 个：

- 20 个拿到 DB connection。
- 80 个在等连接。
- Tomcat thread 被占住。
- p99 上升。
- client timeout 后 retry，压力继续上升。

高分回答：

> Tomcat threads 不应该远远大于后端可承载资源后还无限接流量。否则线程只是把请求排进应用内部，外部看起来像慢而不是被保护。我要看 Hikari pending、connection acquire time 和 HTTP p99，再决定扩 pool、限流、优化 SQL，还是减少锁等待。

### 5.2 DB pool 不能无限加

DB connection pool 太小会排队；太大也危险：

- MySQL thread/CPU 被打满。
- lock wait 变多。
- transaction context 增多。
- slow query 互相放大。

更好的策略：

1. 缩短 transaction。
2. 优化 SQL 和索引。
3. 拆出只读路径到 cache/read model。
4. 对热点或恶意流量限流。
5. 最后再评估 DB pool 和 DB 实例规格。

### 5.3 Kafka concurrency 和 partition

当前项目：

- source topics 3 partitions。
- notification listener concurrency 2。
- risk feature listener concurrency 3。
- ledger listener concurrency 2。

原则：

```text
effective consumer concurrency <= partition count
```

如果 partition=3，consumer concurrency=10，多出来的 consumer 线程会 idle。

但 partition 也不能随便加：

- 可能影响 key 到 partition 的映射。
- 可能破坏依赖局部顺序的假设。
- 增加 broker metadata 和 rebalancing 成本。

### 5.4 Worker pool 和 queue capacity

当前项目：

```yaml
outbox:
  publisher:
    worker-pool-size: 4
    worker-queue-capacity: 100

delay-jobs:
  scheduler:
    worker-pool-size: 4
    worker-queue-capacity: 100
```

设计意义：

- pool 固定，避免无限线程。
- queue 有界，避免无限堆内存。
- queue 满时，poller 捕获 `TaskRejectedException`，mark failed/retry。
- Outbox 和 DelayJob 独立 executor，避免互相抢线程。

高分回答：

> bounded queue 是 backpressure 入口。它让系统在下游慢时显式失败和重试，而不是把压力藏到内存里直到 OOM。

## 6. 瓶颈诊断树

### 6.1 authorization p99 上升

先问五个问题：

1. 是所有请求慢，还是少数 card/account 慢？
2. approve 慢，还是 decline 也慢？
3. 新版本后慢，还是流量上涨后慢？
4. DB lock wait/Hikari pending 有没有上升？
5. external risk latency 有没有上升？

判断树：

| 现象 | 更可能的瓶颈 | 下一步 |
| --- | --- | --- |
| decline 也慢 | controller、risk、DB connection、JVM | 看 HTTP breakdown、risk latency、Hikari |
| 只有 approve 慢 | account row lock 或 account update | 查 lock wait、热点 account |
| 少数 account 慢 | hotspot account | 账户级限流、排队、产品策略 |
| Hikari pending 高 | DB connection 不够或 SQL 慢 | 查 slow query、连接占用时间 |
| DB CPU 高但 lock wait 低 | SQL/索引/读压力 | EXPLAIN、cache、读写分离 |
| lock wait 高 | row lock contention/deadlock | 查锁顺序、热点 row、transaction length |
| risk latency 高 | 外部风控慢 | timeout、bulkhead、CircuitBreaker、fallback |
| Outbox backlog 高但 API 正常 | 异步发布落后 | 查 worker/Kafka，不一定影响授权正确性 |
| Redis timeout 高 | cache dependency 慢 | fallback DB 压力、Redis circuit breaker |
| GC pause 高 | JVM allocation/heap 压力 | GC log/JFR/heap/thread dump |

### 6.2 Outbox backlog 上升

先分状态：

| Outbox 状态 | 可能含义 |
| --- | --- |
| `PENDING` 增长 | publish capacity 跟不上业务产生速度 |
| `PROCESSING` 增长 | worker stuck、Kafka ack 慢、lease timeout 太长 |
| `DEAD` 增长 | poison event、contract bug、Kafka 持续失败 |

排查顺序：

1. Outbox status count。
2. Outbox worker threads。
3. Kafka producer latency/error。
4. Broker health。
5. DB claim SQL latency。
6. Recent deployment 和 event schema。

不要立刻加 worker。先判断：

- Kafka broker 慢，加 worker 可能更慢。
- DB claim 慢，加 worker 会增加 DB 压力。
- poison event 导致 DEAD，加 worker 没用。

### 6.3 Kafka consumer lag 上升

先判断业务影响：

| Consumer | Lag 影响 |
| --- | --- |
| Notification | 用户通知延迟，主交易正确性不受影响 |
| Risk feature | 后续风控特征可能变旧，需要更严肃 |
| Ledger | 内部账务 projection 延迟，报表/对账受影响 |

排查：

1. 哪个 consumer group lag。
2. 是否某个 partition 特别 lag。
3. listener 业务处理是否慢。
4. DB side effect 是否慢。
5. DLT 是否增长。
6. 是否发生 rebalance。
7. concurrency 是否超过/低于 partition 约束。

### 6.4 Redis timeout 增多

当前项目的 cache 设计：

- Redis read 失败：fallback DB。
- Redis write 失败：本次 response 仍成功。
- Redis JSON 坏值：删除并回源。
- Redis timeout：`1s`。

高流量风险：

- Redis 失败不会破坏 correctness。
- 但 fallback DB 会放大 MySQL 读压力。
- 如果 Redis 慢到 1s，主请求 p99 可能被拖高。

生产建议：

- Redis timeout 要短。
- 增加 Redis client metrics。
- 对连续 timeout 做 circuit breaker。
- 对 DB fallback 做 rate limit。
- 对热点 key 使用 single-flight 或 stale-while-revalidate。

## 7. 热点账户设计

热点账户是 credit card authorization 最难的高流量问题之一。

### 7.1 为什么热点账户不能简单横向扩容

同一 credit account 的额度变化必须串行：

```text
availableCredit = creditLimit - reservedAmount - postedBalance
```

如果两个请求同时基于旧值 approve，就会 over-approve。

所以当前项目用：

```sql
SELECT ... FROM credit_accounts WHERE id = ? FOR UPDATE
```

这意味着：

- 不同 account 可以并行。
- 同一 account 必须排队。
- 加 app instance 不能提高同一 account 的写吞吐。

### 7.2 可以优化什么

可以优化：

- 锁前做 validation/risk/card check。
- 锁内只做 reserve/update。
- 确保 account lookup 走主键索引。
- 缩短 transaction。
- 对同 account 做 rate limit。
- 对异常热点做保护或人工策略。
- 对低风险查询用 cache/read model。

不应该做：

- 用 Redis counter 替代 account row lock。
- 允许额度最终一致但没有风险接受。
- 把同一 account balance 随便 shard。
- 在锁内调用外部服务。

### 7.3 能不能用 Redis atomic counter

回答：

> Redis atomic counter 可以用于 rate limit 或预授权保护层，但不适合作为 credit account balance 的 source of truth。Authorization 需要同时写 authorization row、credit account、DelayJob、Outbox，这些必须在一个 MySQL transaction 里提交。Redis counter 成功而 DB 失败，或 DB 成功而 Redis 失败，都会制造新的修复问题。

### 7.4 能不能接受 eventual consistency

对不同对象答案不同：

| 对象 | 是否可 eventual consistency | 原因 |
| --- | --- | --- |
| Credit available amount | 通常不可以 | 直接决定 approve/decline |
| Authorization final response | 不应该随意变化 | 客户端 retry 要稳定 |
| Notification | 可以 | side effect，可延迟 |
| Risk feature projection | 可以但要监控 freshness | 落后会影响后续风控 |
| Ledger projection | 可以短暂延迟，但要可重放/对账 | 内部账务不能永久丢 |
| Statement read model cache | 可以短 stale window | 展示型读取，DB 是 source of truth |

## 8. 限流和 backpressure

### 8.1 限流维度

金融 API 不应该只做一个全局 QPS 限流。

更合理的维度：

| 维度 | 保护什么 |
| --- | --- |
| client/app id | 防止某个调用方打爆系统 |
| cardId | 防止单卡异常重试 |
| creditAccountId | 防热点账户锁竞争 |
| merchantId | 防商户侧异常流量 |
| IP/network | 防边缘恶意流量 |
| endpoint | 区分 write API 和 GET API |

当前项目还没有实现 API 层 rate limit。interview 可以这样说：

> 当前学习项目的 correctness 不依赖限流，但生产里我会在 gateway 或应用层加多维限流。限流是保护系统，不是替代 `idempotency`、`row lock` 或业务状态机。

### 8.2 Redis 还是本地限流

| 方案 | 优点 | 缺点 | 适合 |
| --- | --- | --- | --- |
| 本地 limiter | 快、简单、不依赖网络 | 多实例各算各的 | 单实例保护、快速熔断 |
| Redis limiter | 跨实例共享 | Redis 成为依赖 | 全局 client/account/merchant 限流 |
| Gateway/WAF | 入口统一 | 业务维度有限 | IP/client 级保护 |
| Service mesh | 基础设施统一 | 业务语义弱 | 服务间保护 |

高分回答：

> 我会分层做。本地 limiter 保护单 pod，Redis/gateway 做全局维度，业务热点例如 accountId 需要应用知道业务 key。限流失败返回 `429` 或业务可理解的 retryable error，不能误报成 authorization decline。

### 8.3 Backpressure 在当前项目哪里体现

当前项目已经有后台 backpressure：

```text
OutboxPoller -> bounded outboxWorkerExecutor
DelayJobPoller -> bounded delayJobWorkerExecutor
```

queue 满时：

- 不无限创建线程。
- 不无限堆内存。
- 标记失败并进入 retry/DEAD。

还可以补的 API backpressure：

- request timeout。
- Hikari connection acquire timeout。
- external risk timeout/bulkhead。
- account-level rate limit。
- overload 时快速失败。

## 9. Cache 在高流量中的边界

### 9.1 当前 Redis/Caffeine 适合解决什么

当前代码里的 Redis/Caffeine 主要适合：

- risk velocity sliding-window 计数。
- 降低授权热路径对主库的近期次数查询压力。
- 用 Lua 原子完成“记录本次尝试 + 裁剪窗口 + 计数 + TTL”。
- Redis 不可用时 fail-open，让 velocity 信号降级，而不是拒绝所有授权。
- statement read model GET。
- Caffeine L1 降低同 JVM 热点读取延迟，Redis L2 跨实例共享 statement read model。
- repayment commit 后 after-commit evict statement read cache。

已删除的 Card snapshot cache 仍适合学习“为什么不缓存”：

- card reference data snapshot。
- 降低 DB 读压力。
- 降低热点 read latency。
- 减少同 key miss storm。

无论当前 Redis velocity、statement read cache，还是已删除的 Card snapshot cache，都不适合：

- `availableCredit`。
- `reservedAmount`。
- `postedBalance`。
- idempotency winner。
- transaction status source of truth。

### 9.2 Cache stampede

当前 statement GET cache 用 Caffeine `Cache.get(key, loader)` 做单 JVM per-key single-flight：

```text
Caffeine Cache.get(statementId, loader)
```

作用：

- 同一 pod 内，同 key L1/L2 miss 时只有一个线程回源 DB。
- 其他线程等结果。

局限：

- 跨 pod 不共享这把锁。
- Redis 同时 miss 时，多个 pod 仍可能一起回源。

生产拓展：

- Redis mutex for cache refresh。
- stale-while-revalidate。
- async refresh。
- pre-warming。
- 热点 key 独立 TTL。

> [!WARNING]
> Redis mutex 只能保护 cache rebuild，不能保护 authorization 额度正确性。

### 9.3 Cache avalanche

历史 snapshot cache 对 Redis TTL 加 jitter：

```text
remote-ttl: 5m
remote-ttl-jitter: 30s
```

作用：

- 避免大量 key 同一秒过期。
- 降低同时回源 DB 的概率。

生产拓展：

- 按 key 热度设置 TTL。
- 批量预热。
- 降级返回 stale read model。
- 给 DB fallback 加限流。

### 9.4 Card snapshot 的特殊风险

Card snapshot 参与 authorization 决策，比 statement GET 风险高。这也是它后来从当前请求路径删除的原因之一。

风险：

- card 刚 blocked，本地 L1 仍是 ACTIVE。
- Redis 删除了，但其他 pod L1 仍旧。
- TTL 内可能有 stale decision。

如果未来恢复 Card snapshot cache，生产增强：

- block/unblock after-commit evict。
- Kafka/Redis Pub/Sub invalidation 清所有 pod L1。
- 高风险卡状态 bypass cache。
- 把 stale window 写入设计文档和风险接受。

## 10. Kafka 和异步扩展

### 10.1 为什么 Kafka 不在主请求里

主授权请求不应该等待 Kafka broker ack。

如果同步等待：

- DB transaction 变长。
- account row lock 持有时间变长。
- broker 慢会拖住 authorization p99。
- Kafka 和 MySQL 仍然不是同一个本地 ACID transaction。

当前项目：

```text
business transaction writes outbox_events
commit
OutboxWorker publish to Kafka
consumer side Inbox/unique constraint
```

### 10.2 Kafka backlog 是否影响主 API

短期不直接影响授权正确性：

- 主 API 只写 Outbox。
- Notification/Risk/Ledger 是异步 side effect/projection。

但长期有业务风险：

- Notification 晚到。
- Risk feature projection 变旧。
- Ledger projection 落后。
- Outbox 表膨胀。
- DLT 未处理会隐藏数据问题。

高分回答：

> Kafka lag/backlog 是 eventual consistency 延迟，不是主交易 rollback 信号。但它有 SLO，超过阈值要报警和限流，因为下游业务事实不能无限落后。

### 10.3 Partition key 选择

原则：

```text
key should follow ordering requirement
```

当前项目示例：

| Topic | Key 口径 | 原因 |
| --- | --- | --- |
| authorization events | authorizationId | 同一授权状态局部有序 |
| statement events | creditAccountId | 同一账户账单顺序 |
| repayment events | creditAccountId | 同一账户还款相关顺序 |

错误做法：

- 为了均匀随机 key，破坏同 aggregate 顺序。
- 为了全局有序，所有消息放一个 partition。
- 盲目增加 partition，不评估 key remapping。

## 11. 数据库和 SQL 高流量策略

### 11.1 锁和索引必须一起讲

`FOR UPDATE` 是否高效，取决于查询条件和索引。

当前项目有：

- `authorizations.idempotency_key` unique。
- `credit_accounts.id` primary key。
- `card_transactions.network_transaction_id` unique。
- `outbox_events(status, next_attempt_at, created_at)` index。
- `delay_jobs(status, next_attempt_at, created_at)` index。
- `statements(credit_account_id, period_start, period_end)` unique。

高分回答：

> 我不会只说“用了 `FOR UPDATE`”。我会确认 where condition 是否走主键/唯一索引，否则 InnoDB 可能扫描并锁住更多范围，导致 lock wait 放大。

### 11.2 `SKIP LOCKED` 的意义

Outbox/DelayJob worker 使用：

```sql
FOR UPDATE SKIP LOCKED
```

意义：

- 多个 worker/pod 并发 claim。
- 被别人锁住的 row 会跳过。
- 避免 worker 互相等待。
- 提高后台吞吐。

代价：

- 不是严格 FIFO。
- 某些 stuck row 需要 recoverer。
- 必须有 `PROCESSING lease` 和 retry。

### 11.3 Deadlock 怎么回答

生产系统不能保证永远没有 deadlock。

回答顺序：

1. 设计固定 lock order。
2. 保证 SQL 走合适索引。
3. 缩短 transaction。
4. 捕获 deadlock/lock timeout。
5. 基于 idempotency 做有限 retry。
6. 通过 deadlock graph 修正根因。

不要说：

```text
用了事务就不会死锁。
```

更好的说法：

```text
事务保证 atomicity，不保证没有 deadlock。死锁是并发锁顺序问题，要靠锁顺序、索引、短事务和可重试幂等路径一起处理。
```

## 12. 外部依赖：Risk 服务

当前项目：

- 本地 risk rule 先执行。
- 外部 risk 通过 Feign adapter。
- Resilience4j CircuitBreaker。
- fallback fail-closed。
- risk check 在 account lock 前。

高流量下，external risk 是典型尾延迟来源。

### 12.1 timeout

没有 timeout 的风险：

- request thread 卡住。
- account lock 如果已拿到，会放大锁等待。
- Hikari/Tomcat 资源被慢依赖耗尽。

当前项目把 risk 放在 account lock 前，这是正确方向。

生产还要补：

- Feign connect/read timeout 明确配置。
- bulkhead 隔离外部 risk 并发。
- CircuitBreaker metrics。
- fallback reason 进入 decline/audit。

### 12.2 fail open 还是 fail closed

当前项目 fail-closed：

```text
EXTERNAL_RISK_UNAVAILABLE -> decline
```

这对信用卡授权更保守。

真实系统可以按风险分层：

| 场景 | 策略 |
| --- | --- |
| 高金额、跨境、blocked merchant | fail closed |
| 小额、低风险、老客户 | 可能 fail open with limit |
| 风控长期不可用 | 限流、降级、人工策略 |
| 风控响应慢 | timeout + fallback，不无限等待 |

高分回答：

> fail open/closed 不是纯技术选择，是 risk policy。技术要提供 timeout、audit、fallback、限额和指标，让业务能承担这个选择。

## 13. Observability 指标清单

### 13.1 API 指标

必须看：

- `http.server.requests` count。
- p50/p95/p99 latency。
- 4xx/5xx。
- `409 Conflict` ratio。
- timeout ratio。
- authorization approve/decline ratio。
- decline reason distribution。

### 13.2 DB 指标

必须看：

- Hikari active/idle/pending。
- connection acquire time。
- MySQL CPU。
- slow query。
- lock wait。
- deadlock count。
- row counts for hot tables。
- transaction duration。

关键表：

- `authorizations`
- `credit_accounts`
- `card_transactions`
- `statements`
- `repayments`
- `outbox_events`
- `delay_jobs`
- `consumer_inbox`

### 13.3 Kafka 指标

必须看：

- producer send latency。
- producer error/retry。
- consumer group lag。
- per-partition lag。
- DLT count。
- rebalance count。
- listener processing latency。

### 13.4 Redis/cache 指标

必须看：

- Redis command latency。
- timeout/error。
- hit/miss ratio。
- evictions。
- memory。
- Caffeine hit rate。
- cache load duration。
- cache evict failure。

### 13.5 JVM/thread 指标

必须看：

- heap used。
- allocation rate。
- GC pause。
- live threads。
- Tomcat busy threads。
- worker executor active/queue size。
- Kafka listener threads。
- thread dump state。

### 13.6 业务 SLO

建议定义：

| SLO | 示例 |
| --- | --- |
| Authorization p99 | 99% under 500ms 或按产品要求 |
| Authorization error rate | 5xx under 0.1% |
| Idempotency conflict correctness | same key same body no double hold |
| Outbox publish delay | PENDING older than N minutes alarm |
| Consumer lag | group lag under threshold |
| Statement cache freshness | repayment 后 stale window under TTL/SLO |

## 14. 压测设计

### 14.1 压测目标

压测不是只问最大 QPS。

应该回答：

- p95/p99 在目标 QPS 下是否稳定。
- DB lock wait 是否增加。
- Hikari pending 是否出现。
- external risk 慢时系统如何退化。
- Redis timeout 时 DB 是否被打爆。
- Kafka 慢时 Outbox backlog 是否可控。
- retry storm 是否导致 double hold。
- 热点账户是否被识别和保护。

### 14.2 场景矩阵

| 场景 | 目的 |
| --- | --- |
| 均匀 card/account authorization | 测正常吞吐 |
| 同 account 并发 authorization | 测 row lock 和热点 |
| 同 idempotency key 并发 retry | 测 duplicate winner |
| 同 key 不同 body | 测 conflict |
| external risk latency 1s | 测外部依赖拖慢 |
| Redis timeout | 测 cache fallback 和 DB 压力 |
| Kafka broker 慢/不可用 | 测 Outbox backlog |
| Outbox worker queue 满 | 测 backpressure |
| consumer duplicate event | 测 Inbox 幂等 |
| statement GET 热点 | 测 cache hit/single-flight |

### 14.3 k6 脚本轮廓

可以先写这样的压测结构：

```javascript
import http from "k6/http";
import { check, sleep } from "k6";
import { randomSeed } from "k6";

randomSeed(1234);

export const options = {
  scenarios: {
    steady_authorization: {
      executor: "constant-arrival-rate",
      rate: 100,
      timeUnit: "1s",
      duration: "5m",
      preAllocatedVUs: 50,
      maxVUs: 200,
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    http_req_duration: ["p(95)<300", "p(99)<800"],
  },
};

export default function () {
  const n = __ITER;
  const cardId = `card-load-${n % 1000}`;
  const idempotencyKey = `load-${__VU}-${n}`;

  const res = http.post(
    "http://localhost:8080/api/authorizations",
    JSON.stringify({
      cardId,
      amount: 100,
      currency: "JPY",
      merchantId: "merchant-load",
      merchantCountry: "JP",
      cardholderCountry: "JP",
    }),
    {
      headers: {
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
      },
    }
  );

  check(res, {
    "status is not 5xx": r => r.status < 500,
  });

  sleep(0.1);
}
```

> [!WARNING]
> 压测脚本必须使用独立测试数据和可识别 key 前缀。  
> 不要把压测交易混进普通本地 seed data，后续查账会很痛。

### 14.4 压测后要查什么

每轮压测后检查：

```sql
-- authorization 结果分布
SELECT status, decline_reason, COUNT(*)
FROM authorizations
GROUP BY status, decline_reason;

-- Outbox backlog
SELECT status, COUNT(*), MIN(created_at), MIN(next_attempt_at)
FROM outbox_events
GROUP BY status;

-- DelayJob backlog
SELECT status, job_type, COUNT(*)
FROM delay_jobs
GROUP BY status, job_type;

-- 是否有重复 idempotency key 或异常 conflict
SELECT idempotency_key, COUNT(*)
FROM authorizations
GROUP BY idempotency_key
HAVING COUNT(*) > 1;
```

还要看：

- Actuator HTTP metrics。
- Hikari metrics。
- JVM GC pause。
- Redis/Kafka logs。
- MySQL slow query 和 lock wait。

### 14.5 用压测验证具体竞态

压测不能只生成随机正常流量。下面这些用例能直接验证前面的请求 A/B、线程 A/B 剧本。

#### 用例 1：同账户不同卡并发，不允许 over-approve

准备数据：

```text
card-123 -> account-123
card-secondary -> account-123
account-123 creditLimit = 100000 JPY
```

并发请求：

```text
Request A:
  Idempotency-Key = load-hot-A-{runId}
  cardId = card-123
  amount = 80000 JPY

Request B:
  Idempotency-Key = load-hot-B-{runId}
  cardId = card-secondary
  amount = 30000 JPY
```

期望结果：

```text
exactly one may approve 80000
the other 30000 request must decline with insufficient credit
reservedAmount must never exceed 100000
```

验证 SQL：

```sql
SELECT credit_limit, reserved_amount, posted_balance
FROM credit_accounts
WHERE id = 'account-123';

SELECT status, decline_reason, amount, currency
FROM authorizations
WHERE idempotency_key IN ('load-hot-A-...', 'load-hot-B-...');
```

如果两笔都 APPROVED，就是 `row lock` 或 `CreditAccount.reserve(...)` 边界出问题。

#### 用例 2：同 idempotency key 并发 retry，只能有一个 winner

并发请求：

```text
Request A:
  Idempotency-Key = retry-same-001
  cardId = card-123
  amount = 1000 JPY

Request B:
  Idempotency-Key = retry-same-001
  cardId = card-123
  amount = 1000 JPY
```

期望结果：

```text
both responses represent the same authorization
only one authorizations row
only one account reserve side effect
only one authorization.approved outbox event for that authorization
```

验证 SQL：

```sql
SELECT id, status, amount, currency
FROM authorizations
WHERE idempotency_key = 'retry-same-001';

SELECT aggregate_id, event_type, COUNT(*)
FROM outbox_events
WHERE aggregate_type = 'AUTHORIZATION'
GROUP BY aggregate_id, event_type;
```

#### 用例 3：同 idempotency key 不同 body，必须 conflict

并发或顺序请求：

```text
Request A:
  Idempotency-Key = retry-conflict-001
  amount = 1000 JPY
  merchantId = merchant-123

Request B:
  Idempotency-Key = retry-conflict-001
  amount = 9000 JPY
  merchantId = merchant-999
```

期望结果：

```text
Request A returns normal result
Request B returns 409 Conflict
no second account reserve
```

这个用例验证的是 `requestFingerprint`，不是 row lock。

#### 用例 4：外部 Risk 慢，不应该拉长 account lock

准备配置：

```yaml
risk.external.simulated-latency-millis: 2000
```

压测方式：

```text
100 RPS authorization
same account scenario and different account scenario both run
```

观察：

```text
external risk latency rises
authorization p99 rises
but MySQL account row lock wait should not rise proportionally before risk passes
```

如果 lock wait 跟 risk latency 同步上升，要检查是不是有代码把外部调用放进 account lock 之后。

当前项目锚点：

- `AuthorizationService.decideAndReserve(...)`
- `riskAssessmentService.assess(...)`
- `creditAccountRepository.findByIdForUpdate(...)`

#### 用例 5：Kafka 慢时，API 不应等 broker ack

制造方式：

```text
pause Kafka broker, inject producer timeout, or make broker ack slow
```

期望：

```text
POST /api/authorizations can still commit while MySQL is healthy
outbox_events PENDING/PROCESSING increases
authorization p99 should not include full Kafka outage time
```

验证 SQL：

```sql
SELECT status, COUNT(*), MIN(created_at), MIN(next_attempt_at)
FROM outbox_events
GROUP BY status;
```

如果 API p99 等于 Kafka send timeout，要检查是否有人绕过 Outbox，在主事务里同步 publish。

#### 用例 6：Redis timeout 时，DB fallback 不能打爆主库

制造方式：

```text
stop Redis or inject Redis latency
run high-QPS GET /api/statements/{id}
run authorization traffic at the same time
```

期望：

```text
GET may become slower
Redis error metrics/logs increase
DB read pressure increases but does not starve authorization writes
authorization correctness unchanged
```

如果 authorization p99 被 statement GET fallback 拖垮，说明需要：

- GET 限流。
- Redis circuit breaker。
- statement read model stale fallback。
- DB read/write resource isolation。

#### 用例 7：worker queue 满，不能 OOM

制造方式：

```text
make Kafka publish slow
generate many Outbox events
observe outbox-worker queue saturation
```

期望：

```text
TaskRejectedException is handled
events are marked retry/DEAD according to policy
heap does not grow unbounded
PROCESSING rows do not stay invisible forever
```

当前项目锚点：

- `OutboxPoller.pollPublishableEvents()`
- `OutboxWorker.markRejectedForRetry(...)`
- `WorkerExecutorConfiguration.outboxWorkerExecutor(...)`

## 15. 高流量下的降级策略

不同依赖的降级策略不同。

| 依赖 | 失败时是否能继续 authorization | 策略 |
| --- | --- | --- |
| MySQL primary | 不能 | readiness fail，返回可重试错误 |
| Redis cache | 可以 | fallback DB，告警，保护 DB |
| Kafka broker | 短期可以 | 写 Outbox，backlog alarm，阈值后限流 |
| Notification consumer | 可以 | lag alarm，重试/DLT |
| Ledger consumer | 短期可以 | lag alarm，replay/reconciliation |
| External risk | 视 policy | fail closed 或受限 fail open |
| Read replica | 可以 | 切回 primary 或 cache |

### 15.1 Kafka 不可用时还能授权吗

当前架构可以短期继续：

```text
Authorization transaction writes outbox_events
Kafka publish later
```

但不能无限继续：

- Outbox 表会膨胀。
- 下游通知/风控/账本延迟。
- 如果 backlog 超过业务阈值，应该限流或降级。

高分回答：

> Kafka 不可用不是立即破坏授权 correctness，但它会扩大 eventual consistency lag。我要看 Outbox oldest pending age 和 DEAD count，再决定继续接流量、限流还是进入 incident。

### 15.2 Redis 不可用时怎么办

当前架构：

- Redis 失败 fallback DB。
- cache 不是 correctness dependency。

生产补强：

- Redis circuit breaker。
- DB fallback 限流。
- 热点 key stale read。
- Redis error alarm。

### 15.3 DB 不可用时怎么办

DB 是 source of truth：

- authorization 不能正确处理。
- posting/repayment/statement 都不能正确处理。
- readiness 应 fail。
- client 应 retry with same idempotency key。

不要说：

```text
DB 挂了就用 Redis 继续授权。
```

更好的说法：

```text
Redis 不是账务 source of truth。DB 不可用时，为了避免错误批准和审计断裂，money-changing API 应停止接新写流量。
```

## 16. 系统设计题模板

题目：

```text
Design a high traffic credit card authorization service.
```

推荐回答结构：

### Step 1：业务语义

先说明：

- Authorization 只是 hold credit。
- Posting 才正式入账。
- 同一 account 额度不能并发超用。
- Client timeout retry 必须幂等。

### Step 2：API

```http
POST /api/authorizations
Idempotency-Key: ...
Content-Type: application/json
```

Body：

```json
{
  "cardId": "card-123",
  "amount": 100,
  "currency": "JPY",
  "merchantId": "merchant-123",
  "merchantCountry": "JP",
  "cardholderCountry": "JP"
}
```

### Step 3：核心表

- `cards`
- `credit_accounts`
- `authorizations`
- `outbox_events`
- `delay_jobs`
- `consumer_inbox`

### Step 4：写事务

```text
claim idempotency
-> lock idempotency winner row
-> cheap checks
-> risk check
-> lock account row
-> reserve credit
-> update authorization
-> insert expiry DelayJob
-> insert Outbox event
-> commit
```

### Step 5：异步

```text
OutboxWorker -> Kafka -> Notification/Risk/Ledger consumers -> Inbox idempotency
```

### Step 6：高流量

- Short critical section。
- Bounded worker pool。
- Kafka partition by aggregate/account。
- Cache read model only。
- Rate limit by client/card/account/merchant。
- External risk timeout/bulkhead/circuit breaker。
- Observe p99、DB lock wait、Hikari、Kafka lag、Outbox backlog、Redis、GC。

### Step 7：Failure mode

- response lost -> same idempotency key retry。
- same account concurrent auth -> row lock。
- Kafka fail -> Outbox retry。
- consumer duplicate -> Inbox。
- Redis fail -> DB fallback。
- DB fail -> not ready。
- risk fail -> fail closed or policy-based fallback。

## 17. 高压追问和高分回答

### Q1：QPS 增加 10 倍，你第一件事做什么？

答：

> 我先建立 baseline 和瓶颈定位，不直接改架构。看 authorization p95/p99、error rate、DB lock wait、Hikari pending、external risk latency、Outbox backlog、Kafka lag、Redis timeout 和 GC pause。然后根据瓶颈决定是优化 SQL、缩短锁内逻辑、限流热点账户、扩 app、扩 DB，还是调 Kafka/worker。

### Q2：加 app instance 有没有用？

答：

> 对无共享计算和不同 account 的请求有用；对同一 account row lock 没有线性收益。同一 account 的额度变化必须串行，加实例只会让更多请求排在 DB lock 前。

### Q3：为什么不用 Redis counter 提高授权吞吐？

答：

> Redis counter 很快，但 authorization 还要写 authorization row、credit account、DelayJob、Outbox。它们需要一个 MySQL `transaction boundary`。Redis 成功但 MySQL 失败，或 TTL/failover 出问题，会让修复更复杂。Redis 可以做 rate limit，不做额度 source of truth。

### Q4：Kafka broker 慢会不会拖慢主请求？

答：

> 正常不会，因为主请求只写 Outbox row，不等 Kafka ack。broker 慢会表现为 Outbox backlog 增长。超过阈值后要告警、扩 worker/修 broker、必要时限流，但不应该把 Kafka ack 放进 account row lock。

### Q5：Redis 挂了会不会导致授权失败？

答：

> 不应该。当前 cache 是 performance layer，Redis 失败会 fallback DB。真正需要注意的是 fallback DB 压力和 latency，所以要有 Redis timeout、告警、circuit breaker 和 DB 保护。

### Q6：热点账户怎么办？

答：

> 先承认同一 account 必须串行，不能为了吞吐破坏 credit limit correctness。优化方向是缩短锁内逻辑、锁前过滤、账户级限流、热点监控、必要时业务排队或产品策略。不能简单 shard balance 或用 eventual consistency，除非业务明确接受 over-authorization 风险。

### Q7：如何估算 DB connection pool？

答：

> 用吞吐和 latency 粗估 in-flight DB work，再结合 p99、lock wait 和慢依赖预留 buffer。DB pool 不是越大越好，太大会把压力推给 MySQL。要看 Hikari active/pending/acquire time、SQL latency 和 MySQL CPU/lock wait 一起调。

### Q8：如何防止 retry storm？

答：

> 客户端要 exponential backoff + jitter，服务端要 idempotency key、rate limit、短 timeout 和明确可重试错误。后台 retry 也要有 max attempts、backoff、DLT/DEAD。没有 backoff 的 retry 会把临时故障放大成系统事故。

### Q9：Read replica 能不能提高授权吞吐？

答：

> 对 money-changing decision 不能依赖 replica，因为 replica lag 会让授权基于旧余额。GET/read model 可以用 replica 或 cache。写路径要读 primary 并锁 source row。

### Q10：如何证明压测没有破坏 correctness？

答：

> 压测后查同 idempotency key 是否只有一条 winner、同 account 并发是否没有超过 credit limit、Outbox/DelayJob 是否无异常 DEAD、consumer side effect 是否不重复、statement/repayment 状态是否一致。只看 HTTP 200 和 QPS 不够。

## 18. Warning answer 和 Better answer

### Warning 1：高并发就加 Redis

**Warning answer：**

```text
高并发授权把额度放 Redis，atomic increment 很快。
```

**Better answer：**

```text
Redis 可以做 cache/rate limit，但额度 source of truth 在 MySQL。
Authorization 要和 account reserve、authorization status、DelayJob、Outbox 同事务提交，所以核心并发靠 row lock。
```

### Warning 2：加机器就能扩容

**Warning answer：**

```text
QPS 高就多部署几个 pod。
```

**Better answer：**

```text
先定位瓶颈。无共享计算可以靠 pod 扩容，但同 account row lock、DB connection、Kafka partition、external risk 慢调用不会因为加 pod 自动消失。
```

### Warning 3：Kafka 异步所以不用管 backlog

**Warning answer：**

```text
Kafka 是异步的，backlog 不影响主流程，所以没关系。
```

**Better answer：**

```text
短期 backlog 不破坏主交易 correctness，但会扩大 eventual consistency lag。Notification、Risk projection、Ledger 都有业务 SLO，Outbox oldest pending age 和 DLT 必须报警。
```

### Warning 4：缓存越多越好

**Warning answer：**

```text
为了性能，把 account balance 和 idempotency result 都缓存。
```

**Better answer：**

```text
缓存只放可重建 snapshot。Balance 和 idempotency winner 是写模型/source of truth，必须靠 DB transaction、unique constraint 和 row lock。
```

### Warning 5：只看 CPU

**Warning answer：**

```text
高流量看 CPU 和 memory。
```

**Better answer：**

```text
CPU/memory 只是底层指标。授权系统还要看 p99、DB lock wait、Hikari pending、external risk latency、Outbox backlog、Kafka lag、Redis error、GC pause 和 decline reason distribution。
```

## 19. 当前项目可以继续补的实物证据

如果要把这份文档变成更强的 repo 证据，建议按这个顺序补：

| 优先级 | 建议 | 价值 |
| --- | --- | --- |
| P0 | k6 authorization 压测脚本 | 证明会设计真实负载场景 |
| P0 | Actuator/Hikari/Kafka/Redis 指标截图或命令清单 | 证明会看真实 runtime |
| P1 | account hotspot 压测数据 | 证明理解 row lock 瓶颈 |
| P1 | Redis failure / Kafka failure 演练记录 | 证明理解降级和 backlog |
| P1 | API rate limit 设计文档或小实现 | 补当前 API backpressure 缺口 |
| P2 | ECS/RDS/MSK/ElastiCache 容量映射 | 和 AWS 文档联动 |

不建议优先做：

- 把 authorization balance 迁到 Redis。
- 为了展示 high traffic 直接拆 microservices。
- 为了 NoSQL 把核心账务迁到 DynamoDB。
- 在没有指标前盲调线程池和连接池。

## 20. 最终复习清单

你能回答这些问题，就基本能扛住 high traffic 深挖：

- Authorization 热路径中哪些步骤在 account row lock 前，哪些在锁内？
- 为什么 external risk 要在 account lock 前？
- 为什么 Kafka publish 不进主事务？
- Outbox backlog 上升是否影响授权正确性？
- 同一 account 高并发为什么加 pod 不一定有用？
- Redis timeout 时系统如何退化？
- 为什么不能缓存 available credit？
- Hikari pending 高说明什么？
- Tomcat threads、DB connections、Kafka concurrency、worker pool 怎么一起看？
- `FOR UPDATE` 和索引有什么关系？
- `SKIP LOCKED` 提升了什么，牺牲了什么？
- 如何设计 k6/Gatling 场景验证 idempotency 和 row lock？
- retry storm 怎么防？
- p99 上升时如何区分 DB、Risk、Redis、Kafka、JVM？
- 如果只能补一个 production 证据，为什么先补压测和容量模型？

> [!IMPORTANT]
> 最后再背一遍主线：  
> 高流量不是把所有东西都变异步或缓存，而是在正确性边界不变的前提下，把可并行的部分并行、可异步的部分异步、可缓存的 snapshot 缓存，并用指标证明瓶颈在哪里。
