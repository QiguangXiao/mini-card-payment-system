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
