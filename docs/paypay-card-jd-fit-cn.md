# PayPay Card JD 对照 interview 手册

这份文档把 PayPay Card Backend Engineer JD 拆成可复习、可讲述、可举证的能力清单，
并映射到当前 mini-card-payment-system 的真实代码、文档和还没有覆盖的缺口。

它的目标不是把 JD 每个单词机械翻译一遍，而是帮你在 interview 里做到三件事：

1. 先讲项目和岗位最相关的能力，而不是从技术清单开始背。
2. 每个能力都能落到当前项目里的类、表、配置、测试或文档。
3. 对没做的内容诚实说明 trade-off，并给出合理的下一步设计。

> [!IMPORTANT]
> 这份文档的主线不是“我用过哪些技术”，而是“我如何用这些技术保护金融后端的正确性、可恢复性和可扩展性”。

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
> MySQL、MyBatis、Kafka、Redis/Caffeine 和 Gradle 实现。它覆盖发卡方核心链路：
> authorization 授权占额度、presentment posting 入账、statement 出账、repayment 还款，
> 以及异步 Notification 下游；Risk 保持在授权实时决策链。
>
> 这个项目的重点不是 API 数量，而是金融后端的可靠性设计。比如授权用
> `Idempotency-Key` 和数据库唯一约束防重复占额度；额度变化用 MySQL
> `SELECT ... FOR UPDATE` 做 `row lock`；业务状态和 Outbox event 在同一个
> `transaction boundary` 提交；Kafka 只做 at-least-once delivery，consumer 侧用
> Inbox 或唯一约束保证副作用幂等；statement GET 读扩展用 Caffeine L1 + Redis L2 cache，但不缓存额度和幂等 winner。
>
> 所以这个项目可以从 HTTP request 一直讲到 domain state transition、MySQL commit、
> Kafka async delivery、cache boundary、failure recovery 和 production monitoring。

> [!WARNING]
> 不要把项目讲成技术清单。interview 官听到“我做了一个支付系统，有 Kafka，有 Redis”之后，通常会继续追问具体失败场景。

```text
我做了一个 Spring Boot 支付系统，有 MySQL、Kafka、Redis。
```

> [!TIP]
> 更好的表达方式是把技术和金融问题绑定在一起：

```text
我用这些技术解决了金融交易里的重复请求、并发额度、异步消息可靠性和读扩展边界。
```

## 3. 总体匹配度

| JD 能力 | 当前匹配度 | 项目证据 | 需要注意 |
| --- | --- | --- | --- |
| Java / Spring Boot | 强 | Java 21、Spring Boot 3.5、controller/service/domain/repository 分层 | JD 写 Java 11/17 + Boot2/3，项目更现代，要能讲版本迁移 |
| OOP / DDD | 强 | `Authorization`, `CreditAccount`, `Statement`, `Repayment`, `Money` | 强调不是为 DDD 而 DDD，Outbox/DelayJob 保持机制包 |
| RDBMS | 强 | MySQL、MyBatis XML、Liquibase、唯一约束、`FOR UPDATE` | 重点讲索引、约束、锁和事务 |
| Distributed cache | 强 | `StatementReadService` 的 Caffeine L1 + Redis L2 | 重点讲 cache boundary，不能缓存额度；Card snapshot cache 是已删除取舍 |
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
| Use case orchestration | `AuthorizationService`, `PostingService`, `StatementGenerationService`, `RepaymentService` | Service 负责 `transaction boundary` 和多 aggregate 协作 |
| Domain modeling | `Authorization`, `CreditAccount`, `CardTransaction`, `Statement`, `Repayment`, `Money` | 状态转换和金额规则放在 domain object |
| Infrastructure adapter | `MyBatisAuthorizationRepository`, `KafkaOutboxMessagePublisher`, `RedisRiskVelocityCounter` | 技术细节不泄露到 domain |
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
| 账单快照 | `statement_lines` | 账单不是临时 SUM，而是可审计 snapshot |
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
| Kafka 重复投递 | Inbox / unique source event | `ConsumerInboxRepository`, notification listeners | consumer side effect 幂等 |
| Cache 并发 | Caffeine L1 + Redis L2 + single-flight | `StatementReadService` | 减少 statement GET cache miss 打爆 DB |

### 6.3 Java lock 与 DB lock 的区别

可以这样回答：

> 我不会用 Java `synchronized` 保护额度，因为应用会横向扩容，两个请求可能落在不同 JVM。
> 额度是数据库里的共享事实，所以并发控制必须靠 MySQL row lock 和 transaction。
> Java 内的锁最多能保护单实例内的内存结构，例如 `StatementReadService` 通过 Caffeine `Cache.get` 做的 per-key single-flight；
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
-> notification intent + per-channel delivery
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
- `KafkaOutboxMessagePublisher`
- `IntegrationEventReader`
- Notification listeners

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

当前项目已经恢复一个简洁的 statement GET cache：

```text
StatementReadService
-> Caffeine L1
-> Redis L2
-> StatementGenerationService.get(id)
-> MySQL
```

当前 cache：

| cache name | key | value | 用途 |
| --- | --- | --- | --- |
| `statement-read-model-v3` | statement id | `StatementReadModel` | `GET /api/statements/{id}` 查询快照；L2 信封使用 `statements.version` |

已删除设计：

| cache name | 删除原因 |
| --- | --- |
| `card-snapshot-v1` | stale card status 会影响 authorization 判断，且单卡短 TTL 命中率有限 |

相关证据：

- `spring-boot-starter-data-redis`
- `com.github.ben-manes.caffeine:caffeine`
- `spring.data.redis.*`
- `statement.read-cache.*`
- `StatementReadService`
- `StatementReadModel`
- `StatementReadCacheProperties`
- `RepaymentService` after-commit eviction
- `docs/caching-and-rate-limiting-cn.md`

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
> 例如 notification feed、statement summary、event query projection、operational dashboard。
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
| Notification 异步 | Kafka consumers | 下游失败不阻塞主交易 |
| Worker pool 有界 | Outbox/DelayJob properties | 避免无限线程和内存堆积 |
| Lease + recoverer | Outbox/DelayJob | worker 宕机可恢复 |
| Redis/Caffeine cache | `StatementReadService` | 降低 statement GET read model 的 DB 压力 |
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
7. 看 cache hit ratio：statement read model 的 miss 是否回源太多；Card snapshot cache 当前已删除。

可以这样回答：

> 我会先定位瓶颈，不会直接加机器。授权慢可能是外部风控慢、DB row lock 等待、索引问题、
> connection pool 饱和或热点账户。项目里已经把风控放在 account lock 前，把 Kafka publish 移到 Outbox worker，
> 这都是为了缩短主事务。进一步优化可以做账户级限流、热点账户保护、读模型 cache、DB 索引优化和 partition key 调整。

### 9.3 当前还缺什么

为了更贴 JD 的 high traffic，可以补：

| 优先级 | 建议 | 价值 |
| --- | --- | --- |
| P0 | `docs/traffic-rate-limiting-and-capacity-cn.md` | 把容量、限流、瓶颈、扩容策略系统化 |
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

- Spring Boot Actuator 暴露 `/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`、`/actuator/metrics`（不自建 health controller，避免和 Actuator 重复）。
- readiness 包含 `db`。
- `docs/jvm-threads-runtime-cn.md` 解释 JVM memory、GC、Actuator 与 Tomcat/scheduler/worker/Kafka listener 线程模型。
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
| Kafka consumer lag | 下游 Notification 是否追上 |
| Redis error / cache hit ratio | cache 是否在降级回 DB |
| JVM GC pause / heap usage / thread count | 判断高流量下 runtime 是否稳定 |

## 13. Documentation、stakeholder management 与 multicultural

JD 提到 Confluence、Miro、JIRA、Slack、Zoom、Office 365，以及 multicultural environment。

当前项目不能证明你真实使用这些公司工具，但可以证明你有结构化沟通习惯：

- `README.md` 是项目入口。
- `docs/paypay-card-backend-interview-guide-cn.md` 是总复习手册。
- `docs/implementation-walkthrough-cn.md` 是 request-to-table walkthrough。
- `docs/events-outbox-inbox-kafka-cn.md`、`docs/mybatis-sql-and-migration-cn.md`、`docs/caching-and-rate-limiting-cn.md` 是专题学习材料。
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

## 15. 技术 interview 官视角：他们真正想验证什么

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

> [!WARNING]
> 下面这些回答会让人感觉你只是在背技术名词，没有真的理解 failure mode：

- 一上来就说“加 Redis”。
- 一上来就说“拆微服务”。
- 只说“Kafka 保证异步可靠”。
- 只说“加 synchronized 保证并发安全”。
- 只说“用事务就好了”。
- 遇到没做过的 AWS/NoSQL/gRPC 直接硬装。

> [!TIP]
> 更好的风格是先守住一致性边界，再展开技术取舍：

- 先守住 source of truth。
- 先说明 transaction boundary。
- 再说明哪些地方可以 eventual consistency。
- 再说明 cache、Kafka、worker、cloud deployment 的 trade-off。
- 对没做的东西，讲清合理落点和迁移路径。

## 16. interview 官会怎么给这个项目打分

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


## 17. 相关文档

- 全部问答（速记 / 模板 / 深挖 / 系统设计 / 排障 / red flag / 追问速查）：[`interview-qa-bank-cn.md`](interview-qa-bank-cn.md)
- Distributed lock 通用专题：[`distributed-lock-cn.md`](distributed-lock-cn.md)
- 复习路线、最后一周计划、压缩素材、反向提问：[`paypay-card-backend-interview-guide-cn.md`](paypay-card-backend-interview-guide-cn.md)
- 复习边界（收窄/删除/略读/学透）与锚点数字：[`paypay-card-jd-alignment-review-cn.md`](paypay-card-jd-alignment-review-cn.md)
