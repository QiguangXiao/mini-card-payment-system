# PayPay Card JD 对照 interview 手册

这份文档把 PayPay Card Backend Engineer JD 拆成可复习、可讲述、可举证的能力清单，
并映射到当前 mini-card-payment-system 的真实代码、文档和还没有覆盖的缺口。

它的目标不是把 JD 每个单词机械翻译一遍，而是帮你在 interview 里做到三件事：

1. 先讲项目和岗位最相关的能力，而不是从技术清单开始背。
2. 每个能力都能落到当前项目里的类、表、配置、测试或文档。
3. 对没做的内容诚实说明 trade-off，并给出合理的下一步设计。

一句话总纲：

```text
这个项目最适合用来证明：我理解金融后端的 money-changing path，
能用 Spring Boot、MySQL、Kafka、Redis cache、事务、锁、幂等和异步恢复机制，
把正确性、可扩展性和可观测性放在同一个系统里解释清楚。
```

## 1. JD 核心内容重新排序

JD 原文按公司技术栈和资格项列出。interview 复习时更建议按能力层次重排：

| 顺序 | 能力主题 | JD 里的关键词 | 为什么排在这里 |
| --- | --- | --- | --- |
| 1 | 后端主语言与 Spring 实现能力 | Java, Spring Boot, OOP, Gradle, JUnit, Mockito | 这是岗位基本盘，先证明你能写可维护服务 |
| 2 | 金融数据正确性 | RDBMS, database clients, OOP, DDD | 信用卡后台最重要的是状态、金额、事务、审计 |
| 3 | 并发与分布式一致性 | concurrency, distributed computing, high traffic | PayPay Card 很容易追问重复请求、锁、重试、扩容 |
| 4 | 平台组件能力 | RESTful APIs, Pub/Sub, Kafka, event-driven architecture | 说明你能做 API、消息、数据库、异步 side effects |
| 5 | 缓存与读扩展 | distributed cache, NoSQL databases | 既要会 cache，也要知道哪些金融数据不能 cache |
| 6 | 生产运行与 AWS | ECS, CloudFormation, CloudWatch, CodePipeline | 当前项目有本地可观测性基础，但 AWS 需要补强 |
| 7 | 架构演进与协作 | microservices, Java EE legacy, stakeholder management | 用 modular monolith 讲清拆分边界和迁移思路 |

这个顺序的好处是：你不会被问到 Redis、AWS、gRPC 时突然跳出主线。所有回答都能回到：

```text
业务状态如何正确变化？
并发和失败时如何不出错？
流量变大时哪里可以扩，哪里不能靠缓存取巧？
```

## 2. 当前项目的一分钟定位

可以这样介绍当前项目：

> 我做的是一个 mini credit card issuer backend，用 Java 21、Spring Boot 3、
> MySQL、MyBatis、Kafka、Redis/Caffeine cache 和 Gradle 实现。它覆盖发卡方核心链路：
> authorization 授权占额度、presentment posting 入账、statement 出账、repayment 还款，
> 以及 Notification、Risk、Ledger 这些异步下游。
>
> 这个项目的重点不是 API 数量，而是金融后端的可靠性设计。比如授权用
> `Idempotency-Key` 和数据库唯一约束防重复占额度；额度变化用 MySQL
> `SELECT ... FOR UPDATE` 做 `row lock`；业务状态和 Outbox event 在同一个
> `transaction boundary` 提交；Kafka 只做 at-least-once delivery，consumer 侧用
> Inbox 或唯一约束保证副作用幂等；读扩展用 Caffeine L1 + Redis L2 cache，但不缓存额度和幂等 winner。
>
> 所以这个项目可以从 HTTP request 一直讲到 domain state transition、MySQL commit、
> Kafka async delivery、cache boundary、failure recovery 和 production monitoring。

不要这样讲：

```text
我做了一个 Spring Boot 支付系统，有 MySQL、Kafka、Redis。
```

这太像技术堆砌。更好的表达是：

```text
我用这些技术解决了金融交易里的重复请求、并发额度、异步消息可靠性和读扩展边界。
```

## 3. 总体匹配度

| JD 能力 | 当前匹配度 | 项目证据 | 需要注意 |
| --- | --- | --- | --- |
| Java / Spring Boot | 强 | Java 21、Spring Boot 3.5、controller/service/domain/repository 分层 | JD 写 Java 11/17 + Boot2/3，项目更现代，要能讲版本迁移 |
| OOP / DDD | 强 | `Authorization`, `CreditAccount`, `Statement`, `Repayment`, `Money` | 强调不是为 DDD 而 DDD，Outbox/DelayJob 保持机制包 |
| RDBMS | 强 | MySQL、MyBatis XML、Liquibase、唯一约束、`FOR UPDATE` | 重点讲索引、约束、锁和事务 |
| Distributed cache | 强 | Caffeine L1 + Redis L2 `SnapshotCache` | 重点讲 cache boundary，不能缓存额度 |
| Pub/Sub / Kafka | 强 | Transactional Outbox、Kafka topics、Consumer Inbox、DLT | 说明 Kafka 不替代本地事务 |
| Concurrency | 强 | idempotency claim、row lock、`SKIP LOCKED`、lease、bounded worker pool | 讲清 DB lock 和 Java lock 的区别 |
| High traffic systems | 中强 | lock scope、cache、worker pools、partition key、Actuator metrics | 建议补 load test 和容量推导文档 |
| RESTful APIs | 强 | Authorization/Presentment/Statement/Repayment controllers | Controller 只做 adapter 和 validation |
| Database clients | 强 | MyBatis、JdbcTemplate 对比示例 | MyBatis 放 infrastructure，domain 不依赖 |
| NoSQL | 弱 | 当前没有 NoSQL 实现 | 建议用 trade-off 文档说明 DynamoDB/DocumentDB 适合哪些 read model |
| Microservices | 中 | modular monolith + event contracts + consumer groups | 没有物理拆服务，需讲为什么当前不拆 |
| gRPC | 弱 | 当前没有 gRPC | 可作为未来 Risk internal API 小切片 |
| AWS ECS / CloudFormation / CodePipeline | 弱 | 当前是 Docker Compose local | 建议补 AWS deployment 设计文档或 skeleton |
| CloudWatch / observability | 中 | Actuator health/metrics、JVM/thread docs | 需要映射到 CloudWatch metrics/logs/alarms |
| JUnit / Mockito | 强 | service/domain/controller/listener/worker tests | 可以举 Mockito 隔离 service 依赖 |
| Collaboration / documentation | 中强 | README + 多份中文 study docs + glossary | 可以说项目文档模拟 Confluence 风格知识沉淀 |

## 4. 后端实现能力

### 4.1 JD 期望

JD 明确提到：

- Spring Boot 主实现。
- Java 11 / Spring Boot 2 与 Java 17 / Spring Boot 3 混合。
- Legacy systems written in Java EE。
- JUnit 和 Mockito。
- GitHub、IntelliJ、Gradle。

### 4.2 项目证据

当前项目：

- `build.gradle` 使用 Java toolchain 21、Spring Boot 3.5.14、Gradle Wrapper。
- `src/main/java/com/minicard/.../api` 暴露 REST controllers。
- `src/main/java/com/minicard/.../application` 编排 use case 和事务。
- `src/main/java/com/minicard/.../domain` 放业务状态和 invariant。
- `src/main/java/com/minicard/.../infrastructure` 放 MyBatis、Kafka、Redis、external client。
- `src/test/java` 有 JUnit 5 + Mockito 测试，覆盖 controller、domain、service、listener、worker。

可以重点举这些类：

| 能力 | 项目锚点 | 怎么讲 |
| --- | --- | --- |
| REST adapter | `AuthorizationController`, `RepaymentController`, `StatementController` | Controller 只处理 HTTP、validation、DTO/command mapping |
| Use case orchestration | `AuthorizationService`, `PostingService`, `StatementService`, `RepaymentService` | Service 负责 `transaction boundary` 和多 aggregate 协作 |
| Domain modeling | `Authorization`, `CreditAccount`, `CardTransaction`, `Statement`, `Repayment`, `Money` | 状态转换和金额规则放在 domain object |
| Infrastructure adapter | `MyBatisAuthorizationRepository`, `KafkaOutboxMessagePublisher`, `CachedCardRepository` | 技术细节不泄露到 domain |
| Testing | `AuthorizationServiceTest`, `PostingServiceTest`, `OutboxWorkerTest`, `DelayJobWorkerTest` | Mockito 隔离 collaborator，测试 use case 行为 |

### 4.3 Java 11/17/21 怎么回答

项目用 Java 21，不等于和 JD 不匹配。可以这样讲：

> JD 里提到 Java 11/17 和 Spring Boot 2/3，我的项目使用 Java 21 和 Spring Boot 3，
> 但核心能力是可迁移的：OOP 分层、事务、MyBatis SQL、Kafka、JUnit/Mockito 都不依赖某个最新语法。
> 如果进入 Java 11/17 或 Boot 2 系统，我会重点检查 Jakarta/Javax 包迁移、Spring Security/Actuator 配置差异、
> dependency 版本和 runtime baseline。

不要说：

```text
我只会 Java 21。
```

更好的说法：

```text
我用 Java 21 做学习项目，但我理解 Boot2 到 Boot3、Javax 到 Jakarta、JDK baseline 变化带来的迁移点。
```

### 4.4 Java EE legacy 怎么回答

当前项目没有 Java EE 代码。不要硬说做过。

可以这样讲：

> 这个项目没有直接实现 Java EE legacy，但我可以从 Spring Boot 分层反推 modernization 思路：
> 先识别 servlet/EJB/JPA 或老式 service 的业务边界，把 transaction boundary 和 database access 明确化；
> 对 money-changing path 先加 characterization tests，再逐步抽 application service、domain behavior 和 repository adapter。
> 对外 contract 尽量保持兼容，避免一次性重写核心交易链路。

这类回答体现的是“面对 legacy 的工程判断”，不是虚假经历。

## 5. 金融数据正确性

### 5.1 JD 期望

JD 提到 RDBMS、database clients、OOP、DDD。对信用卡后端来说，这些词最后都会落到：

- 金额怎么表示。
- 状态怎么变化。
- 重复请求怎么处理。
- 并发下额度会不会错。
- 哪些更新必须同事务提交。
- 哪些异步 side effect 可以 eventual consistency。

### 5.2 项目证据

| 主题 | 项目证据 | 关键点 |
| --- | --- | --- |
| 金额模型 | `Money`, MySQL `DECIMAL(19,2)` | 不用 `double`，币种和金额比较集中封装 |
| 授权幂等 | `AuthorizationCommand.requestFingerprint`, `authorizations.idempotency_key` unique | 同 key 不同 body 返回 conflict |
| Presentment 幂等 | `network_transaction_id` unique | 外部 clearing record 重放不会重复入账 |
| 还款幂等 | `repayments.idempotency_key` unique | 防止重复还款或重复释放 posted balance |
| 额度并发 | `creditAccountRepository.findByIdForUpdate(...)` | 同账户额度变化串行化 |
| 状态机 | `AuthorizationStatus`, `CardTransactionStatus`, `StatementStatus`, `RepaymentStatus` | 非法状态转换由 domain 拒绝 |
| 账单快照 | `statement_items` | 账单不是临时 SUM，而是可审计 snapshot |
| Migration | Liquibase changelog | schema 变化可追踪 |

### 5.3 最重要的一条链路

最值得讲的是 authorization：

```text
POST /api/authorizations
-> AuthorizationController
-> AuthorizationCommand
-> AuthorizationService.authorize(...)
-> authorizationRepository.claim(idempotencyKey, pending)
-> findByIdempotencyKeyForUpdate(idempotencyKey)
-> cardRepository.findById(cardId)
-> riskAssessmentService.assess(...)
-> creditAccountRepository.findByIdForUpdate(accountId)
-> CreditAccount.reserve(...)
-> authorization.approve(...)
-> DelayJob + Outbox event 同事务提交
```

这里覆盖了 JD 里的多个关键词：

- RESTful API。
- OOP / DDD。
- RDBMS。
- database client。
- concurrency。
- distributed computing。
- Pub/Sub。
- high traffic lock scope。

### 5.4 可以复述的回答

> 这个项目里最关键的金融正确性设计是：所有 money-changing path 都先确定幂等所有权，
> 再在同一个 database transaction 里锁住需要串行化的业务行，执行 domain state transition，
> 最后把 Outbox/DelayJob 这种后续动作计划和业务状态一起 commit。Kafka、cache、worker pool 都不能替代这个正确性边界。

## 6. 并发和分布式计算

### 6.1 JD 期望

JD 写的是：

```text
In-depth understanding of concurrency and distributed computing.
Experience designing high traffic systems.
```

这类问题不会只问 Java `synchronized`。更可能问：

- 两个请求同时刷同一张卡怎么办？
- 客户端 timeout retry 会不会重复占额度？
- Kafka 重复投递怎么办？
- 多个 worker 会不会处理同一条任务？
- Redis cache stale 会不会影响授权正确性？
- 多实例横向扩容后，JVM 内存锁还可靠吗？

### 6.2 项目里的并发控制层次

| 层次 | 机制 | 项目证据 | 解决什么 |
| --- | --- | --- | --- |
| API retry | `Idempotency-Key` + unique constraint | `AuthorizationService`, `RepaymentService` | 客户端重试不重复写 |
| 同 key 并发 | `findByIdempotencyKeyForUpdate` | authorization mapper/repository | duplicate 等 winner 完成 |
| 同账户并发 | `SELECT ... FOR UPDATE` | `CreditAccountRepository.findByIdForUpdate` | 额度检查和更新串行 |
| Worker 并发 | `FOR UPDATE SKIP LOCKED` + PROCESSING lease | Outbox/DelayJob mapper | 多 worker 不重复 claim |
| Worker 宕机 | recoverer 回收 stuck lease | `OutboxRecoverer`, `DelayJobRecoverer` | PROCESSING 不永久卡住 |
| Kafka 重复投递 | Inbox / unique source event | `ConsumerInboxRepository`, notification/ledger/risk listeners | consumer side effect 幂等 |
| Cache 并发 | Caffeine L1 + Redis L2 + single-flight | `TwoLevelSnapshotCache` | 减少 cache miss 打爆 DB |

### 6.3 Java lock 与 DB lock 的区别

可以这样回答：

> 我不会用 Java `synchronized` 保护额度，因为应用会横向扩容，两个请求可能落在不同 JVM。
> 额度是数据库里的共享事实，所以并发控制必须靠 MySQL row lock 和 transaction。
> Java 内的锁最多能保护单实例内的内存结构，例如 `TwoLevelSnapshotCache` 里的 per-key single-flight；
> 但不能保护跨实例的金融状态。

### 6.4 Distributed computing 怎么讲

项目里最典型的是 Outbox + Kafka + Inbox：

```text
业务 DB transaction
-> 写业务状态
-> 写 outbox_events
-> commit
-> OutboxWorker publish to Kafka
-> Kafka at-least-once delivery
-> Consumer Inbox claim
-> notification / risk projection / ledger side effect
```

回答重点：

- MySQL 和 Kafka 没有一个本地 ACID transaction。
- Outbox 解决 business state 和 publish intent 的 dual-write。
- Kafka 仍然是 at-least-once，所以 consumer side 还要幂等。
- DLT 是失败隔离，不是正确性的唯一保障。
- Event version 和 reader validation 能降低 schema 演进风险。

## 7. REST、Pub/Sub 和 database client

### 7.1 RESTful APIs

项目入口：

| API | Controller | 业务含义 |
| --- | --- | --- |
| `POST /api/authorizations` | `AuthorizationController` | 授权占额度 |
| `GET /api/authorizations/{id}` | `AuthorizationController` | 查询授权状态 |
| `POST /api/presentments` | `PresentmentController` | 商户请款入账 |
| `POST /api/statements/generate` | `StatementController` | 手动/运营 backfill 出账 |
| `GET /api/statements/{id}` | `StatementController` | 查询账单 read model |
| `POST /api/repayments` | `RepaymentController` | 手动还款 |
| `GET /api/repayments/{id}` | `RepaymentController` | 查询还款状态 |

Controller 的设计口径：

> Controller 是 HTTP adapter。它负责 validation、header/body 到 command 的转换和 response mapping。
> `idempotency`、`transaction boundary`、`row lock`、state transition 都在 application/domain 层。

### 7.2 Pub/Sub Systems

项目不是直接在业务事务里发 Kafka，而是：

- `AuthorizationOutboxAdapter`
- `CardTransactionOutboxAdapter`
- `StatementOutboxAdapter`
- `RepaymentOutboxAdapter`
- `KafkaOutboxMessagePublisher`
- `IntegrationEventReader`
- Notification / Risk / Ledger listeners

可以这样讲：

> 我把 Kafka 当成异步 delivery mechanism，不把它当成 source of truth。
> 业务事实先进入 MySQL Outbox，再由 worker publish。下游 consumer 按自己的 bounded context 消费，
> 失败时进入 retry/DLT，重复投递靠 Inbox 或唯一约束处理。

### 7.3 Database Clients

项目同时保留 MyBatis 和一个小的 JdbcTemplate 示例：

- MyBatis 是主 repository 实现，SQL 在 XML 中显式可 review。
- JdbcTemplate 用在 `JdbcRiskVelocityCounter`，作为轻量聚合查询示例。

回答重点：

> 金融后端很多关键行为依赖明确 SQL，例如 `FOR UPDATE`、`SKIP LOCKED`、unique constraint、
> 状态条件更新和 batch claim。MyBatis 让 SQL 保持显式，同时减少 JDBC mapping boilerplate。

## 8. Distributed cache 与 NoSQL

### 8.1 JD 期望

JD 写的是：

```text
Experience with RDBMS, NoSQL databases along with distributed cache.
```

这句话不要简单理解成“必须把三种都塞进核心链路”。金融系统更重要的是知道：

- 哪些数据必须强一致。
- 哪些数据可以 eventual consistency。
- 哪些读模型可以 cache。
- 哪些场景适合 NoSQL。

### 8.2 当前 distributed cache 证据

当前项目已经实现：

```text
SnapshotCache<K, V>
-> TwoLevelSnapshotCache
   -> Caffeine L1
   -> Redis L2
   -> loader 回源 MySQL
```

当前 cache：

| cache name | key | value | 用途 |
| --- | --- | --- | --- |
| `statement-read-model-v1` | statement id | `StatementReadModel` | `GET /api/statements/{id}` 查询快照 |
| `card-snapshot-v1` | card id | `CardSnapshot` | authorization/posting/expiry 读取 card reference data |

相关证据：

- `spring-boot-starter-data-redis`
- `com.github.ben-manes.caffeine:caffeine`
- `spring.data.redis.*`
- `snapshot-cache.caches.*`
- `TwoLevelSnapshotCache`
- `CachedCardRepository`
- `StatementReadModelService`
- `TransactionAwareSnapshotCacheEvictor`
- `docs/cache-snapshot-design-cn.md`

### 8.3 Cache 回答口径

可以这样讲：

> 我们用了 Caffeine L1 + Redis L2 的 two-level cache。L1 解决单 JVM 热点读取，
> L2 解决多实例共享；miss 后通过 loader 回源 MySQL。cache 只保存可重建 snapshot，
> 比如 statement read model 和 card reference data。Redis 故障、JSON 损坏或 miss 都会回源 DB，
> 所以 cache 不影响主业务正确性。

继续补一句金融边界：

> 我不会缓存 `availableCredit`、`reservedAmount`、`postedBalance` 或幂等 winner。
> 这些是 money-changing write model，必须靠 database transaction、unique constraint 和 `row lock`。

### 8.4 NoSQL 当前缺口和合理回答

当前项目没有 NoSQL 实现。不要说做过。

可以这样讲：

> 当前项目把核心交易状态放在 MySQL，因为 authorization、posting、statement、repayment 需要强事务、
> row lock、唯一约束和审计。NoSQL 更适合放在可重建或访问模式明确的 read model 上，
> 例如 notification feed、risk feature document、event query projection、operational dashboard。
> 如果使用 DynamoDB，我会先按 access pattern 设计 partition key/sort key，再评估 hot partition、
> conditional write、TTL、GSI 和 replay 重建能力，而不是把额度余额直接搬过去。

如果要补项目，建议先补文档，不急着引入 NoSQL 依赖：

```text
docs/nosql-tradeoffs-cn.md
-> 哪些表不能迁移到 NoSQL
-> 哪些 projection 可以用 DynamoDB
-> eventual consistency 和 replay 如何解释
```

## 9. High traffic system design

### 9.1 当前项目已经体现的高流量设计

| 设计点 | 项目证据 | high traffic 意义 |
| --- | --- | --- |
| 提前 validation | DTO + Bean Validation | 无效请求不进入事务 |
| 风控在 account lock 前 | `AuthorizationService.decideAndReserve` | 缩短 `row lock` critical section |
| Kafka publish 不进主事务 | Outbox worker | 主请求不等待 broker ack |
| Notification/Risk/Ledger 异步 | Kafka consumers | 下游失败不阻塞主交易 |
| Worker pool 有界 | Outbox/DelayJob properties | 避免无限线程和内存堆积 |
| Lease + recoverer | Outbox/DelayJob | worker 宕机可恢复 |
| Redis/Caffeine cache | `SnapshotCache` | 降低低风险读模型 DB 压力 |
| Actuator metrics | `/actuator/metrics` | 支持延迟、线程、GC、健康检查 |
| Kafka partition key | Outbox event partition key | 保持局部顺序并扩展 consumer |

### 9.2 高并发授权变慢怎么排查

推荐回答顺序：

1. 看接口 latency breakdown：是 controller、risk、DB、Kafka、cache 还是连接池。
2. 看 DB slow query、`EXPLAIN`、lock wait、Hikari pending。
3. 看同一 credit account 是否形成热点。
4. 看 external risk latency 和 CircuitBreaker 状态。
5. 看 Outbox backlog，但主请求不应等待 Kafka publish。
6. 看 JVM：CPU、GC pause、thread count、blocked/waiting 状态。
7. 看 cache hit ratio：statement/card snapshot 的 miss 是否回源太多。

可以这样回答：

> 我会先定位瓶颈，不会直接加机器。授权慢可能是外部风控慢、DB row lock 等待、索引问题、
> connection pool 饱和或热点账户。项目里已经把风控放在 account lock 前，把 Kafka publish 移到 Outbox worker，
> 这都是为了缩短主事务。进一步优化可以做账户级限流、热点账户保护、读模型 cache、DB 索引优化和 partition key 调整。

### 9.3 当前还缺什么

为了更贴 JD 的 high traffic，可以补：

| 优先级 | 建议 | 价值 |
| --- | --- | --- |
| P0 | `docs/high-traffic-system-design-cn.md` | 把容量、瓶颈、扩容策略系统化 |
| P0 | k6/Gatling 脚本 | 有实际压测入口 |
| P1 | 指标清单 | p95/p99、DB pool、lock wait、Kafka lag、cache hit ratio |
| P1 | backpressure 策略 | worker queue 满、限流、retry/DEAD 的解释 |

## 10. AWS、ECS、CloudFormation、CloudWatch

### 10.1 JD 期望

JD 公司栈里写到：

- All services run in AWS。
- Deployment relies on AWS ECS。
- CI/CD is handled by AWS CodePipeline。
- Infrastructure is managed by AWS CloudFormation。
- AWS CloudWatch is used for observability。

### 10.2 当前项目真实状态

当前项目没有 AWS 资源定义，也没有 ECS/CodePipeline/CloudFormation 配置。

已有的可迁移基础：

- Docker Compose 本地运行 MySQL、Kafka、Redis。
- Spring Boot Actuator 暴露 health、liveness、readiness、metrics。
- `/actuator/health/readiness` 包含 DB readiness。
- 应用日志使用 Spring Boot logging。
- Kafka/Outbox/DelayJob 都有 backlog、status、retry、DEAD 等可观测对象。

### 10.3 interview 里怎么映射到 AWS

可以这样讲：

> 当前项目没有真正部署到 AWS，但它的运行边界适合迁移到 ECS：Spring Boot app 做成 container，
> ECS service 挂 ALB，ALB health check 指向 liveness/readiness；MySQL 可以对应 RDS，
> Redis 可以对应 ElastiCache，Kafka 可以对应 MSK 或托管 Kafka。CloudWatch 收集 application logs、
> ECS CPU/memory、ALB 5xx/latency、RDS connection/lock、Redis metrics、Kafka lag，以及 Actuator/Micrometer 指标。

CloudFormation 可以这样拆：

```text
network stack
-> VPC, subnets, security groups

data stack
-> RDS MySQL
-> ElastiCache Redis
-> MSK / Kafka dependency

app stack
-> ECS cluster
-> task definition
-> ECS service
-> ALB target group
-> IAM task role
-> CloudWatch log group

pipeline stack
-> CodePipeline
-> CodeBuild
-> ECR image
-> ECS deploy action
```

### 10.4 当前建议补强

如果要让 JD 匹配更完整，建议补一份：

```text
docs/aws-ecs-deployment-cn.md
```

内容不必一开始就能生产部署，但应该覆盖：

- ECS task definition 里如何配置 DB/Kafka/Redis env vars。
- liveness/readiness 如何接 ALB / ECS health check。
- CloudWatch alarm 应该看哪些指标。
- CodePipeline 从 GitHub 到 ECR 到 ECS 的基本路径。
- CloudFormation stack 边界怎么拆。

## 11. Microservices 与 event-driven architecture

### 11.1 当前项目不是物理微服务

当前项目是 modular monolith。它没有拆成多个独立部署服务。

但是它已经有未来拆分的边界：

| Bounded context | 当前包 | 未来可拆分点 |
| --- | --- | --- |
| Authorization | `authorization` | 实时授权服务 |
| Transaction / Posting | `transaction` | clearing/presentment 入账服务 |
| Statement | `statement` | billing 服务 |
| Repayment | `repayment` | repayment / auto debit 服务 |
| Notification | `notification` | 通知服务 |
| Risk | `risk` | 风控服务 |
| Ledger | `ledger` | 账务 projection / accounting 服务 |

### 11.2 为什么现在不直接拆

可以这样讲：

> 我没有为了展示 microservices 就把项目拆成多个进程。authorization、posting、statement、repayment
> 都有强事务和清晰锁顺序，学习阶段先放在一个 modular monolith 里，更容易保证 correctness。
> 但通过 package boundary、repository port、Outbox event contract、consumer group 和 Inbox，
> 已经把未来拆分边界表达出来。真正拆服务时，最大的成本不是 HTTP 调用，而是分布式一致性、contract versioning、
> deployment、observability 和 failure recovery。

### 11.3 gRPC 当前缺口

当前项目没有 gRPC。

合理回答：

> 当前 public API 用 REST 更适合调试和展示。gRPC 更适合内部 service-to-service 调用，例如 Risk service
> 或 customer profile service。如果未来补 gRPC，我会优先把 `ExternalRiskGateway` 背后的模拟 REST client
> 做成一个可切换 adapter，保持 application service 只依赖 gateway port。

## 12. Observability 与 operations

### 12.1 当前项目证据

当前已有：

- `HealthController` 暴露轻量 `/api/health`。
- Spring Boot Actuator 暴露 `/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`、`/actuator/metrics`。
- readiness 包含 `db`。
- `docs/jvm-monitoring-learning-cn.md` 解释 JVM memory、GC、threads、Actuator。
- `docs/thread-runtime-learning-cn.md` 解释 Tomcat、scheduler、worker、Kafka listener 线程模型。
- Outbox/DelayJob 有 `PENDING`、`PROCESSING`、`PUBLISHED/DONE`、`DEAD` 和 attempts，可作为 backlog 与失败恢复指标。

### 12.2 CloudWatch 口径

可以这样讲：

> 如果部署到 AWS，我会把 application logs 输出到 CloudWatch Logs，
> 用 ECS/Container Insights 看 CPU、memory、restart count，用 ALB metrics 看 4xx/5xx 和 target response time，
> 用 RDS metrics 看 connection、CPU、lock wait、slow query，用 Redis metrics 看 hit/miss、eviction、latency，
> 用 Kafka/MSK metrics 看 consumer lag 和 broker health。Actuator/Micrometer 的 JVM、HTTP、Hikari 指标可以进一步接到 CloudWatch 或 Prometheus。

### 12.3 生产告警建议

| 告警 | 为什么重要 |
| --- | --- |
| Authorization p95/p99 latency | 实时授权路径变慢会影响用户刷卡体验 |
| HTTP 5xx / 409 ratio | 区分系统错误和幂等 conflict |
| DB connection pool pending | 连接池耗尽会放大所有请求延迟 |
| MySQL lock wait / deadlock | 直接影响 money-changing transaction |
| Outbox PENDING/DEAD backlog | 表示事件发布延迟或失败 |
| DelayJob PENDING/DEAD backlog | 表示授权过期/自动还款可能延迟 |
| Kafka consumer lag | 下游 Notification/Risk/Ledger 是否追上 |
| Redis error / cache hit ratio | cache 是否在降级回 DB |
| JVM GC pause / heap usage / thread count | 判断高流量下 runtime 是否稳定 |

## 13. Documentation、stakeholder management 与 multicultural

JD 提到 Confluence、Miro、JIRA、Slack、Zoom、Office 365，以及 multicultural environment。

当前项目不能证明你真实使用这些公司工具，但可以证明你有结构化沟通习惯：

- `README.md` 是项目入口。
- `docs/paypay-card-backend-interview-guide-cn.md` 是总复习手册。
- `docs/implementation-walkthrough-cn.md` 是 request-to-table walkthrough。
- `docs/kafka-learning-cn.md`、`docs/mybatis-sql-learning-cn.md`、`docs/cache-snapshot-design-cn.md` 是专题学习材料。
- `docs/trilingual-glossary-cn.md` 做中文、English、日本語对照。

可以这样讲：

> 我习惯把复杂设计写成可复用文档：先写业务目标，再写实现链路、关键类、表、失败场景和 trade-off。
> 当前项目里我把 credit-card issuer 术语、Java/Spring 实现、Kafka/DB/cache 机制和 Japanese glossary 都整理出来，
> 是为了让不同背景的人能对齐同一套系统语言。

## 14. Gap 排序与下一步建议

按 JD 命中率和当前项目缺口排序：

| 优先级 | 建议 | 原因 |
| --- | --- | --- |
| P0 | 高流量设计与压测文档 | JD 明确要求 high traffic systems，当前有设计点但缺压测证据 |
| P0 | AWS ECS / CloudFormation / CloudWatch 设计文档 | 公司栈强相关，当前只有可迁移基础 |
| P1 | NoSQL trade-off 文档 | JD 提到 NoSQL，核心交易不该硬迁移，文档解释最合适 |
| P1 | gRPC Risk adapter 小切片 | Preferred qualification，做小而清楚即可 |
| P1 | 最小 Reconciliation | 贴近金融后台运营异常处理，比继续堆 refund/dispute 更有价值 |
| P2 | Java EE / Boot2 modernization 笔记 | 补 legacy 迁移口径 |
| P2 | CodePipeline skeleton | 如果 AWS 文档后还有时间，可以补 CI/CD artifact |

不建议优先做：

- 真拆 microservices。
- 把核心交易改成 NoSQL。
- 为了展示 Redis 缓存额度。
- 一口气实现 refund、dispute、settlement。
- 加 user login/authentication。

这些都会稀释当前项目最强的主线：金融交易正确性和可靠性。

## 15. JD 问题回答模板

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

> 有，在项目里我做了 Caffeine L1 + Redis L2 的 `SnapshotCache`。
> 它是 read-through cache：先查本地 Caffeine，再查 Redis，miss 后回源 MySQL。
> 我只缓存可从 DB 重建的 snapshot，例如 statement read model 和 card reference data。
> 额度、幂等 claim 和还款入账不缓存，因为这些必须依赖 DB transaction 和 row lock。

### Q3：你如何解释 NoSQL 经验不足？

答：

> 当前项目没有使用 NoSQL，因为核心交易路径需要强事务、row lock、unique constraint 和审计。
> 但我知道 NoSQL 更适合 access pattern 明确、可重建或 eventual consistency 的 read model，
> 例如 notification feed、risk feature document、event query projection。
> 如果用 DynamoDB，我会先设计 partition key/sort key、conditional write、TTL、GSI 和 replay 策略，
> 不会把 credit account balance 这种强一致数据直接迁过去。

### Q4：你如何设计高流量授权接口？

答：

> 我会先保证 correctness，再优化 throughput。授权请求先做 validation 和 idempotency claim，
> 便宜的 policy check、card check 和 risk check 放在 account row lock 前，减少 critical section。
> 真正额度变化时用 `SELECT ... FOR UPDATE` 锁 account row。Kafka publish、notification、ledger projection
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

## 16. 复习路线

如果只有 30 分钟：

1. 背熟第 2 节一分钟定位。
2. 看第 3 节总体匹配度。
3. 练第 15 节 Q1、Q2、Q4、Q5。

如果有 2 小时：

1. 看 `docs/paypay-card-backend-interview-guide-cn.md` 的主链路。
2. 看本文件第 5、6、8、9、10 节。
3. 看 `docs/cache-snapshot-design-cn.md` 和 `docs/thread-runtime-learning-cn.md`。
4. 用自己的话讲一遍：authorization 请求如何从 HTTP 到 DB lock，再到 Outbox/Kafka/cache/metrics。

如果有 1 天：

1. 补 `docs/high-traffic-system-design-cn.md`。
2. 补 `docs/aws-ecs-deployment-cn.md`。
3. 补 `docs/nosql-tradeoffs-cn.md`。
4. 选做 gRPC Risk adapter 或最小 Reconciliation。

最后记住这个回答原则：

```text
先讲业务正确性，再讲技术机制，再讲扩展 trade-off。
不要为了 JD 关键词而牺牲金融系统的正确性边界。
```

## 17. 技术 interview 官视角：他们真正想验证什么

这一节开始，不再按 JD 条目罗列。

它从 interview 官视角反推：

- 哪些点会被追问。
- 哪些回答会显得真实。
- 哪些回答会暴露你只是背技术名词。
- 哪些项目细节可以拿出来抗压。

一个真实后端技术 interview 通常不会停在：

```text
你用过 Kafka 吗？
你用过 Redis 吗？
你用过 MySQL 吗？
```

它会继续追问：

```text
Kafka 发送成功但 DB 回滚怎么办？
Redis 里是旧数据怎么办？
两个请求同时刷同一个账户会不会超额？
worker publish Kafka 后宕机，Outbox row 还是 PROCESSING 怎么办？
为什么不用 Java synchronized？
为什么不把所有东西拆成 microservices？
如果上 AWS，你怎么设计 health check、alarm、rollback？
```

所以这份补充内容按真实追问组织。

回答时建议始终用这个结构：

```text
结论
-> 项目里怎么做
-> 为什么这样做
-> 失败或并发场景会怎样
-> 代价和下一步改进
```

interview 官通常在看四种能力：

| 能力 | 表面问题 | 真正考察 |
| --- | --- | --- |
| 编码能力 | 你怎么写 Spring Boot API | 分层、validation、测试、可维护性 |
| 数据正确性 | 怎么防重复扣钱 | 事务、锁、幂等、约束、状态机 |
| 分布式能力 | Kafka/Redis 怎么用 | failure mode、at-least-once、stale data、backpressure |
| 生产意识 | 高流量怎么处理 | latency、capacity、observability、rollback、降级 |

最危险的回答风格：

- 一上来就说“加 Redis”。
- 一上来就说“拆微服务”。
- 只说“Kafka 保证异步可靠”。
- 只说“加 synchronized 保证并发安全”。
- 只说“用事务就好了”。
- 遇到没做过的 AWS/NoSQL/gRPC 直接硬装。

更好的风格：

- 先守住 source of truth。
- 先说明 transaction boundary。
- 再说明哪些地方可以 eventual consistency。
- 再说明 cache、Kafka、worker、cloud deployment 的 trade-off。
- 对没做的东西，讲清合理落点和迁移路径。

## 18. interview 官会怎么给这个项目打分

下面是一个偏真实的评分视角。

它不是官方标准，但很贴后端系统设计和金融交易岗位。

| 评分项 | 强证据 | 弱证据 | 当前项目状态 |
| --- | --- | --- | --- |
| 业务理解 | 能讲 authorization/posting/statement/repayment 状态变化 | 只说“支付系统” | 强 |
| 金额正确性 | `BigDecimal`, `DECIMAL`, currency check, invariant | 用 `double` 或普通数字 | 强 |
| 幂等设计 | API key、业务 key、Kafka event key 分层 | 只说“前端不要重复点” | 强 |
| 并发控制 | unique constraint + `FOR UPDATE` + lock order | JVM lock 或 check-then-insert | 强 |
| 事务边界 | 能说哪些必须同事务，哪些不能同事务 | 把 Kafka/通知放进主事务 | 强 |
| Event-driven | Outbox/Inbox/DLT/版本/partition key | 直接 DB commit 后 send Kafka | 强 |
| Cache 判断 | 缓存 snapshot，不缓存额度 | Redis 当 source of truth | 强 |
| NoSQL 判断 | 讲 access pattern 和 consistency | 为了 JD 硬迁核心表 | 中弱 |
| High traffic | lock scope、pool、backlog、metrics | 只说“加机器” | 中强 |
| AWS | 能映射 ECS/RDS/ElastiCache/CloudWatch | 没有部署概念 | 中弱 |
| Legacy | 能讲 modernization 风险 | 只说“重写” | 中 |
| 沟通 | 文档结构、trade-off、glossary | 只有代码没有解释 | 强 |

如果 interview 官很严格，他可能会说：

```text
你项目做得挺完整，但有没有真实生产部署？
有没有真实高流量压测？
有没有 NoSQL 实操？
有没有 gRPC？
有没有真实 AWS pipeline？
```

这时不要防御。

好的回应是：

```text
这些是当前学习项目还没有完全覆盖的部分。
但我已经把核心交易正确性、异步可靠性和 cache 边界做出来了。
如果继续补，我会先补 high traffic 压测和 AWS ECS/CloudWatch 设计，
NoSQL 会放在 read model/projection，不会替代核心 MySQL transaction。
```

这比虚构经验可靠得多。

## 19. 深挖题库：业务主链路

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
> 所以项目创建 `statement` 和 `statement_items`，把本周期 posted transactions 固定下来。

项目锚点：

- `StatementService.generate(...)`
- `statement_items`
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
> 在项目里，这体现为 `Statement.paidAmount/status` 前进，同时 `CreditAccount.postedBalance` 减少。
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
- `StatementNotificationListener`
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

### Q15：Ledger 在项目里为什么只是 minimal projection？

强回答：

> 当前 `Ledger` 是学习 projection，用来说明 posted transaction 和 repayment 如何形成内部账务记录。
> 它不是生产级 double-entry general ledger。
> 生产 ledger 需要科目、借贷平衡、调整、冲正、审计、reconciliation 和报表。

项目锚点：

- `RecordLedgerEntryService`
- `LedgerEntry`
- `CardTransactionLedgerListener`
- `RepaymentLedgerListener`

追问方向：

- 为什么 CardTransaction 不是 ledger？
- 为什么 ledger 通过 Kafka 消费，而不是主事务直接写？
- 如果 ledger consumer 落后怎么办？

强补充：

> CardTransaction 是客户可见交易流水，Ledger 更接近内部会计事实。
> 当前用 Kafka projection 展示边界，避免把主交易事务拖得过重。

## 20. 深挖题库：幂等、事务、锁

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
- `StatementService`
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
> 如果状态变了但没有 Outbox event，下游 Notification/Risk/Ledger 会丢业务事实。

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

## 21. 深挖题库：Kafka、Outbox、Inbox、DelayJob

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
- `RiskFeatureProjectionService`
- `RecordLedgerEntryService`

追问方向：

- 每个 consumer 都共用一个 inbox 吗？
- 同一个 event 被不同 bounded context 消费怎么办？
- Inbox row 要保留多久？

强补充：

> Inbox key 要包含 consumer name。
> 因为同一事件可能被 Notification、Risk、Ledger 各自处理一次。

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
- `EventContractException`
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
- Ledger 是否需要顺序？

强补充：

> 对 notification 这类用户展示 side effect，可能可以按事件独立处理。
> 对 ledger/accounting，需要更严格的 source event ordering 或状态校验。

## 22. 深挖题库：Cache、Redis、NoSQL

### Q39：你们为什么不用 Spring `@Cacheable`？

强回答：

> 项目选择显式 `SnapshotCache`，因为我们想把 cache boundary 写清楚。
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

### Q43：Card snapshot cache 有什么风险？

强回答：

> Card snapshot 会参与 authorization 决策，所以风险高于纯展示型 cache。
> stale ACTIVE 可能让刚 blocked 的卡在短 TTL 内继续通过 card lifecycle check。
> 因此项目把 TTL 设短，并明确未来 block/unblock API 必须 after-commit evict。

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

> 项目 cache name 带版本，例如 `statement-read-model-v1`。
> 如果 value schema 不兼容，升级 cache name 到 v2，让旧 key 自然过期。
> 如果只是兼容新增字段，可以用默认值或兼容 reader。

追问方向：

- 大规模 key 如何清理？
- 反序列化失败怎么办？
- 是否需要 cache migration？

强补充：

> `TwoLevelSnapshotCache` 遇到 Redis JSON 损坏会删除坏 value 并回源 DB。
> 这比让坏缓存持续影响请求更安全。

### Q46：NoSQL 在这个项目里最适合放哪里？

强回答：

> 适合放可重建、访问模式明确、eventual consistency 可接受的 read model。
> 例如 notification feed、risk feature document、event query projection、operational dashboard。
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

- Risk feature projection 是 read model 吗？
- Ledger projection 能 cache 吗？
- Cache miss 如何重建？

强补充：

> 项目里的 Risk feature projection、Ledger entry 更像异步投影。
> 它们可以有自己的查询优化，但写入正确性仍靠 Kafka consumer 幂等和 DB 约束。

## 23. 深挖题库：High traffic 和性能设计

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

> 项目设置 Redis timeout，并在 `TwoLevelSnapshotCache` 里 catch Redis read/write failure。
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

- `docs/jvm-monitoring-learning-cn.md`
- `docs/thread-runtime-learning-cn.md`
- Actuator JVM metrics

追问方向：

- 看哪些指标？
- G1 和 ZGC 怎么选？
- Thread dump 里 DB lock 是 Java BLOCKED 吗？

强补充：

> 等 MySQL row lock 的线程通常在 JDBC/socket 调用里，不一定显示 Java `BLOCKED`。
> 要结合 MySQL lock wait、slow query、Hikari metrics。

## 24. 深挖题库：AWS、部署、观测、CI/CD

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

## 25. 深挖题库：DDD、OOP、架构演进

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
> 更适合先拆 Notification、Risk projection、Ledger projection 这类异步下游。
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

## 26. 深挖题库：Java、Spring、测试、Legacy

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

## 27. 真实系统设计题：从 0 设计一个信用卡授权系统

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
-> Notification/Risk/Ledger consumer
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

## 28. 真实排障题

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
4. 看 `StatementSnapshotCacheInvalidator` 是否调用。
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
- Notification/Risk/Ledger 哪个慢。
- DB side effect 是否慢。
- DLT 是否增长。
- 是否 poison message 重试。
- partition 数和 concurrency 是否匹配。

风险：

- Notification 延迟。
- Risk feature projection 落后。
- Ledger projection 落后。

回答模板：

> 我会先判断 lag 的业务影响。
> 如果是 notification，可以接受短暂延迟但要报警。
> 如果是 risk feature projection，可能影响后续风控准确性，需要更严肃处理。

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

## 29. 反向提问准备

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

## 30. 最后一周复习计划

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

## 31. 红旗回答与更好版本

### 红旗 1：用 Redis 保证额度一致

不好：

```text
额度高并发就放 Redis，用 atomic increment。
```

更好：

```text
Redis 可以做限流或 cache，但额度 source of truth 仍在 MySQL。
Authorization 要和 authorization row、Outbox、DelayJob 同事务，所以核心并发靠 `FOR UPDATE`。
```

### 红旗 2：Kafka 保证消息 exactly-once

不好：

```text
Kafka 可以保证消息不重复，所以不用担心。
```

更好：

```text
Kafka 常按 at-least-once 设计。
Producer/consumer 侧都要考虑重复。
项目用 Outbox 保证 publish intent durable，用 Inbox/unique constraint 保证 consumer side effect 幂等。
```

### 红旗 3：事务能解决所有一致性问题

不好：

```text
加 `@Transactional` 就好了。
```

更好：

```text
`@Transactional` 只定义本地 DB transaction。
它不能覆盖 Kafka、Redis、外部风控。
所以要明确本地强一致边界，再用 Outbox、Inbox、retry、cache invalidation 处理分布式边界。
```

### 红旗 4：微服务一定更好

不好：

```text
系统大了就拆微服务。
```

更好：

```text
先看一致性边界和团队边界。
当前项目先用 modular monolith 保持核心交易事务简单。
Notification/Risk/Ledger 这种异步下游更适合先拆。
```

### 红旗 5：NoSQL 更高性能，所以替换 MySQL

不好：

```text
高并发就把 MySQL 换 DynamoDB。
```

更好：

```text
NoSQL 要按 access pattern 设计。
核心授权额度需要 transaction、row lock、unique constraint 和审计，仍适合 RDBMS。
NoSQL 可以放 notification feed、risk feature、event projection。
```

### 红旗 6：CloudWatch 只看 CPU

不好：

```text
线上看 CPU 和 memory。
```

更好：

```text
CPU/memory 只是基础。
授权系统还要看 p95/p99、DB lock wait、connection pool、Kafka lag、Outbox/DelayJob backlog、
Redis hit ratio、GC pause 和业务 decline reason 分布。
```

### 红旗 7：只背项目，不承认缺口

不好：

```text
我这个项目已经覆盖所有 JD。
```

更好：

```text
项目强项是金融交易正确性、Kafka reliability、cache boundary 和 JVM/Actuator 观测。
还没有真实 AWS deployment、NoSQL implementation、gRPC 和压测。
我会优先补 high traffic 压测和 AWS ECS/CloudWatch 设计。
```

## 32. 最终压缩版：强回答素材

如果只记 12 句话，记这些：

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

最终 60 秒硬核版：

> 我的项目是一个 credit card issuer backend，核心是 authorization、posting、statement、repayment。
> 我用 Spring Boot 和 MySQL/MyBatis 实现主交易链路，用 domain object 管状态机和金额 invariant。
> 对重复请求，API 层用 `Idempotency-Key` 和数据库 unique constraint；对同账户并发额度，使用
> `SELECT ... FOR UPDATE` 锁 `credit_accounts` 行；对异步通知、风控投影、ledger 投影，使用
> Transactional Outbox + Kafka + Consumer Inbox，承认 at-least-once 并让 consumer 幂等。
> 对读扩展，我实现了 Caffeine L1 + Redis L2 的 `SnapshotCache`，只缓存 statement read model 和 card snapshot，
> 不缓存额度或幂等 winner。生产上我会用 Actuator/CloudWatch 观测 API latency、DB lock wait、
> Kafka lag、Outbox/DelayJob backlog、Redis hit ratio 和 JVM GC。这个项目还没真实上 AWS 和 NoSQL，
> 但我能说明 ECS/RDS/ElastiCache/MSK 的部署映射，以及 NoSQL 更适合 read model 而不是核心账务 transaction。
