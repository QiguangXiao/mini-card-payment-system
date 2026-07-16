# PayPay Card 后端interview准备手册

这份文档专门面向后端工程师interview，尤其是金融、支付、信用卡发卡方
issuer backend 场景。

它不是完整系统说明书，而是帮你准备这些能力：

- 用 1 分钟讲清楚这个项目解决什么问题。
- 用一条主链路讲清楚 authorization、posting、statement、repayment。
- 用真实类、表、状态、SQL 机制解释 `idempotency`、`row lock`、`transaction boundary`。
- 面对追问时能说明 trade-off，而不是只背结论。

先记住一句话：

```text
这个项目最有interview价值的地方，不是功能多，而是每个 money-changing flow 都有明确的幂等、事务、锁、状态机和失败恢复设计。
```

## 1. 怎么使用这份文档

如果你只有 30 分钟：

1. 看第 2 节的 10 个核心重点。
2. 背熟第 3 节的 60 秒项目介绍。
3. 用第 4 节把主链路从请求到数据库讲一遍。
4. 重点看第 5、6、7 节：幂等、事务锁、Outbox/Kafka。

如果你有 2 到 3 小时：

1. 每个专题都按“项目锚点 -> 30 秒回答 -> 追问”复述一次。
2. 过一遍问答总库 [`interview-qa-bank-cn.md`](interview-qa-bank-cn.md) 第 1 节的速记二十问，
   不要死背，重点练“先给结论，再给项目例子，再讲 trade-off”；答不顺的题看它标注的深挖版。
3. 最后用第 15 节自测，任何答不顺的题回到对应专题补。

本手册管冲刺流程（专题速记 + 自测 + 复习计划 + 压缩素材 + 反向提问）；
全部问答在问答总库，JD 逐条对照在 `paypay-card-jd-fit-cn.md`。

interview时的推荐回答结构：

```text
结论
-> 本项目怎么做
-> 为什么这样做
-> 代价是什么
-> 生产环境可以怎么扩展
```

## 2. interview最需要掌握的 10 个重点

| 优先级 | 重点 | 你必须能回答什么 | 项目锚点 |
| --- | --- | --- | --- |
| P0 | 信用卡主链路 | 授权、请款入账、出账、还款分别改变什么状态 | `AuthorizationService`, `PostingService`, `StatementGenerationService`, `RepaymentService` |
| P0 | `idempotency` | 重试、并发重复请求、重复消息分别怎么防 | `idempotency_key`, `network_transaction_id`, `source_event_id`, `consumer_inbox` |
| P0 | `transaction boundary` | 哪些状态必须同事务提交，哪些不能放进主事务 | Spring `@Transactional`, MySQL rows, Outbox rows |
| P0 | `row lock` | 为什么用 `SELECT ... FOR UPDATE`，为什么不用 Java lock | mapper XML, `findByIdForUpdate(...)` |
| P0 | Outbox / Inbox / Kafka | 为什么 Kafka 不能替代本地事务，为什么 consumer 还要幂等 | `outbox_events`, `consumer_inbox`, `KafkaConsumerConfiguration` |
| P1 | 状态机 | 哪些状态转换合法，非法转换如何拒绝 | `Authorization`, `CardTransaction`, `Statement`, `Repayment` |
| P1 | DDD 边界 | domain、application service、repository、infrastructure 各做什么 | `domain`, `application`, `infrastructure/mybatis` |
| P1 | MyBatis / SQL / Migration | SQL 如何支持并发安全、索引、唯一约束、schema migration 和可审计性 | mapper XML, Liquibase changelog |
| P1 | 失败恢复 | worker 宕机、Kafka 失败、外部服务失败、重复投递怎么办 | `OutboxWorker`, `DelayJobWorker`, DLT, CircuitBreaker |
| P2 | 高流量取舍 | 如何扩容、如何减少锁时间、何时考虑缓存或拆服务 | worker pool, partition key, lock scope |

## 3. 60 秒项目介绍

interview中如果对方问“介绍一下你做的项目”，可以这样讲：

> 我做的是一个 mini credit card issuer backend，用 Spring Boot、MySQL、MyBatis 和 Kafka 模拟发卡方核心链路。它覆盖 Authorization 授权占额度、Presentment Posting 入账、Statement 出账、Repayment 还款，以及异步 Notification 下游；Risk 保持在授权实时决策链。
>
> 我重点不是做一个 demo API，而是练金融后端最核心的可靠性设计。比如授权接口用 `Idempotency-Key` 和唯一索引防重复占额度；额度变化用 MySQL `SELECT ... FOR UPDATE` 做 row-level locking；业务状态和 Outbox event 在同一个 transaction boundary 内提交；Kafka 只负责异步传递，consumer 侧仍然用 Inbox 或业务唯一键做幂等。
>
> 所以这个项目可以讲清楚一个金融后端请求从 HTTP 进入，到 domain state transition，到 MySQL commit，再到 Kafka async delivery 和 failure recovery 的完整过程。

不要这样讲：

```text
我做了一个支付系统，有 Kafka，有 DDD，有 MySQL。
```

这样太泛。interview官会继续追问，你必须马上落到具体链路、具体表、具体失败场景。

## 4. 主链路速记

### 4.1 Authorization：授权占额度

请求入口：

```text
AuthorizationController.authorize(...)
-> AuthorizationCommand
-> AuthorizationService.authorize(...)
```

核心动作：

```text
Authorization: new -> PENDING -> APPROVED / DECLINED
CreditAccount: reservedAmount 增加
DelayJob: 创建 AUTHORIZATION_EXPIRY
OutboxEvent: 写 authorization.approved / authorization.declined
```

关键设计：

- `Idempotency-Key` 是 API 级幂等键。
- `AuthorizationCommand.requestFingerprint()` 防止同一个 key 对应不同请求 body。
- `authorizationRepository.claim(...)` 先插入 `PENDING` row，用唯一索引抢占幂等所有权。
- `findByIdempotencyKeyForUpdate(...)` 锁住同一个幂等键对应的 authorization row。
- 风控检查放在 account row lock 之前，减少锁等待时间。
- `creditAccountRepository.findByIdForUpdate(...)` 锁住账户行，再执行额度预占。
- `CreditAccount.reserve(...)` 负责业务不变量，不在 service 里直接做普通加减法。
- Authorization 状态、额度预占、DelayJob、Outbox 在同一个 MySQL transaction 内提交。

30 秒回答：

> 授权阶段本质是实时判断这张卡能不能先批准这笔消费。我的实现先用 `Idempotency-Key` 和唯一约束处理客户端重试，再做卡状态、风控和额度检查。真正改变额度时，会对 `credit_accounts` 做 `SELECT ... FOR UPDATE`，让同一账户的额度计算串行化。批准后只增加 `reservedAmount`，不直接入账，因为 presentment 还没到。最后把过期释放额度的 DelayJob 和 authorization event 的 Outbox row 一起提交，保证状态变化和后续动作不会脱节。

### 4.2 Presentment Posting：商户请款后入账

请求入口：

```text
PresentmentController
-> PostPresentmentCommand
-> PostingService.post(...)
```

核心动作：

```text
Authorization: APPROVED -> POSTED
CreditAccount: reservedAmount 减少, postedBalance 增加
CardTransaction: PENDING -> POSTED
OutboxEvent: authorization.posted + card_transaction.posted
```

关键设计：

- `network_transaction_id` 是 presentment 的天然业务幂等键。
- 只支持 full presentment，金额必须等于原授权金额。
- 过期授权不能再 posting。
- `account.postAuthorized(...)` 把 hold 转成 posted balance。
- `Authorization` event 表达授权生命周期结束。
- `CardTransaction` event 表达用户可见交易已入账。

30 秒回答：

> Presentment 是发卡方收到商户正式请款，posting 是把这笔交易入到持卡人账户。这里我不叫 capture，因为 capture 更偏商户侧语言。实现上会锁住原 authorization，校验它是 `APPROVED` 且未过期，再用 `network_transaction_id` 防重复 presentment。成功时在同一个事务里释放 reserved amount、增加 posted balance、把 authorization 标为 `POSTED`，并把 `CardTransaction` 标为 `POSTED`。

### 4.3 Statement：账单生成

入口：

```text
BillingCycleScheduler
-> StatementCycleService.createDueJobs()
-> StatementJobDispatcher
-> StatementJobHandler
-> StatementGenerationService.generate(...)
```

也可以通过 API 手动生成：

```text
StatementController
-> StatementGenerationService.generate(...)
```

核心动作：

```text
Statement: 创建 CLOSED
StatementLine: 快照 posted transactions
CardTransaction: 标记 billing_status=BILLED 并写入 statementId
DelayJob: 创建 AUTO_REPAYMENT
```

关键设计：

- 同一账户、同一 billing cycle 只能有一张 statement。
- `credit_account_id + period_start + period_end` 是账单生成的自然幂等键。
- 先锁 `credit_accounts`，再锁待出账 `card_transactions`，和 posting 保持锁顺序。
- `statement_lines` 是出账时的快照，不是每次查询时临时 SUM。
- 自动扣款是 future business action，所以用 DelayJob，不用 Outbox。

30 秒回答：

> 账单生成不是简单查询汇总，而是创建一个可审计的账单快照。实现上先锁账户行作为 concurrency gate，再查找同周期是否已有 statement。如果没有，就锁住本周期未出账的 posted transactions，创建 statement 和 statement lines，并把这些交易标记为 `billing_status=BILLED` 且归入该 statement。这样可以防止同一笔交易进入两张账单，也避免账单金额之后被历史交易变化影响。

### 4.4 Repayment：还款入账

请求入口：

```text
RepaymentController.receive(...)
-> ReceiveRepaymentCommand
-> RepaymentService.receive(...)
```

自动扣款入口：

```text
DelayJobWorker
-> AutoRepaymentDelayJobHandler
-> AutoRepaymentService
-> RepaymentService
```

核心动作：

```text
Repayment: PENDING -> RECEIVED
Statement: CLOSED / PARTIALLY_PAID -> PARTIALLY_PAID / PAID
CreditAccount: postedBalance 减少
```

关键设计：

- 还款请求也需要 `Idempotency-Key`。
- 同一个 key + 不同 statement/amount 要返回 conflict。
- 锁顺序是 `credit_accounts` 再 `statements`，避免和账单生成死锁。
- 不支持 overpayment。
- 还款成功后释放 posted balance，相当于恢复可用额度。

30 秒回答：

> 还款会同时改变 repayment row、statement paid amount/status 和 credit account posted balance，所以必须在同一个 transaction boundary 内提交。实现上先 claim repayment idempotency key，然后锁账户和账单，校验币种、金额、剩余应还金额，最后让 account 和 statement 各自执行 domain behavior。这样可以避免重复还款、超额还款和并发还款把账单状态改乱。

## 5. 高频专题一：`idempotency`

### 5.1 你要掌握什么

interview官问幂等时，通常不是只问“什么是幂等”，而是想知道：

- 客户端超时后重试会不会重复扣钱。
- 两个并发请求使用同一个幂等键会怎样。
- 同一个幂等键但请求内容不同会怎样。
- Kafka 重复投递会不会重复发通知、重复更新投影。
- 幂等是靠应用判断，还是数据库约束兜底。

### 5.2 项目里的幂等层次

| 场景 | 幂等键 | 数据库保护 | 说明 |
| --- | --- | --- | --- |
| Authorization API | `Idempotency-Key` | `authorizations.idempotency_key` unique | 防止重复占额度 |
| Presentment Posting | `network_transaction_id` | `card_transactions.network_transaction_id` unique | 防止重复入账 |
| Statement Generation | `credit_account_id + period_start + period_end` | `statements` unique cycle | 防止同周期重复出账 |
| Repayment API | `Idempotency-Key` | `repayments.idempotency_key` unique | 防止重复还款 |
| Notification consumer | `source_event_id` | `notifications.source_event_id` unique | 防止重复创建通知 |
| Kafka consumer 通用去重 | `event_id` | `consumer_inbox` | 防止重复处理 integration event |

### 5.3 interview回答

> 我会把幂等设计分成 API 幂等、业务自然键幂等和消息消费幂等。比如授权 API 用客户端传入的 `Idempotency-Key`，服务端还会计算 request fingerprint，确保同一个 key 只能对应同一笔请求。presentment 则用外部网络的 `network_transaction_id` 做天然业务键。Kafka consumer 不能假设 exactly-once，所以用 event id、Inbox 或业务唯一约束防重复 side effect。最终都要有数据库 unique constraint 兜底，因为并发请求下应用层先查再写是不可靠的。

### 5.4 常见追问

问：为什么不能先查有没有，再没有就插入？

答：

> 并发下两个请求可能同时查不到，然后都插入或都执行 side effect。正确做法是让数据库唯一约束参与竞争，例如 insert-first claim，只有一个 winner 继续执行业务，loser 读取 winner 的最终结果。

问：同一个 `Idempotency-Key` 但 body 不同怎么办？

答：

> 返回 conflict。项目里用 request fingerprint 比较关键业务字段，避免客户端 bug 把不同交易伪装成重试请求。

问：Kafka producer 开了 `enable.idempotence=true`，consumer 还要幂等吗？

答：

> 要。producer idempotence 只减少 producer retry 写 Kafka 的重复，不解决 Outbox publish 成功但应用还没 mark published 就宕机的窗口，也不解决 consumer 处理成功但 offset commit 前宕机的窗口。

## 6. 高频专题二：`transaction boundary` 和 `row lock`

### 6.1 你要掌握什么

金融后端interview里，事务和锁通常会这样问：

- 授权成功和额度冻结是不是同一个事务。
- Kafka publish 能不能放在这个事务里。
- 外部风控调用能不能放在锁里。
- 为什么用数据库锁，不用 Java `synchronized`。
- 多个流程同时改一个账户会不会死锁。

### 6.2 本项目的事务边界

| 用例 | 同事务内必须提交 | 不能放进主事务 |
| --- | --- | --- |
| Authorization | authorization 状态、account reserved amount、expiry DelayJob、Outbox event | Kafka publish、真实 push/email、慢外部通知 |
| Posting | authorization posted、account balance movement、card transaction posted、Outbox events | Kafka publish、对账文件生成 |
| Statement | statement、statement lines、transaction billing mark、auto repayment DelayJob | PDF 生成、账单通知（当前未实现） |
| Repayment | repayment received、statement payment state、account posted balance | 银行异步通知、缓存失效 |

### 6.3 为什么不用 Java lock

interview回答：

> Java `synchronized` 只能锁当前 JVM。生产环境通常有多个 pod 或多个实例，同一个账户的请求可能打到不同实例，所以 JVM lock 没法保护共享数据库状态。MySQL `SELECT ... FOR UPDATE` 锁的是数据库行，所有实例都会遵守这个并发控制。

### 6.4 为什么锁账户行

核心不变量：

```text
availableCredit = creditLimit - reservedAmount - postedBalance
```

凡是会改变 `reservedAmount` 或 `postedBalance` 的操作，都必须对同一个 `credit_accounts` row 串行化。

典型场景：

- 两笔授权同时进来，都认为额度够。
- posting 和 statement 同时处理，账单漏掉刚入账交易。
- repayment 和 statement 同时处理，账单金额或账户余额错乱。

### 6.5 锁顺序

项目里尽量保持：

```text
credit account row lock -> related business rows
```

好处：

- 减少 deadlock。
- 让同一账户的 money-changing 操作更容易推理。
- 账单生成和 posting 不会互相绕过。

interview时可以补充：

> 锁顺序不是越复杂越好，关键是团队要有一致规则。金融系统里只要涉及同一账户余额，通常会用账户行作为 concurrency gate。

### 6.6 外部风控为什么放在账户锁前

项目里 `RiskAssessmentService.assess(...)` 在 account row lock 之前执行。

原因：

- 风控可能有本地统计、外部 HTTP 调用、CircuitBreaker。
- 如果先锁账户再等外部服务，会扩大 critical section。
- 高并发下同账户其他请求会被慢调用拖住。

代价：

- 风控通过后，到真正锁账户之间，账户可用额度可能变化。
- 所以额度是否足够必须在锁内重新判断，不能只相信锁外计算。

## 7. 高频专题三：Outbox、Inbox、Kafka

### 7.1 先讲清楚 dual-write problem

问题：

```text
业务 DB commit 成功
Kafka publish 失败
```

或者：

```text
Kafka publish 成功
业务 DB rollback
```

这就是 MySQL 和 Kafka 双写不原子的 classic dual-write problem。

项目做法：

```text
业务事务内写业务表 + outbox_events
事务提交后
OutboxClaimer claim
OutboxWorker publish Kafka
publish 成功后 mark PUBLISHED
失败则 retry / DEAD
```

### 7.2 interview回答

> 我不会在主业务事务里直接发 Kafka。因为 MySQL commit 和 Kafka ack 不能组成一个本地原子事务。本项目使用 Transactional Outbox：业务状态和 outbox row 在同一个 MySQL transaction 内提交，后台 worker 再异步发布 Kafka。这样主业务成功后至少有一个 durable publish plan。Kafka 是 at-least-once，所以 consumer 仍然要用 Inbox 或唯一约束做幂等。

### 7.3 Outbox 和 Inbox 的区别

| 机制 | 解决什么问题 | 项目例子 |
| --- | --- | --- |
| Outbox | 本服务状态变化后可靠发布事件 | `outbox_events`, `OutboxPoller`, `OutboxClaimer`, `OutboxWorker` |
| Inbox | 本服务消费外部事件时防重复 side effect | `consumer_inbox` |
| DLT | 消息无法处理时隔离坏消息 | `mini-card.notification.dlt.v1` |

一句话：

```text
Outbox 保护 producer side，Inbox 保护 consumer side。
```

### 7.4 Kafka 配置怎么讲

本项目关键配置：

- `acks=all`：提高 broker durability。
- `enable.idempotence=true`：减少 producer retry 在 Kafka 内部产生重复。
- `enable-auto-commit=false`：listener 成功处理后再提交 offset。
- `ack-mode=record`：按 record 处理成功后提交。
- consumer group 按 bounded context 划分（当前只有 Notification 一个消费方；DLT 按失败消费组路由）。
- DLT 保留 original partition，方便排查和 replay。

interview回答：

> Kafka 配置只能提高消息传递可靠性，不能消除业务幂等。比如 `acks=all` 提高写入 Kafka 的可靠性，但不解决 MySQL 和 Kafka 的原子性；`enable.idempotence` 减少 producer retry 重复，但不等于业务 exactly-once；consumer 关闭 auto commit，是为了避免 offset 先提交、MySQL side effect 还没成功时消息丢失。

### 7.5 Partition key 怎么讲

原则：

```text
同一个 aggregate 需要有序的事件，用同一个 partition key。
```

项目例子：

- Authorization event 用 authorization id。
- CardTransaction event 用 card transaction id。

追问回答：

> Kafka 只能保证同一 partition 内有序，不能保证全局有序。所以业务事件设计时要先明确哪个范围需要顺序。金融系统里通常不追求全局顺序，而是追求账户、交易、账单等 aggregate 范围内可推理的顺序。

## 8. 高频专题四：DelayJob 和后台任务

### 8.1 Outbox 和 DelayJob 不要混

| 机制 | 本质 | 例子 |
| --- | --- | --- |
| Outbox | 可靠发布已经发生的业务事实 | `authorization.approved`, `card_transaction.posted` |
| DelayJob | 未来某个时间执行一个业务动作 | authorization expiry, auto repayment |

interview回答：

> Outbox 表达“这件事已经发生，要可靠通知别人”。DelayJob 表达“到某个时间要执行一个业务动作”。授权批准后写 Outbox 是通知下游授权已批准；同时写 DelayJob 是计划 7 天后如果还没 posting 就释放额度。两者都可以用 poller/worker/retry，但业务语义不同。

### 8.2 Scheduler 为什么要薄

项目结构：

```text
Poller: 定时醒来，claim rows，提交 worker
Worker: 执行业务或 publish，并 finalize state
Recoverer: 回收超时 PROCESSING lease
```

interview回答：

> 我不会让 `@Scheduled` 方法直接做所有事情。生产里 worker 可能执行很久、线程池可能拒绝、进程可能宕机。项目里 poller 只负责短事务 claim，claim commit 后 worker 执行业务，成功或失败由 worker 自己 finalize。PROCESSING 是 lease，不是永久状态，recoverer 会把超时任务放回 retry。

### 8.3 为什么需要 lease 校验

场景：

```text
worker A claim job
worker A 很慢
lease 过期
recoverer 把 job 放回 PENDING
worker B 重新 claim 并处理
worker A 后来返回
```

如果 A 不校验 lease，就可能覆盖 B 的结果。

项目里 `OutboxWorker` 和 `DelayJobWorker` finalize 前都会重新 `FOR UPDATE` 锁 row，并校验状态和 `nextAttemptAt` 是否仍然是自己 claim 的 lease。

## 9. 高频专题五：DDD 和分层

### 9.1 你要表达的核心观点

这个项目不是为了套 DDD 名词，而是为了让业务规则有合适归属。

| 层 | 职责 | 例子 |
| --- | --- | --- |
| `api` | HTTP request/response、Bean Validation | `AuthorizationController`, DTO |
| `application` | use case、事务边界、调用顺序 | `AuthorizationService`, `PostingService` |
| `domain` | 状态转换、不变量、业务事实 | `Authorization`, `CreditAccount`, `Statement` |
| `repository` | 领域需要的持久化接口 | `AuthorizationRepository` |
| `infrastructure` | MyBatis、Kafka、外部 client、scheduler adapter | `MyBatisAuthorizationRepository`, Outbox adapters |

### 9.2 interview回答

> 我用 DDD 是为了让状态转换和不变量靠近业务对象，而不是把所有规则都塞进 service。比如 `CreditAccount.reserve(...)` 负责判断额度是否足够，`Authorization.approve(...)` 负责产生 approved domain event，`Statement.applyRepayment(...)` 负责推进账单状态。Application service 负责事务边界和多个 aggregate 的编排。对于 Outbox、DelayJob 这种通用可靠性机制，我不会强行套复杂 DDD 分层，而是采用更直接的 mechanism-oriented package。

### 9.3 可能追问：为什么一个事务会改多个 aggregate

答：

> 真实金融 use case 里经常需要在一个业务操作内维护多个对象的一致性。比如授权批准必须同时冻结额度和更新 authorization 状态。这里 application service 是事务脚本式 orchestration，domain object 仍然保护自己的状态规则。DDD 不是说一个事务永远只能改一个 aggregate，而是要清楚一致性边界和代价。

### 9.4 可能追问：事件为什么由 aggregate 产生

答：

> 业务事件应该从真实状态转换产生，而不是 service 事后凭参数重新拼一个事实。比如 `Authorization.approve(...)` 发生时产生 approved event，这能保证 event 和 domain state transition 对齐。Service 只负责把 aggregate pull 出来的 domain events 交给 Outbox adapter。

## 10. 高频专题六：SQL、MyBatis、约束和索引

### 10.1 MyBatis 在项目中的价值

interview回答：

> 这个项目选择 MyBatis，是因为金融后端很多关键行为依赖明确 SQL，例如 `SELECT ... FOR UPDATE`、unique constraint、状态条件更新和批处理 claim。MyBatis 让 SQL 保持可 review，同时减少 JDBC row mapping boilerplate。Domain 不依赖 MyBatis，MyBatis 在 infrastructure adapter 里。

### 10.2 SQL interview重点

必须能讲：

- `#{}` 和 `${}` 的区别，用户输入不能直接进 `${}`。
- `SELECT ... FOR UPDATE` 锁什么，何时释放。
- `FOR UPDATE SKIP LOCKED` 为什么适合多个 worker 并发 claim。
- unique constraint 为什么是幂等最后防线。
- index 如何支持查询和锁范围。
- `DECIMAL(19,2)` 与 Java `BigDecimal` 的对应。

### 10.3 项目里的重要约束

| 表 | 约束 | interview意义 |
| --- | --- | --- |
| `authorizations` | `uk_authorizations_idempotency_key` | 授权幂等 |
| `card_transactions` | `uk_card_transactions_network_transaction` | presentment 幂等 |
| `statements` | `uk_statements_cycle` | 同账户同周期只生成一张账单 |
| `repayments` | `uk_repayments_idempotency_key` | 还款幂等 |
| `notifications` | `uk_notifications_source_event` | consumer side effect 幂等 |
| `delay_jobs` | `uk_delay_jobs_aggregate` | 同一未来业务动作不重复调度 |

### 10.4 interview追问：索引和锁有什么关系

答：

> InnoDB 的行锁依赖索引查找路径。如果查询条件没有合适索引，数据库可能扫描更多记录，锁范围扩大，甚至影响并发。所以金融系统里不仅要写 `FOR UPDATE`，还要确保 where condition 有合适索引，锁住的是预期的业务行。

## 11. 高频专题七：金额、币种和状态机

### 11.1 金额为什么用 `BigDecimal`

interview回答：

> 金额不能用 `double`，因为二进制浮点会有精度误差。项目用 `Money` value object 包装 Java `BigDecimal` 和 `Currency`，数据库用 `DECIMAL(19,2)`。这样金额加减、币种匹配、正数校验都可以集中在 domain 层，而不是散落在 service 里。

### 11.2 状态机为什么重要

金融系统里状态不是普通字段，而是业务事实。

例子：

```text
Authorization: PENDING -> APPROVED -> POSTED
Authorization: PENDING -> DECLINED
Authorization: APPROVED -> EXPIRED
```

不能允许：

```text
DECLINED -> APPROVED
POSTED -> EXPIRED
PENDING -> POSTED
```

interview回答：

> 我会把合法状态转换放在 domain object 方法里，例如 `approve(...)`、`decline(...)`、`post(...)`、`expire(...)`。这样 scheduler、Kafka consumer、test 或未来其他入口都不能绕过状态规则直接 set status。

### 11.3 数据库 check constraint 还有必要吗

答：

> 有必要。Domain invariant 是应用层第一道防线，数据库 constraint 是最后一道防线。生产系统里可能有 bug、脚本、批处理或未来服务绕过当前 domain code，所以核心金额非负、状态字段合法、paid amount 不超过 total amount 这类规则最好在数据库也有保护。

## 12. 高频专题八：风控、外部依赖和可用性

### 12.1 风控链路怎么讲

项目里：

```text
RiskAssessmentService
-> local checks
-> ExternalRiskGateway
-> ExternalRiskGatewayAdapter
-> ExternalRiskClient
-> CircuitBreaker fallback
```

本地规则包括：

- blocked merchant。
- velocity window。
- high-risk amount。
- cross-border check。

外部风控失败时，当前项目采用 fail-closed：

```text
EXTERNAL_RISK_UNAVAILABLE -> decline
```

interview回答：

> 授权是实时路径，风控既要控制风险，也要控制延迟。项目先做便宜的 local checks，明显拒绝的请求不进入外部调用。外部风控用 CircuitBreaker 保护调用方，fallback 采用 fail-closed，因为信用卡授权里风险不可判定时放行可能造成资金损失。生产中也可以按产品策略区分小额放行、大额拒绝或人工复核。

### 12.2 为什么风控通过后还要锁账户判断额度

答：

> 风控和额度是两个不同问题。风控通过只能说明交易风险可接受，不说明账户额度仍然足够。因为风控在账户锁之前执行，期间可能有其他授权改变额度，所以必须在锁内重新计算 available credit。

## 13. 高频专题九：高流量和生产扩展

### 13.1 可以怎么扩容

| 部分 | 扩容方式 | 注意点 |
| --- | --- | --- |
| API service | 横向扩容多个实例 | 幂等和 row lock 必须依赖数据库，不依赖 JVM 内存 |
| Kafka consumer | 增加 concurrency 和实例数 | partition 数限制同一 consumer group 最大并行度 |
| Outbox worker | 增加 worker pool 和 batch size | 注意 Kafka timeout、DB load、lease timeout |
| DelayJob worker | 增加 worker pool | handler 必须幂等，PROCESSING lease 要可恢复 |
| MySQL | 索引优化、读写分离、分库分表 | money-changing path 不能随便读从库 |

### 13.2 怎么减少锁竞争

项目里已经有几个选择：

- 风控放在 account lock 前。
- Controller 参数校验不持锁。
- Kafka publish 不在主事务内。
- Notification 不在主事务内。
- Scheduler claim 使用短事务。
- Worker 执行和 finalize 分开。

interview回答：

> 我会把锁内逻辑限制在真正需要一致性的数据库读写和 domain state transition 上。慢外部调用、Kafka publish、通知发送、PDF 生成都应该移出主事务。这样既保证 money-changing 状态一致，又减少高并发下的锁等待。

### 13.3 什么时候考虑 Redis 或缓存

答：

> 可以缓存低风险、变化不频繁的读模型，例如卡产品配置、商户黑名单、静态风控规则。但账户额度、授权幂等、还款状态这类强一致 money-changing 数据不能先靠缓存决定。即使用缓存优化读，最终写路径仍要回到数据库事务和 row lock。

### 13.4 如果要拆微服务，怎么拆

可以按 bounded context 拆：

- Authorization。
- Transaction / Posting。
- Statement。
- Repayment。
- Notification。
- Risk。

但interview要强调：

> 拆服务会增加分布式一致性、部署、观测和契约管理成本。这个项目先保持单体，但用明确 package、domain boundary、Outbox event contract 和 consumer group 模拟未来拆分边界。不是因为不会微服务，而是当前学习阶段单体更容易讲清楚核心业务和事务。

## 14. interview 问答库（已迁至问答总库）

20 题速记问答连同深挖题库一起收拢在 [`interview-qa-bank-cn.md`](interview-qa-bank-cn.md)。
速记版编号 速Q1–速Q20，每题标注对应深挖题；冲刺时先过速记版。

## 15. 自测清单

如果你能不看文档回答下面问题，说明准备已经比较扎实：

1. 用 1 分钟讲清楚项目主链路。
2. 用一笔 1,000 JPY 消费讲清楚 `reservedAmount` 和 `postedBalance` 怎么变化。
3. 解释 authorization 幂等的 insert-first claim。
4. 解释 presentment 为什么不用 `Idempotency-Key`，而用 `network_transaction_id`。
5. 解释为什么 Kafka producer idempotence 不等于业务 exactly-once。
6. 解释 Outbox publish 成功但 mark published 失败时会发生什么。
7. 解释 `credit_accounts` row lock 为什么是额度并发控制核心。
8. 解释为什么外部风控调用不应该放在 account row lock 里面。
9. 解释 statement generation 如何防止同一交易进入两张账单。
10. 解释 repayment 如何防止超额还款和重复还款。
11. 解释 DelayJob 的 `PROCESSING` 为什么是 lease。
12. 解释为什么 CardTransaction、Statement、Ledger 和 Reconciliation 不是同一个概念，以及本项目为什么只实现交易与账单、不实现 accounting Ledger。
13. 解释 DDD 在项目里带来了什么，哪里没有强行套 DDD。
14. 解释 MyBatis 相比 JPA 在这个项目里的优势。
15. 解释金额为什么用 `BigDecimal` 和 `DECIMAL(19,2)`。
16. 说出这个学习项目离真实生产系统还缺哪些东西。

## 16. 复习路线

如果只有 30 分钟：

1. 背熟 JD 对照手册（`paypay-card-jd-fit-cn.md`）第 2 节的一分钟定位。
2. 看 JD 对照手册第 3 节总体匹配度。
3. 练问答总库（`interview-qa-bank-cn.md`）第 2 节的 Q1、Q2、Q4、Q5。

如果有 2 小时：

1. 看 `docs/paypay-card-backend-interview-guide-cn.md` 的主链路。
2. 看本文件第 5、6、8、9、10 节。
3. 看 `docs/caching-and-rate-limiting-cn.md` 和 `docs/jvm-threads-runtime-cn.md`。
4. 用自己的话讲一遍：authorization 请求如何从 HTTP 到 DB lock，再到 Outbox/Kafka/cache/metrics。

如果有 1 天：

1. 学习 `docs/traffic-rate-limiting-and-capacity-cn.md`。
2. 补 `docs/aws-ecs-deployment-cn.md`。
3. 补 `docs/nosql-tradeoffs-cn.md`。
4. 选做 gRPC Risk adapter 或最小 Reconciliation。

> [!IMPORTANT]
> 最后记住这个回答原则：先讲业务正确性，再讲技术机制，再讲扩展 trade-off。
> 不要为了 JD 关键词而牺牲金融系统的正确性边界。

## 17. 最后一周复习计划

### 第 7 天：主链路

目标：

- 60 秒讲项目。
- 5 分钟讲 authorization。
- 5 分钟讲 posting。
- 5 分钟讲 statement/repayment。

必须能画：

```text
Controller
-> Command
-> Application Service
-> Domain
-> Repository/MyBatis
-> Outbox/DelayJob
-> Kafka Consumer
```

### 第 6 天：幂等和锁

目标：

- 讲清 API idempotency。
- 讲清 presentment idempotency。
- 讲清 Kafka Inbox。
- 讲清 `FOR UPDATE` 和 unique constraint。

练习题：

- 同 key 同请求。
- 同 key 不同请求。
- 同 account 不同授权。
- Worker duplicate。

### 第 5 天：Kafka/Outbox/DelayJob

目标：

- 讲清 Outbox dual-write。
- 讲清 Inbox at-least-once。
- 讲清 DelayJob future action。
- 讲清 DLT 和 replay。

练习题：

- publish 成功后宕机。
- consumer 成功后 offset 未提交。
- DelayJob worker 宕机。
- Kafka backlog 上升。

### 第 4 天：Cache/NoSQL

目标：

- 讲清 Caffeine L1 + Redis L2。
- 讲清为什么不缓存额度。
- 讲清 after-commit evict。
- 讲清 NoSQL 适合 read model。

练习题：

- Redis stale。
- Redis timeout。
- Card blocked 后 cache 旧值。
- DynamoDB 是否适合 credit account。

### 第 3 天：High traffic 和 observability

目标：

- 讲清性能瓶颈定位。
- 讲清 thread pool/DB pool/Kafka concurrency。
- 讲清 Actuator/JVM/CloudWatch 指标。
- 讲清 backpressure。

练习题：

- p99 变慢。
- DB lock wait。
- Kafka lag。
- Outbox DEAD 增长。

### 第 2 天：AWS 和架构演进

目标：

- 讲清 ECS/RDS/ElastiCache/MSK 映射。
- 讲清 CloudFormation stack。
- 讲清 CodePipeline 基本路径。
- 讲清 modular monolith 到 microservices。

练习题：

- readiness/liveness。
- rollback。
- DB migration。
- Java EE modernization。

### 第 1 天：模拟 interview

模拟流程：

1. 60 秒项目介绍。
2. 10 分钟主链路。
3. 15 分钟幂等/锁深挖。
4. 15 分钟 Kafka/cache/high traffic。
5. 10 分钟 AWS/NoSQL/gRPC gap。
6. 5 分钟反向提问。

检查标准：

- 每个回答都能落到项目类/表/配置。
- 每个回答都能说出 trade-off。
- 遇到没做过的东西能诚实讲迁移路径。
- 不把 Redis、Kafka、microservices 当万能答案。

## 18. 最后一轮复习顺序

interview前一天建议这样复习：

1. 读 `docs/domain-state-flow-cn.md`，把状态流转和锁顺序过一遍。
2. 读本文第 5 到 8 节，重点练幂等、锁、Outbox、DelayJob。
3. 读 `docs/mybatis-sql-and-migration-cn.md` 的锁、索引、事务、`EXPLAIN` 部分。
4. 读 `docs/events-outbox-inbox-kafka-cn.md` 的 producer/consumer 配置和 DLT 部分。
5. 对着问答总库（`interview-qa-bank-cn.md`）的速记二十问口头模拟 30 分钟。

最终目标不是背出所有类名，而是能做到：

```text
任何一个设计问题
都能回到一个具体请求、具体表、具体锁、具体失败场景来解释。
```

## 19. 最终压缩版：强回答素材

> [!IMPORTANT]
> 如果只记 12 句话，记这些：

1. Authorization 不是扣款，而是 hold credit。
2. Posting 才把 reserved amount 转成 posted balance。
3. Statement 是审计快照，不是实时 SUM。
4. Repayment 必须同时推进 statement 和 account。
5. API 幂等靠 unique constraint，不靠 JVM memory。
6. 同账户额度并发靠 `SELECT ... FOR UPDATE`。
7. Kafka 不替代 DB transaction。
8. Outbox 解决 publish intent 和业务状态的 dual-write。
9. Inbox 解决 consumer side effect 重复执行。
10. Cache 只保存可重建 snapshot，不缓存额度。
11. High traffic 先定位瓶颈，不是先加机器。
12. Microservices 是边界和组织问题，不是越拆越好。

> [!TIP]
> 最终 60 秒硬核版可以这样讲：

> 我的项目是一个 credit card issuer backend，核心是 authorization、posting、statement、repayment。
> 我用 Spring Boot 和 MySQL/MyBatis 实现主交易链路，用 domain object 管状态机和金额 invariant。
> 对重复请求，API 层用 `Idempotency-Key` 和数据库 unique constraint；对同账户并发额度，使用
> `SELECT ... FOR UPDATE` 锁 `credit_accounts` 行；对异步通知，使用
> Transactional Outbox + Kafka + Consumer Inbox，承认 at-least-once 并让 consumer 幂等。
> 对读扩展，我实现了 Caffeine L1 + Redis L2 的 statement GET cache，只缓存 statement read model；
> 旧版 card snapshot cache 已删除，因为 stale card status 会影响 authorization。生产上我会用 Actuator/CloudWatch 观测 API latency、DB lock wait、
> Kafka lag、Outbox/DelayJob backlog、Redis hit ratio 和 JVM GC。这个项目还没真实上 AWS 和 NoSQL，
> 但我能说明 ECS/RDS/ElastiCache/MSK 的部署映射，以及 NoSQL 更适合 read model 而不是核心账务 transaction。

## 20. 反向提问准备

真实 interview 最后通常会让你问问题。

不要只问：

```text
团队氛围怎么样？
```

可以问更贴岗位的问题：

### 问题 1：架构演进

```text
JD 提到 PayPay Card 正在挑战当前架构。
目前团队更关注从 monolith 到 microservices 的拆分，
还是先提升现有系统的 reliability、observability 和 delivery speed？
```

为什么好：

- 呼应 JD。
- 显示你理解架构演进不是盲目换技术。

### 问题 2：一致性边界

```text
在信用卡核心链路里，哪些场景团队认为必须强一致，
哪些场景可以接受 eventual consistency？
```

为什么好：

- 直接命中金融后端核心。
- 也能引出 Outbox、Kafka、cache 的讨论。

### 问题 3：高流量挑战

```text
目前高流量瓶颈更多来自数据库锁竞争、外部依赖延迟、Kafka backlog，
还是应用层资源，例如 thread pool 和 connection pool？
```

为什么好：

- 显示你知道性能瓶颈有层次。

### 问题 4：Legacy modernization

```text
JD 提到有 Java EE legacy。
团队现在是倾向 strangler pattern 渐进迁移，
还是在某些领域做较大的 rewrite？
```

为什么好：

- 显示你知道 legacy 风险。

### 问题 5：Cloud operations

```text
服务在 AWS ECS 上运行时，团队主要用 CloudWatch 还是也接入 Prometheus/Grafana？
对业务指标比如 authorization latency、Kafka lag、Outbox backlog 有没有统一 SLO？
```

为什么好：

- 把 AWS、observability、业务可靠性连起来。

### 问题 6：Cache/NoSQL

```text
团队对 Redis/NoSQL 的使用边界是怎样的？
哪些数据会进入 distributed cache，哪些仍然坚持以 RDBMS transaction 为准？
```

为什么好：

- 显示你不会为了性能牺牲正确性。
