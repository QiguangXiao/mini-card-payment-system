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
> 以及 Notification、Risk、Ledger 这些异步下游。
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
| Use case orchestration | `AuthorizationService`, `PostingService`, `StatementService`, `RepaymentService` | Service 负责 `transaction boundary` 和多 aggregate 协作 |
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
| Kafka 重复投递 | Inbox / unique source event | `ConsumerInboxRepository`, notification/ledger/risk listeners | consumer side effect 幂等 |
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
- `docs/distributed-cache-cn.md`

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
- `docs/kafka-learning-cn.md`、`docs/mybatis-sql-learning-cn.md`、`docs/distributed-cache-cn.md` 是专题学习材料。
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

> 有，在项目里我做了 Caffeine L1 + Redis L2 的 statement GET cache。
> 它是 cache-aside：先查本地 Caffeine，再查 Redis，miss 后回源 MySQL 并写回缓存。
> 我只缓存可从 DB 重建的 statement read model；旧版 card reference data cache 已删除，因为 stale card status 会影响授权判断。
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
3. 看 `docs/distributed-cache-cn.md` 和 `docs/thread-runtime-learning-cn.md`。
4. 用自己的话讲一遍：authorization 请求如何从 HTTP 到 DB lock，再到 Outbox/Kafka/cache/metrics。

如果有 1 天：

1. 学习 `docs/traffic-rate-limiting-and-capacity-cn.md`。
2. 补 `docs/aws-ecs-deployment-cn.md`。
3. 补 `docs/nosql-tradeoffs-cn.md`。
4. 选做 gRPC Risk adapter 或最小 Reconciliation。

> [!IMPORTANT]
> 最后记住这个回答原则：先讲业务正确性，再讲技术机制，再讲扩展 trade-off。
> 不要为了 JD 关键词而牺牲金融系统的正确性边界。

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
4. 看 `StatementReadService.evictAfterCommit(statementId)` 是否在还款提交路径注册。
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

## 31. Warning / red flag 回答与更好版本

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
Notification/Risk/Ledger 这种异步下游更适合先拆。
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
NoSQL 可以放 notification feed、risk feature、event projection。
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

## 32. 最终压缩版：强回答素材

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
> `SELECT ... FOR UPDATE` 锁 `credit_accounts` 行；对异步通知、风控投影、ledger 投影，使用
> Transactional Outbox + Kafka + Consumer Inbox，承认 at-least-once 并让 consumer 幂等。
> 对读扩展，我实现了 Caffeine L1 + Redis L2 的 statement GET cache，只缓存 statement read model；
> 旧版 card snapshot cache 已删除，因为 stale card status 会影响 authorization。生产上我会用 Actuator/CloudWatch 观测 API latency、DB lock wait、
> Kafka lag、Outbox/DelayJob backlog、Redis hit ratio 和 JVM GC。这个项目还没真实上 AWS 和 NoSQL，
> 但我能说明 ECS/RDS/ElastiCache/MSK 的部署映射，以及 NoSQL 更适合 read model 而不是核心账务 transaction。

## 33. Distributed lock 专题：是什么、生产怎么做、为什么本项目不做

这一节专门回答一个容易被追问的问题：

```text
你用了 Redis cache，也做了高并发授权，为什么没有用 distributed lock？
```

> [!IMPORTANT]
> 先给结论：

```text
本项目没有使用 distributed lock，不是忘了，而是当前需要保护的共享事实都在 MySQL。
额度、幂等 winner、Outbox claim、DelayJob claim 都已经由 MySQL unique constraint、
SELECT ... FOR UPDATE、FOR UPDATE SKIP LOCKED、PROCESSING lease 和 transaction boundary 保护。
在这个系统里再引入 Redis/ZooKeeper/etcd distributed lock，反而会增加第二套一致性边界。
```

### 33.1 Distributed lock 是什么

Distributed lock 是跨多个进程、多个机器、多个 pod 协调“同一时间只有一个执行者可以处理某个资源”的机制。

普通 Java lock：

```text
synchronized
ReentrantLock
```

只能保护同一个 JVM 进程内的线程。

Distributed lock 要保护的是：

```text
app-pod-1
app-pod-2
app-pod-3
```

这些不同进程对同一个业务资源的并发访问。

典型资源 key：

```text
lock:account:{accountId}
lock:authorization:{authorizationId}
lock:job:{jobId}
lock:statement-cycle:{accountId}:{period}
```

它想表达：

```text
谁拿到 lock，谁暂时拥有处理这个资源的权利。
其他实例要等待、失败返回、重试，或者跳过。
```

Distributed lock 常见使用场景：

- 多 pod 中只允许一个 scheduler 执行某个全局任务。
- 防止多个 worker 同时处理同一个外部文件。
- 防止多个实例同时刷新同一个超热点 cache key。
- 对没有数据库 row 可以锁的外部资源做互斥。
- 做 leader election。
- 做一次性 migration 或 maintenance job 的执行保护。

注意：distributed lock 只是一种 coordination mechanism。

它本身不等于：

- transaction。
- idempotency。
- exactly-once。
- 数据库约束。
- 状态机。
- 审计记录。

这是很多人容易混淆的地方。

### 33.2 生产里常见 distributed lock 做法

#### 方式 1：Redis `SET key value NX PX ttl`

最常见的 Redis lock 是：

```text
SET lock:resource unique-owner-token NX PX 30000
```

含义：

- `NX`：key 不存在时才写入，表示抢锁。
- `PX 30000`：设置 30 秒 TTL，避免持锁进程宕机后锁永远不释放。
- `unique-owner-token`：锁 owner 身份，释放锁时要校验。

释放锁不能简单 `DEL key`。

必须用 Lua script 做 compare-and-delete：

```text
if redis.call("GET", key) == ownerToken then
  return redis.call("DEL", key)
else
  return 0
end
```

原因：

- A 拿锁后卡住。
- TTL 到期，B 拿到新锁。
- A 恢复后如果直接 `DEL key`，会误删 B 的锁。

生产要点：

- value 必须是 unique token。
- unlock 必须校验 token。
- TTL 必须有限。
- 业务执行时间必须小于 TTL，或者要有续期机制。
- Redis timeout 要短。
- lock acquire 失败要有明确策略：等待、快速失败、重试、跳过。
- 被锁保护的业务操作仍要 idempotent。

典型问题：

- 业务执行超过 TTL，锁自动过期，另一个实例进来并发执行。
- 持锁者 GC pause 或网络卡顿，恢复后以为自己仍然持锁。
- Redis master failover 时可能丢锁状态。
- 锁释放失败，直到 TTL 才恢复。
- 缺少 fencing token 时，旧 owner 可能覆盖新 owner 结果。

#### 方式 2：Redis Redlock

Redlock 是 Redis 多节点锁算法，尝试在多个独立 Redis 节点上拿锁。

它的目标是降低单 Redis 节点故障导致锁错误的概率。

但是 interview 里不要把 Redlock 说成万能强一致锁。

更稳妥的表达：

```text
Redlock 可以提高 Redis lock 的可用性和容错性，但它仍然依赖时间、TTL、网络假设。
如果业务是金融账务强一致更新，我不会只靠 Redlock 决定余额正确性。
```

如果生产系统使用 Redlock，仍然要考虑：

- clock drift。
- network partition。
- Redis failover。
- lock TTL 与业务执行时间。
- owner token。
- fencing token。
- 幂等和补偿。

#### 方式 3：ZooKeeper / etcd / Consul lease

ZooKeeper、etcd、Consul 更常用于 coordination。

典型机制：

- ephemeral node。
- session。
- lease。
- compare-and-swap。
- watch。
- revision。

它们比普通 Redis lock 更适合：

- leader election。
- service coordination。
- 配置一致性。
- 长生命周期 ownership。

但它们也不是免费午餐：

- 运维复杂度更高。
- 延迟通常高于 Redis。
- client session 语义要理解清楚。
- 仍要处理业务幂等。
- 仍要处理旧 owner 恢复后的写入问题。

生产里如果用 etcd，比较好的做法是配合 `fencing token`。

例如：

```text
worker A gets lock with fencing token 10
worker B later gets lock with fencing token 11
downstream DB only accepts writes with token >= current token
```

这样即使 A 之后恢复，也不能用旧 token 覆盖 B 的结果。

#### 方式 4：数据库锁

数据库本身也可以做 coordination。

常见形式：

- row lock：`SELECT ... FOR UPDATE`
- unique constraint：insert-first claim
- `FOR UPDATE SKIP LOCKED`：多 worker batch claim
- advisory lock：例如 MySQL `GET_LOCK` 或 PostgreSQL advisory lock
- lease column：`status`, `locked_until`, `owner`

本项目主要使用：

```text
unique constraint
SELECT ... FOR UPDATE
FOR UPDATE SKIP LOCKED
PROCESSING lease
```

这不是传统意义上的 Redis distributed lock，但它解决的是同一类“多实例协调”问题，而且和业务数据在同一个 MySQL 事务里。

这就是本项目选择它的核心原因。

### 33.3 生产 distributed lock 必须回答的 10 个问题

如果 interview 官问你“生产里怎么做 distributed lock”，不要只说 `SETNX`。

你应该主动回答这些问题：

| 问题 | 为什么重要 |
| --- | --- |
| lock key 怎么设计？ | key 太粗会降低并发，key 太细保护不了共享资源 |
| lock value 是什么？ | 需要 unique owner token 防误删 |
| TTL 多长？ | 太短会并发执行，太长会故障恢复慢 |
| 业务超时怎么办？ | 执行时间超过 TTL 是经典事故来源 |
| 要不要续期？ | 续期要处理 owner 存活、网络、GC pause |
| unlock 是否校验 owner？ | 不校验会删掉别人的锁 |
| acquire 失败怎么办？ | 等待、快速失败、重试、跳过是不同业务语义 |
| 旧 owner 恢复怎么办？ | 需要 fencing token 或下游状态校验 |
| 锁服务不可用怎么办？ | 是 fail closed，还是降级，还是走 DB 兜底 |
| 被锁保护的操作是否幂等？ | distributed lock 不能替代 idempotency |

一个比较完整的生产回答：

> 如果我必须使用 Redis distributed lock，我会用 `SET key value NX PX ttl` 获取锁，
> value 是唯一 owner token，释放时用 Lua compare-and-delete。
> TTL 要小于业务可接受的故障恢复时间，并且业务执行要有 timeout。
> 对可能超过 TTL 的任务，要么做安全续期，要么把任务拆短。
> 对会写入下游持久化状态的场景，最好加 fencing token 或状态版本校验。
> 同时，锁保护的业务操作仍然要 idempotent，因为锁可能因为 TTL、网络、failover、GC pause 出现边界问题。

### 33.4 Distributed lock 的典型事故场景

#### 事故 1：TTL 过期导致双执行

```text
T1: worker A gets lock, TTL = 30s
T2: worker A pauses for 40s because GC/network/slow DB
T3: lock expires
T4: worker B gets lock and starts processing
T5: worker A resumes and continues writing
```

如果没有 fencing token 或状态校验，A 和 B 可能同时写业务状态。

这就是为什么 lock 不等于 transaction。

#### 事故 2：误删别人的锁

```text
A gets lock value=A-token
A pauses
TTL expires
B gets lock value=B-token
A resumes
A DEL lock key
```

如果 A 不校验 value，B 的锁被删掉。

所以 unlock 必须 compare owner token。

#### 事故 3：锁拿到了，DB transaction 失败

```text
get Redis lock success
write DB row 1 success
write DB row 2 fails
rollback DB
release lock
```

如果业务没有审计和幂等，外部观察可能很难知道这次到底执行到哪里。

所以金融系统仍然需要 DB transaction 和状态机。

#### 事故 4：DB transaction 还没提交，锁已经释放

```text
get Redis lock
start DB transaction
update account
release Redis lock too early
another worker gets lock
first transaction not committed yet
```

这会让第二个 worker 基于不稳定状态做决策。

所以锁生命周期必须覆盖真实 critical section。

如果 critical section 本质就是 DB row update，用 DB row lock 更自然。

#### 事故 5：没有 fencing token，旧 owner 覆盖新 owner

```text
A gets lock with old ownership
A pauses
B gets new lock and writes correct result
A resumes and writes stale result
```

如果 downstream 不检查 owner version，旧写入仍然可能成功。

这就是生产 distributed lock 里经常强调 fencing token 的原因。

### 33.5 为什么本项目不使用 distributed lock

本项目的核心共享事实都在 MySQL。

| 场景 | 当前保护机制 | 为什么不需要 distributed lock |
| --- | --- | --- |
| Authorization 幂等 winner | `authorizations.idempotency_key` unique + `findByIdempotencyKeyForUpdate` | winner 选择和业务记录在同一个 DB |
| Credit account 额度 | `credit_accounts` row 的 `SELECT ... FOR UPDATE` | 余额 source of truth 就是这行 DB row |
| Presentment 幂等 | `card_transactions.network_transaction_id` unique | 外部交易身份由 DB 唯一约束保护 |
| Repayment 幂等 | `repayments.idempotency_key` unique + row lock | 防重复还款必须和 statement/account 更新同事务 |
| Statement cycle | `credit_account_id + period_start + period_end` unique + row lock | 防重复账单和账单快照同事务 |
| Outbox publish claim | `FOR UPDATE SKIP LOCKED` + `PROCESSING` lease | 多 worker claim 和 row 状态在一个 DB 表 |
| DelayJob claim | `FOR UPDATE SKIP LOCKED` + `PROCESSING` lease | future business action 的 ownership 由 DB lease 表达 |
| Consumer duplicate | `consumer_inbox` unique | consumer side effect 幂等在 DB 中记录 |
| Cache stampede | Caffeine per-key single-flight + Redis TTL jitter | 只是性能优化，不是金融正确性 |

最关键的是这句话：

```text
如果被保护的数据在 MySQL，优先用 MySQL 的 transaction、row lock、unique constraint 和 lease。
如果另加 Redis lock，就变成 Redis 决定执行权、MySQL 保存业务事实，两套系统之间没有同一个 ACID transaction。
```

这会引入新的 failure mode：

- Redis lock 成功，但 MySQL transaction 失败。
- MySQL transaction 成功，但 Redis unlock 失败。
- Redis TTL 过期，但 MySQL transaction 还没结束。
- Redis failover 导致锁状态不确定。
- app GC pause 导致旧 owner 恢复后继续写。
- 没有 fencing token，旧 owner 可能覆盖新 owner。
- 业务以为有锁就不做 idempotency，结果重复请求仍然出错。

所以，本项目不使用 distributed lock 的原因不是“项目小”，而是：

```text
当前并发冲突的 source of truth 在数据库，
数据库锁和约束已经是更准确、更可审计、更容易和业务状态同事务提交的工具。
```

### 33.6 为什么 DB row lock 在这里比 Redis lock 更合适

以 authorization 为例。

需要同时保证：

- 只能有一个 idempotency winner。
- 同一个 account 的额度不能被并发超用。
- authorization 状态要可审计。
- credit account reserved amount 要更新。
- expiry DelayJob 要写入。
- Outbox event 要写入。
- 失败时全部 rollback。

如果用 DB：

```text
BEGIN
insert authorization with unique idempotency_key
select authorization for update
select credit_account for update
update credit_account
update authorization
insert delay_job
insert outbox_event
COMMIT
```

这些都在同一个 transaction boundary。

如果用 Redis lock：

```text
SET lock:account:{accountId} token NX PX 30000
BEGIN MySQL transaction
update several MySQL tables
COMMIT
DEL lock
```

现在你必须额外处理：

- Redis lock TTL 和 MySQL transaction 时间是否匹配。
- Redis lock 成功但 DB 失败怎么恢复。
- DB 成功但 unlock 失败怎么办。
- TTL 到期后第二个请求进来怎么办。
- 旧 owner 恢复后是否还能写。
- Redis 和 MySQL 之间无法 atomic commit。

所以在这个业务里，Redis lock 没有简化问题，而是把一个 DB transaction 问题变成了 Redis + MySQL 分布式一致性问题。

### 33.7 本项目哪些地方“看起来像 distributed lock”，但其实是 DB lease

Outbox 和 DelayJob 的 `PROCESSING` 状态很像 distributed lock。

例如 Outbox：

```text
PENDING
-> PROCESSING lease
-> PUBLISHED / PENDING retry / DEAD
```

DelayJob：

```text
PENDING
-> PROCESSING lease
-> DONE / PENDING retry / DEAD
```

这确实是在做跨 worker ownership。

但它不是 Redis lock，而是 database-backed lease。

好处：

- claim row 和 status update 在 MySQL transaction。
- worker finalize 前重新 `FOR UPDATE` 锁 row。
- 可以检查当前 row 是否仍然是自己的 lease。
- recoverer 可以扫描 stuck `PROCESSING`。
- attempts、error、nextAttemptAt 都可审计。
- 多实例 worker 用 `SKIP LOCKED` 不互相等待。

这比“拿一个 Redis lock 然后处理 DB row”更适合本项目，因为 queue state 本身就在 DB。

### 33.8 什么时候本项目未来可能使用 distributed lock

虽然当前不需要，但不是永远不用。

可能合理的场景：

#### 场景 1：跨 pod cache refresh mutex

如果某个 statement read model 或 risk config 变成超热点，所有 pod 同时 Redis miss，可能打爆 DB。

可以考虑：

```text
lock:cache-refresh:{cacheName}:{key}
```

拿到锁的 pod 回源 DB 并重建 cache。

没拿到锁的 pod 短暂等待、返回 stale value，或直接回源。

注意：

```text
这只保护 cache refresh，不保护金融余额正确性。
```

#### 场景 2：全局单例 maintenance job

如果未来有一个不适合拆成 DB row claim 的全局维护任务，例如：

- 每天生成全局运营报表。
- 清理外部临时文件。
- 触发某个第三方系统的全局同步。

可以用 etcd/ZooKeeper/Redis lock 做 leader election 或 execution guard。

但仍要记录执行结果和幂等标记。

#### 场景 3：外部资源没有 DB row 可锁

如果要处理一个外部对象：

```text
s3://network-clearing-file/2026-06-21.csv
```

并且本地 DB 还没有建立 file processing row，那么 distributed lock 可以临时保护“只有一个 worker 下载处理”。

更好的生产做法通常是先落 DB：

```text
clearing_files(file_name unique, status, owner, lease_until)
```

然后继续用 DB lease。

#### 场景 4：账户级限流或防抖

Redis 可以用来做 account/card/merchant 级 rate limit。

例如：

```text
rate:authorization:account:{accountId}
```

这不是 distributed lock，而是流量控制。

它可以保护系统，但不能替代额度 row lock。

### 33.9 如果 interview 官坚持问“那生产中到底会不会用 distributed lock”

可以这样回答：

> 会用，但我会先问它保护的资源在哪里。
> 如果资源本身是数据库行，而且业务更新必须和这行数据同事务提交，我优先用 DB row lock、unique constraint 或 DB lease。
> 如果资源是跨进程的外部任务、leader election、cache rebuild 或没有天然 DB row 的操作，才考虑 Redis/ZooKeeper/etcd distributed lock。
> 即使用 distributed lock，也必须有 TTL、owner token、safe unlock、timeout、fencing token 或状态版本校验，并且业务操作仍要 idempotent。

如果对方继续问“为什么不用 Redis lock 保护 authorization accountId”：

> 因为 authorization 不只是保护 accountId 这个 key。
> 它要同时写 authorization、credit account、DelayJob、Outbox。
> 这些状态的 source of truth 都在 MySQL，所以用 MySQL transaction 和 row lock 能把锁和状态放在同一个一致性边界。
> Redis lock 会把 ownership 放到 Redis，把业务状态放到 MySQL，反而制造跨系统一致性问题。

### 33.10 一句话总结

> [!IMPORTANT]
> 最适合背下来的版本：

```text
Distributed lock 是跨实例协调执行权的工具，但不是 transaction，也不是 idempotency。
生产使用时要处理 TTL、owner token、safe unlock、fencing token、超时、重试和幂等。
本项目不使用 Redis/ZooKeeper/etcd distributed lock，因为核心共享事实在 MySQL；
用 DB unique constraint、SELECT ... FOR UPDATE、SKIP LOCKED 和 PROCESSING lease，
可以把执行权、业务状态和审计记录放在同一个 transaction/source of truth 里。
```

## 34. 追问逐题回答速查

前面章节里的“追问方向”是 interview 官真正会继续深挖的地方。下面把这些追问统一补成可直接复述的回答。

使用方式：

1. 先背每个主 Q 的“强回答”。
2. 再用本节补齐追问里的 failure mode、trade-off 和项目证据。
3. 回答时不要只给结论，尽量按“结论 -> 项目做法 -> 为什么 -> 如果去掉会怎样”展开。

> [!IMPORTANT]
> 本节的标准不是“答案越炫越好”，而是每个追问都能守住金融后端的 correctness boundary：
> `idempotency`、`transaction boundary`、`row lock`、状态机、Outbox/Inbox、cache boundary 和 observability。

### 34.1 前置散列追问：并发、Kafka、Redis、AWS

#### 两个请求同时刷同一张卡怎么办？

答：

> 如果是同一个 `Idempotency-Key`，数据库 unique constraint 会选出一个 winner，duplicate 读取 winner 结果。
> 如果是两个不同授权请求但属于同一个 credit account，最终会在 `credit_accounts` row 上通过
> `SELECT ... FOR UPDATE` 串行化额度检查和更新。
> 所以并发安全不依赖 JVM lock，而依赖共享 source of truth 里的 row lock 和 transaction。

追补：

> 如果去掉 account row lock，两个 transaction 可能都基于旧 available credit 批准，造成 over-approve。

#### 客户端 timeout retry 会不会重复占额度？

答：

> 不会，只要客户端使用同一个 `Idempotency-Key` 重试。
> 第一次请求如果已经 commit，重试会命中同一条 authorization row 并返回原结果。
> 如果第一次还在处理中，duplicate 会通过 `FOR UPDATE` 等待 winner 完成，而不是再次 reserve credit。

追补：

> 生产里还要定义 idempotency key retention。窗口太短会让晚到 retry 变成新请求，窗口太长会增加存储和查询成本。

#### Kafka 重复投递怎么办？

答：

> Kafka 按 at-least-once 思路设计，重复投递是正常 failure mode，不应该假装没有。
> 项目里 consumer side 用 Inbox 或业务 unique constraint 记录 `(consumerName, eventId)`，同一 consumer 对同一事件只执行一次 side effect。

追补：

> offset commit 不是业务幂等证明。真正的处理证明要落在 consumer 自己的 DB 表里。

#### 多个 worker 会不会处理同一条任务？

答：

> Outbox/DelayJob worker 通过 `FOR UPDATE SKIP LOCKED` claim rows，把 row 从 `PENDING` 改成 `PROCESSING`，
> 并写入 lease owner/lease until。
> 其他 worker 会跳过已锁或已 claim 的 row。

追补：

> worker finalize 前还要重新锁 row 并校验 lease。否则旧 worker 在 lease 过期后恢复，可能覆盖新 worker 的处理结果。

#### Redis cache stale 会不会影响授权正确性？

答：

> 这个项目的核心额度、幂等 winner 和账务状态不依赖 Redis cache。
> Redis 当前只缓存可重建的 statement read model；旧版 card snapshot cache 已删除。
> Card snapshot 如果未来恢复，会参与卡状态判断，所以 TTL 要短，并且 block/unblock API 必须 after-commit evict 或发布 invalidation event。

追补：

> 不能把 `availableCredit`、`reservedAmount` 或幂等 claim 放进 cache 当 source of truth。

#### 多实例横向扩容后，JVM 内存锁还可靠吗？

答：

> 不可靠。`synchronized`、`ReentrantLock` 只保护当前 JVM。
> 多 pod 部署时，同一个 account 的两个请求可能落在不同实例，JVM lock 完全看不到对方。
> 因此金融共享状态必须由 DB row lock、unique constraint 或 DB lease 保护。

追补：

> JVM 内存锁可以用于本地 cache single-flight 这类单实例优化，但不能保护跨实例 money-changing path。

#### Kafka 发送成功但 DB 回滚怎么办？

答：

> 所以不能在业务 DB commit 前直接 publish Kafka。
> 如果先发 Kafka 再 DB rollback，下游会看到不存在的业务事实。
> 项目用 Outbox：业务状态和 publish intent 同事务 commit，commit 后 worker 再发 Kafka。

追补：

> Outbox 解决的是 dual-write，不是让 Kafka 变成 DB transaction 的一部分。

#### Redis 里是旧数据怎么办？

答：

> 先看旧数据是否会影响 correctness。
> 如果只是 statement GET 展示，TTL 和 after-commit evict 可以把 stale window 控制住。
> 如果会影响授权卡状态，需要更短 TTL、明确 evict、必要时 bypass cache。

追补：

> 高风险写模型不进 cache。用 cache 提升读性能，不能让 cache 决定账务事实。

#### worker publish Kafka 后宕机，Outbox row 还是 PROCESSING 怎么办？

答：

> recoverer 会在 lease timeout 后把 stuck row 恢复为可处理状态。
> 这条 event 可能再次 publish，所以 consumer 必须幂等。
> 这是 at-least-once 的典型代价。

追补：

> 如果为了避免重复 publish 而让 row 永远 PROCESSING，会造成消息丢失或 backlog 卡死，风险更大。

#### 为什么不用 Java `synchronized`？

答：

> 因为授权额度是数据库里的共享事实，不是一个 JVM 内存变量。
> 多实例、重启、批处理、consumer、scheduler 都可能修改相关状态。
> 只有 DB lock/constraint 能跨实例保护同一份事实。

#### 为什么不把所有东西拆成 microservices？

答：

> 当前项目最重要的是讲清核心交易的 `transaction boundary`、锁顺序和状态机。
> 过早拆服务会把本地事务问题变成分布式一致性问题。
> 更合理的演进是先拆 Notification、Risk projection、Ledger projection 这类异步下游。

#### 如果上 AWS，怎么设计 health check、alarm、rollback？

答：

> Health check 分 liveness 和 readiness：liveness 看进程是否活着，readiness 看核心依赖是否可接流量。
> Alarm 不只看 CPU，还要看 authorization p99、HTTP 5xx、RDS lock wait、Hikari pending、Kafka lag、Outbox/DelayJob backlog、Redis error、GC pause。
> Rollback 通过 ECS previous task definition/blue-green 完成，但 DB migration 必须 backward-compatible，否则代码 rollback 也救不了 schema 不兼容。

### 34.2 业务主链路追问回答

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
> 如果同周期能生成多张账单，会造成还款目标、通知、ledger projection 和用户展示全部混乱。
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

#### Q15 追问：为什么 ledger 通过 Kafka 消费，而不是主事务直接写？

答：

> 当前项目把 Ledger 作为 projection，避免主交易事务承担太多下游写入。
> 业务事实先由 Outbox 发布，Ledger consumer 幂等生成 entry。
> 如果生产 ledger 是强源系统，则可能需要更严格同步边界；本项目先用 projection 展示异步可靠性。

#### Q15 追问：如果 ledger consumer 落后怎么办？

答：

> 主交易仍以 source tables 为准，但内部账务 projection 会延迟。
> 需要监控 consumer lag、DLT、ledger entry delay，并给运营/报表设置数据新鲜度提示。
> 如果 lag 超过 SLO，要扩 consumer、查 DB side effect、修复 poison message 或暂停依赖该 projection 的报表。

### 34.3 幂等、事务、锁追问回答

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

### 34.4 Kafka、Outbox、Inbox、DelayJob 追问回答

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
> 同一个 event 对 Notification、Risk、Ledger 是三次不同消费。
> 如果只按 eventId 去重，会误伤其他 bounded context。

#### Q29 追问：同一个 event 被不同 bounded context 消费怎么办？

答：

> 每个 bounded context 用自己的 consumerName claim。
> Notification 创建通知，Risk 更新 feature projection，Ledger 生成 ledger entry。
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
> consumer 必须幂等，否则重放会重复创建通知、ledger 或 projection。

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

### 34.5 Cache、Redis、NoSQL 追问回答

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
> Notification feed 可用 `userId/cardholderId` + time sort key，risk feature 可用 `accountId` 或 `cardId`，event query 可用 aggregate id。
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

#### Q48 追问：Risk feature projection 是 read model 吗？

答：

> 是，它是为风控查询/决策优化的投影，不是原始交易 source of truth。
> 它可以 eventual consistency，但要知道落后会影响后续 risk accuracy。
> 因此需要 consumer lag 和 projection freshness 指标。

#### Q48 追问：Ledger projection 能 cache 吗？

答：

> 查询型 ledger view 可以 cache，但 ledger entry 本身不应该靠 cache 作为事实。
> Ledger 更接近审计/会计数据，cache 只能加速展示或报表。
> 写入正确性仍靠 DB constraint、event id 和 reconciliation。

#### Q48 追问：Cache miss 如何重建？

答：

> 从 source of truth 或可重放 projection 重建。
> Statement read model 从 statement/statement_lines/repayment 状态回源。
> 如果重建成本高，要 single-flight、限流或异步预热，避免 miss storm。

### 34.6 High traffic 和性能追问回答

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
> 需要判断业务影响：notification 延迟可容忍，risk/ledger 落后可能更严重。

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
> 对 Kafka duplicate，重复投递同 eventId，确认 Notification/Ledger 只执行一次。

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

### 34.7 AWS、部署、观测、CI/CD 追问回答

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
> 再查 authorization、credit_account、Outbox event、Kafka consumer、notification/ledger/risk projection。
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

### 34.8 DDD、OOP、架构演进追问回答

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

### 34.9 Java、Spring、测试、Legacy 追问回答

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

### 34.10 可替换和新增的高压追问

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
