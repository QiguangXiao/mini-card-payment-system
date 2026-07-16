# 面试问答总库（Interview Q&A Bank）

本文档收拢全部面试问答，来源为原 `paypay-card-jd-fit-cn.md` 的回答模板、
深挖题库、系统设计题、排障题、red flag 回答、追问速查，以及原
`paypay-card-backend-interview-guide-cn.md` 的 20 题速记问答。

使用方式：

1. 速记版（第 1 节，编号 速Q1–速Q20）先过一遍，能顺畅复述再进深挖版。
2. 深挖题库（第 3–10 节，编号 Q8–Q80）按主题查，每题都是"结论 -> 项目做法 -> 为什么 -> 如果去掉会怎样"。
3. 追问速查（第 14 节）是每个主 Q 的追问补全，考前按弱项抽查。
4. JD 对照与项目证据见 `paypay-card-jd-fit-cn.md`；复习计划与冲刺流程见
   `paypay-card-backend-interview-guide-cn.md`。

> [!NOTE]
> 编号约定：速Q1–速Q20 是速记版（原 guide 问答库）；Q1–Q7 是 JD 高频模板；
> Q8–Q80 是深挖题库。速记版每题标注了对应深挖题，重复的问题以"速记 + 深挖"两档
> 深度并存，不重复展开。

## 1. 高频二十问（速记版）

### 速Q1：一次授权请求从进来到返回，发生了什么？

答：

> Controller 读取 `Idempotency-Key` 和 request body，转成 `AuthorizationCommand`。Application service 开启事务，先创建 `PENDING` authorization 并通过 unique key claim 幂等所有权。winner 做卡状态、风控、账户额度检查，额度检查时锁住 `credit_accounts` row。批准后更新 reserved amount 和 authorization 状态，写 expiry DelayJob 和 Outbox event，最后事务提交返回结果。

深挖版：Q9（钱到底发生了什么变化）、Q16（幂等具体怎么保证）；完整链路推演见第 11 节系统设计题。

### 速Q2：如何防止同一个授权请求重复扣额度？

答：

> 用 `Idempotency-Key` 加数据库唯一约束。第一次请求 insert-first claim 成功后继续决策；重复请求拿不到 claim，会锁住并读取已有 authorization。如果 fingerprint 相同返回原结果，如果不同返回 conflict。这样并发 retry 不会重复 reserve credit。

深挖版：Q16、Q17（duplicate 为什么要 FOR UPDATE 读 winner）、Q18（为什么不用 Redis SETNX）。

### 速Q3：如果两个不同授权同时刷同一个账户，会不会超额度？

答：

> 不会依赖应用内存判断。两笔请求都会在改变额度前执行 `creditAccountRepository.findByIdForUpdate(...)`，同一个 account row 会被 InnoDB 串行化。第二个请求会等第一个 commit 后再看到最新的 reserved amount 和 posted balance。

深挖版：Q20（并发同账户会不会超额）、Q21（lock order 与 deadlock）。

### 速Q4：为什么 authorization 批准后不直接写 posted balance？

答：

> 授权只是 issuer 临时批准并 hold 额度，商户还没有正式请款。只有 presentment/clearing 到达后，issuer 才把 hold 转成 posted transaction。直接入账会混淆授权和清分入账，也会让取消、过期、金额不匹配等场景难处理。

深挖版：Q9（authorization 成功后钱的变化）。

### 速Q5：presentment 为什么用 `network_transaction_id` 做幂等？

答：

> 因为 presentment 来自外部网络或 clearing record，`network_transaction_id` 是这条外部交易记录的业务身份。客户端 header 的 idempotency key 更适合 API 请求重试，而 presentment 的重复通常来自文件重放、网络重发或上游重复投递，所以用外部业务 id 更自然。

深挖版：Q10（presentment 为什么必须有自己的 idempotency key）。

### 速Q6：为什么账单要保存 `statement_lines` 快照？

答：

> 账单是对某个 billing cycle 的正式结果，应该稳定、可审计。不能每次查询时临时 SUM 当前交易表，因为后续退款、调整、交易字段修正可能改变查询结果。`statement_lines` 保存出账时的交易快照，让用户看到的账单明细稳定。

深挖版：Q11（statement generation 为什么不是简单 SUM）。

### 速Q7：Outbox 解决了什么，没解决什么？

答：

> Outbox 解决业务 DB 和消息发布的 dual-write 问题，让业务状态变化后一定有 durable event 待发布。但它不保证 consumer side effect exactly-once，也不保证 Kafka 全局有序。所以 consumer 还需要 Inbox 或 unique constraint，事件设计还需要合适 partition key 和版本管理。

深挖版：Q28（Outbox 解决什么）、Q30（publish 后宕机）、Q24（为什么 Kafka publish 不能进 DB 事务）。

### 速Q8：Kafka 消息重复投递怎么办？

答：

> 设计上承认 Kafka 是 at-least-once。Notification 用 `source_event_id` 唯一约束防重复创建通知，其他 consumer 可以用 `consumer_inbox` 按 `consumer_name + event_id` 去重。处理逻辑要能重复执行而不产生重复 side effect。

深挖版：Q29（Inbox 解决什么）、Q38（consumer 乱序）。

### 速Q9：为什么 consumer 关闭 auto commit？

答：

> 如果 offset 自动提交，而业务 side effect 还没写入 MySQL 时进程宕机，Kafka 会认为消息已消费，导致 side effect 丢失。关闭 auto commit 后，listener 成功处理并返回后再提交 offset。即便处理成功但提交 offset 前宕机，也只是重复投递，靠幂等处理。

深挖版：Q33（consumer 什么时候 commit offset）。

### 速Q10：DelayJob 和 Outbox 有什么区别？

答：

> Outbox 是发布已经发生的业务事实，例如 authorization approved。DelayJob 是计划未来执行业务动作，例如授权 7 天后过期释放额度，或账单到期自动扣款。两者都需要 retry 和 lease，但语义不同。

深挖版：Q32（Outbox 和 DelayJob 的区别）。

### 速Q11：为什么 `@Scheduled` 不直接处理所有 job？

答：

> 因为定时方法如果同时负责扫描、执行业务、更新状态，会很难处理长任务、线程池拒绝、进程宕机和并发实例。项目把 poller 做薄，只 claim 到 PROCESSING lease，worker 执行业务并 finalize，recoverer 回收超时 lease。这样失败恢复路径更清楚。

深挖版：Q31（为什么 Outbox/DelayJob 都有 PROCESSING lease）。

### 速Q12：DDD 在这个项目里怎么体现？

答：

> 业务状态和不变量放在 domain object，例如 `CreditAccount.reserve(...)`、`Authorization.approve(...)`、`Statement.applyRepayment(...)`。Application service 负责 use case orchestration 和 transaction boundary。Repository 是 domain 需要的接口，MyBatis 是 infrastructure adapter。对 Outbox、DelayJob 这种技术机制，不强行套复杂 DDD 结构，而是保持简单机制包。

深挖版：Q66（DDD 哪里体现）、Q68（domain object 为什么不调 repository）。

### 速Q13：为什么事件要由 aggregate 产生？

答：

> 因为 integration event 应该反映真实发生的状态转换。状态从 `PENDING` 到 `APPROVED` 时，`Authorization` aggregate 产生 approved domain event；service 只是把它交给 Outbox。这样不会出现 service 根据参数拼出一个和实际状态不一致的事件。

深挖版：Q67（domain event 为什么由 aggregate 产生）。

### 速Q14：如何处理外部风控服务不可用？

答：

> 项目用 CircuitBreaker 保护外部风控调用，fallback 采用 fail-closed，返回 `EXTERNAL_RISK_UNAVAILABLE` 拒绝授权。原因是信用卡授权涉及资金风险，不可判定时默认放行可能造成损失。生产中可以按金额、客户等级、商户风险做更细策略。

深挖版：Q14（Risk 为什么放在 account row lock 之前）；可用性追问见第 14 节追问速查。

### 速Q15：为什么金额不用 `double`？

答：

> 金额需要十进制精确语义，`double` 有二进制浮点误差。项目用 `Money` value object 包装 `BigDecimal` 和 `Currency`，数据库用 `DECIMAL(19,2)`，并在 domain 层做正数、币种一致、金额比较等规则。

### 速Q16：你如何解释 `FOR UPDATE SKIP LOCKED`？

答：

> 它适合多个 worker 并发 claim 队列表。一个 worker 锁住某些 `PENDING` rows 后，其他 worker 查询时跳过已锁定 rows，而不是等待。这样可以横向扩容 poller/worker，同时避免重复 claim。同一条 row 最终仍由数据库锁和状态字段保护。

深挖版：Q19（SELECT ... FOR UPDATE 锁住的到底是什么）；SKIP LOCKED 机制详见 claimable-jobs-cn.md。

### 速Q17：如果 Outbox worker publish 成功后，mark published 前宕机怎么办？

答：

> Outbox row 仍然是 `PROCESSING`，lease 过期后 recoverer 会放回 retry，可能再次 publish 同一 event。这是 at-least-once 的典型窗口，所以 consumer 必须按 `eventId` 幂等。系统选择重复可控，而不是消息丢失。

深挖版：Q30（publish 成功、markPublished 前宕机）。

### 速Q18：如果你加入生产团队，只负责一个模块，这个项目还有什么帮助？

答：

> 生产工作通常只负责一个 bounded context，但你必须理解上下游契约和一致性责任。比如只做 statement，也要知道 posted transactions 从哪里来、是否可能重复、账单生成如何防止漏账、通知如何异步发出、还款如何影响 statement 状态。这个项目训练的是跨模块读代码和判断风险的能力。

### 速Q19：这个项目离真实生产系统还差什么？

答：

> 它是学习项目，不是完整生产信用卡核心。项目刻意不实现 Ledger；真实 accounting system 需要 balanced journal、科目、调整/冲正和严格审计，不能用单边分录投影代替。真实系统还需要账户持有人、权限认证、settlement/reconciliation、refund/dispute、监管报表、监控告警、迁移工具、灰度发布、密钥管理和更严格的安全控制。但当前项目已经覆盖后端interview最常问的交易状态、一致性、幂等、锁、异步可靠性和失败恢复。

另见第 13 节 red flag 回答与 JD 对照手册的 Gap 排序（§14）。

### 速Q20：如果高并发下授权接口变慢，你会怎么排查？

答：

> 先看慢在哪里：外部风控延迟、数据库锁等待、索引问题、连接池、Kafka/Outbox 不应该阻塞主请求。对数据库看 slow query、lock wait、`EXPLAIN` 和相关索引；对应用看接口 latency breakdown、thread pool、connection pool。优化方向包括把慢外部调用放锁外、缩短事务、确保 account lookup 走索引、调优连接池，必要时做账户级限流或读模型缓存，但不能牺牲额度一致性。

深挖版：Q49（QPS 增加 10 倍先改哪里）；排查手順见第 12 节排障题。


## 2. JD 问题回答模板

### Q1：这个项目和 PayPay Card Backend Engineer JD 最相关的地方是什么？

答：

> 最相关的是金融后端的 transaction correctness 和 distributed reliability。
> 我实现了 credit card issuer backend 的 authorization、posting、statement、repayment 主链路；
> 用 MySQL unique constraint 和 `FOR UPDATE` 处理幂等和额度并发；
> 用 Kafka + Outbox/Inbox 处理异步事件和重复投递；
> 用 Redis/Caffeine cache 做低风险 snapshot 读扩展；
> 并用 Actuator、JVM/thread 文档和 worker backlog 状态解释运行时观测。

### Q2：你有没有 distributed cache 经验？

答：

> 有，在项目里我做了 Caffeine L1 + Redis L2 的 statement GET cache。
> 它是 cache-aside：先查本地 Caffeine，再查 Redis，miss 后回源 MySQL 并写回缓存。
> 我只缓存可从 DB 重建的 statement read model；旧版 card reference data cache 已删除，因为 stale card status 会影响授权判断。
> 额度、幂等 claim 和还款入账不缓存，因为这些必须依赖 DB transaction 和 row lock。

### Q3：你如何解释 NoSQL 经验不足？

答：

> 当前项目没有使用 NoSQL，因为核心交易路径需要强事务、row lock、unique constraint 和审计。
> 但我知道 NoSQL 更适合 access pattern 明确、可重建或 eventual consistency 的 read model，
> 例如 notification feed、statement summary、event query projection。
> 如果用 DynamoDB，我会先设计 partition key/sort key、conditional write、TTL、GSI 和 replay 策略，
> 不会把 credit account balance 这种强一致数据直接迁过去。

### Q4：你如何设计高流量授权接口？

答：

> 我会先保证 correctness，再优化 throughput。授权请求先做 validation 和 idempotency claim，
> 便宜的 policy check、card check 和 risk check 放在 account row lock 前，减少 critical section。
> 真正额度变化时用 `SELECT ... FOR UPDATE` 锁 account row。Kafka publish、notification
> 都走 Outbox/Kafka 异步。读模型用 cache，worker pool 有界，Kafka consumer 按 partition 扩展。
> 监控上看 p95/p99、DB lock wait、connection pool、Kafka lag、Outbox backlog 和 cache hit ratio。

### Q5：如果部署到 AWS，你会怎么做？

答：

> Spring Boot app 可以容器化后部署到 ECS service，前面挂 ALB。
> MySQL 对应 RDS，Redis 对应 ElastiCache，Kafka 对应 MSK 或托管 Kafka。
> CloudFormation 管 VPC、security group、RDS、ElastiCache、ECS task/service、ALB、CloudWatch log group。
> CodePipeline 从 GitHub 触发，CodeBuild 构建镜像并推到 ECR，再部署 ECS。
> Observability 用 CloudWatch Logs、ECS/ALB/RDS/Redis/Kafka metrics，以及 Actuator/Micrometer 指标。

### Q6：为什么当前项目不拆微服务？

答：

> 因为这是一条 money-changing issuer backend 学习链路，authorization、posting、statement、repayment
> 需要先把事务、锁顺序、幂等和状态机讲清楚。当前项目用 modular monolith 保持本地 transaction 简单，
> 同时用 package boundary、domain event、Outbox contract、consumer group 和 Inbox 模拟未来服务边界。
> 真拆微服务时，难点会变成 distributed transaction、contract versioning、observability 和部署复杂度。

### Q7：你如何证明自己理解 concurrency？

答：

> 我会从三个层次讲。第一，单请求内用 transaction boundary 保证一起提交或一起 rollback。
> 第二，多请求并发靠数据库 unique constraint 和 `FOR UPDATE`，不是 Java 内存锁。
> 第三，异步 worker 靠 `FOR UPDATE SKIP LOCKED`、PROCESSING lease、recoverer 和幂等 handler 防重复处理。
> 这三个层次分别解决 API retry、账户热点并发、后台任务和 Kafka 重复投递。

## 3. 深挖题库：业务主链路

### Q8：为什么这个项目叫 issuer backend，而不是普通 payment backend？

强回答：

> 因为它站在发卡方 issuer 视角，不是商户收单 acquirer 视角。
> 授权阶段回答“这张卡和这个账户是否允许这笔消费”，posting 阶段处理商户请款后的入账，
> statement 阶段生成持卡人账单，repayment 阶段处理还款和恢复额度。
> 所以项目里的核心对象是 `Authorization`、`CreditAccount`、`CardTransaction`、`Statement`、`Repayment`，
> 而不是 merchant order 或 capture/refund first 的模型。

追问方向：

- Authorization 和 posting 的区别是什么？
- 为什么不是授权成功就直接入账？
- 为什么 presentment 不叫 capture？

容易踩坑：

- 把 issuer 和 merchant/acquirer 混在一起。
- 把 authorization 当成最终扣款。
- 用电商订单模型解释信用卡账务。

### Q9：authorization 成功后，钱到底发生了什么变化？

强回答：

> authorization 成功后不是 posted balance 增加，而是 `reservedAmount` 增加。
> 这代表发卡方临时 hold 额度，减少 available credit，但还不是正式账单债务。
> 只有 presentment posting 到达后，才把 hold 转成 posted balance，并创建 posted `CardTransaction`。

项目锚点：

- `AuthorizationService.authorize(...)`
- `CreditAccount.reserve(...)`
- `AuthorizationStatus.APPROVED`
- `AuthorizationExpiryDelayJobScheduler`

追问方向：

- 如果商户最后没有请款怎么办？
- 如果 presentment 金额和 authorization 不一致怎么办？
- 为什么需要 authorization expiry？

强补充：

> 当前项目支持 full presentment，金额必须匹配；真实系统可能支持 partial presentment、incremental authorization、
> reversal、adjustment，这些会让状态机更复杂。学习项目先保留主链路，避免把核心并发和事务讲散。

### Q10：presentment posting 为什么必须有自己的 idempotency key？

强回答：

> authorization 的 `Idempotency-Key` 是客户端 API retry key。
> presentment 来自外部 network/clearing 记录，它的重复通常来自文件重放、消息重投或上游补发。
> 所以 posting 更自然的幂等键是 `network_transaction_id`，它代表外部业务事实的身份。

项目锚点：

- `PostPresentmentCommand.networkTransactionId`
- `card_transactions.network_transaction_id`
- `PostingService.post(...)`

追问方向：

- 如果同一个 `network_transaction_id` 对应不同 amount 怎么办？
- 如果 authorization 已过期后 presentment 才到怎么办？
- 如果两个 worker 同时处理同一条 presentment 怎么办？

强补充：

> 幂等键不仅要防重复成功，还要防“同 key 不同内容”。所以项目里 authorization 有 fingerprint，
> presentment 也应该在冲突时检查 amount、currency、authorizationId 是否一致。

### Q11：为什么 statement generation 不是一个简单 SUM 查询？

强回答：

> 账单是一个时间点的正式快照，不是每次查询实时计算的临时结果。
> 如果只做 SUM，后续交易修正、退款、入账状态变化可能导致同一个账单页面前后不一致。
> 所以项目创建 `statement` 和 `statement_lines`，把本周期 posted transactions 固定下来。

项目锚点：

- `StatementGenerationService.generate(...)`
- `statement_lines`
- `CardTransaction.statementId`

追问方向：

- 如果账单生成过程中又有 posting 到达怎么办？
- 为什么同周期只能有一张账单？
- 账单生成失败一半怎么办？

强补充：

> 项目通过 transaction boundary 和 row lock 保证同一周期账单快照的一致性。
> 同账户同周期自然键防止重复生成。
> 这比每次 GET 临时汇总更适合审计和客户争议处理。

### Q12：repayment 为什么同时更新 statement 和 credit account？

强回答：

> repayment 是还款入账，它会减少持卡人的应还款，同时恢复可用额度。
> 在项目里，这体现为 `Statement.paidAmount/status/version` 前进，同时 `CreditAccount.postedBalance` 减少。
> 这两个变化必须在同一个 transaction boundary 内提交，否则会出现账单显示已还但额度没恢复，或反过来的不一致。

项目锚点：

- `RepaymentService.receive(...)`
- `Statement.applyRepayment(...)`
- `CreditAccount.applyRepayment(...)`

追问方向：

- 如果还款金额超过剩余应还怎么办？
- 两笔还款同时进来怎么办？
- 自动扣款失败怎么办？

强补充：

> 当前项目不支持 overpayment，这是为了保持学习范围清楚。
> 真实信用卡可能有 credit balance、退款抵扣、调整项，这些需要更完整 ledger 和 product policy。

### Q13：Notification 为什么不在主交易事务里直接创建？

强回答：

> 通知是下游 side effect，不应该影响 money-changing transaction。
> 如果授权成功但通知服务失败，不能回滚额度 hold。
> 项目把业务事实写入 Outbox，Kafka 发布后由 Notification bounded context 消费，再用 Inbox 防重复创建通知。

项目锚点：

- `AuthorizationNotificationListener`
- `CardTransactionNotificationListener`
- `RequestNotificationService`
- `ConsumerInboxRepository`

追问方向：

- 通知晚到能接受吗？
- Kafka 重复投递会不会发两条通知？
- Notification consumer 挂了怎么办？

强补充：

> Notification 是 eventual consistency。
> 它可以 retry、DLT、人工重放，但不应该破坏主交易的 ACID 边界。

### Q14：Risk 为什么放在 account row lock 之前？

强回答：

> 风控可能有外部调用、计算和网络延迟。
> 如果先锁 account row 再调用风控，同账户其他授权都会在锁上等待，扩大 critical section。
> 项目先做本地规则和外部风控，只有通过后才锁账户检查额度。

项目锚点：

- `RiskAssessmentService.assess(...)`
- `AuthorizationService.decideAndReserve(...)`
- `ExternalRiskGateway`
- Resilience4j CircuitBreaker

追问方向：

- 风控通过后，额度可能已经被别的请求占用怎么办？
- 外部风控不可用时是放行还是拒绝？
- 高风险小额交易是否一定拒绝？

强补充：

> 风控和额度是两个不同问题。
> 风控通过不代表额度仍然足够。
> 所以通过风控后仍然必须在 account lock 内重新计算 available credit。

### Q15：为什么项目不实现 Ledger？

强回答：

> Ledger 概念很重要，但当前项目主动不实现它。生产 Ledger 需要科目、balanced journal、
> 调整、冲正、审计、reconciliation 和报表；只有单边 DEBIT/CREDIT 标签的异步投影容易冒充
> accounting source of truth。项目用 CardTransaction、Statement、Repayment 讲清交易生命周期，
> 用文档解释 Ledger 边界，避免为了广度实现一个错误的会计模型。

项目锚点：

- `CardTransaction`
- `StatementLine`
- `Repayment`

追问方向：

- 为什么 CardTransaction 不是 ledger？
- 如果未来 Ledger 是 source of truth，transaction boundary 应如何设计？
- 为什么 Statement 不应依赖一个可选异步 projection？

强补充：

> CardTransaction 是客户可见交易流水，Ledger 更接近内部会计事实。
> 当前用 Kafka projection 展示边界，避免把主交易事务拖得过重。

## 4. 深挖题库：幂等、事务、锁

### Q16：你说 authorization 幂等，具体怎么保证？

强回答：

> Controller 要求 `Idempotency-Key`。
> Service 先构造 PENDING authorization，然后通过 `authorizationRepository.claim(...)`
> 走数据库唯一约束抢占幂等所有权。
> winner 继续做决策；loser 读取同 key 的已有 authorization。
> 之后用 request fingerprint 判断同 key 是否对应同一请求。

项目锚点：

- `AuthorizationCommand.requestFingerprint()`
- `AuthorizationService.authorize(...)`
- `authorizations.idempotency_key`
- `IdempotencyConflictException`

追问方向：

- 如果两个请求同时 insert 怎么办？
- 如果同 key 不同 body 怎么办？
- 如果 winner 还没提交，loser 读取什么？

硬核补充：

> 真正的并发安全点不是 Java if 判断，而是数据库 unique constraint。
> 应用层 check-then-insert 在多实例下会 race。
> 所以要 insert-first claim，把 winner 选择交给数据库。

### Q17：为什么 duplicate request 要 `FOR UPDATE` 读取 winner？

强回答：

> 因为 duplicate 可能在 winner 还没完成决策时到达。
> `findByIdempotencyKeyForUpdate(...)` 锁住 authorization row，可以让 duplicate 等到 winner transaction 完成，
> 然后读取最终状态，避免返回半成品 PENDING。

追问方向：

- 这会不会降低并发？
- 可以直接 read committed 读吗？
- 客户端一直重试会怎样？

强补充：

> 它只串行化同一个 idempotency key，不会串行化所有授权。
> 这是精确锁定业务冲突范围，而不是全局锁。

### Q18：为什么不用 Redis `SETNX` 做 authorization 幂等？

强回答：

> Redis `SETNX` 可以做请求去重或短期防抖，但不适合作为这个项目的最终金融幂等边界。
> 因为授权状态、额度变化、Outbox、DelayJob 都在 MySQL transaction 里。
> 如果幂等 winner 在 Redis，业务状态在 MySQL，就会出现跨资源一致性问题。

追问方向：

- Redis 更快，为什么不用？
- Redis 宕机怎么办？
- 可以用 Redis lock 吗？

强补充：

> 可以把最终结果放 Redis 做 retry storm 的 read shortcut，
> 但 source of truth 仍然必须是 MySQL unique constraint 和 transaction。
> Redis cache miss 或 Redis 故障不能改变是否重复占额度。

### Q19：`SELECT ... FOR UPDATE` 锁住的到底是什么？

强回答：

> 在 InnoDB 里，`FOR UPDATE` 会对查询命中的索引记录加排他锁。
> 项目里锁的是具体业务行，例如 `credit_accounts.id = ?`。
> 这样同一个 account 的额度变化串行，不同 account 可以并行。

追问方向：

- 如果没有索引会怎样？
- gap lock 是什么？
- read committed 和 repeatable read 有什么影响？

强补充：

> `FOR UPDATE` 是否精确，取决于 where condition 和索引。
> 如果查询走不到合适索引，可能扫描并锁住更多范围，导致高并发下锁等待扩大。
> 所以锁设计必须和索引设计一起 review。

### Q20：如果两个不同 authorization 同时刷同一个 account，会不会超额？

强回答：

> 不会，只要所有额度变化都经过 `creditAccountRepository.findByIdForUpdate(...)`。
> 第一个 transaction 锁住 account row，计算 available credit 并更新 reserved amount。
> 第二个 transaction 等第一个 commit 后，再读取最新余额重新计算。
> 所以不会基于旧余额同时批准。

追问方向：

- 如果请求落到不同 app instance 呢？
- 如果第二个请求先通过风控呢？
- 如果 DB transaction timeout 呢？

强补充：

> 这就是为什么不能靠 JVM lock。
> 多实例部署时，共享事实在 DB，锁也必须在 DB。

### Q21：你如何设计 lock order，避免 deadlock？

强回答：

> 首先识别每条 money-changing path 会锁哪些表。
> 然后固定顺序，避免 A 先锁 account 后锁 statement，而 B 先锁 statement 后锁 account。
> 项目里 statement generation、posting、repayment 都要小心 lock order。

项目锚点：

- `PostingService`
- `StatementGenerationService`
- `RepaymentService`
- mapper XML 中的 `FOR UPDATE`

追问方向：

- 如果死锁仍然发生怎么办？
- MySQL deadlock 会自动回滚哪个 transaction？
- 应用层要不要 retry？

强补充：

> 生产系统即使设计了 lock order，也要准备 deadlock retry。
> retry 必须依赖 idempotency，否则重试本身可能造成重复副作用。

### Q22：`@Transactional` 放在哪里最合适？

强回答：

> 放在 application service 的 use case 边界。
> Controller 不开启业务事务，domain object 不知道事务，repository 只执行持久化。
> `AuthorizationService.authorize(...)` 这种方法天然是 transaction boundary。

追问方向：

- 为什么不是每个 repository 方法一个事务？
- self-invocation 会有什么问题？
- async worker 里事务怎么拆？

强补充：

> Outbox/DelayJob worker 里会显式用 `TransactionOperations` 拆分 claim、handler、finalize。
> 这是为了避免长事务包住 Kafka ack 或业务 handler 后的 finalize。

### Q23：哪些东西必须和 authorization 同事务提交？

强回答：

> authorization 状态、credit account reserved amount、authorization expiry DelayJob、authorization Outbox event。
> 如果 authorization APPROVED 但没有 DelayJob，hold 可能永远不释放。
> 如果状态变了但没有 Outbox event，下游 Notification 会丢业务事实。

追问方向：

- Kafka publish 是否同事务？
- Notification 是否同事务？
- 外部风控是否同事务？

强补充：

> Kafka publish 不同事务。
> 主事务只写 durable publish intent，也就是 Outbox row。
> 真正发送 Kafka 由 worker 在 commit 后完成。

### Q24：为什么不能把 Kafka publish 放进 DB transaction？

强回答：

> 因为 Kafka ack 是外部网络 I/O。
> 如果在 DB transaction 内等待 broker ack，会延长锁持有时间，放大 DB lock contention。
> 而且 MySQL 和 Kafka 不是同一个本地 ACID transaction，仍然解决不了真正 atomic commit。

追问方向：

- Kafka transaction 能不能解决？
- 如果 Kafka 发送失败怎么办？
- 如果 DB commit 成功后应用宕机怎么办？

强补充：

> Outbox 的设计就是 DB commit 成功后，即使应用宕机，Outbox row 仍然在。
> worker 恢复后可以继续 publish。

### Q25：什么是 check-then-insert race？

强回答：

> 先查有没有，再决定插入，在并发下不安全。
> 两个 transaction 可能都查到不存在，然后都尝试插入。
> 只有数据库 unique constraint 才能最终决定谁成功。

项目应用：

- authorization idempotency claim。
- repayment idempotency claim。
- card transaction network id claim。
- notification source event unique。

追问方向：

- 应用层加 synchronized 是否能解决？
- 唯一约束冲突后怎么处理？
- 为什么 insert-first 更好？

强补充：

> 在多实例环境里，应用内锁无法覆盖所有请求。
> 数据库约束是最后一道一致性防线。

### Q26：如何处理 transaction isolation level？

强回答：

> 这个项目主要依赖显式 `FOR UPDATE` 和 unique constraint，而不是依赖幻读避免所有问题。
> 对 money-changing row，用 `FOR UPDATE` 锁住具体记录。
> 对自然幂等键，用 unique constraint 防重复。
> isolation level 仍重要，但不能代替业务锁和约束。

追问方向：

- Repeatable Read 下有什么现象？
- Read Committed 能不能用？
- Gap lock 会不会影响吞吐？

强补充：

> 我会尽量用主键或唯一索引精确锁行，避免范围锁扩大。
> 对 batch claim 使用 `SKIP LOCKED`，避免 worker 互相等待。

### Q27：如果 transaction 提交后 response 丢了，客户端重试怎么办？

强回答：

> 客户端用同一个 `Idempotency-Key` 重试。
> 服务端通过 unique constraint 找到已有 authorization，并校验 fingerprint。
> 如果请求相同，返回原结果。
> 如果请求不同，返回 conflict。

追问方向：

- 如果第一次其实失败了呢？
- 如果第一次还在处理中呢？
- 幂等结果要保存多久？

强补充：

> 生产里需要 idempotency key retention policy。
> 金融交易通常不能太短，否则 retry 窗口外可能重复处理。
> 当前项目通过 authorization row 保留审计记录。

## 5. 深挖题库：Kafka、Outbox、Inbox、DelayJob

### Q28：Outbox 解决了什么问题？

强回答：

> Outbox 解决业务 DB 状态和消息发布之间的 dual-write 问题。
> 如果业务状态 commit 了但 Kafka send 失败，下游会丢事件。
> 如果先 send Kafka 再 DB rollback，下游会看到不存在的业务事实。
> Outbox 把 publish intent 作为 DB row 和业务状态同事务提交。

追问方向：

- Outbox 能保证 exactly-once 吗？
- Outbox row 什么时候删除？
- Outbox backlog 上升怎么办？

强补充：

> Outbox 通常提供 at-least-once publish。
> Consumer 仍然必须幂等。

### Q29：Inbox 解决了什么问题？

强回答：

> Inbox 解决 consumer side effect 的重复执行问题。
> Kafka 是 at-least-once，consumer 处理成功但 offset commit 前宕机，消息会再次投递。
> Inbox 用 `(consumerName, eventId)` claim，保证同一个 consumer 对同一事件只执行一次业务副作用。

项目锚点：

- `ConsumerInboxRepository`
- `RequestNotificationService`

追问方向：

- 每个 consumer 都共用一个 inbox 吗？
- 同一个 event 被不同 bounded context 消费怎么办？
- Inbox row 要保留多久？

强补充：

> Inbox key 要包含 consumer name。
> consumer name 是 Notification 的持久消费身份；未来新下游应使用自己的 consumer name。

### Q30：Outbox worker publish 成功后，markPublished 前宕机怎么办？

强回答：

> Kafka 可能已经收到消息，但 Outbox row 仍然是 PROCESSING。
> recoverer 会在 lease timeout 后把它放回可处理状态，worker 可能再次 publish。
> 所以下游必须按 eventId 幂等。

追问方向：

- 会不会发两条 Kafka 消息？
- 下游怎么防重复？
- 能不能避免重复 publish？

强补充：

> 这是 at-least-once 的典型 trade-off。
> 要完全避免重复需要更复杂的 exactly-once 端到端设计，但 MySQL side effect 仍要自己幂等。

### Q31：为什么 Outbox/DelayJob 都有 PROCESSING lease？

强回答：

> PROCESSING 不是终态，而是“某个 worker 暂时拥有处理权”。
> lease 防止多个 worker 同时处理同一条 row。
> 如果 worker 宕机，recoverer 根据超时把 stuck row 放回 retry。

项目锚点：

- `OutboxWorker.lockCurrentLease(...)`
- `DelayJobWorker.lockCurrentLease(...)`
- `OutboxRecoverer`
- `DelayJobRecoverer`

追问方向：

- lease timeout 怎么设？
- 旧 worker 回来会不会覆盖新 worker？
- 为什么 finalize 前要重新 `FOR UPDATE`？

强补充：

> worker finalize 前重新锁 row，并比较 claimed lease。
> 如果 lease 已变化，旧 worker 不能覆盖新 worker 状态。

### Q32：Outbox 和 DelayJob 的区别是什么？

强回答：

> Outbox 是发布已经发生的业务事实，例如 `authorization.approved`。
> DelayJob 是计划未来要执行的业务动作，例如 authorization expiry 或 auto repayment。
> 两者都需要 retry、lease、recoverer，但语义不同。

追问方向：

- 为什么 authorization expiry 不用 Kafka delayed message？
- 为什么 notification 不用 DelayJob？
- 两者是否能合并成一个 job 表？

强补充：

> 结构可以相似，语义不要混。
> Outbox 是 reliable publication。
> DelayJob 是 future business action scheduling。

### Q33：Kafka consumer 什么时候 commit offset？

强回答：

> 项目关闭 auto commit，listener 成功返回后按 record ack 提交。
> 业务 side effect 写入 MySQL 后，listener 才算成功。
> 如果 side effect 成功但 offset commit 前宕机，会重复投递，由 Inbox 幂等处理。

追问方向：

- 如果 auto commit 开着会怎样？
- 如果处理失败怎么办？
- DLT 什么时候用？

强补充：

> Offset 不是业务处理证明。
> 业务是否处理过，要看本地 DB 的 Inbox 或业务唯一约束。

### Q34：Kafka partition key 怎么选？

强回答：

> partition key 应该跟顺序需求相关。
> 如果同一 authorization 的状态事件必须有序，就用 authorizationId。
> 如果同一 account 的账务事件要局部有序，可以考虑 accountId。
> 不能只为了均匀随机选择 key，而忽略业务 ordering。

追问方向：

- partition 数量如何影响 consumer concurrency？
- 热点 key 怎么办？
- 全局有序是否需要？

强补充：

> Kafka 只能保证同 partition 内有序。
> 全局有序成本很高，通常不需要。
> 要先定义 aggregate 内顺序需求。

### Q35：event version 为什么重要？

强回答：

> Kafka event 是跨 bounded context 的 contract。
> producer 和 consumer 可能独立演进。
> eventVersion 可以让 consumer 明确支持哪些 schema，遇到未知版本时进入错误路径，而不是静默解析错。

项目锚点：

- `IntegrationEventReader`
- `InvalidIntegrationEventException`
- message reader validation

追问方向：

- 加字段是否兼容？
- 删除字段怎么办？
- DLT 后如何重放？

强补充：

> 事件演进要遵循 backward/forward compatibility。
> 对必填语义变化，宁可升版本。

### Q36：DLT 是什么，不是什么？

强回答：

> DLT 是 dead-letter topic，用来隔离反复处理失败的消息，避免一个坏消息阻塞整个 consumer。
> 它不是自动修复机制，也不是 exactly-once 保证。
> 进入 DLT 后需要报警、排查、修复数据或代码，再人工/工具化重放。

追问方向：

- 哪些错误应该 retry，哪些直接 DLT？
- DLT 消息保留多久？
- 重放如何避免重复副作用？

强补充：

> 重放仍然要依赖 Inbox/unique constraint 幂等。
> 否则 DLT 重放会变成新的重复执行风险。

### Q37：Kafka backlog 上升，你怎么排查？

强回答：

> 先看是 producer 侧 Outbox backlog，还是 consumer lag。
> Outbox backlog 上升可能是 Kafka broker 慢、worker pool 满、DB claim 慢或 publish timeout。
> Consumer lag 上升可能是 listener 业务慢、DB 写慢、错误重试、partition 数不足或下游锁等待。

排查顺序：

1. 看 Outbox `PENDING/PROCESSING/DEAD` 数量。
2. 看 worker thread 是否都在等 Kafka ack。
3. 看 Kafka broker/network metrics。
4. 看 consumer group lag。
5. 看 consumer DB side effect latency。
6. 看 DLT 数量和错误类型。

强补充：

> 不要第一反应把 concurrency 调很大。
> 如果瓶颈在 DB 或外部服务，盲目加 consumer 会放大压力。

### Q38：如果 consumer 乱序收到事件怎么办？

强回答：

> 先看是否真的要求顺序。
> 对同一 aggregate 的强顺序需求，应通过 partition key 保证同 partition。
> Consumer 侧仍要校验当前状态，不能假设消息永远按理想顺序到达。

追问方向：

- `authorization.posted` 比 `authorization.approved` 先到怎么办？
- Notification 是否需要强顺序？
- production Ledger 是否需要顺序？

强补充：

> 对 notification 这类用户展示 side effect，可能可以按事件独立处理。
> 对 ledger/accounting，需要更严格的 source event ordering 或状态校验。

## 6. 深挖题库：Cache、Redis、NoSQL

### Q39：你们为什么不用 Spring `@Cacheable`？

强回答：

> 项目选择显式 `StatementReadService`，因为我们想把 cache boundary 写清楚。
> 哪些对象允许缓存、TTL、L1/L2、evict after commit、fallback 行为都在业务可读的位置。
> `@Cacheable` 很方便，但容易让写路径和一致性边界变得隐式。

追问方向：

- 显式 cache 会不会代码更多？
- 为什么不缓存 repository 所有 findById？
- 如何保证 evict？

强补充：

> 金融系统里 cache 是 correctness-sensitive trade-off。
> 我宁愿多写一点显式代码，也不要让 cache annotation 随意散落在核心写路径。

### Q40：L1 Caffeine 和 L2 Redis 分别解决什么？

强回答：

> Caffeine L1 解决同 JVM 内热点读取，延迟低，不走网络。
> Redis L2 解决多实例共享和跨 pod cache 命中。
> L1 TTL 更短，因为多 pod 下本地 cache 不会天然互相失效。

追问方向：

- Redis 宕机怎么办？
- 多 pod L1 如何失效？
- L1 最大容量如何设置？

强补充：

> 项目里 Redis 故障会 fallback 到 DB。
> 对高风险 stale 场景，靠短 L1 TTL、after-commit evict、未来 Pub/Sub/Kafka invalidation 降低风险。

### Q41：为什么 Redis TTL 要 jitter？

强回答：

> 如果大量 key 同时写入并设置相同 TTL，它们可能在同一时间过期。
> 下一波请求会同时 miss，打到 DB，形成 cache avalanche。
> TTL jitter 让过期时间分散。

追问方向：

- jitter 多大合适？
- 还有什么防 cache avalanche 方法？
- 热点 key 失效怎么办？

强补充：

> 对超热点 key，可以考虑 single-flight、Redis mutex、预热、异步刷新。
> 项目当前先用 L1 single-flight 和 Redis TTL jitter 保持简单。

### Q42：为什么不做 negative cache？

强回答：

> 项目里 `card-not-found` 和 `statement-not-found` 暂时不做 negative cache。
> 因为学习环境和未来发卡/补数据场景里，一个刚刚不存在的 id 可能很快变成存在。
> 缓存 404 会增加新数据可见性的 stale risk。

追问方向：

- 如果有人恶意请求不存在 cardId 打 DB 怎么办？
- negative cache 完全不能用吗？
- TTL 设很短是否可以？

强补充：

> 生产里可以对明显攻击流量做短 TTL negative cache 或 rate limit。
> 但要和新数据创建路径、授权风险和安全策略一起设计。

### Q43：Card snapshot cache 有什么风险？为什么项目删掉了它？

强回答：

> Card snapshot 会参与 authorization 决策，所以风险高于纯展示型 cache：
> stale ACTIVE 可能让刚 blocked 的卡在 TTL 窗口内继续通过 card lifecycle check。
> 项目最终删掉了这层 cache——授权路径改为直接读 DB 里的卡状态。
> 保留它的前提是 TTL 足够短、block/unblock API 严格 after-commit evict，
> 而卡状态查询本身是主键点查，DB 压力不大，收益撑不起这套一致性维护成本。

追问方向：

- 如果业务要求 block 后立即全局生效怎么办？
- 只删除 Redis 是否够？
- 本地 L1 怎么办？

强补充：

> 严格场景可以在 block/unblock 后发布 cache invalidation event，
> 各 pod 清理 L1；或者高风险卡状态检查 bypass cache。

### Q44：Statement read model cache 为什么比 Card cache 风险低？

强回答：

> Statement read model 主要服务 GET 展示。
> 账单明细和 total amount 是出账时的审计快照，相对稳定。
> 还款会改变 paid amount/status，所以 repayment 后 after-commit evict。

追问方向：

- 如果还款后用户马上刷新看到旧状态怎么办？
- 为什么 after commit evict？
- 如果 evict 失败怎么办？

强补充：

> after-commit evict 避免事务提交前清 cache 后，另一个线程读旧 DB 并重新写入 Redis。
> evict 失败时 TTL 兜底，DB 仍是 source of truth。

### Q45：Redis 里的 JSON schema 变了怎么办？

强回答：

> 项目 cache name 带版本，例如当前 `statement-read-model-v3`。
> 如果 value schema 或 version 语义不兼容，升级 cache name，让旧 key 自然过期。
> 如果只是兼容新增字段，可以用默认值或兼容 reader。

追问方向：

- 大规模 key 如何清理？
- 反序列化失败怎么办？
- 是否需要 cache migration？

强补充：

> `StatementReadService` 遇到 Redis JSON 损坏会删除坏 value 并回源 DB。
> 这比让坏缓存持续影响请求更安全。

### Q46：NoSQL 在这个项目里最适合放哪里？

强回答：

> 适合放可重建、访问模式明确、eventual consistency 可接受的 read model。
> 例如 notification feed、statement summary、event query projection、operational dashboard。
> 不适合替代 `credit_accounts` 这种需要 row lock 和事务的核心写模型。

追问方向：

- DynamoDB partition key 怎么选？
- Hot partition 怎么办？
- GSI 有什么代价？

强补充：

> NoSQL 设计从 query/access pattern 出发。
> 不能像关系型数据库那样先随便建表再 join。

### Q47：DynamoDB conditional write 能不能替代 MySQL unique constraint？

强回答：

> 在某些单 item 幂等场景，DynamoDB conditional write 可以实现 first-write-wins。
> 但当前项目的 authorization 不只是写一条幂等记录，还要同时更新 account balance、Outbox、DelayJob。
> 这些多实体强一致更新更适合 MySQL transaction。

追问方向：

- DynamoDB transaction 可以用吗？
- 为什么不用 Saga？
- 如果拆服务怎么办？

强补充：

> DynamoDB transaction 有场景，但成本、限制、建模方式和运维指标都不同。
> 不能只因为 JD 提到 NoSQL 就替换核心账务模型。

### Q48：Cache 和 CQRS/read model 有什么关系？

强回答：

> CQRS/read model 是为查询建模的数据视图。
> Cache 是加速读取的一层。
> Statement read model 可以被 cache，但 cache 不是 read model 本身，更不是 source of truth。

追问方向：

- Statement read model 的 source of truth 是什么？
- Production Ledger 的查询视图能 cache 吗？
- Cache miss 如何重建？

强补充：

> Statement read model 可以有自己的查询和缓存优化，但它仍以 MySQL 业务表为 source of truth。

## 7. 深挖题库：High traffic 和性能设计

### Q49：如果授权 QPS 增加 10 倍，你先改哪里？

强回答：

> 我不会先改代码，而是先找瓶颈。
> 看 p95/p99、DB lock wait、connection pool、external risk latency、cache hit ratio、Outbox backlog。
> 如果瓶颈在 external risk，加 timeout、bulkhead、cache/rule precheck 或独立扩容。
> 如果瓶颈在 account lock，说明热点账户或锁内逻辑太长。
> 如果瓶颈在 DB connection pool，要看慢 SQL 和连接占用时间。

追问方向：

- 加实例是否一定有用？
- DB 是单点瓶颈怎么办？
- 如何做限流？

强补充：

> 加 app instance 只能扩无共享计算。
> 如果瓶颈是同一 account row lock，加实例不会让同一账户并发写变快。

### Q50：如何估算线程池和 DB connection pool？

强回答：

> 用 Little's Law 做粗估：并发数约等于吞吐乘以平均响应时间。
> 如果 authorization 100 RPS，平均 100ms，同步处理并发大约 10。
> 但要按 p95/p99、外部依赖慢、DB lock wait 预留 buffer。
> DB pool 不能无限大，否则会把压力推给 MySQL。

追问方向：

- Tomcat threads 比 DB connections 多会怎样？
- Kafka listener concurrency 怎么配？
- Worker queue 为什么要 bounded？

强补充：

> 线程数、DB pool、Kafka concurrency、worker pool 要一起看。
> 任一层无界都会在故障时放大延迟和内存压力。

### Q51：Backpressure 在这个项目里怎么体现？

强回答：

> Outbox/DelayJob worker pool 有固定大小和 queue capacity。
> poller submit 被拒绝时，不是无限创建线程或无限堆内存，而是 mark failed 进入 retry/DEAD。
> 这是一种后台任务层面的 backpressure。

追问方向：

- API 层有没有 backpressure？
- Kafka consumer lag 上升算不算 backpressure？
- retry 会不会造成风暴？

强补充：

> API 层可以加 rate limit、bulkhead、timeout。
> Kafka 层可以通过 lag、pause/resume、DLT 和重试策略控制压力。

### Q52：如何处理热点账户？

强回答：

> 热点账户的本质是同一 account row 必须串行更新。
> 可以优化锁内逻辑，但不能为了吞吐牺牲额度正确性。
> 策略包括账户级限流、风险规则前置、热点监控、异步排队或产品层限制。

追问方向：

- 能不能把账户余额拆 shard？
- 能不能用 Redis atomic counter？
- 能不能最终一致？

强补充：

> 信用额度授权是实时强一致决策。
> 如果允许最终一致，可能批准超过 credit limit。
> 除非业务明确接受 over-authorization 风险，否则不能这么做。

### Q53：高流量下 Kafka partition 数怎么考虑？

强回答：

> partition 数限制同一个 consumer group 的最大并行度。
> 但 partition key 又决定局部顺序。
> 设计时要在吞吐、顺序、热点 key、未来扩容之间平衡。

追问方向：

- partition 数能不能随便增加？
- 增加 partition 会影响 key ordering 吗？
- consumer concurrency 超过 partition 数有用吗？

强补充：

> 对已有 topic 增加 partition 可能改变 key 到 partition 的映射。
> 如果依赖顺序，需要谨慎。

### Q54：如何判断该读写分离？

强回答：

> 先看读写比例和一致性要求。
> money-changing path 不能随便读 replica，因为 replica lag 会导致基于旧状态决策。
> 查询型 read model 可以考虑读 replica 或 cache。

追问方向：

- GET authorization 能读 replica 吗？
- Statement GET 能读 replica 吗？
- replica lag 怎么监控？

强补充：

> 对授权、还款、posting 这种写路径，source of truth 应该读 primary 或在事务内锁 primary row。
> 对低风险展示查询，cache/read replica 可以作为优化。

### Q55：如何设计限流？

强回答：

> 限流要按业务风险分层。
> 可以按 client、cardId、accountId、merchantId、IP 或 API key 限制。
> 对 authorization，账户级和商户级限流比全局限流更有意义。

追问方向：

- 限流状态存在 Redis 还是本地？
- 被限流返回什么？
- 如何避免误伤？

强补充：

> 本地限流只保护单实例。
> 多实例全局限流通常需要 Redis、gateway 或 service mesh。
> 但限流不能替代 idempotency 和 row lock。

### Q56：如何做容量压测？

强回答：

> 先定义目标：authorization p95、p99、error rate、DB CPU、lock wait、Outbox lag。
> 再设计场景：均匀 cardId、热点 account、external risk 慢、Kafka 慢、Redis 失败。
> 压测不是只看最大 QPS，而是看瓶颈和退化行为。

追问方向：

- k6/Gatling 脚本怎么写？
- 如何避免压测污染数据？
- 如何验证幂等？

强补充：

> 金融系统压测要包含重复请求和并发同账户请求。
> 否则看不出幂等和锁设计是否真的可靠。

### Q57：Redis 失败会不会拖慢主请求？

强回答：

> 项目设置 Redis timeout，并在 `StatementReadService` 里 catch Redis read/write failure。
> Redis 失败时回源 DB，不让低风险 cache 影响业务可用性。
> 但如果 Redis 长时间失败，DB 读压力会上升，需要告警。

追问方向：

- Redis timeout 设多长？
- fallback 到 DB 会不会打爆 DB？
- 是否需要 circuit breaker？

强补充：

> 生产里可以对 Redis client 加短 timeout、metrics、circuit breaker 或降级开关。
> cache failure 要可观测。

### Q58：如何看待 JVM GC 对高流量的影响？

强回答：

> RPS 上升会提高 allocation rate。
> 如果外部依赖或 DB lock 变慢，请求对象存活时间变长，可能进入 old generation。
> GC pause 会影响 API latency、Kafka consumer poll 和 worker throughput。

项目锚点：

- `docs/jvm-threads-runtime-cn.md`
- Actuator JVM metrics

追问方向：

- 看哪些指标？
- G1 和 ZGC 怎么选？
- Thread dump 里 DB lock 是 Java BLOCKED 吗？

强补充：

> 等 MySQL row lock 的线程通常在 JDBC/socket 调用里，不一定显示 Java `BLOCKED`。
> 要结合 MySQL lock wait、slow query、Hikari metrics。

## 8. 深挖题库：AWS、部署、观测、CI/CD

### Q59：把这个项目部署到 ECS，你会怎么拆资源？

强回答：

> 我会拆成 network、data、app、pipeline 几个 CloudFormation stack。
> Network 管 VPC/subnet/security group。
> Data 管 RDS MySQL、ElastiCache Redis、MSK 或 Kafka dependency。
> App 管 ECS cluster、task definition、service、ALB、target group、log group。
> Pipeline 管 GitHub trigger、CodeBuild、ECR、ECS deploy。

追问方向：

- Secret 怎么管理？
- DB migration 谁执行？
- Blue/green 怎么做？

强补充：

> DB password 不应该写在 image 或 repo。
> 可以用 Secrets Manager/SSM Parameter Store 注入 ECS task。

### Q60：ECS health check 应该打哪个 endpoint？

强回答：

> Liveness 和 readiness 要分开。
> Liveness 判断进程是否活着，不应该依赖 MySQL/Kafka。
> Readiness 判断是否可以接流量，应该包含核心依赖，例如 MySQL。
> 项目已经有 Actuator liveness/readiness，并把 DB 放进 readiness。

追问方向：

- Kafka 不可用时 readiness 要不要 fail？
- Redis 不可用时 readiness 要不要 fail？
- ALB health check 用哪个？

强补充：

> 对 money-changing API，MySQL 不可用必须 not ready。
> Redis cache 不可用可以降级回 DB，不一定 fail readiness。
> Kafka 不可用时主交易可写 Outbox，但 backlog 会增长，是否 fail readiness 取决于业务策略。

### Q61：CloudWatch 你会看哪些指标？

强回答：

> API 层看 ALB target response time、5xx、ECS CPU/memory、task restart。
> JVM 看 heap、GC pause、threads、HTTP latency。
> DB 看 RDS CPU、connections、lock wait、deadlock、slow query。
> Redis 看 latency、eviction、hit/miss、connection。
> Kafka 看 consumer lag、broker health、produce latency。
> 业务看 Outbox/DelayJob backlog 和 DEAD count。

追问方向：

- 哪些指标要报警？
- 如何区分 app 慢和 DB 慢？
- 日志如何关联请求？

强补充：

> 技术指标要和业务指标一起看。
> 对信用卡授权，authorization p99 和 decline reason 分布也很关键。

### Q62：CodePipeline 失败怎么处理？

强回答：

> 首先 pipeline 要分阶段：build、test、image scan、deploy。
> 失败时不能影响当前线上 service。
> ECS deploy 可以用 rolling 或 blue/green。
> 失败要自动 stop deployment，并保留上一版 task definition。

追问方向：

- DB migration 在 deploy 前还是后？
- rollback 能不能回滚 schema？
- Kafka event schema 怎么发布？

强补充：

> DB migration 最危险。
> 应该尽量做 backward-compatible migration：先加字段，再双写/读兼容，再切流量，最后清理旧字段。

### Q63：如何设计日志？

强回答：

> 日志要能串起 request、transaction、Outbox event 和 consumer side effect。
> 关键字段包括 request id、idempotency key、authorization id、account id、event id、job id。
> 但不能把敏感卡号、密钥、完整个人信息打进日志。

追问方向：

- PII 怎么处理？
- 日志太多怎么办？
- 如何排查一笔交易？

强补充：

> 金融系统日志要在可追踪和数据保护之间平衡。
> 应该打业务 id 和状态变化，不打敏感明文。

### Q64：如何做 DB migration？

强回答：

> 项目使用 Liquibase 管 schema migration。
> 生产 migration 要考虑锁表、回滚、历史数据 backfill、应用版本兼容。
> 对大表变更，要避免长时间 exclusive lock。

追问方向：

- 改 enum/status 怎么做？
- 加非空字段怎么做？
- 回滚失败怎么办？

强补充：

> 加非空字段通常分步：先 nullable，加代码双写或默认值，backfill，验证，再加 not null constraint。
> 不要在高流量时直接做危险 DDL。

### Q65：如果新版本导致 authorization 500，你怎么 rollback？

强回答：

> 先通过 metrics/logs 确认错误范围和版本。
> 如果是 app bug，ECS 回滚到上一版 task definition。
> 如果 migration 已经不兼容，rollback 会复杂很多，所以 migration 应该 backward-compatible。
> 同时检查是否产生 partial side effect：authorization、Outbox、DelayJob 状态是否一致。

追问方向：

- 已经写入的坏数据怎么办？
- Outbox 里错误事件怎么办？
- 客户端重试怎么办？

强补充：

> rollback 不只是代码回退。
> 金融系统还要做数据修复、事件补偿和审计记录。

## 9. 深挖题库：DDD、OOP、架构演进

### Q66：这个项目哪里体现了 DDD？

强回答：

> DDD 体现在业务语言和 invariant 放在 domain object。
> 例如 `CreditAccount.reserve(...)` 负责额度规则，`Authorization.approve(...)` 负责状态转换，
> `Statement.applyRepayment(...)` 负责账单还款状态。
> Application service 负责 orchestration 和 transaction boundary。

追问方向：

- Repository 是 domain 还是 infrastructure？
- Domain event 谁产生？
- Outbox 是 domain 吗？

强补充：

> 我没有把所有东西都套成 DDD tower。
> Outbox/DelayJob 是 reliability mechanism，所以保持机制包更清楚。

### Q67：为什么 domain event 应该由 aggregate 产生？

强回答：

> 因为 event 应该反映真实发生的 state transition。
> 如果 service 根据参数拼 event，可能和 aggregate 实际状态不一致。
> Aggregate 在 `approve/post/expire` 这种方法里产生 event，再由 service pull 出来交给 Outbox。

追问方向：

- Event 是 domain event 还是 integration event？
- Event payload 放多少字段？
- Event 失败会不会回滚？

强补充：

> Domain event 是内存里的业务事实。
> Integration event 是跨边界 contract。
> Outbox adapter 负责转换和持久化 publish intent。

### Q68：为什么不直接让 domain object 调 repository？

强回答：

> Domain object 应该表达业务规则，不应该知道数据库、MyBatis、Redis、Kafka。
> Repository 调用和 transaction boundary 属于 application service orchestration。
> 这样 domain 更容易测试，也不会和 infrastructure 耦合。

追问方向：

- Domain service 什么时候需要？
- Aggregate 能不能跨 repository 查数据？
- Anemic domain model 怎么避免？

强补充：

> 不是所有逻辑都必须塞进 entity。
> 跨 aggregate orchestration 放 application service。
> 单 aggregate invariant 放 domain object。

### Q69：如果未来拆 microservices，你先拆哪个？

强回答：

> 我不会先拆 authorization 和 credit account，因为它们有强一致额度关系。
> 更适合先拆 Notification 这类异步下游。
> 它们已经通过 Kafka event contract 和 Inbox 与主交易解耦。

追问方向：

- Statement 能拆吗？
- Repayment 能拆吗？
- 拆后事务怎么办？

强补充：

> 先拆异步边界，后拆强事务边界。
> 拆强事务边界前要设计 Saga、补偿、幂等和可观测性。

### Q70：Modular monolith 有什么优缺点？

强回答：

> 优点是本地 transaction 简单、部署简单、调试容易，适合先把业务边界和正确性做清楚。
> 缺点是团队规模变大后可能出现代码边界腐化，部署粒度粗，资源隔离弱。
> 项目通过 package boundary、port、event contract 和文档降低这些风险。

追问方向：

- 如何防止模块互相乱依赖？
- 什么时候必须拆？
- 拆服务后如何测试？

强补充：

> 架构不是越分布式越高级。
> 对金融核心链路，错误拆分会把本地事务问题变成分布式一致性问题。

### Q71：如何评价这个项目的 OOP？

强回答：

> OOP 不只是类很多，而是把行为和规则放到合适对象。
> `Money` 封装金额和币种。
> `CreditAccount` 封装额度变化。
> `Authorization` 封装授权状态转换。
> `Statement` 封装还款对账单状态的影响。

追问方向：

- 为什么不用 static util 做金额？
- record 是否适合 domain？
- Lombok 会不会影响可读性？

强补充：

> 对 value object，record 很适合。
> 对有复杂生命周期的 aggregate，要谨慎保留显式方法和 invariant。

### Q72：如果要支持 refund，你会怎么加？

强回答：

> 我不会直接在 `CardTransaction` 上减金额。
> Refund 是新的业务事实，需要自己的 idempotency key、状态、ledger entry 和对 statement 的影响规则。
> 已出账前和已出账后 refund 处理不同，可能影响 current statement、next statement 或 credit balance。

追问方向：

- Refund 会不会恢复 available credit？
- 已还款后 refund 怎么办？
- Ledger 怎么记？

强补充：

> 这就是为什么当前项目先不急着实现 refund。
> 它很有价值，但会显著扩大状态机和账务规则。

### Q73：如果要支持 dispute/chargeback，你会怎么考虑？

强回答：

> Dispute 是长生命周期流程，涉及证据、时限、卡组织规则、临时 credit、最终裁决。
> 它不是简单 refund。
> 我会先建状态机和 case management，再接 ledger adjustment 和 notification。

追问方向：

- 临时 credit 是什么？
- Chargeback 成功/失败怎么入账？
- SLA 怎么监控？

强补充：

> 对 interview 来说，能讲清 dispute 为什么复杂，比草率实现一个字段更重要。

### Q74：Reconciliation 为什么比继续加 refund 更适合下一步？

强回答：

> Reconciliation 更贴金融后台日常工作。
> 它验证内部 records 和外部 network/bank file 是否一致，发现 missing、duplicate、amount mismatch。
> 它能连接 posting、repayment、ledger、operational exception handling。

追问方向：

- mismatch 怎么处理？
- 自动修复还是人工处理？
- 对账结果如何审计？

强补充：

> 最小实现可以先用 CSV 输入和 report 输出。
> 不要一开始就自动改账。

## 10. 深挖题库：Java、Spring、测试、Legacy

### Q75：Spring Boot 2 到 3 迁移有什么注意点？

强回答：

> 最大变化之一是 Javax 到 Jakarta namespace。
> 还要检查 Spring Security、Actuator endpoint、Hibernate/JPA、validation、第三方依赖兼容性。
> Java baseline 也提高了。

追问方向：

- `javax.validation` 和 `jakarta.validation` 怎么处理？
- Actuator endpoint 是否变化？
- 老应用如何渐进迁移？

强补充：

> 对 legacy 系统，不建议一次性大爆炸迁移。
> 先加测试和契约保护，再分模块迁移。

### Q76：JUnit 和 Mockito 在项目里怎么用？

强回答：

> Domain test 直接测对象行为。
> Application service test 用 Mockito mock repository、publisher、gateway，验证 orchestration 和边界行为。
> Controller test 用 Spring MVC Test 验证 validation、HTTP status 和 response mapping。
> Worker/listener test 验证 retry、lease、event parsing、idempotency。

追问方向：

- Mock 太多会不会脆弱？
- 什么时候需要 integration test？
- 如何测试并发？

强补充：

> Mockito 适合测试 use case 协作，不适合替代所有 integration test。
> MyBatis SQL、transaction、lock 行为最好用 Testcontainers 或真实 MySQL 验证。

### Q77：如何测试 idempotency？

强回答：

> 单测验证 same key same request 返回原结果，same key different request 返回 conflict。
> 集成测试应该验证数据库 unique constraint 在并发下只允许一个 winner。
> 还要测试 response lost 后 retry 的行为。

追问方向：

- 只用 mock 能测出 unique constraint 吗？
- 如何构造并发测试？
- Kafka duplicate 怎么测？

强补充：

> 真正的并发安全依赖 DB。
> Mock 测行为分支，integration test 测数据库约束和事务。

### Q78：如何测试 Outbox/Inbox？

强回答：

> Outbox 要测业务状态和 Outbox row 同事务写入。
> Worker 要测 publish success markPublished、publish failure retry/DEAD、lease changed 不覆盖。
> Inbox 要测 duplicate event 不重复执行 side effect。

追问方向：

- Kafka 要不要嵌入式测试？
- DLT 怎么测？
- 重放怎么测？

强补充：

> 最关键的是 failure mode。
> 只测 happy path 不足以证明可靠性。

### Q79：Java EE legacy 系统你会怎么接手？

强回答：

> 先不要急着重写。
> 先画出业务流程、事务边界、数据库表、外部接口和批处理。
> 对核心 money-changing path 加 characterization tests。
> 再逐步抽 service boundary、repository adapter、event publishing pattern。

追问方向：

- 没有测试怎么办？
- 老代码事务散落怎么办？
- 如何避免迁移期间业务中断？

强补充：

> Legacy modernization 的核心是降低风险。
> 先保护行为，再重构结构。

### Q80：为什么 Bean Validation 还不够？

强回答：

> Bean Validation 只保护 HTTP DTO 边界。
> Scheduler、Kafka consumer、repository restore、test、未来 admin tool 都可能绕过 controller。
> 所以 domain constructor/factory 仍然要保护 invariant。

追问方向：

- 哪些校验放 DTO？
- 哪些放 domain？
- 重复校验是否浪费？

强补充：

> DTO 校验解决输入格式。
> Domain invariant 解决业务对象永远合法。

## 11. 真实系统设计题：从 0 设计一个信用卡授权系统

### 题目

```text
设计一个高流量 credit card authorization backend。
要求支持幂等、并发额度控制、风控、异步通知、失败恢复和可观测性。
```

### 推荐回答结构

第一步，明确业务语义：

- Authorization 不是 posting。
- Authorization 成功只 hold credit。
- Presentment 到达后才 posted。
- Authorization 可能 decline、expire、reverse。

第二步，定义 API：

```text
POST /api/authorizations
Idempotency-Key: ...

body:
cardId
amount
currency
merchantId
merchantCountry
cardholderCountry
```

第三步，定义核心表：

- `cards`
- `credit_accounts`
- `authorizations`
- `outbox_events`
- `delay_jobs`
- `consumer_inbox`

第四步，定义事务流程：

```text
claim idempotency
-> lock authorization row
-> check card
-> assess risk
-> lock credit account
-> reserve credit
-> approve/decline authorization
-> insert expiry delay job
-> insert outbox event
-> commit
```

第五步，定义异步流：

```text
OutboxWorker
-> Kafka
-> Notification consumer
-> Inbox idempotency
-> side effect
```

第六步，定义 cache：

- Card snapshot 可以短 TTL cache。
- Statement read model 可以 cache。
- Credit balance 不 cache。
- Idempotency winner 不依赖 Redis。

第七步，定义高流量策略：

- Horizontal scale app instances。
- DB row lock protects account。
- Partition Kafka by aggregate id。
- Worker pool bounded。
- External risk timeout/bulkhead/circuit breaker。
- Metrics and alarms。

第八步，定义 failure mode：

- Client retry。
- DB deadlock。
- Kafka publish fail。
- Worker crash。
- Consumer duplicate。
- Redis fail。
- External risk timeout。

### interview 官可能打断你

打断 1：

```text
你这个锁 account row，高流量下不会慢吗？
```

回答：

> 会，所以要缩短锁内逻辑，把风控、validation、Kafka publish 移出锁。
> 但同一账户额度必须串行，否则会 over-approve。
> 高流量优化不能牺牲 credit limit correctness。

打断 2：

```text
为什么不用 Redis counter 做额度？
```

回答：

> Redis counter 很快，但它和 authorization row、Outbox、DelayJob 不在一个 ACID transaction。
> 一旦部分成功部分失败，恢复复杂。
> 可以用 Redis 做 rate limit 或 read cache，不能替代核心账务 source of truth。

打断 3：

```text
Kafka 不可用时还能授权吗？
```

回答：

> 如果 Kafka 不可用但 MySQL 可用，主交易可以继续写 Outbox。
> Outbox backlog 会增长，worker 重试。
> 是否停止接流量取决于 backlog 阈值和业务策略。
> 不能在授权主事务里等待 Kafka 恢复。

打断 4：

```text
如果 DB 不可用呢？
```

回答：

> DB 是 source of truth，authorization 不能正确处理。
> Readiness 应该 fail，停止接新流量。
> 可以返回明确错误，让客户端稍后重试。

## 12. 真实排障题

### 场景 1：授权 p99 从 200ms 升到 3s

先问：

- 是所有请求慢，还是部分 card/account 慢？
- 是 approve 慢，还是 decline 也慢？
- 是新版本后慢，还是流量变化后慢？
- DB CPU 和 lock wait 怎么样？
- external risk latency 怎么样？

排查顺序：

1. HTTP metrics：p50/p95/p99。
2. Error rate 和 decline reason 分布。
3. Hikari active/pending。
4. MySQL slow query。
5. MySQL lock wait。
6. External risk timeout/circuit breaker。
7. Redis timeout 是否导致 fallback DB。
8. Thread dump 是否大量线程在 JDBC/Feign。

可能原因：

- 热点 account row。
- External risk 慢。
- 缺索引导致 `FOR UPDATE` 锁范围变大。
- DB connection pool 饱和。
- 新代码把慢操作放进 transaction。

回答模板：

> 我会先定位慢在哪里，再决定扩容或优化。
> 对授权这种路径，最怕锁等待和外部依赖慢。
> 如果是热点 account，扩 app instance 没用。
> 如果是 external risk 慢，要看 timeout、bulkhead、circuit breaker。
> 如果是 DB 慢，要看 slow query、lock wait 和索引。

### 场景 2：Outbox backlog 持续增长

先区分：

- `PENDING` 增长。
- `PROCESSING` 增长。
- `DEAD` 增长。

可能原因：

- Kafka broker 不可用。
- Producer ack 慢。
- Worker pool 太小或 queue 满。
- Outbox claim SQL 慢。
- finalize markPublished 慢。
- Event serialization bug。

排查顺序：

1. Outbox status count。
2. Worker logs。
3. Kafka producer metrics。
4. Broker health。
5. DB lock/slow query。
6. Recent deployment diff。

回答模板：

> 如果 PENDING 增长，说明 publish capacity 跟不上生成速度。
> 如果 PROCESSING 增长，可能 worker 卡住或 lease timeout 太长。
> 如果 DEAD 增长，要看错误类型，是 Kafka、serialization 还是 contract。
> 修复后要考虑是否重放 DEAD event。

### 场景 3：用户还款后账单页面仍显示未还

可能原因：

- Repayment transaction 未提交。
- Statement update 失败。
- Statement read model cache 未 evict。
- Redis evict 失败且 TTL 未到。
- 用户请求打到另一个 pod 的 L1 stale cache。

排查顺序：

1. 查 repayment row。
2. 查 statement paid/status。
3. 查 cache key 是否存在。
4. 看 `StatementReadService.evictAfterCommit(statement)` 是否在还款提交路径注册。
5. 看 after-commit evict 日志。
6. 看 L1 TTL 和 Redis TTL。

回答模板：

> 先确认 DB source of truth 是否正确。
> 如果 DB 已更新但页面旧，很可能是 cache invalidation 问题。
> 项目里 repayment 后 after-commit evict statement cache，避免提交前 evict 导致 stale reload。
> 多 pod L1 stale 需要短 TTL 或 future invalidation event。

### 场景 4：Kafka consumer lag 上升，但主 API 正常

解释：

> 这是 event-driven 架构常见现象。
> 主 API 只写 Outbox，不等下游 consumer。
> Consumer lag 上升说明下游 side effect 延迟，不一定影响授权正确性。

排查：

- 哪个 consumer group lag。
- Notification 的哪个 topic/listener 慢。
- DB side effect 是否慢。
- DLT 是否增长。
- 是否 poison message 重试。
- partition 数和 concurrency 是否匹配。

风险：

- Notification 延迟。

回答模板：

> 我会先判断 lag 的业务影响。
> 如果是 notification，可以接受短暂延迟但要报警。

### 场景 5：Redis timeout 增多

影响：

- Statement GET 可能回源 DB。
- Card snapshot 可能回源 DB。
- 主交易正确性不受 Redis 决定。

风险：

- DB read pressure 上升。
- Authorization card lookup 变慢。
- Cache hit ratio 下降。

处理：

- 检查 Redis CPU/memory/network。
- 检查 connection pool。
- 检查 timeout 设置。
- 临时关闭 cache 或调低依赖。
- 观察 DB 是否被 fallback 打满。

回答模板：

> Redis 是性能依赖，不是 correctness 依赖。
> 它失败时系统可以回源 DB，但需要告警，因为 fallback 会把压力转移给 MySQL。

## 13. Warning / red flag 回答与更好版本

这一节专门整理 interview 中容易踩坑的说法。这里用 `Warning answer` 表示危险回答，用 `Better answer` 表示更适合真实 technical interview 的表达。

### Warning 1：用 Redis 保证额度一致

**Warning answer：**

```text
额度高并发就放 Redis，用 atomic increment。
```

**Better answer：**

```text
Redis 可以做限流或 cache，但额度 source of truth 仍在 MySQL。
Authorization 要和 authorization row、Outbox、DelayJob 同事务，所以核心并发靠 `FOR UPDATE`。
```

### Warning 2：Kafka 保证消息 exactly-once

**Warning answer：**

```text
Kafka 可以保证消息不重复，所以不用担心。
```

**Better answer：**

```text
Kafka 常按 at-least-once 设计。
Producer/consumer 侧都要考虑重复。
项目用 Outbox 保证 publish intent durable，用 Inbox/unique constraint 保证 consumer side effect 幂等。
```

### Warning 3：事务能解决所有一致性问题

**Warning answer：**

```text
加 `@Transactional` 就好了。
```

**Better answer：**

```text
`@Transactional` 只定义本地 DB transaction。
它不能覆盖 Kafka、Redis、外部风控。
所以要明确本地强一致边界，再用 Outbox、Inbox、retry、cache invalidation 处理分布式边界。
```

### Warning 4：微服务一定更好

**Warning answer：**

```text
系统大了就拆微服务。
```

**Better answer：**

```text
先看一致性边界和团队边界。
当前项目先用 modular monolith 保持核心交易事务简单。
Notification 这种异步下游更适合先拆。
```

### Warning 5：NoSQL 更高性能，所以替换 MySQL

**Warning answer：**

```text
高并发就把 MySQL 换 DynamoDB。
```

**Better answer：**

```text
NoSQL 要按 access pattern 设计。
核心授权额度需要 transaction、row lock、unique constraint 和审计，仍适合 RDBMS。
NoSQL 可以放 notification feed、statement summary、event projection。
```

### Warning 6：CloudWatch 只看 CPU

**Warning answer：**

```text
线上看 CPU 和 memory。
```

**Better answer：**

```text
CPU/memory 只是基础。
授权系统还要看 p95/p99、DB lock wait、connection pool、Kafka lag、Outbox/DelayJob backlog、
Redis hit ratio、GC pause 和业务 decline reason 分布。
```

### Warning 7：只背项目，不承认缺口

**Warning answer：**

```text
我这个项目已经覆盖所有 JD。
```

**Better answer：**

```text
项目强项是金融交易正确性、Kafka reliability、cache boundary 和 JVM/Actuator 观测。
还没有真实 AWS deployment、NoSQL implementation、gRPC 和压测。
我会优先补 high traffic 压测和 AWS ECS/CloudWatch 设计。
```

## 14. 追问逐题回答速查

前面章节里的“追问方向”是 interview 官真正会继续深挖的地方。下面把这些追问统一补成可直接复述的回答。

使用方式：

1. 先背每个主 Q 的“强回答”。
2. 再用本节补齐追问里的 failure mode、trade-off 和项目证据。
3. 回答时不要只给结论，尽量按“结论 -> 项目做法 -> 为什么 -> 如果去掉会怎样”展开。

> [!IMPORTANT]
> 本节的标准不是“答案越炫越好”，而是每个追问都能守住金融后端的 correctness boundary：
> `idempotency`、`transaction boundary`、`row lock`、状态机、Outbox/Inbox、cache boundary 和 observability。

### 14.1 业务主链路追问回答

#### Q8 追问：Authorization 和 posting 的区别是什么？

答：

> Authorization 是实时授权，决定是否临时 hold credit；posting 是清算/请款后的正式入账。
> Authorization 改变 `reservedAmount`，posting 把 hold 转成 posted balance 并形成账单候选交易。
> 这两个阶段分开，是因为商户授权成功后不一定最终请款，金额和时间也可能发生变化。

#### Q8 追问：为什么不是授权成功就直接入账？

答：

> 因为授权只是发卡方对未来可能发生的交易做额度预留，不代表商户已经完成清算。
> 如果授权成功就直接入账，未请款的交易也会变成客户债务，账单和争议都会错误。
> 正确做法是授权阶段 hold，presentment 到达后再 posted。

#### Q8 追问：为什么 presentment 不叫 capture？

答：

> `capture` 更常出现在商户/acquirer 或 payment gateway 视角，表示商户把预授权转为扣款。
> issuer backend 看到的通常是 network/clearing 侧的 presentment record。
> 本项目用 `presentment` 是为了强调发卡方处理外部清算记录，而不是商户订单 capture API。

#### Q9 追问：如果商户最后没有请款怎么办？

答：

> 授权 hold 不能永久占用额度。
> 项目通过 authorization expiry DelayJob 在到期后释放 `reservedAmount`，并把 authorization 状态推进到 expired。
> 如果没有 expiry，用户的可用额度会被无效 hold 长期占住。

#### Q9 追问：如果 presentment 金额和 authorization 不一致怎么办？

答：

> 当前项目选择严格匹配，这是学习系统里最清楚的主链路。
> 真实系统可能支持 partial presentment、incremental authorization、tips、currency conversion 和 adjustment。
> 如果要支持，就必须把金额差异建模成明确状态和 ledger adjustment，不能在 posting 时静默覆盖原金额。

#### Q9 追问：为什么需要 authorization expiry？

答：

> 因为 authorization 是临时 hold，不是永久债务。
> expiry 是把未请款 hold 自动释放的未来业务动作。
> 它适合 DelayJob，因为它不是“已经发生的事件发布”，而是“未来到点要检查并执行的动作”。

#### Q10 追问：如果同一个 `network_transaction_id` 对应不同 amount 怎么办？

答：

> 这不是正常 duplicate，而是 idempotency conflict 或上游数据异常。
> 服务端应该读取已有 record，比较 amount、currency、authorizationId 等 fingerprint 字段。
> 如果不一致，拒绝并进入异常处理/对账，而不是把它当成重试成功。

#### Q10 追问：如果 authorization 已过期后 presentment 才到怎么办？

答：

> 当前项目可以选择拒绝 late presentment，保持状态机简单。
> 真实信用卡系统可能允许 late presentment，并通过重新建账、adjustment 或特殊状态处理。
> 关键是规则必须显式，不应该让 expired authorization 被普通 posting 路径静默复活。

#### Q10 追问：如果两个 worker 同时处理同一条 presentment 怎么办？

答：

> 外部交易身份 `network_transaction_id` 必须有 unique constraint。
> 两个 worker 并发时，数据库会选出一个 insert/update winner，另一个识别 duplicate。
> 如果 duplicate payload 不一致，应返回 conflict 或进入 reconciliation，而不是重复入账。

#### Q11 追问：如果账单生成过程中又有 posting 到达怎么办？

答：

> 账单生成必须定义 period cutoff，并在 transaction 内固定本次 statement lines。
> cutoff 前已经 posted 的进入本期，cutoff 后或生成后到达的进入下期或后续 adjustment。
> 如果不固定快照，同一个账单可能前后显示不同交易，审计不可接受。

#### Q11 追问：为什么同周期只能有一张账单？

答：

> 同账户同账期的 statement 是正式账务快照，自然键应该唯一。
> 如果同周期能生成多张账单，会造成还款目标、通知和用户展示全部混乱。
> 因此需要 `credit_account_id + period` 的唯一约束或等价业务约束。

#### Q11 追问：账单生成失败一半怎么办？

答：

> `statement`、`statement_lines` 和交易标记应在同一个 transaction boundary 内完成。
> 失败则整体 rollback，不留下半张账单。
> 对已 commit 后的异步 side effect，比如 notification，则通过 Outbox retry/Inbox 幂等处理。

#### Q12 追问：如果还款金额超过剩余应还怎么办？

答：

> 当前项目可以拒绝 overpayment，让学习模型保持清晰。
> 生产系统可能允许 credit balance，但那需要额外 product policy、ledger 科目和后续抵扣规则。
> 不能简单把 paid amount 加到超过 statement total，否则状态语义会混乱。

#### Q12 追问：两笔还款同时进来怎么办？

答：

> 两笔还款必须锁同一个 statement/account 的关键 row。
> 第一笔 commit 后，第二笔基于最新 remaining amount 重新计算。
> 如果不锁，会出现两笔都认为剩余应还足够，导致 overpayment 或状态错误。

#### Q12 追问：自动扣款失败怎么办？

答：

> 自动扣款是未来业务动作，更适合 DelayJob。
> 失败后应记录 attempts、nextAttemptAt、lastError，超过阈值进入 DEAD 或人工处理。
> 它不能让 statement/account 进入不明状态；只有扣款确认成功才推进还款状态。

#### Q13 追问：通知晚到能接受吗？

答：

> 通常可以接受短暂晚到，因为通知是 side effect，不是授权是否成功的 source of truth。
> 但必须有 SLO 和 backlog alarm，避免晚到变成长期丢失。
> 用户可见的交易状态应来自主业务表/read model，不应该只靠通知。

#### Q13 追问：Kafka 重复投递会不会发两条通知？

答：

> 不应该。
> Notification consumer 应使用 Inbox 或 `source_event_id` unique constraint 识别重复 event。
> 重复投递时返回已有 notification 或跳过 side effect，而不是再次创建/发送。

#### Q13 追问：Notification consumer 挂了怎么办？

答：

> 主交易不回滚，Outbox/Kafka 保留业务事实。
> consumer 恢复后继续处理 lag；如果消息持续失败进入 DLT，需要报警、修复、重放。
> 这体现了 eventual consistency 和主交易可靠性的分离。

#### Q14 追问：风控通过后，额度可能已经被别的请求占用怎么办？

答：

> 所以风控通过后仍必须在 account row lock 内重新计算 available credit。
> 风控回答的是风险，row lock 内检查回答的是此刻额度是否足够。
> 两者不能互相替代。

#### Q14 追问：外部风控不可用时是放行还是拒绝？

答：

> 这是 product/risk policy，不是纯技术问题。
> 高风险交易通常 fail closed，低风险小额可能走降级规则，但必须有限额、审计和告警。
> 技术上要配置 timeout、CircuitBreaker、fallback reason，避免无限等待拖住 DB transaction。

#### Q14 追问：高风险小额交易是否一定拒绝？

答：

> 不一定，要看风险策略和业务目标。
> 可能选择 step-up verification、限额放行、临时观察或拒绝。
> 关键是 risk decision 要可解释、可审计，并且不破坏额度 transaction。

#### Q15 追问：为什么 CardTransaction 不是 ledger？

答：

> `CardTransaction` 是客户可见交易流水，描述授权/入账等交易事实。
> Ledger 是内部会计视角，需要账户科目、借贷方向、平衡、调整和审计。
> 两者可以有关联，但不能混成一个模型，否则后续 reconciliation 和报表会很难做。

#### Q15 追问：为什么项目删除了异步 Ledger projection？

答：

> 因为它不是 accounting source of truth，却被 Statement 当成生成前置条件：Kafka lag/DLT 会反向阻塞账单。
> 同时单边 DEBIT/CREDIT entry 没有 balanced journal，容易给人“已经实现账本”的错误印象。
> Notification 已足够展示 Kafka/Inbox 幂等，因此删除重复 projection，把 Ledger 留在概念和生产演进讨论中。

#### Q15 追问：未来实现 production Ledger 时放在哪个 transaction boundary？

答：

> 如果 Ledger 是资金会计 source of truth，就不能把它当成可随意延迟的附属投影。
> 应明确 posting/repayment 与 balanced journal 的原子性边界，或设计可证明不丢账的跨服务协议；
> 具体选择取决于服务边界、吞吐和监管审计要求，而不是默认“发 Kafka 后最终会有”。

### 14.2 幂等、事务、锁追问回答

#### Q16 追问：如果两个请求同时 insert 怎么办？

答：

> 数据库 unique constraint 决定 winner。
> 一个 insert 成功成为幂等 owner，另一个收到 duplicate key 后读取已有 row。
> 这比应用层先查再插更可靠，因为判断发生在 DB 的并发控制里。

#### Q16 追问：如果同 key 不同 body 怎么办？

答：

> 通过 request fingerprint 比较业务参数。
> 同 key 同 body 返回已有结果；同 key 不同 body 返回 `409 conflict` 或等价错误。
> 如果不做 fingerprint，客户端 bug 可能把不同交易错误合并成同一笔。

#### Q16 追问：如果 winner 还没提交，loser 读取什么？

答：

> loser 应通过 `FOR UPDATE` 等待同 key row 的 winner 完成。
> winner commit 后，loser 读取最终状态并返回一致结果。
> 如果直接读到 PENDING 半成品，会造成客户端误判。

#### Q17 追问：这会不会降低并发？

答：

> 只降低同一个 idempotency key 的并发，不影响不同 key 的授权。
> 这正是想要的冲突范围：同一业务请求要串行，不同业务请求可以并行。
> 锁粒度比全局锁小很多。

#### Q17 追问：可以直接 read committed 读吗？

答：

> 对已完成 winner 可以读，但对处理中 winner 不够。
> duplicate 需要等待最终状态，否则可能读到 PENDING 或读不到未提交 row。
> `FOR UPDATE` 的价值是把 duplicate 对齐到 winner 的 transaction 完成点。

#### Q17 追问：客户端一直重试会怎样？

答：

> 同 key retry 会命中同一条幂等记录，不会重复 reserve。
> 但高频 retry 会增加 DB 读锁压力，所以生产还要有 client retry backoff、rate limit 和幂等结果缓存策略。
> 服务端要区分合理 retry 和 retry storm。

#### Q18 追问：Redis 更快，为什么不用？

答：

> 快不等于更正确。
> Authorization 需要同时写 authorization、credit account、Outbox、DelayJob，这些都在 MySQL transaction。
> 如果幂等 winner 在 Redis，业务事实在 MySQL，会引入跨资源一致性问题。

#### Q18 追问：Redis 宕机怎么办？

答：

> 如果 Redis 是幂等 source of truth，Redis 宕机会影响授权正确性，这是不可接受的。
> 当前项目让 MySQL 承担幂等和额度正确性，Redis 故障只影响低风险 cache 性能。
> 这就是 source of truth 边界。

#### Q18 追问：可以用 Redis lock 吗？

答：

> 可以用于 cache refresh、rate limit 或没有 DB row 的外部资源互斥。
> 不适合作为 authorization 核心额度和幂等边界。
> 如果必须用，也要 owner token、TTL、safe unlock、fencing token 和业务幂等。

#### Q19 追问：如果没有索引会怎样？

答：

> InnoDB 可能扫描更多记录并锁住更大范围，导致锁等待扩大。
> `FOR UPDATE` 必须和精确 where condition、主键/唯一索引一起设计。
> 锁不是写上 SQL 就安全高效，索引决定锁的实际范围。

#### Q19 追问：gap lock 是什么？

答：

> gap lock 是 InnoDB 为防止幻读在索引间隙上加的锁，常见于 Repeatable Read 下的范围查询。
> 它可能阻止其他 transaction 在范围内 insert。
> 对高并发系统，应尽量用主键/唯一键点查锁行，减少不必要的范围锁。

#### Q19 追问：Read Committed 和 Repeatable Read 有什么影响？

答：

> Repeatable Read 提供一致性读视图，也可能在范围锁上更保守。
> Read Committed 减少部分 gap lock，但不能替代显式 `FOR UPDATE` 和 unique constraint。
> 本项目思路是：关键写路径用显式锁和约束，而不是把正确性寄托在默认 isolation 上。

#### Q20 追问：如果请求落到不同 app instance 呢？

答：

> 不影响正确性，因为锁在 MySQL，不在 app memory。
> 不同实例访问同一 `credit_accounts` row 时，InnoDB 会串行化 `FOR UPDATE`。
> 这就是 DB lock 比 JVM lock 更适合金融共享状态的原因。

#### Q20 追问：如果第二个请求先通过风控呢？

答：

> 风控通过不代表额度仍然足够。
> 第二个请求在 account lock 内会读取第一个请求 commit 后的新余额，再决定 approve/decline。
> 因此风控前置只缩短锁时间，不跳过锁内额度检查。

#### Q20 追问：如果 DB transaction timeout 呢？

答：

> transaction timeout 应 rollback，不能留下半完成授权。
> 客户端用同一 idempotency key retry，服务端根据已有状态返回结果或重新处理。
> 生产上要监控 lock wait/timeout，并避免外部 I/O 放在锁内。

#### Q21 追问：如果死锁仍然发生怎么办？

答：

> 先让 DB rollback 一个 transaction，然后应用识别 deadlock 异常并做有限重试。
> 重试必须建立在 idempotency 上，否则 retry 可能重复副作用。
> 同时要分析 deadlock graph，修正锁顺序或索引。

#### Q21 追问：MySQL deadlock 会自动回滚哪个 transaction？

答：

> InnoDB 会检测死锁，并选择一个 victim rollback，另一个继续。
> 应用会收到 deadlock/lock rollback 异常。
> 不能假设所有相关操作都成功，必须按失败路径处理。

#### Q21 追问：应用层要不要 retry？

答：

> 对可重试的 deadlock/timeout 可以有限 retry，并加 jitter/backoff。
> 但不是所有异常都 retry，例如业务状态冲突、fingerprint mismatch 不应该 retry。
> retry 前提是操作幂等，且不会重复发通知/重复入账。

#### Q22 追问：为什么不是每个 repository 方法一个事务？

答：

> 因为业务一致性通常跨多个 repository。
> 如果每个 repository 自己 commit，authorization 状态、account reserve、Outbox、DelayJob 可能部分成功。
> transaction boundary 应放在 application use case，而不是单个 SQL 方法。

#### Q22 追问：self-invocation 会有什么问题？

答：

> Spring `@Transactional` 依赖 proxy。
> 同一个 bean 内部 `this.method()` 调用不会经过 proxy，事务注解可能不生效。
> worker 里如果需要短事务 claim/finalize，更稳的是显式 `TransactionOperations`。

#### Q22 追问：async worker 里事务怎么拆？

答：

> 通常拆成 claim 短事务、处理逻辑、finalize 短事务。
> 不要用一个长 transaction 包住 Kafka publish 或外部调用。
> finalize 前重新锁 row 并校验 lease，避免旧 worker 覆盖新状态。

#### Q23 追问：Kafka publish 是否同事务？

答：

> 不和业务 DB transaction 同事务。
> 同事务提交的是 Outbox row，也就是 durable publish intent。
> Kafka publish 在 commit 后由 worker 完成。

#### Q23 追问：Notification 是否同事务？

答：

> 不应该和 authorization 主事务同事务。
> Notification 是 eventual consistency side effect。
> 主事务只记录业务事实和 Outbox event，Notification consumer 后续幂等处理。

#### Q23 追问：外部风控是否同事务？

答：

> 外部风控调用不是 DB transaction 的一部分。
> 技术上它发生在 transaction/use case 内的决策流程里，但不能和 MySQL rollback/commit 形成 ACID。
> 因此需要 timeout、CircuitBreaker、decision audit，并避免持有 account row lock 时等待风控。

#### Q24 追问：Kafka transaction 能不能解决？

答：

> Kafka transaction 可以在 Kafka 内部实现 producer 原子写多个 partition/topic，并和 consumer offset 有配合。
> 但它不能把 MySQL row update 和 Kafka publish 变成一个本地 ACID transaction。
> 对 MySQL source of truth，Outbox 仍然更直接。

#### Q24 追问：如果 Kafka 发送失败怎么办？

答：

> Outbox row 保持 `PENDING` 或 retry 状态，worker 后续重试。
> 超过阈值进入 `DEAD` 并报警。
> 主业务事实已经 commit，不应该因为 Kafka 短暂失败而回滚已成功授权。

#### Q24 追问：如果 DB commit 成功后应用宕机怎么办？

答：

> Outbox row 已经在 DB 中，应用恢复或其他实例 worker 会继续 publish。
> 这正是 Outbox 的价值。
> 如果直接 DB commit 后同步 send Kafka，没有 durable publish intent，就可能丢事件。

#### Q25 追问：应用层加 synchronized 是否能解决？

答：

> 只能解决单 JVM 内部分并发，不能解决多实例、scheduler、consumer、重启后的请求。
> 金融幂等必须落在 DB unique constraint。
> `synchronized` 可以作为本地优化，不能作为 correctness boundary。

#### Q25 追问：唯一约束冲突后怎么处理？

答：

> 捕获 duplicate key，读取已有业务 row，校验 fingerprint。
> 如果一致，返回已有结果；如果不一致，返回 conflict。
> 不要吞掉异常后重新生成一笔新交易。

#### Q25 追问：为什么 insert-first 更好？

答：

> insert-first 让数据库以原子方式决定 winner。
> check-then-insert 在并发下两个 transaction 可能都看到不存在。
> 数据库唯一约束是最后一道不可绕过的一致性防线。

#### Q26 追问：Repeatable Read 下有什么现象？

答：

> 普通一致性读会看到 transaction 开始时的快照，可能看不到其他 transaction 后续 commit。
> 但 `SELECT ... FOR UPDATE` 是当前读，会读最新已提交并加锁。
> 所以写路径不能只靠普通 select，要用当前读和锁。

#### Q26 追问：Read Committed 能不能用？

答：

> 可以用，但要明确依赖点。
> 如果关键路径都有 unique constraint、`FOR UPDATE` 和状态条件更新，Read Committed 也能正确。
> isolation 选择要结合锁范围、gap lock、吞吐和业务一致性要求。

#### Q26 追问：Gap lock 会不会影响吞吐？

答：

> 会，尤其是范围查询和缺索引时。
> 它可能阻塞范围内 insert，导致高并发下等待扩大。
> 解决方向是主键/唯一键点查、合理索引、缩小 transaction 和避免不必要范围锁。

#### Q27 追问：如果第一次其实失败了呢？

答：

> 要看失败发生在 commit 前还是 commit 后。
> commit 前 rollback，retry 可以重新 claim/处理或返回失败状态。
> commit 后 response 丢失，retry 应返回已提交结果。

#### Q27 追问：如果第一次还在处理中呢？

答：

> duplicate 应等待或返回明确 `processing`，不能创建第二笔交易。
> 本项目更偏向通过锁住 idempotency row 等 winner 完成。
> 生产里也可以对长任务返回 202 + 查询接口，但授权通常希望同步得到最终 approve/decline。

#### Q27 追问：幂等结果要保存多久？

答：

> 至少覆盖客户端 retry、网络超时、上游重放的合理窗口。
> 金融交易通常还要考虑审计和 dispute，所以业务 row 本身会长期保留。
> 可以把高频查询索引和历史归档分开设计。

### 14.3 Kafka、Outbox、Inbox、DelayJob 追问回答

#### Q28 追问：Outbox 能保证 exactly-once 吗？

答：

> 通常不能保证端到端 exactly-once。
> Outbox 保证业务状态和 publish intent 同事务，后续 publish 是 at-least-once。
> exactly-once 的实际效果要靠 consumer idempotency 和业务约束一起实现。

#### Q28 追问：Outbox row 什么时候删除？

答：

> 不建议 publish 后立刻硬删，至少要保留一段时间用于审计、排障和重放。
> 可以按状态和时间归档，或把 payload/archive 分层。
> 删除策略要和事件追踪、合规、存储成本一起考虑。

#### Q28 追问：Outbox backlog 上升怎么办？

答：

> 先区分 `PENDING`、`PROCESSING`、`DEAD`。
> `PENDING` 上升看 publish capacity，`PROCESSING` 上升看 worker stuck/lease，`DEAD` 上升看 poison event 或 contract bug。
> 处理前先看错误类型，不要盲目加 worker。

#### Q29 追问：每个 consumer 都共用一个 inbox 吗？

答：

> 可以共用同一张物理表，但唯一键必须包含 consumer identity。
> 同一个 event 对不同 consumer group 是独立消费；当前只保留 Notification group。
> 如果只按 eventId 去重，会误伤其他 bounded context。

#### Q29 追问：同一个 event 被不同 bounded context 消费怎么办？

答：

> 每个 bounded context 用自己的 consumerName claim。
> Notification 创建通知意图和 per-channel delivery。
> 它们共享 event contract，但 side effect 和幂等记录独立。

#### Q29 追问：Inbox row 要保留多久？

答：

> 至少覆盖 Kafka retention、DLT 重放和业务重试窗口。
> 保留太短，旧消息重放可能重复 side effect；保留太长，会增加表规模。
> 生产上常按 consumer、event time 分区/归档。

#### Q30 追问：会不会发两条 Kafka 消息？

答：

> 会有可能。
> publish 成功后 markPublished 前宕机，recoverer 可能让 row 再次 publish。
> 所以系统设计承认重复 publish，并要求下游幂等。

#### Q30 追问：下游怎么防重复？

答：

> 用 eventId、consumerName、source business id 建唯一约束。
> side effect 执行前先 claim Inbox，claim 成功才处理。
> 处理成功后记录完成状态，重复消息直接跳过或返回已有结果。

#### Q30 追问：能不能避免重复 publish？

答：

> 可以降低概率，例如 producer idempotence、事务 producer、mark/publish 顺序优化。
> 但只要跨 DB 和 Kafka，就很难保证端到端无重复。
> 更现实的生产答案是 at-least-once + consumer idempotency。

#### Q31 追问：lease timeout 怎么设？

答：

> 要大于正常 p99 处理时间，又不能长到 worker 宕机后恢复太慢。
> 需要结合 Kafka publish timeout、handler耗时、GC pause、DB 慢查询来设。
> 最好有 metrics 观察 lease timeout、processing duration 和 recover count。

#### Q31 追问：旧 worker 回来会不会覆盖新 worker？

答：

> 不应该。
> finalize 前重新 `FOR UPDATE` 当前 row，并校验 lease owner/version。
> 如果 lease 已被新 worker claim，旧 worker 的 finalize 必须失败或跳过。

#### Q31 追问：为什么 finalize 前要重新 `FOR UPDATE`？

答：

> 因为 worker 处理期间 row ownership 可能已经变化。
> 重新锁行可以读取当前状态并防止并发 finalize。
> 如果不重新锁，旧 owner 可能把新 owner 的处理结果覆盖掉。

#### Q32 追问：为什么 authorization expiry 不用 Kafka delayed message？

答：

> Kafka delayed message 不是 Kafka 原生核心语义，运维和可观测性也不如 DB job 明确。
> Expiry 是未来业务动作，需要查询当前 authorization 状态后决定是否释放 hold。
> DelayJob row 有 attempts、nextAttemptAt、lease、DEAD，适合审计和恢复。

#### Q32 追问：为什么 notification 不用 DelayJob？

答：

> Notification 是业务事实发生后的异步 side effect，天然来自 event。
> 它不需要“未来某个时间点再判断是否执行”，所以用 Outbox/Kafka 更贴切。
> DelayJob 用于 expiry、auto repayment 这类 delayed business action。

#### Q32 追问：两者是否能合并成一个 job 表？

答：

> 物理上可以抽象成统一任务表，但语义上要保持分离。
> Outbox 表示 reliable publication，DelayJob 表示 future action scheduling。
> 合并后如果语义混乱，会让 retry、监控、SLO 和排障都变难。

#### Q33 追问：如果 auto commit 开着会怎样？

答：

> offset 可能在业务 side effect 完成前提交。
> consumer 宕机后 Kafka 认为消息已处理，但 DB side effect 其实没写完，导致丢处理。
> 所以关键 consumer 应在业务成功后再 ack/commit。

#### Q33 追问：如果处理失败怎么办？

答：

> 可重试异常走 retry/backoff。
> 不可重试 contract/data error 进入 DLT，并报警。
> 失败期间不能提交 offset 为成功，否则消息会丢。

#### Q33 追问：DLT 什么时候用？

答：

> 当消息经过有限 retry 仍失败，且继续阻塞主 topic 会影响后续消息时使用。
> DLT 是隔离和人工处理入口，不是修复本身。
> 重放 DLT 前要修复代码/数据，并依赖 Inbox 防重复。

#### Q34 追问：partition 数量如何影响 consumer concurrency？

答：

> 同一个 consumer group 内，一个 partition 同时只能由一个 consumer 实例处理。
> 最大有效并行度受 partition 数限制。
> concurrency 超过 partition 数通常没有额外吞吐收益。

#### Q34 追问：热点 key 怎么办？

答：

> 如果某个 accountId/cardId 特别热，它所在 partition 会成为瓶颈。
> 可以先确认是否真的需要按该 key 保序。
> 若能放宽顺序，可用更分散 key；若不能，就要用业务限流、拆分 workload 或接受该 aggregate 串行。

#### Q34 追问：全局有序是否需要？

答：

> 大多数业务不需要全局有序，只需要 aggregate 内有序。
> 全局有序会把系统吞吐压到单 partition 或单 worker。
> 金融系统更常见的是按 account/authorization/statement 定义局部顺序。

#### Q35 追问：加字段是否兼容？

答：

> 可选字段通常向后兼容，旧 consumer 可以忽略。
> 新 consumer 要给默认值，不能要求所有 producer 同时升级。
> 如果字段语义必需，应升 event version 并做双读/双写过渡。

#### Q35 追问：删除字段怎么办？

答：

> 删除字段风险大，因为旧 consumer 可能仍依赖它。
> 应先标记 deprecated，升级所有 consumer，再停止写，最后删除。
> event schema 演进要按 contract 管理，不要随业务代码随手改 payload。

#### Q35 追问：DLT 后如何重放？

答：

> 先修复失败原因，再按 eventId 有控制地重放。
> 重放工具要记录操作者、时间、原因和结果。
> consumer 必须幂等，否则重放会重复创建通知或更新 projection。

#### Q36 追问：哪些错误应该 retry，哪些直接 DLT？

答：

> transient 错误可 retry，例如 DB connection timeout、Kafka 暂时不可用、外部服务超时。
> deterministic 错误应 DLT，例如 event schema 不支持、必填字段缺失、业务引用不存在且无法自动恢复。
> retry 前要判断重试是否可能成功。

#### Q36 追问：DLT 消息保留多久？

答：

> 至少覆盖排障和业务补偿窗口。
> 金融事件通常需要较长审计留存，具体取决于合规和存储策略。
> DLT 也要有监控，不能变成无人查看的垃圾桶。

#### Q36 追问：重放如何避免重复副作用？

答：

> 使用 Inbox、source event unique constraint、业务状态条件更新。
> 重放前可 dry-run 或检查当前状态。
> 重放过程要可审计，避免人工操作造成二次事故。

#### Q38 追问：`authorization.posted` 比 `authorization.approved` 先到怎么办？

答：

> 如果同一 authorization 的事件必须有序，partition key 应保证它们在同一 partition。
> consumer 侧仍要校验当前状态；如果前置状态不存在，可以 retry、暂存或 DLT。
> 不能盲目按收到顺序改状态。

#### Q38 追问：Notification 是否需要强顺序？

答：

> 通常不需要强全局顺序，但同一业务对象的用户体验要合理。
> 可以按 event time/status 做展示排序，或者避免发送明显矛盾的通知。
> Notification 的一致性要求低于 ledger，但仍要防重复。

#### Q38 追问：Ledger 是否需要顺序？

答：

> Ledger 对同一 account/transaction 通常需要严格状态校验和可追溯顺序。
> 可以用 partition key 保证局部有序，并在 DB 里用 sequence/status 防乱序写入。
> 如果顺序无法保证，要用 reconciliation 检查差异。

### 14.4 Cache、Redis、NoSQL 追问回答

#### Q39 追问：显式 cache 会不会代码更多？

答：

> 会，但换来的是 cache boundary 可见。
> 金融系统里哪些数据可缓存、何时 evict、失败怎么 fallback，都需要被 review。
> 显式 `StatementReadService` 比散落的 `@Cacheable` 更容易解释和排障。

#### Q39 追问：为什么不缓存 repository 所有 findById？

答：

> repository 读到的不一定都是低风险 snapshot。
> 有些 `findById` 服务于写路径，读到 stale value 会影响业务决策。
> 本项目只缓存可重建且 stale 风险可接受的数据。

#### Q39 追问：如何保证 evict？

答：

> 写路径在 transaction commit 后 evict，避免 rollback 后把正确 cache 清掉，或 commit 前被旧 DB 值重新填充。
> 如果 evict 失败，TTL 兜底，同时要有日志和 metrics。
> 对更严格场景可发布 invalidation event 清理各 pod L1。

#### Q40 追问：Redis 宕机怎么办？

答：

> Redis 失败时 fallback 到 DB，主业务正确性不受 Redis 决定。
> 但 DB 压力会上升，所以要告警和 circuit breaker。
> 如果 Redis 长期不可用，可以临时调低 cache 依赖或保护 DB。

#### Q40 追问：多 pod L1 如何失效？

答：

> 最简单是短 TTL。
> 更严格可以通过 Kafka/Redis Pub/Sub 发布 invalidation event，让所有 pod 删除本地 L1。
> 对高风险 key，可以在写后短时间 bypass L1 或直接读 DB。

#### Q40 追问：L1 最大容量如何设置？

答：

> 根据 heap size、对象大小、热点 key 数量和 GC 压力估算。
> 容量太小命中率差，太大会占 heap 并增加 GC pause。
> 要结合 Caffeine metrics、JVM heap、eviction count 调整。

#### Q41 追问：jitter 多大合适？

答：

> 通常按基础 TTL 的小比例随机，例如 5%-20%，具体看 key 数量和刷新成本。
> 目标是打散同一时刻过期，不是让数据无限 stale。
> 高风险数据 TTL 本身要短，jitter 也要控制。

#### Q41 追问：还有什么防 cache avalanche 方法？

答：

> single-flight、预热、异步刷新、限流、熔断、分层 TTL、stale-while-revalidate。
> 对 Redis 故障，要避免所有请求同时回源 DB。
> 防 avalanche 是容量保护，不是业务正确性机制。

#### Q41 追问：热点 key 失效怎么办？

答：

> 可以用 per-key single-flight 让同一时刻只有一个 loader 回源。
> 也可以短暂返回 stale value，同时后台刷新。
> 对参与授权决策的 key，要谨慎使用 stale value，必要时直接读 DB。

#### Q42 追问：如果有人恶意请求不存在 cardId 打 DB 怎么办？

答：

> 这属于 abuse/rate-limit 问题。
> 可以按 IP/client/cardId prefix 做限流，或对明显不存在 key 做极短 TTL negative cache。
> 但 negative cache 要避免挡住刚创建的数据。

#### Q42 追问：negative cache 完全不能用吗？

答：

> 不是不能用，而是要分场景。
> 对稳定不存在、低风险查询，可以短 TTL negative cache。
> 对刚创建可能马上可见的业务对象，negative cache 会放大 stale risk。

#### Q42 追问：TTL 设很短是否可以？

答：

> 可以降低 stale window，但也降低保护 DB 的效果。
> 如果攻击流量很大，短 TTL 仍可能频繁回源。
> 更完整方案是限流、认证、监控和负载保护一起做。

#### Q43 追问：如果业务要求 block 后立即全局生效怎么办？

答：

> block/unblock commit 后必须发布 invalidation event，清理 Redis 和所有 pod L1。
> 对高风险状态，还可以让 authorization path bypass cache 或使用极短 TTL。
> 如果做不到立即失效，就要明确 stale window 和风险接受者。

#### Q43 追问：只删除 Redis 是否够？

答：

> 不够，因为每个 pod 可能还有 Caffeine L1。
> 删除 Redis 后，其他 pod 的本地 L1 仍可能返回旧 statement read model，直到 `local-ttl` 过期。
> 对 Card 这类高风险状态，如果未来恢复 cache，还需要 L1 invalidation 或直接绕过 L1。

#### Q43 追问：本地 L1 怎么办？

答：

> 方案包括短 TTL、最大容量控制、事件驱动 invalidation、管理端强制清理。
> 如果 block 是强一致要求，就不能只靠等 TTL。
> 需要让写路径触发跨 pod 清理或让读路径确认最新状态。

#### Q44 追问：如果还款后用户马上刷新看到旧状态怎么办？

答：

> 先确认 DB 是否已更新。
> 如果 DB 正确但页面旧，就是 cache invalidation/replica lag 问题。
> 本项目通过 repayment commit 后 evict statement cache；多 pod L1 stale 需要短 TTL或 invalidation event。

#### Q44 追问：为什么 after commit evict？

答：

> 如果事务未 commit 就 evict，另一个请求可能读到旧 DB 值并重新写入 cache。
> commit 后 evict 可以确保下一次 miss 回源读到新状态。
> 这是 cache invalidation 和 transaction boundary 的关键顺序。

#### Q44 追问：如果 evict 失败怎么办？

答：

> TTL 兜底，DB 仍是 source of truth。
> 但应记录日志/metrics，因为用户可能在 TTL 内看到旧展示。
> 严格场景可把 invalidation 也做成 Outbox event 重试。

#### Q45 追问：大规模 key 如何清理？

答：

> 优先通过 versioned cache name 切换，例如 `statement-read-model-v2` → `statement-read-model-v3`，让旧 key 自然过期。
> 不建议在生产高峰 `KEYS` 全量扫描删除。
> 如需清理，用 `SCAN` 分批、限速、可中断。

#### Q45 追问：反序列化失败怎么办？

答：

> 视为 cache miss，删除坏 value 并回源 DB。
> 不能让坏 JSON 持续导致请求失败。
> 同时要打 metrics，因为它可能意味着版本不兼容或数据污染。

#### Q45 追问：是否需要 cache migration？

答：

> 大多数 cache 不需要复杂 migration，versioned key + TTL 更简单安全。
> 如果 cache 很大且重建成本高，可以做 lazy migration 或后台预热。
> 但 cache migration 不应比 DB migration 更复杂，cache 不是 source of truth。

#### Q46 追问：DynamoDB partition key 怎么选？

答：

> 从 access pattern 倒推。
> Notification feed 可用 `userId/cardholderId` + time sort key，statement summary 可用 `accountId` + cycle，event query 可用 aggregate id。
> 不能先建一个万能表再期待后续 join。

#### Q46 追问：Hot partition 怎么办？

答：

> 识别高频 key，评估是否能加时间 bucket、shard suffix 或改变访问模式。
> 如果业务必须按单个 account 强顺序写，就不能简单打散。
> NoSQL 热点处理要和一致性需求一起设计。

#### Q46 追问：GSI 有什么代价？

答：

> GSI 增加写放大、存储成本和 eventual consistency 风险。
> GSI key 也可能热点。
> 每加一个 GSI 都应对应明确查询，不是为了“以后可能用”。

#### Q47 追问：DynamoDB transaction 可以用吗？

答：

> 可以用于有限数量 item 的原子更新，但有吞吐、成本、限制和建模代价。
> 当前项目的 MySQL transaction、row lock、SQL 查询和审计更自然。
> 如果拆到 DynamoDB，要重新设计 aggregate、item collection 和 failure recovery。

#### Q47 追问：为什么不用 Saga？

答：

> Saga 适合跨服务长事务，但它是 eventual consistency + compensation，不适合替代同一个 DB 内可完成的强一致更新。
> authorization reserve credit 是核心实时决策，能用本地 transaction 就不要先引入 Saga。
> 拆服务后才需要认真设计 Saga/补偿。

#### Q47 追问：如果拆服务怎么办？

答：

> 先拆异步下游；若拆 authorization/account，必须定义 owner service 和写入边界。
> 跨服务不能再靠本地 transaction，需要 command/event、幂等、状态机、补偿和 reconciliation。
> 关键是明确谁拥有 credit account balance。

#### Q48 追问：Production Ledger 的查询视图能 cache 吗？

答：

> 查询型 ledger view 可以 cache，但 ledger entry 本身不应该靠 cache 作为事实。
> Ledger 更接近审计/会计数据，cache 只能加速展示或报表。
> 写入正确性仍靠 DB constraint、event id 和 reconciliation。

#### Q48 追问：Cache miss 如何重建？

答：

> 从 source of truth 或可重放 projection 重建。
> Statement read model 从 statement/statement_lines/repayment 状态回源。
> 如果重建成本高，要 single-flight、限流或异步预热，避免 miss storm。

### 14.5 High traffic 和性能追问回答

#### Q49 追问：加实例是否一定有用？

答：

> 不一定。
> 如果瓶颈是 CPU 或无共享计算，加实例有用。
> 如果瓶颈是同一 account row lock、DB connection、外部 risk 或 Kafka partition 热点，加实例可能只会增加排队。

#### Q49 追问：DB 是单点瓶颈怎么办？

答：

> 先优化 SQL、索引、transaction length、connection pool 和锁范围。
> 读多可用 cache/read replica，写热点要做业务限流、拆分工作负载或重新建模。
> 核心账务写不能简单靠读写分离解决。

#### Q49 追问：如何做限流？

答：

> 按业务维度限流：client、merchant、account、card、IP。
> Redis/Gateway 可做全局限流，本地 limiter 只能保护单实例。
> 被限流要返回明确可重试语义，并确保 idempotency key 不被错误消费。

#### Q50 追问：Tomcat threads 比 DB connections 多会怎样？

答：

> 多出来的 request threads 会等待 DB connection，造成排队和 latency 上升。
> 如果 thread 太多，还会增加 context switch 和内存压力。
> 要看 Hikari pending、active、timeout 和 HTTP p99 一起调。

#### Q50 追问：Kafka listener concurrency 怎么配？

答：

> 不能超过 partition 数期待线性收益。
> 还要看每条消息的 DB side effect、锁竞争和外部依赖。
> 配太高可能把 DB 打满或造成 retry storm。

#### Q50 追问：Worker queue 为什么要 bounded？

答：

> 无界 queue 在下游慢时会无限堆积，占满内存并扩大延迟。
> bounded queue 让系统尽早暴露压力，通过 retry/DEAD/backpressure 控制失败。
> 金融后台宁愿可观测地降级，也不要无声 OOM。

#### Q51 追问：API 层有没有 backpressure？

答：

> 可以通过 rate limit、bulkhead、connection pool timeout、request timeout、快速失败实现。
> 当前项目更多体现在线程池/worker 有界。
> 生产上 authorization API 应该有按 client/account/merchant 的保护。

#### Q51 追问：Kafka consumer lag 上升算不算 backpressure？

答：

> 它是下游处理能力不足的信号。
> Kafka 本身可以缓冲，但 lag 持续上升说明消费跟不上生产。
> 需要判断业务影响：notification 延迟通常可短暂容忍，但持续 lag 仍要报警和扩容。

#### Q51 追问：retry 会不会造成风暴？

答：

> 会。
> 如果所有失败消息立即重试，会和正常流量争抢资源。
> 需要 exponential backoff、jitter、最大 attempts、DLT 和 circuit breaker。

#### Q52 追问：能不能把账户余额拆 shard？

答：

> 对信用额度这种需要实时强一致的账户余额，随意拆 shard 很危险。
> 如果拆成多个 bucket，要解决跨 bucket 汇总、超额批准和 reconciliation。
> 除非业务接受近似或预留额度分片，否则不优先这么做。

#### Q52 追问：能不能用 Redis atomic counter？

答：

> Redis counter 可以做限流或预估，但不能替代 MySQL 里的授权状态、Outbox、DelayJob 同事务更新。
> Redis 成功而 DB 失败，或 DB 成功而 Redis 失败都会造成修复复杂。
> 核心额度仍以 DB 为准。

#### Q52 追问：能不能最终一致？

答：

> 对用户展示、通知、projection 可以 eventual consistency。
> 对实时授权额度通常不能，因为最终一致可能导致 over-approve。
> 除非产品明确接受风险并有离线补偿，否则 credit limit decision 要强一致。

#### Q53 追问：partition 数能不能随便增加？

答：

> 不能。
> 增加 partition 可能改变 key 到 partition 的映射，影响局部顺序假设。
> 还会影响 consumer rebalancing、监控和容量规划。

#### Q53 追问：增加 partition 会影响 key ordering 吗？

答：

> 对同一个 key，增加 partition 后新消息可能被 hash 到不同 partition，具体取决于 partitioner。
> 历史消息仍在旧 partition，新消息去新 partition 会破坏跨时间顺序假设。
> 依赖 ordering 的 topic 要提前规划 partition 数。

#### Q53 追问：consumer concurrency 超过 partition 数有用吗？

答：

> 同一个 consumer group 内通常没有用，多余 consumer 会 idle。
> 如果处理逻辑内部还能并行，需要自己保证同 key 顺序和幂等。
> 最自然的扩展单位仍是 partition。

#### Q54 追问：GET authorization 能读 replica 吗？

答：

> 如果只是用户查询历史状态，可以接受 replica lag 的场景下读 replica。
> 如果紧接着用于新的 money-changing decision，不应该读 replica。
> 写路径必须读 primary/事务内锁定 source row。

#### Q54 追问：Statement GET 能读 replica 吗？

答：

> 更适合，因为它是展示型 read model。
> 但还款后立刻刷新可能受到 replica lag 影响。
> 可以通过 read-your-write 策略、cache evict、短期读 primary 或前端提示处理。

#### Q54 追问：replica lag 怎么监控？

答：

> 监控 seconds behind source、replication delay、relay log、apply lag 和 query latency。
> 应用侧可以打 read timestamp/freshness 指标。
> 超过阈值时把关键查询切回 primary 或返回数据新鲜度提示。

#### Q55 追问：限流状态存在 Redis 还是本地？

答：

> 本地限流简单低延迟，但多实例下每个 pod 都有自己的额度。
> Redis 可以做全局限流，但引入网络依赖和 Redis 可用性问题。
> 金融 API 常两层结合：本地保护实例，Redis/Gateway 做全局控制。

#### Q55 追问：被限流返回什么？

答：

> 通常返回 `429 Too Many Requests`，带 retry-after 或明确错误码。
> 对授权这类业务，还要考虑上游网络/客户端是否理解可重试。
> 不能把限流误报成业务 decline，否则会污染风险/交易统计。

#### Q55 追问：如何避免误伤？

答：

> 按维度分层，不要只做全局粗暴限流。
> 配合 allowlist、动态阈值、监控和灰度。
> 限流策略要区分恶意流量、热点商户、正常高峰和系统故障。

#### Q56 追问：k6/Gatling 脚本怎么写？

答：

> 要包含正常授权、同 idempotency key retry、同 account 并发、热点 account、外部 risk 慢、Redis/Kafka 故障等场景。
> 每个虚拟用户要生成可追踪 idempotency key 和业务 id。
> 指标看 p95/p99、error ratio、DB lock wait、Outbox lag，而不只是 RPS。

#### Q56 追问：如何避免压测污染数据？

答：

> 使用独立环境、独立 card/account 前缀、可清理数据集。
> 压测后按 test run id 清理或归档。
> 不要在共享开发库里混入不可识别交易。

#### Q56 追问：如何验证幂等？

答：

> 构造同 key 同 body 的并发请求，确认只产生一条 authorization/account reserve。
> 构造同 key 不同 body，确认返回 conflict。
> 对 Kafka duplicate，重复投递同 eventId，确认 Notification side effect 幂等。

#### Q57 追问：Redis timeout 设多长？

答：

> 要短于主请求可接受 latency budget，通常是毫秒级短 timeout，而不是秒级长等待。
> Redis 是性能依赖，不能让它拖垮授权路径。
> 具体值要结合网络、部署、p99 和 fallback DB 成本压测。

#### Q57 追问：fallback 到 DB 会不会打爆 DB？

答：

> 会有风险。
> 所以需要 Redis error alarm、DB read capacity 保护、single-flight、限流和 circuit breaker。
> cache failure 的降级设计必须同时考虑 DB 保护。

#### Q57 追问：是否需要 circuit breaker？

答：

> 生产上建议有。
> Redis 连续 timeout 时短时间内停止请求 Redis，直接 fallback 或返回降级结果，避免线程都卡在 Redis client。
> breaker 状态要有 metrics 和恢复策略。

#### Q58 追问：看哪些指标？

答：

> JVM heap、allocation rate、GC pause p95/p99、old gen usage、thread count、blocked/waiting、Hikari pool、HTTP latency。
> 还要结合 DB lock wait 和 external risk latency。
> GC 很少孤立出现，通常和对象分配、慢依赖、队列积压一起看。

#### Q58 追问：G1 和 ZGC 怎么选？

答：

> G1 是通用默认选择，吞吐和 pause 平衡较好。
> ZGC 更适合大 heap、低 pause 要求，但要评估 CPU 成本和运行环境。
> 选择 GC 前先看实际 pause、heap 和 allocation，不要只凭名字。

#### Q58 追问：Thread dump 里 DB lock 是 Java BLOCKED 吗？

答：

> 不一定。
> 等 MySQL row lock 的线程通常在 JDBC/socket read 上，可能显示 RUNNABLE 或 WAITING，而不是 Java monitor `BLOCKED`。
> 要结合 MySQL processlist、InnoDB lock wait、slow query 和 Hikari metrics 判断。

### 14.6 AWS、部署、观测、CI/CD 追问回答

#### Q59 追问：Secret 怎么管理？

答：

> 用 AWS Secrets Manager 或 SSM Parameter Store，不写进 repo、image 或明文 env 文件。
> ECS task role 授权读取对应 secret。
> secret rotation 要考虑 DB connection refresh 和应用重启策略。

#### Q59 追问：DB migration 谁执行？

答：

> 可以由独立 migration job/CodeBuild step 执行，而不是每个 ECS task 启动时同时抢跑。
> migration 要有锁、版本记录和失败处理。
> 大表变更要用 backward-compatible 分步迁移。

#### Q59 追问：Blue/green 怎么做？

答：

> 用两组 ECS target group 或 CodeDeploy blue/green。
> 新版本先接少量流量，通过 health check 和关键指标验证后切全量。
> rollback 切回旧 target group，但前提是 schema/event contract 兼容。

#### Q60 追问：Kafka 不可用时 readiness 要不要 fail？

答：

> 取决于业务策略。
> 如果主 API 可以继续写 Outbox，Kafka 短暂不可用不一定 fail readiness，但要报警 backlog。
> 如果 Kafka 长期不可用导致下游风险不可接受，可以按 backlog/时间阈值 fail readiness 或限流。

#### Q60 追问：Redis 不可用时 readiness 要不要 fail？

答：

> 通常不 fail，因为 Redis cache 可以 fallback DB。
> 但要报警并保护 DB，避免 fallback 流量打爆 MySQL。
> 如果某些功能把 Redis 当强依赖，则要单独定义 readiness group。

#### Q60 追问：ALB health check 用哪个？

答：

> ALB 通常打 readiness endpoint，而不是 liveness。
> readiness 确认实例能接流量；liveness 用于容器自愈。
> 不要让 ALB health check 依赖太多非关键下游，避免雪崩式摘除所有实例。

#### Q61 追问：哪些指标要报警？

答：

> Authorization p99、5xx、DB connection pending、lock wait/deadlock、Outbox/DelayJob DEAD、Kafka lag、Redis timeout、GC pause、ECS restart。
> 还要有业务指标，比如 approval/decline ratio 异常。
> 报警要有阈值、持续时间和 runbook。

#### Q61 追问：如何区分 app 慢和 DB 慢？

答：

> 看 request trace 分段：controller/service、risk call、DB query、lock wait、cache、Kafka/Outbox。
> 如果 Hikari pending、slow query、RDS CPU/lock wait 上升，多半是 DB。
> 如果 CPU/GC/thread pool 高而 DB 正常，多半是 app/runtime。

#### Q61 追问：日志如何关联请求？

答：

> 使用 requestId/correlationId 贯穿 API、service、Outbox event、Kafka header、consumer log。
> 同时记录 business ids：authorizationId、accountId、idempotencyKey、eventId。
> 不记录敏感卡号或 PII 明文。

#### Q62 追问：DB migration 在 deploy 前还是后？

答：

> 取决于变更类型。
> 安全模式通常是 expand-and-contract：先 deploy 兼容 schema，再切代码，最后清理旧字段。
> 不要让新代码和旧 schema 或旧代码和新 schema 互相不兼容。

#### Q62 追问：rollback 能不能回滚 schema？

答：

> 生产上 schema rollback 往往比代码 rollback 更危险。
> 破坏性 migration 应避免，优先 backward-compatible。
> 如果必须回滚，要有数据备份、变更窗口和手工 runbook。

#### Q62 追问：Kafka event schema 怎么发布？

答：

> 按 contract version 管理，先让 consumer 兼容新旧 schema，再让 producer 发布新字段/新版本。
> 删除字段要等所有 consumer 不再依赖。
> DLT 和 unknown version 要可观测。

#### Q63 追问：PII 怎么处理？

答：

> 不打完整卡号、个人身份、密钥、access token。
> 必要时只打 masked value 或 hash，并限制日志权限和保留期。
> 排障依赖业务 id 和 correlation id，而不是敏感明文。

#### Q63 追问：日志太多怎么办？

答：

> 分级别、采样、结构化字段和按错误类型聚合。
> 正常高频路径不要打大量 INFO payload。
> 关键状态转换、错误、retry、DLT、deadlock 要保留足够上下文。

#### Q63 追问：如何排查一笔交易？

答：

> 从 requestId/idempotencyKey/authorizationId 查 API log。
> 再查 authorization、credit_account、Outbox event、Kafka consumer 和 notification/delivery。
> 如果涉及 cache，查 cache evict 和 read model freshness。

#### Q64 追问：改 enum/status 怎么做？

答：

> 先让代码能读新旧状态，再写新状态。
> DB constraint 或 enum 类型要分步更新。
> consumer 和 reporting 也要同步支持，否则新状态会变成未知错误。

#### Q64 追问：加非空字段怎么做？

答：

> 分步：先加 nullable 字段，部署代码写入，backfill 历史数据，验证无 null，再加 not null constraint。
> 大表 backfill 要分批限速。
> 不能在高流量表上直接加非空无默认导致长锁或失败。

#### Q64 追问：回滚失败怎么办？

答：

> 先止血：暂停流量或回滚 app 到兼容版本。
> 保留现场数据，执行修复脚本或补偿 migration。
> 事后补 runbook 和 pre-check，避免 migration 只靠“希望成功”。

#### Q65 追问：已经写入的坏数据怎么办？

答：

> 先界定影响范围：时间窗口、版本、API、业务 id。
> 对坏数据做修复脚本或补偿 transaction，并保留审计。
> 如果已经发出错误 event，还要发 correction event 或重建 projection。

#### Q65 追问：Outbox 里错误事件怎么办？

答：

> 如果未 publish，可以标记 DEAD 或修正 payload 后重放。
> 如果已 publish，要通过补偿 event、consumer 修复或 projection rebuild 处理。
> 不要直接删掉无审计，否则下游状态来源会断。

#### Q65 追问：客户端重试怎么办？

答：

> idempotency key 会让 retry 绑定到原业务结果。
> 如果原请求产生坏状态，需要让 retry 返回明确修复后状态或临时错误。
> 不能让 retry 再创建一笔“修复用”的新授权。

### 14.7 DDD、OOP、架构演进追问回答

#### Q66 追问：Repository 是 domain 还是 infrastructure？

答：

> Repository interface 可以被 application/domain 视为 port，但 MyBatis/XML 实现属于 infrastructure。
> Domain object 不应该依赖 MyBatis。
> 这样业务规则和持久化技术保持分离。

#### Q66 追问：Domain event 谁产生？

答：

> 最好由发生状态转换的 aggregate 产生，例如 `Authorization.approve()`。
> Application service 负责收集 event 并交给 Outbox adapter。
> 这样 event 和真实 state transition 更一致。

#### Q66 追问：Outbox 是 domain 吗？

答：

> 不是，它是可靠发布机制。
> Domain event 是业务事实，Outbox 是把 integration event durable 化的 infrastructure pattern。
> 混在一起会让 domain 关心 publish retry、lease 这些技术细节。

#### Q67 追问：Event 是 domain event 还是 integration event？

答：

> Domain event 是进程内业务事实，服务于当前 bounded context。
> Integration event 是跨边界 contract，要考虑版本、兼容、payload 稳定性。
> Outbox adapter 可以把 domain event 转成 integration event。

#### Q67 追问：Event payload 放多少字段？

答：

> 放 consumer 需要且稳定的业务事实，不要整个 entity dump。
> 字段太少会迫使 consumer 回查主服务，字段太多会增加 contract 耦合和隐私风险。
> 金融事件还要考虑审计和 PII。

#### Q67 追问：Event 失败会不会回滚？

答：

> Domain state transition 和 Outbox row 同事务，写 Outbox 失败会 rollback 主事务。
> Kafka publish 失败不会 rollback 已 commit 的业务事实，而是 Outbox retry。
> 这是本地强一致和分布式 eventual consistency 的分界。

#### Q68 追问：Domain service 什么时候需要？

答：

> 当规则不自然属于单个 aggregate，或者需要多个 domain object 协作但仍是纯业务规则时。
> 如果只是调用 repository、发 Kafka、开事务，那是 application service。
> 不要为了 DDD 名词滥用 domain service。

#### Q68 追问：Aggregate 能不能跨 repository 查数据？

答：

> 不建议。
> Aggregate 应保护自己的 invariant，不负责查询外部世界。
> 跨 aggregate 查询和 transaction orchestration 放在 application service。

#### Q68 追问：Anemic domain model 怎么避免？

答：

> 把状态转换和 invariant 放回 domain object，例如 `reserve`、`approve`、`applyRepayment`。
> Service 不应直接 set status/set amount 拼业务。
> 但也不要把外部 I/O 和 transaction 管理塞进 domain。

#### Q69 追问：Statement 能拆吗？

答：

> 可以拆，但要看它是否需要同步读取 transaction/account 状态。
> 如果 statement 由 posted transaction events 构建，拆分后要处理 event lag、重复、rebuild 和 reconciliation。
> 强一致出账规则可能仍需要仔细设计边界。

#### Q69 追问：Repayment 能拆吗？

答：

> 难度高于 Notification，因为 repayment 会同时影响 statement 和 credit account。
> 拆后要定义谁拥有 balance，如何做补偿，如何避免重复还款。
> 如果没有清晰 owner，先保持 modular monolith 更安全。

#### Q69 追问：拆后事务怎么办？

答：

> 本地 transaction 不再跨服务。
> 需要 Saga、Outbox、Inbox、幂等 command、状态机、补偿和 reconciliation。
> 还要接受一部分 eventual consistency，并明确哪些路径不能拆成异步。

#### Q70 追问：如何防止模块互相乱依赖？

答：

> 定义 package boundary、只暴露 application ports/events、禁止跨模块直接访问内部 repository。
> 可用 ArchUnit 或 Gradle module 做依赖检查。
> 文档要说明 bounded context 和数据 owner。

#### Q70 追问：什么时候必须拆？

答：

> 当团队独立交付、扩容需求、部署频率、故障隔离或数据 ownership 明确要求时。
> 不是因为“系统大了”就拆。
> 拆分收益要大于分布式一致性和运维成本。

#### Q70 追问：拆服务后如何测试？

答：

> 除单元测试外，需要 contract test、consumer-driven test、integration test、端到端 smoke test。
> 还要测试重试、乱序、重复、服务不可用和 schema 演进。
> 分布式系统测试重点是 failure mode。

#### Q71 追问：为什么不用 static util 做金额？

答：

> 金额不是普通数字工具函数，它有 currency、scale、rounding 和比较规则。
> `Money` value object 能把 invariant 封装起来，避免各处散落 `BigDecimal` 处理。
> static util 很容易让业务规则分散。

#### Q71 追问：record 是否适合 domain？

答：

> 适合不可变 value object，例如 `Money`、command、snapshot。
> 对有生命周期和状态转换的 aggregate，普通 class 更适合表达行为和 invariant。
> 选择看模型语义，不看语法新旧。

#### Q71 追问：Lombok 会不会影响可读性？

答：

> 会有可能。
> 对 DTO 或简单 data carrier，Lombok 可以减少样板。
> 对关键 domain object，显式 constructor/factory 和方法更有利于展示 invariant。

#### Q72 追问：Refund 会不会恢复 available credit？

答：

> 通常 refund 会减少 posted balance 或形成 credit balance，从而影响可用额度。
> 但是否立即恢复、恢复多少，要看是否已出账、是否已还款、是否存在 dispute。
> 不能简单等同于 authorization reversal。

#### Q72 追问：已还款后 refund 怎么办？

答：

> 可能形成 credit balance、退回银行账户、抵扣下期账单或生成 adjustment。
> 这需要 product policy 和 ledger 支持。
> 因此 refund 不是简单把原交易金额减掉。

#### Q72 追问：Ledger 怎么记？

答：

> Refund 应产生独立 ledger entry，保留与原交易的 reference。
> 需要借贷方向、科目、时间、原因和审计。
> 不能覆盖原 posted transaction，否则审计链断裂。

#### Q73 追问：临时 credit 是什么？

答：

> Dispute 处理中，发卡方可能先给持卡人临时额度/账务减免，等待调查结果。
> 如果 chargeback 成功，临时 credit 变成最终调整；失败则 reversal 回去。
> 这需要明确状态机和通知。

#### Q73 追问：Chargeback 成功/失败怎么入账？

答：

> 成功时生成 adjustment/ledger entry，减少客户债务或恢复额度。
> 失败时撤销临时 credit 或重新计入应还。
> 每一步都要可审计，不能只改一个 status。

#### Q73 追问：SLA 怎么监控？

答：

> Dispute 有卡组织/监管时限。
> 需要 case due date、状态停留时间、即将超时 alarm、处理人和操作日志。
> SLA 是业务指标，不只是系统 uptime。

#### Q74 追问：mismatch 怎么处理？

答：

> 先分类：missing internal、missing external、duplicate、amount mismatch、status mismatch。
> 小差异可能进入人工 review，确定性差异可自动生成 adjustment。
> 所有修复都要有 audit trail。

#### Q74 追问：自动修复还是人工处理？

答：

> 低风险、规则明确、可逆的 mismatch 可以自动修复。
> 涉及金额、客户可见状态或外部文件不可信时应人工确认。
> 最小系统先做报告和标记，不急着自动改账。

#### Q74 追问：对账结果如何审计？

答：

> 保留 reconciliation run id、输入文件 hash、匹配规则版本、差异明细、处理人和修复结果。
> 修复动作也要产生 ledger/adjustment event。
> 这样才能解释“为什么这笔账被改了”。

### 14.8 Java、Spring、测试、Legacy 追问回答

#### Q75 追问：`javax.validation` 和 `jakarta.validation` 怎么处理？

答：

> Spring Boot 3 使用 Jakarta namespace，需要把 `javax.validation.*` 迁到 `jakarta.validation.*`。
> 还要检查依赖库是否支持 Jakarta。
> 迁移时先编译失败清单，再逐模块修复和测试。

#### Q75 追问：Actuator endpoint 是否变化？

答：

> Boot 2 到 3 有 endpoint 暴露、health group、security 配置等差异。
> 要检查 management endpoint exposure、liveness/readiness、metrics name 和权限。
> 监控系统也要同步更新 dashboard/alarm。

#### Q75 追问：老应用如何渐进迁移？

答：

> 先加 characterization tests 和 contract tests，锁住行为。
> 再从边缘模块、低风险 API 或 adapter 开始迁。
> 对核心交易链路使用 strangler pattern，避免一次性 rewrite。

#### Q76 追问：Mock 太多会不会脆弱？

答：

> 会。
> Mock 适合验证 application service 协作，但过多会绑定实现细节。
> Domain 规则用纯单测，SQL/transaction/lock 用 integration test，端到端用少量关键路径 smoke test。

#### Q76 追问：什么时候需要 integration test？

答：

> 当行为依赖真实 DB、MyBatis XML、transaction、unique constraint、`FOR UPDATE`、Kafka serialization 时。
> 这些 mock 测不出来。
> 金融正确性关键路径应有 integration test。

#### Q76 追问：如何测试并发？

答：

> 用真实 DB、多线程 barrier 同时发起同 account 或同 idempotency key 请求。
> 验证最终只有一条 winner、余额不超、状态一致。
> 并发测试要可重复，失败时能打印业务 id 和 DB 状态。

#### Q77 追问：只用 mock 能测出 unique constraint 吗？

答：

> 不能。
> Mock 可以模拟 duplicate exception，但不能证明数据库 schema 真的有约束，也不能证明并发下行为正确。
> 需要 integration test 或至少 schema migration 验证。

#### Q77 追问：如何构造并发测试？

答：

> 准备同一 account/card，多线程同时发请求，用 latch/barrier 对齐开始时间。
> 同 key 测 duplicate，同 account 不同 key 测 row lock。
> 最后查 DB 的 authorization 数量、reserved amount、状态和 outbox rows。

#### Q77 追问：Kafka duplicate 怎么测？

答：

> 向 listener 投递同一个 eventId 两次。
> 验证 Inbox 只有一条 completed record，业务 side effect 只执行一次。
> 还要测 side effect 成功但 ack 前失败后的重投递路径。

#### Q78 追问：Kafka 要不要嵌入式测试？

答：

> 对 event serialization、topic/header、listener wiring 可以用 embedded Kafka 或 Testcontainers。
> 对业务幂等逻辑，直接调用 listener + DB 也能覆盖很多核心场景。
> 选择取决于测试成本和风险。

#### Q78 追问：DLT 怎么测？

答：

> 构造不可重试 event，例如 unknown version 或缺必填字段。
> 验证重试次数耗尽后进入 DLT，并记录错误原因。
> 如果使用 framework DLT，要验证 topic、headers 和 alert。

#### Q78 追问：重放怎么测？

答：

> 先让消息进入 DLT，修复原因后用重放工具或测试 helper 再投递。
> 验证 side effect 幂等，不重复创建。
> 同时验证 audit/log 能追踪重放动作。

#### Q79 追问：没有测试怎么办？

答：

> 先不要大改。
> 用 production logs、DB examples、API contract 补 characterization tests。
> 从核心 money-changing path 和高风险 bug 区域开始补。

#### Q79 追问：老代码事务散落怎么办？

答：

> 先画出实际 transaction boundary 和 DB write path。
> 找出哪些 service/repository 会部分 commit。
> 逐步把 use case transaction 收敛到 application service，期间用测试保护行为。

#### Q79 追问：如何避免迁移期间业务中断？

答：

> 采用 strangler pattern、兼容 API、双写/双读验证、灰度发布和快速 rollback。
> 迁移期间保留旧路径，逐步切流量。
> 对账和监控要证明新旧结果一致。

#### Q80 追问：哪些校验放 DTO？

答：

> 格式和接口边界校验放 DTO，例如 `@NotBlank`、`@NotNull`、金额字段存在、字符串长度。
> DTO 校验尽早拒绝无效 HTTP 输入。
> 它不应该包含复杂业务状态机。

#### Q80 追问：哪些放 domain？

答：

> 业务 invariant 放 domain，例如金额不能为负、币种必须一致、状态转换是否合法、还款不能超过规则允许范围。
> 因为 domain 可能被 scheduler、Kafka consumer、test 或 repository restore 使用。
> 这些路径不一定经过 Controller。

#### Q80 追问：重复校验是否浪费？

答：

> 不是浪费，是分层防线。
> DTO 校验保护输入质量，domain invariant 保护业务对象永远合法。
> 两者目的不同，重复一点关键规则可以换来更可靠的边界。

### 14.9 可替换和新增的高压追问

下面这些追问比“你会不会某技术”更接近真实高压 interview。可以用来替换前文里过于宽泛的问题。

#### 新追问 1：如果 authorization APPROVED 后 Outbox row 写入失败怎么办？

高分回答：

> 在正确设计里，authorization 状态、account reserve、DelayJob、Outbox row 必须同一个 transaction。
> 如果 Outbox row 写入失败，整个 transaction rollback，authorization 不应 APPROVED。
> 如果业务状态已经 commit 但没有 Outbox，那说明 transaction boundary 设计错了，需要补偿扫描或修复数据。

#### 新追问 2：如果 DelayJob expiry 和 presentment 同时发生怎么办？

高分回答：

> 两者都必须通过 authorization/account 的 row lock 和状态条件更新协调。
> 如果 presentment 先把 authorization posted，expiry 再执行时应看到状态已变化并跳过。
> 如果 expiry 先释放 hold，late presentment 要按明确规则拒绝或走异常处理。

#### 新追问 3：如果 same idempotency key 的 winner 最终 DECLINED，retry 要返回 DECLINED 还是重新评估？

高分回答：

> 默认返回原 DECLINED 结果，因为 idempotency key 代表同一业务请求。
> 如果每次 retry 都重新评估，客户端 timeout 会导致同一请求出现不同结果，破坏幂等语义。
> 只有新业务请求才应该使用新的 idempotency key。

#### 新追问 4：如果 cache evict 先成功，DB commit 后失败怎么办？

高分回答：

> 这个顺序本身就危险。
> 应使用 after-commit evict，只有 DB commit 成功后才清 cache。
> 如果事务 rollback，旧 cache 仍然对应旧 DB 状态，不需要清。

#### 新追问 5：如果外部风控返回慢，但 DB 很健康，怎么保护系统？

高分回答：

> 风控调用要在 account row lock 前，并配置 timeout、bulkhead、CircuitBreaker。
> 超时后按 risk policy fail closed 或降级，不允许无限等待拖住 servlet threads。
> 指标上要看 risk latency、timeout ratio、CircuitBreaker open count 和 authorization p99。

#### 新追问 6：如果 Outbox backlog 很高，但 Kafka broker 正常，下一步看什么？

高分回答：

> 看 worker pool 是否饱和、claim SQL 是否慢、DB connection pool 是否满、event serialization 是否失败、PROCESSING lease 是否 stuck。
> 再看最近部署是否改变了 payload 或 topic。
> 不能只盯 broker，因为 backlog 也可能是应用 worker 或 DB claim 的瓶颈。

#### 新追问 7：如果某个 consumer 处理慢，能不能直接加 concurrency？

高分回答：

> 先看 partition 数、DB side effect、锁竞争和外部依赖。
> 如果 partition 不够，加 concurrency 没用；如果 DB 是瓶颈，加 concurrency 会更慢。
> 正确做法是定位瓶颈，再决定扩 partition、优化 SQL、拆 handler 或限流。

#### 新追问 8：如果 AWS rollback 后旧版本读不了新 event 怎么办？

高分回答：

> 这说明 event schema 没有 backward compatibility。
> 发布新 event 前应先升级 consumer 兼容新旧版本，再切 producer。
> rollback 策略必须同时覆盖 app、DB schema 和 event contract。

#### 新追问 9：如果 statement cache 显示旧数据，但 DB 是对的，算事故吗？

高分回答：

> 要看 stale window 和业务影响。
> 如果只是短时间展示旧 paid status，可能是可接受 degraded experience；如果导致用户重复还款或客服误判，就是严重问题。
> 所以 cache 要有 TTL、after-commit evict、必要时 read-your-write 和告警。

#### 新追问 10：如果你只能补一个新能力来提高 JD 匹配度，补什么？

高分回答：

> 我会优先补 high traffic 压测和 AWS ECS/CloudWatch 设计。
> 因为当前项目核心 correctness 已经比较完整，下一步最能证明岗位匹配的是容量、运行、告警、rollback 和瓶颈定位。
> NoSQL/gRPC 可以补，但不应牺牲金融主链路的解释质量。

#### 新追问 11：你的 Command 对象为什么不做防御性校验？每层都校验不是更安全吗？

高分回答：

> 校验应该发生在信任边界上，而不是在自己进程内的层与层之间重复。
> 这个项目有两个不可信入口：HTTP 用 Bean Validation 在 DTO 上拦，Kafka 消息由
> IntegrationEventReader 做完整校验；进入 application 后数据已经可信，command 只是
> 类型化的传递载体，UUID、Currency 这些类型签名本身就是编译期防御。
> aggregate 的 invariant 是另一回事——那是业务规则的 owner，不是重复校验。
> 我删过一个 command 的 compact constructor：它的每条 null 检查在运行时都是死代码，
> 注释里防的是"未来可能有的 Kafka 路径"这种不存在的调用方。
> 例外是 GenerateStatementCommand——它有跨字段日期规则、且有批处理这条非 HTTP 创建路径，
> 它本身就是一个信任边界，所以保留校验。

追补：

> 层间重复防御的真实代价是契约位置模糊：规则改了要同步几处？漏一处就出现两层规则不一致，
> 比没有防御更糟。defense-in-depth 是跨信任域的安全策略，不是在进程内把 null check 抄三遍。

#### 新追问 12：用户到期没还清会怎样？为什么状态机里没有 OVERDUE 状态？

高分回答：

> 当前模型刻意不建 OVERDUE。逾期状态需要一个写入方——due-date 扫描 job——才有意义；
> 没有写入方的状态只是状态机里的死分支。项目早期版本有这个枚举值，
> 但全代码库没有任何路径写入它，我把它连同 applyRepayment 的分支和
> schema CHECK 约束一起删掉了（用增量 migration 收紧约束，不改已应用的 baseline）。
> 如果要实现：Statement.markOverdue 校验 dueDate 已过 + 状态合法，
> 由 dueDate 加一个营业日的 delay job 触发，同时发 statement.overdue 事件走 Outbox。

追补：

> 这题的加分点是"知道为什么不做"：留一个不可达状态会被读代码的面试官当缺陷；
> 删掉并能讲出完整的实现方案，把同一个问题从扣分项变成判断力证明。

#### 新追问 13：你有几套 poll-claim-work 循环，为什么不统一成一个框架？

高分回答：

> 四套：Outbox 中继、DelayJob、Notification delivery、Statement job dispatcher。
> 机制确实同构——都是 SKIP LOCKED claim、短事务 PROCESSING lease、recoverer 兜底——
> 但语义不同：Outbox 是发布顺序敏感的消息中继，所以 scheduler 单线程；
> DelayJob 是单实体延迟任务的通用框架，按 handler 注册分发；
> Statement job 是 cron 批量创建的分片批处理，刻意写成单类 reference dispatcher；
> delivery 在 claim 之外还叠加了 Resilience4j 的限流、熔断和两层重试预算。
> 强行统一，框架会被最复杂的需求绑架，其余三个都要背上不需要的复杂度。

追补：

> 形状上我两种都写过：四类拆分（职责清晰但文件多）和单类 dispatcher（易读易讲）。
> 如果重来我会把 DelayJob 对齐成单类形状——能说出"如果重来"本身就是这题的高分信号。

#### 新追问 14：domain event 的 port 为什么叫 Appender 不叫 Publisher？

高分回答：

> 因为 Publisher 这个名字 overclaim 了。这个接口做的事是在业务事务内把事件追加成
> Outbox row，和状态变更同事务提交；真正发 Kafka 的是事务外的后台 worker。
> 叫 Publisher 会让读者以为 service 同步发消息，进而误判 rollback 语义——
> 如果真是同步 publish，DB rollback 后就有一条已经发出的幽灵消息。
> 改名后整条链路的命名和职责对齐：append 追加事实、outbox worker 可靠投递、Kafka 消费。

追补：

> 命名 overclaim 是一类真实的工程债：注释和名字宣称了代码没有提供的保证，
> 读者基于错误的心智模型做决策。review 时我会专门检查"名字承诺的和代码做的是否一致"。

#### 新追问 15：MyBatis 和 JdbcTemplate 你都用了，怎么选？

高分回答：

> 主链路全部用 MyBatis XML mapper：金融写路径的 SQL、悲观锁、幂等语义必须显式可审查，
> XML 把 FOR UPDATE、SKIP LOCKED、唯一键冲突处理都摆在明面上，review 时能逐条对照。
> JdbcRiskVelocityCounter 刻意保留为 JdbcTemplate 实现：它只是一个轻量聚合查询，
> 藏在 RiskVelocityCounter port 后面，两种实现放在一起正好说明选择标准——
> 行映射多、SQL 需要集中管理时用 MyBatis；一两条简单查询、不值得建 mapper 体系时 JdbcTemplate 更直接。
> JPA 没有用：核心表的每条 SQL 都承担锁和幂等语义，自动生成的 SQL 反而是审查负担。

追补：

> 这题的陷阱是把三者说成优劣关系。它们是"SQL 显式程度和映射自动化程度"光谱上的三个点，
> 选择依据是这条 SQL 承担的正确性责任有多重，不是框架新旧。
