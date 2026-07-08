# Spring、Java 与第三方库使用学习笔记

这份文档专门整理本项目里的 Spring、Java 语言特性和第三方库使用习惯。

它回答的不是“业务为什么要这样做”，而是更底层的问题：

```text
为什么要加这个注解、构造函数、配置类或工具类？
如果不这样写，程序会怎么失败？
这是 Spring/Java 的通用习惯，还是本项目的特殊取舍？
```

阅读代码时，可以先问三个问题：

1. 这个东西由谁读取？
2. 它在启动时、请求时、事务提交时，还是后台线程里生效？
3. 如果删掉它，问题会在编译期、启动期、运行期，还是并发/故障时才暴露？

这套思考方式比背注解更重要。很多 Spring 代码看起来像模板，其实都是在告诉框架：

```text
这个类要不要放进 container
这个方法要不要被 proxy 拦截
这个配置要不要被绑定
这个异常要不要统一映射
这个线程池要不要参与生命周期
这条消息失败后要不要进 DLT
```

## 0. 全局地图

| 技术点 | 本项目代表位置 | 为什么要这样写 | 如果不这样做 |
| --- | --- | --- | --- |
| Spring Boot 启动 | `MiniCardPaymentSystemApplication` | 启动 component scan、auto configuration、配置绑定 | Controller/Service/Repository 不会自动成 bean |
| OpenFeign 扫描 | `@EnableFeignClients`、`ExternalRiskClient` | 让 Feign interface 变成 HTTP client proxy | `ExternalRiskClient` 只是普通 interface，无法注入 |
| API controller | `AuthorizationController` | HTTP adapter，只处理 request/response/validation | 业务逻辑散进 Web 层，测试和事务边界变混乱 |
| Bean Validation | `CreateAuthorizationRequest`、`@Validated` | 在 API boundary fail fast | null/blank 进入 service/domain 后变成低层异常 |
| constructor injection | 多数 service/config 类 | 依赖显式、不可变、测试友好 | field injection 隐藏依赖，测试和初始化顺序更脆 |
| `@Transactional` | `AuthorizationService`、`PostingService` | 由 Spring proxy 开启事务 | self-invocation 或非 Spring 对象上不会生效 |
| `TransactionOperations` | `OutboxWorker`、`DelayJobWorker` | 一个方法里显式拆多个事务 | worker finalize 容易和 handler transaction 混在一起 |
| configuration properties | `AuthorizationPolicyProperties`、`StatementReadCacheProperties`、历史版本的 `SnapshotCacheProperties` | YAML 绑定为 typed record | `@Value` 分散，类型/默认值难统一管理 |
| scheduler | `PollingSchedulerConfiguration`、pollers | 打开 `@Scheduled` 并指定调度线程池 | 任务可能不运行，或共享默认 scheduler 互相拖住 |
| worker executor | `WorkerExecutorConfiguration` | 后台业务和 poller 分线程池 | scheduler 线程直接跑长任务，任务堆积时不可控 |
| MyBatis mapper | `AuthorizationMapper` + XML | SQL 显式、参数绑定、row mapping | SQL 和业务混杂，或出现 SQL injection/映射错误 |
| Kafka listener factory | `KafkaConsumerConfiguration` | 每个 consumer group 有独立 retry/DLT/concurrency | 一个上下文失败影响其他上下文，重试策略混乱 |
| Jackson JSON | `IntegrationEventReader` | 统一解析 envelope、校验 headers/payload | 每个 listener 重复解析且失败行为不一致 |
| Caffeine + Redis | `StatementReadService` | L1 降本机热点，L2 跨实例共享 | Redis 故障或热点 miss 容易放大成 API/DB 压力 |
| Feign + Resilience4j | `ExternalRiskGatewayAdapter` | 外部 HTTP 调用声明式代理和断路器 | 慢外部服务拖住授权请求和线程 |
| Java record | DTO、row、event、properties | 不可变数据载体，构造器集中校验 | setter POJO 容易被修改，状态来源不清 |
| `BigDecimal`/`Currency`/`Clock` | `Money`、services | 金额精度、币种类型、可测试时间 | `double` 精度错，字符串币种错，时间测试不稳定 |

## 1. Spring Boot 启动与 Gradle

### 1.1 `@SpringBootApplication`

位置：`src/main/java/com/minicard/MiniCardPaymentSystemApplication.java`

`@SpringBootApplication` 不是一个装饰性注解。它组合了几个关键能力：

- component scan：扫描当前 package 下面的 `@Controller`、`@Service`、`@Repository`、`@Component`、`@Configuration`。
- auto configuration：根据 classpath 自动创建 MVC、Jackson、DataSource、TransactionManager、Kafka、Redis 等基础 bean。
- configuration support：让 Spring Boot 的配置绑定、条件装配等能力进入应用。

如果删掉它：

- `AuthorizationController` 不会自动注册成 HTTP endpoint。
- `AuthorizationService` 不能被注入。
- MyBatis、Kafka、Redis、Actuator 等 starter 也不会按 Boot 约定自动配置。
- 程序可能还能编译，但启动时会大量报 missing bean，或者根本没有任何 API 路由。

### 1.2 `@EnableFeignClients`

位置：

- `MiniCardPaymentSystemApplication`
- `risk.infrastructure.gateway.ExternalRiskClient`

`@FeignClient` 本身只是接口上的声明，真正扫描并生成代理的是 `@EnableFeignClients`。

如果没有 `@EnableFeignClients`：

```text
ExternalRiskClient interface exists
but no Spring bean is created
ExternalRiskGatewayAdapter constructor injection fails at startup
```

这也是 Spring 很常见的模式：

```text
marker annotation on class/interface
+ enabling annotation/configuration
= framework creates runtime bean/proxy
```

类似组合还有：

- `@ConfigurationProperties` + `@EnableConfigurationProperties`
- `@Scheduled` + `@EnableScheduling`
- `@Mapper` + MyBatis starter/scan

### 1.3 Gradle starters 不是普通依赖清单

位置：`build.gradle`

Spring Boot starter 的价值是“一组默认可工作的依赖组合”。例如：

| starter | 提供什么 | 不加会怎样 |
| --- | --- | --- |
| `spring-boot-starter-web` | Spring MVC、Tomcat、Jackson HTTP JSON | 没有内嵌 Web server 或 JSON MVC 支持 |
| `spring-boot-starter-jdbc` | DataSource、transaction manager、JdbcTemplate | `@Transactional` 没有底层事务管理器 |
| `spring-boot-starter-validation` | Jakarta Bean Validation integration | `@Valid`、`@NotBlank` 等不会按预期校验 |
| `spring-boot-starter-aop` | Spring AOP proxy | `@CircuitBreaker` 这类 proxy-based 注解无法工作 |
| `spring-boot-starter-data-redis` | Redis connection factory、`StringRedisTemplate` | Redis cache 需要手工创建连接和序列化组件 |
| `spring-boot-starter-actuator` | health、metrics、info endpoints | JVM/依赖诊断只能自己写或缺失 |

`io.spring.dependency-management` 和 BOM 的意义是版本协调。

如果手工指定所有传递依赖版本，最常见的问题不是编译错误，而是运行中出现：

```text
NoSuchMethodError
ClassNotFoundException
serialization behavior mismatch
Kafka/Feign/Boot integration version conflict
```

### 1.4 Java toolchain

位置：`build.gradle`

```gradle
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
```

它固定编译 JDK 版本。没有 toolchain 时，项目会使用开发机当前默认 JDK。

问题通常发生在团队协作或 CI：

- 你本地是 JDK 21，CI 是 JDK 17，代码用了 record pattern 或新版 API 后 CI 才失败。
- 本地多个 JDK 切换，Gradle daemon 使用的 JDK 和 IDE 显示的不一致。
- 生成的 bytecode 和运行环境不一致。

toolchain 是让“我以为用 Java 21”变成“构建系统保证用 Java 21”。

## 2. Web/API 层

### 2.1 `@RestController`

位置：`AuthorizationController`、`PresentmentController`、`HealthController`

`@RestController` 等价于：

```text
@Controller
+ @ResponseBody
```

它告诉 Spring MVC：方法返回值要写入 HTTP response body，通常由 Jackson 转成 JSON。

如果误用普通 `@Controller`：

- 返回 `AuthorizationResponse` 这类对象时，Spring 可能尝试解析 view。
- 错误表现可能是 view name 找不到，而不是业务代码错。
- API controller 的意图不清楚。

### 2.2 `@RequestMapping`、`@PostMapping`、`@GetMapping`

class-level `@RequestMapping("/api/authorizations")` 是资源前缀。

method-level `@PostMapping`、`@GetMapping("/{id}")` 是具体动作。

如果每个方法都写完整路径：

- 路径重复。
- 改资源前缀时容易漏改。
- controller 的“这是同一类资源”不明显。

这种结构也让 review 更快：

```text
AuthorizationController
base path: /api/authorizations
POST: create/authorize
GET /{id}: query by id
```

### 2.3 `@Validated` 和 `@Valid` 不一样

位置：`AuthorizationController`

`@Valid` 常用于 request body：

```java
public AuthorizationResponse authorize(
        @Valid @RequestBody CreateAuthorizationRequest request
)
```

`@Validated` 放在 controller class 上，能让 method parameter 上的约束也生效：

```java
@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey
```

如果只有 `@Valid` 而没有 `@Validated`：

- body 字段可能会校验。
- header/path variable 上的 `@NotBlank`、`@Size` 可能不会按预期触发。
- 空 idempotency key 会进入 application service，后面才变成业务异常或数据库异常。

interview 可以这样说：

> 我把 API contract 的校验放在 HTTP boundary。`@Valid` 处理 body object，`@Validated` 处理 method parameter。domain 里仍然保留 invariant，因为 Kafka consumer、scheduler、repository restore 和测试不会经过 controller。

### 2.4 Bean Validation 注解

位置：

- `CreateAuthorizationRequest`
- `CreatePresentmentRequest`
- `ReceiveRepaymentRequest`
- `GenerateStatementRequest`

常见注解含义：

| 注解 | 用途 | 不加会怎样 |
| --- | --- | --- |
| `@NotBlank` | 字符串不能为 null、空串、纯空白 | blank id 进入 service，错误更晚暴露 |
| `@NotNull` | 字段必须存在 | NPE 或 domain restore 时才失败 |
| `@Size` | 控制字符串长度 | 超长字段可能到数据库才失败 |
| `@Pattern` | 控制格式，例如国家/币种代码形状 | `Currency.getInstance` 或业务层抛更低层异常 |
| `@DecimalMin` | 控制金额下限 | 0 或负金额进入 Money/domain |
| `@Digits` | 对齐数据库 DECIMAL 精度 | 写库时才发现截断/scale 问题 |

注意：Bean Validation 不替代 domain invariant。

原因是 controller 不是唯一入口：

```text
HTTP request
Kafka listener
DelayJob handler
test fixture
repository restore
manual command
```

所以本项目的模式是：

```text
DTO validation: give client fast, clear 400
Domain validation: protect business object from every path
```

### 2.5 DTO 为什么用 `record`

位置：

- `CreateAuthorizationRequest`
- `AuthorizationResponse`
- `StatementReadModel`
- MyBatis `*Row`
- event records
- properties records

Java record 适合表达“数据载体”：

- 字段 final。
- constructor、accessor、equals/hashCode/toString 自动生成。
- Jackson 可以绑定 record constructor。
- MyBatis 可以通过 constructor mapping 创建 row record。

如果用可变 POJO + setter：

- 请求对象进入 service 后仍可能被修改。
- 测试断言时很难判断对象何时变化。
- row DTO 可能被 mapping 后意外改字段。
- domain 和 persistence model 的边界更松。

但 record 不适合所有地方。

例如 `Authorization` aggregate 不是 record，因为它有生命周期和状态转换：

```text
PENDING -> APPROVED -> POSTED
PENDING -> DECLINED
APPROVED -> EXPIRED
```

这种对象需要封装行为和 invariant，不只是数据。

## 3. Bean、依赖注入与 Lombok

### 3.1 `@Service`、`@Component`、`@Repository`

这些注解共同点：让 class 成为 Spring bean。

区别在语义：

| 注解 | 本项目例子 | 语义 |
| --- | --- | --- |
| `@Service` | `AuthorizationService`、`PostingService` | application use case / 业务编排 |
| `@Component` | `IntegrationEventReader`、Outbox adapters | 通用组件或 adapter |
| `@Repository` | `MyBatisAuthorizationRepository`、历史版本的 `CachedCardRepository` | persistence adapter |

如果省掉这些注解：

- 类可以编译。
- 但 Spring container 不会创建它。
- 依赖它的 constructor injection 会在启动时报 missing bean。

如果把所有类都标 `@Component` 也能跑，但语义会变差。`@Repository` 还会参与 Spring 的数据访问异常转换，对 persistence adapter 更合适。

### 3.2 constructor injection 为什么优先

本项目主要使用 constructor injection：

```java
public AuthorizationService(
        AuthorizationRepository authorizationRepository,
        AuthorizationPolicyProperties policyProperties,
        ...
) {
    ...
}
```

好处：

- 必需依赖一眼可见。
- 字段可以是 `final`。
- 测试可以直接 `new` service，传入 fake/mock。
- bean 创建时依赖缺失会立刻失败。
- 避免 field injection 的隐藏状态。

field injection 的问题：

```java
@Autowired
private AuthorizationRepository repository;
```

- 构造函数看不出对象需要什么。
- 单元测试必须依赖 Spring 或反射设置字段。
- 字段可能在构造期间为 null。
- 依赖越来越多时不明显。

构造函数有时需要手写，例如 `AuthorizationService`。

原因是它不仅注入依赖，还把配置中的字符串币种转换成 `Currency` 和 `Money`：

```text
YAML Map<String, BigDecimal>
-> Map<Currency, Money>
```

如果用 Lombok 自动生成 constructor，就没有地方集中做这个转换，或者转换会散到热路径里。

### 3.3 Lombok 什么时候用

本项目使用较保守的 Lombok：

- `@RequiredArgsConstructor`
- `@Slf4j`

`@RequiredArgsConstructor` 适合无额外初始化逻辑的 final dependency：

```java
@RequiredArgsConstructor
public class IntegrationEventReader {
    private final ObjectMapper objectMapper;
}
```

`@Slf4j` 生成 logger，减少样板代码。

不建议在 domain aggregate 上随便用 `@Data`：

- 会生成 setter，让对象看起来可随意改状态。
- 会生成 equals/hashCode，可能不符合 aggregate identity。
- 会暴露过多方法，让状态转换绕过业务行为。

简单记法：

```text
DTO/row/properties: record is often best
service/adapter with final deps only: @RequiredArgsConstructor is fine
domain aggregate with behavior: write methods explicitly
```

### 3.4 `@Primary` 和 `@Qualifier`

位置：

- 历史版本的 `CachedCardRepository`
- `DelayJobPoller`
- `OutboxPoller`
- 历史版本的 cache configuration

`@Primary` 曾经用在 `CachedCardRepository`：

```text
CardRepository has MyBatis implementation and cached decorator
@Primary says inject cached decorator by default
```

如果没有 `@Primary`：

- Spring 看到两个 `CardRepository` bean。
- 注入 `CardRepository` 时会 ambiguous。

`@Qualifier` 用在多个同类型 bean 场景：

```java
@Qualifier("delayJobWorkerExecutor") TaskExecutor delayJobWorkerExecutor
```

如果省掉：

- `outboxWorkerExecutor` 和 `delayJobWorkerExecutor` 都是 `TaskExecutor`。
- Spring 不知道要注入哪个。
- 或者后续重构时注错线程池，任务隔离失效。

Bean name 是技术边界，不只是字符串。

## 4. 事务与 Spring proxy

### 4.1 `@Transactional` 什么时候生效

位置：

- `AuthorizationService.authorize`
- `PostingService.post`
- `RepaymentService.receive`
- `StatementGenerationService.generate`
- `OutboxRecoverer.recoverStuckEvents`

`@Transactional` 通过 Spring AOP proxy 生效。

最重要的限制：

```text
外部 bean 调用 public method -> 经过 proxy -> transaction 生效
同一个对象内部 self-invocation -> 不经过 proxy -> 注解可能不生效
```

反例：

```java
public void outer() {
    inner(); // same object call, bypass proxy
}

@Transactional
public void inner() {
    ...
}
```

这也是本项目在 worker 里使用 `TransactionOperations` 的原因之一。

### 4.2 `readOnly = true`

位置：`AuthorizationService.get`

`@Transactional(readOnly = true)` 是两个信号：

- 给读者：这条路径不应改变业务状态。
- 给事务框架/数据库驱动：可以做只读优化或避免 flush 写入。

如果查询和写入都使用默认事务：

- review 时不容易看出哪些方法会改状态。
- 未来加入 ORM 或更多 persistence 技术时，误 flush 的风险更高。
- 业务读路径和写路径边界变模糊。

### 4.3 `TransactionOperations` / `TransactionTemplate`

位置：

- `TransactionOperationsConfiguration`
- `OutboxWorker`
- `DelayJobWorker`

它适合这种场景：

```text
worker thread
1. 已经在别处 claim 了 row
2. 执行业务 handler 或 Kafka publish
3. 需要用另一个短事务 finalize 状态
```

为什么不用在同一个方法里到处加 `@Transactional`？

- worker 需要显式控制多个事务。
- `publish Kafka` 不应该被长时间包在数据库事务里。
- `handler transaction` 和 `finalize transaction` 含义不同。
- self-invocation 容易让注解失效。

`TransactionTemplate` 把边界写成代码：

```java
transactionOperations.executeWithoutResult(status -> {
    OutboxEvent event = lockCurrentLease(claimedEvent);
    event.markPublished(now);
    repository.updateDeliveryState(event);
});
```

如果不拆：

- 长事务持有 DB connection 和 row lock 更久。
- Kafka 等待或外部调用可能放大数据库锁等待。
- worker 失败后 finalize 状态不清晰。

### 4.4 `TransactionSynchronization`

位置：`StatementReadService.evictAfterCommit`

写路径改了 MySQL 后，缓存失效要等事务 commit 后执行：

```text
DB update in transaction
register afterCommit cache evict
commit succeeds
evict cache（当前是写 tombstone 版本地板，不是裸 DELETE）
```

如果事务中先删缓存：

```text
evict cache
other GET reads old DB value
other GET writes old value back to Redis
original transaction commits
cache now contains stale snapshot
```

如果事务 rollback：

```text
evict cache
DB change does not happen
next GET unnecessarily rebuilds cache
```

`TransactionSynchronizationManager` 是 Spring 提供的事务生命周期 hook。它不是业务逻辑，而是让缓存动作跟随事务结果。

## 5. 配置绑定与 `application.yml`

### 5.1 `@ConfigurationProperties`

位置：

- `AuthorizationPolicyProperties`
- `RiskProperties`
- `StatementReadCacheProperties`
- 历史版本的 `SnapshotCacheProperties`
- `OutboxProperties`
- `DelayJobProperties`
- `KafkaTopicsProperties`

它把 YAML 子树绑定成 typed Java object：

```yaml
authorization:
  policy:
    single-transaction-limits:
      JPY: 100000.00
```

```java
@ConfigurationProperties(prefix = "authorization.policy")
public record AuthorizationPolicyProperties(
        Map<String, BigDecimal> singleTransactionLimits
) {
}
```

优点：

- 配置集中。
- 类型明确。
- IDE 可以提示。
- 测试可以直接构造 properties object。
- 默认值和归一化可以放在 compact constructor。

如果到处用 `@Value`：

```java
@Value("${authorization.policy.single-transaction-limits.JPY}")
private BigDecimal jpyLimit;
```

问题：

- key 分散在多个类。
- map/list/nested config 很难维护。
- 默认值逻辑重复。
- 改配置结构时容易漏。

### 5.2 `@EnableConfigurationProperties`

`@ConfigurationProperties` 标在 record 上，不一定意味着它已经是 Spring bean。

本项目通过配置类启用：

```java
@Configuration
@EnableConfigurationProperties(AuthorizationPolicyProperties.class)
public class AuthorizationPolicyConfiguration {
}
```

如果省掉启用步骤：

- record 仍能编译。
- 但 Spring 不会创建该 properties bean。
- 依赖它的 service 启动失败。

### 5.3 compact constructor 做默认值

位置：`StatementReadCacheProperties`

```java
public StatementReadCacheProperties {
    localTtl = localTtl == null ? Duration.ofSeconds(30) : localTtl;
    localMaximumSize = localMaximumSize == null ? 1000L : localMaximumSize;
    remoteTtl = remoteTtl == null ? Duration.ofMinutes(5) : remoteTtl;
    remoteTtlJitter = remoteTtlJitter == null ? Duration.ofSeconds(30) : remoteTtlJitter;
}
```

这类默认值适合放在 properties object 里，因为它是配置本身的规则。

如果默认值散在 factory/service：

- 每个使用方都要记得处理 null。
- 第一次运行到某个 cache 才发现配置错误。
- 同一配置可能在不同地方有不同默认值。

### 5.4 `Duration` 配置

YAML：

```yaml
local-ttl: 30s
remote-ttl: 5m
```

Java：

```java
Duration localTtl
```

Spring Boot 会自动把可读字符串绑定成 `Duration`。

如果用 `long localTtlMillis`：

- 配置可读性差。
- 秒、毫秒、分钟容易混。
- 文档和代码容易不一致。

### 5.5 `${ENV:default}`

位置：`application.yml`

```yaml
url: ${DB_URL:jdbc:mysql://localhost:3306/mini_card}
```

含义：

```text
如果环境变量 DB_URL 存在，用它
否则用本地默认值
```

如果把生产地址直接写死：

- 本地和生产需要改同一个文件。
- 容易把敏感配置提交到仓库。
- CI/CD 环境难以覆盖。

如果完全没有默认值：

- 本地学习启动门槛更高。
- 每次运行都需要额外 export。

本项目是学习项目，所以保留本地默认值，同时允许环境变量覆盖。

## 6. Scheduler、线程池与后台任务

### 6.1 `@EnableScheduling`

位置：`PollingSchedulerConfiguration`

没有 `@EnableScheduling` 时：

- `@Scheduled` 方法不会被扫描。
- Outbox poller、DelayJob poller、recoverer、statement batch 都不会定时运行。
- 代码编译和启动可能正常，但后台机制静默失效。

这类 bug 很隐蔽，因为没有异常，只是“该发生的事情没发生”。

### 6.2 `@Scheduled(..., scheduler = "...")`

位置：

- `DelayJobPoller`
- `OutboxPoller`
- `DelayJobRecoverer`
- `OutboxRecoverer`
- `BillingCycleScheduler`
- `StatementJobDispatcher`

显式指定 scheduler：

```java
@Scheduled(
        fixedDelayString = "${delay-jobs.scheduler.fixed-delay-ms:1000}",
        scheduler = "delayJobTaskScheduler"
)
```

好处：

- Outbox、DelayJob、Statement batch 使用不同调度线程池。
- thread dump 里能通过线程名识别任务来源。
- 一个机制卡住时不容易拖住另一个机制。

如果不指定：

- 使用默认 scheduler。
- 多个定时任务可能共享少量线程。
- 某个 recoverer 慢了，其他 poller 触发也可能延迟。

### 6.3 `ThreadPoolTaskScheduler` vs `ThreadPoolTaskExecutor`

本项目刻意分两类线程池：

```text
ThreadPoolTaskScheduler: 定时醒来，poll/claim/recover
ThreadPoolTaskExecutor: 执行业务 worker
```

为什么不让 scheduler 线程直接跑业务？

- scheduler 应该轻量、可预测。
- DelayJob handler 可能拿 row lock 或调用外部系统。
- Outbox publish 可能等待 Kafka ack。
- 如果 scheduler 线程跑长任务，下一轮 poll/recover 会被拖住。

所以：

```text
poller:
  short transaction claim
  submit to worker executor

worker:
  handle business/publish
  finalize state
```

### 6.4 `TaskRejectedException`

位置：

- `DelayJobPoller`
- `OutboxPoller`

worker queue 满或应用 shutdown 时，`execute()` 可能抛 `TaskRejectedException`。

如果吞掉异常：

```text
job/event already claimed as PROCESSING
but no worker actually runs it
it remains invisible until recoverer sees lease timeout
downstream observes delay
```

本项目立即把它标成失败路径，让任务回到 retry/DEAD 逻辑。这样比静默等待 lease timeout 更可控。

### 6.5 `@ConditionalOnProperty`

位置：

- `OutboxPoller`
- `DelayJobPoller`
- recoverers
- statement batch

作用：

```text
配置 enabled=true 时创建 bean
否则不创建 bean
```

为什么需要开关？

- 本地排查数据时可能只想写 Outbox，不想自动发布。
- 集成测试可能不希望后台线程抢数据。
- 迁移或回放时需要控制副作用。

如果没有开关，应用一启动就开始扫描和处理 backlog，调试成本会高很多。

## 7. MyBatis、JDBC 与 Liquibase（机制详见数据层文档）

> 本节的 MyBatis 用法、SQL、锁、索引、事务、批处理和 Liquibase 迁移**完整说明已移到**
> [`mybatis-sql-and-migration-cn.md`](mybatis-sql-and-migration-cn.md)，不在此重复。这里只保留几个
> **Spring/Java 集成视角**的小点（机制本身看上面那份）：

- **`@Mapper` + `@Param`**：mapper 接口由 MyBatis 在运行时实现；多参数方法必须 `@Param` 命名，否则 XML 里只能用 `param1/param2` 这种不直观名字。
- **`Optional` 用在 repository 返回值**：`findById` 返回 `Optional<...>` 而非裸 null，强制调用方在 use case 层显式处理 not-found，不把 null 漏进 domain。
- **`DuplicateKeyException`**：Spring 把 MySQL 唯一键冲突（如 `idempotency_key`）转成 `DataAccessException` 体系的 `DuplicateKeyException`；insert-first claim 正是 catch 它判断"别的请求已抢占"，而不是先 SELECT 再 INSERT。
- **Liquibase 与 SQL init**：`spring.sql.init.mode: never` 关掉 Spring 的 schema.sql 自动建表，改由 Liquibase changelog 启动时迁移（当前 0001–0009）。

## 8. Kafka、Spring Kafka 与 Jackson（机制详见消息文档）

> 本节的 topic/partition、consumer factory、DLT/error handler、`KafkaTemplate`、envelope、投递语义
> 和消费者幂等**完整说明已移到** [`events-outbox-inbox-kafka-cn.md`](events-outbox-inbox-kafka-cn.md)。
> 这里只保留几个 **Spring/库集成视角**的小点：

- **`@KafkaListener` 是 inbound adapter**：方法只做 eventType 过滤 + payload→command 映射，真正 side effect 在 application service；每个 bounded context 独立 group + 独立 DLT。
- **`DefaultErrorHandler` + `addNotRetryableExceptions`**：瞬时异常按 `FixedBackOff(1000ms, 2)` 重试再进 DLT，`EventContractException` 这种永久错误**不重试直接 DLT**。
- **捕获 `InterruptedException` 后恢复 interrupt flag**：`Thread.currentThread().interrupt()`——不吞 shutdown 信号，让阻塞等待（如 `send().get()`）能正确响应停机。
- **`ObjectMapper` + `JsonNode`**：payload 用 `JsonNode` 而非每事件一组 DTO（envelope 稳定、payload 灵活，Tolerant Reader）；`ObjectMapper` 直接复用 Spring Boot 配置好的实例，保持序列化一致。

## 9. 缓存、Redis 与 Caffeine（机制详见缓存文档）

> statement 两级缓存（Caffeine L1 + Redis L2）、cache-aside、版本化 CAS + 墓碑、跨 pod 失效广播、
> Redis 滑动窗口限流、TTL jitter、fail-open 的**完整说明已移到**
> [`caching-and-rate-limiting-cn.md`](caching-and-rate-limiting-cn.md)。这里只保留 **Spring/库集成视角**：

- **`StringRedisTemplate`**：用字符串模板而非 `RedisTemplate<Object,Object>`，让 Redis 里存可读 JSON 字符串，序列化边界显式可控。
- **复用 Spring Boot 的 `ObjectMapper`**：缓存值的 JSON 序列化与 Kafka/Web 用同一个 `ObjectMapper`，避免多套配置导致格式漂移。
- **Caffeine L1 + `ThreadLocalRandom` TTL jitter**：`Cache.get(key, loader)` 自带同 JVM single-flight；Redis TTL 上加 `ThreadLocalRandom` 抖动，避免同批 key 同秒过期（cache avalanche）。

## 10. Feign、Resilience4j 与外部调用

### 10.1 `@FeignClient`

位置：`ExternalRiskClient`

Feign 把接口变成 HTTP client：

```java
@FeignClient(name = "external-risk", url = "${risk.external.base-url}")
public interface ExternalRiskClient {
    @PostMapping("/external-risk/assess")
    ExternalRiskResponse assess(@RequestBody ExternalRiskRequest request);
}
```

好处：

- HTTP endpoint、method、request/response DTO 集中。
- application service 不关心 HTTP 细节。
- 测试可以 mock `ExternalRiskGateway` port。

如果在 service 里直接手写 HTTP 调用：

- URL、timeout、DTO、错误处理侵入业务编排。
- 外部依赖和 domain rule 混在一起。
- 切换模拟/真实外部服务更难。

### 10.2 `@CircuitBreaker`

位置：`ExternalRiskGatewayAdapter`

```java
@CircuitBreaker(name = "externalRisk", fallbackMethod = "fallback")
public RiskDecision assess(RiskAssessmentRequest request) {
    ...
}
```

断路器解决的是外部依赖故障扩散：

```text
external risk slow/failing
-> request thread waits
-> Tomcat threads occupied
-> DB lock window grows
-> whole authorization API slows down
```

fallback 采用 fail-closed：

```text
external risk unavailable
-> decline with EXTERNAL_RISK_UNAVAILABLE
```

如果没有断路器：

- 外部服务慢时每个请求都等超时。
- 线程池和连接池更容易被拖满。
- 故障恢复前持续打外部服务。

注意：`@CircuitBreaker` 也依赖 Spring AOP proxy，所以 `spring-boot-starter-aop` 是运行基础。

### 10.3 注解式 vs 编程式：R4j 的双轨约定

本项目刻意保留两种 Resilience4j 包装方式（类比 MyBatis vs JdbcTemplate 的对照示例），
但选择不是随意的，遵循一条显式规则：

| 场景特征 | 用哪种 | 项目实例 |
|---|---|---|
| 策略静态（实例名编译期固定）、单方法、fallback 要返回域内值 | **注解式**（`@CircuitBreaker`/`@Bulkhead` + fallbackMethod） | `ExternalRiskGatewayAdapter`：CB + Bulkhead，fail-closed 降级成 decline |
| 实例名运行时决定（如按渠道选限流器/熔断器）、需要显式控制装饰顺序 | **编程式**（Registry + `decorateSupplier`，helper 收口） | `ResilientCallHelper`：Retry(CB(RateLimiter(call)))，按 notificationPush/Email 选实例 |
| 策略静态、单方法、已有 durable retry 层 | **注解式**，且只挂 CB | `BankDebitGatewayAdapter`：仅 CB；fallback 按异常类型分派（permanent 重抛 / 瞬态转 failed 结果） |

三种组合的差异是业务驱动的，不只是风格：

- **risk**：授权同步热路径——不能 retry（延迟预算）、必须 bulkhead（防 brownout 钉死 Hikari）。
- **notification**：后台 worker——DB 层已有 durable retry 所以进程内 retry 便宜；出站 RateLimiter 保护 provider quota；
  worker pool 有界所以不需要 bulkhead。
- **bank debit**：后台资金操作——DelayJob 已是带退避的 durable retry 层，叠 R4j retry 会相乘且每次尝试都是资金请求；
  跑在专用 auto-repay worker 池里所以不需要 bulkhead；只留 CB 防银行 brownout 钉线程。

注解式的已知代价（也是编程式存在的理由）：依赖 AOP 代理（自调用绕过防护）、装饰顺序
隐式、fallback 靠方法签名反射匹配。**不为三个调用点抽统一 resilience 框架**：策略组合
几乎无交集，硬抽只会得到一堆布尔参数（参见 `ResilientCallHelper` 注释里"不要长成门面"
的警告）。

## 11. Java 语言与标准库习惯

### 11.1 `BigDecimal`

位置：`Money`

金融金额不能用 `double`。

原因：

```java
0.1 + 0.2 != 0.3 // binary floating point issue
```

本项目使用：

```text
Java BigDecimal
MySQL DECIMAL(19,2)
Money value object
```

还要注意 `BigDecimal.equals` 会比较 scale：

```java
new BigDecimal("1.0").equals(new BigDecimal("1.00")) // false
new BigDecimal("1.0").compareTo(new BigDecimal("1.00")) == 0 // true
```

所以 `Money` 在 compact constructor 里统一 `setScale(2)`。

如果不统一：

- 两个业务上相同的金额在 equals/hashCode 里可能不同。
- record 自动生成的 equals 会把 scale 差异也算进去。
- 缓存 key、测试断言、对象比较容易出现意外。

### 11.2 `Currency`

位置：

- `AuthorizationCommand`
- `Money`
- `AuthorizationService`

`Currency.getInstance("JPY")` 把字符串转换成 JDK 认识的 ISO 4217 currency。

如果一直用字符串：

- `"jpy"`、`"JP Y"`、`"YYY"` 这类错误更晚暴露。
- domain 中到处重复比较字符串。
- 币种规则无法集中。

当前项目仍简化为两位小数。真实多币种系统应按 currency 定义 scale rule，例如 JPY 无小数位。

### 11.3 `Clock`

位置：

- `TimeConfiguration`
- application services
- workers/recoverers

不要在业务代码里直接写：

```java
Instant.now()
```

而是：

```java
Instant.now(clock)
```

好处：

- 单元测试可以注入 fixed clock。
- 所有业务时间统一 UTC。
- 线程、机器、时区差异更容易控制。

如果直接用系统时间：

- 测试依赖当前时间，容易 flaky。
- 时区问题难排查。
- scheduler/expiry/due date 逻辑很难稳定复现。

### 11.4 `UUID`

本项目很多内部业务对象使用 `UUID`：

- `Authorization.id`
- `Statement.id`
- `OutboxEvent.id`

好处：

- 应用端生成，不依赖数据库自增。
- 多实例创建时冲突概率极低。
- 日志和消息里携带方便。

代价：

- 索引比自增整数大。
- 随机 UUID 对 clustered index 不友好。

学习项目这里优先选择解释清晰和跨组件携带方便。

### 11.5 `Optional`

适合：

```java
Optional<Authorization> findById(UUID id);
Optional<Instant> decidedAt();
```

不适合：

- entity field。
- method parameter。
- JSON DTO field。

原因：

- 返回值上的 Optional 强迫调用方处理缺失。
- 字段和参数上的 Optional 反而让序列化、mapping、调用变复杂。

如果返回 nullable：

```java
Authorization authorization = repository.findById(id);
authorization.status(); // possible NPE
```

Optional 让缺失分支更显式：

```java
repository.findById(id)
        .orElseThrow(...);
```

### 11.6 switch expression

位置：

- `AuthorizationService.mapFailure`
- enum mapping

```java
return switch (failure) {
    case ACCOUNT_BLOCKED -> CREDIT_ACCOUNT_BLOCKED;
    case CURRENCY_MISMATCH -> CURRENCY_MISMATCH;
    case INSUFFICIENT_AVAILABLE_CREDIT -> INSUFFICIENT_AVAILABLE_CREDIT;
};
```

好处：

- enum 新增值时，编译器能提示 switch 未覆盖。
- 不需要 default 吞掉未知状态。
- 比 if/else chain 更直接。

如果加一个宽泛 default：

```java
default -> UNKNOWN;
```

短期少写代码，长期会把新业务状态悄悄映射错。

### 11.7 `EnumMap`

位置：`DelayJobWorker.handlersByType`

`EnumMap` 是 Java 为 enum key 优化的 map。

比 `HashMap<DelayJobType, Handler>` 更合适：

- 内部可用数组结构。
- key 范围固定。
- 语义上表达“这个 map 的 key 就是一个 enum 集合”。

如果用普通 `HashMap` 也能跑，但语义和性能都没有 `EnumMap` 贴合。

### 11.8 `List.copyOf` 和 `Stream.toList`

位置：

- domain event buffer
- read model mapping

`List.copyOf(domainEvents)` 返回不可变 copy，然后清空内部 buffer。

如果直接返回内部 list：

- 外部调用方可以修改 aggregate 内部事件。
- 事件可能重复 append 或丢失。

`stream().map(...).toList()` 在现代 Java 中返回不可变 list，适合 read model。

如果需要可变 list，应显式使用 `Collectors.toCollection(ArrayList::new)`，不要依赖默认行为。

### 11.9 `Objects.requireNonNull`

位置：

- domain constructors
- cache constructors
- event records

它的意义不是省 if，而是 fail fast：

```java
Objects.requireNonNull(requestedAmount, "requestedAmount must not be null");
```

如果不在 constructor 防御：

- null 可能被保存到对象内部。
- 真正使用时才 NPE。
- 栈信息离创建错误很远。

尤其是 domain/event/value object，constructor 是保护对象完整性的最后一道门。

### 11.10 logging 参数化

位置：

- workers
- cache
- web filter

```java
log.warn("outbox_publish_failed eventId={} status={}", event.id(), event.status(), exception);
```

好处：

- 日志框架可延迟格式化。
- exception 作为最后一个参数能打印 stack trace。
- 结构更统一，便于搜索。

如果用字符串拼接：

```java
log.warn("failed " + event.id() + exception);
```

- 即使日志级别关闭也可能先拼字符串。
- exception 可能只打印 `toString()`，丢 stack trace。
- 日志字段不统一。

## 12. 常见“看起来多余”的代码，删掉会怎样

| 代码/写法 | 看起来像什么 | 实际解决什么 | 删掉后的典型问题 |
| --- | --- | --- | --- |
| class 上的 `@Validated` | 多一个注解 | header/path variable validation | 空 header 进入 service |
| 手写 service constructor | 样板代码 | 配置预转换、依赖显式 | 配置错误到运行期才暴露 |
| `@Qualifier` | 字符串噪音 | 多 bean 精确选择 | ambiguous bean 或注错线程池 |
| `@ConditionalOnProperty` | 多余开关 | 控制后台副作用 | 测试/本地启动自动消费 backlog |
| `TransactionOperations` | 不如 `@Transactional` 简洁 | 同一 worker 方法内拆事务 | self-invocation、长事务、finalize 边界不清 |
| `afterCommit` evict | 延后一步 | commit 后写 tombstone，避免旧 DB 值回填 cache | stale snapshot |
| `DefaultErrorHandler` + DLT | Kafka 配置复杂 | 坏消息隔离和 replay 入口 | partition 被坏消息卡住或错误被吞 |
| `EventContractException` not retryable | 少重试 | malformed message 重试不会自愈 | 无意义 retry 占用 consumer |
| `Thread.currentThread().interrupt()` | 奇怪的固定写法 | 恢复中断信号 | shutdown 语义被吞 |
| `record compact constructor` | 额外校验 | 所有创建路径统一 invariant | controller 外路径可造出坏对象 |
| `BigDecimal.setScale(2)` | 格式化 | 统一 equality/display/DB scale | `1.0` 和 `1.00` 比较意外 |
| `StringRedisTemplate` | Redis 客户端细节 | 明确 JSON string 序列化 | Java binary value 难排查、难演进 |
| `mapper-locations` | 配置路径 | XML SQL 与 interface 绑定 | runtime invalid bound statement |

## 13. 看代码时的检查清单

当你看到一个 Spring/Java 技术点，可以按这个顺序拆：

1. 它是 compile-time、startup-time、runtime 还是 failure-time 机制？
2. 它靠 Spring container、AOP proxy、MyBatis proxy、Kafka container，还是 JDK 自己生效？
3. 它保护的是输入错误、依赖缺失、事务边界、并发问题、外部故障，还是可维护性？
4. 如果删掉，最先坏在哪里：编译、启动、第一笔请求、并发高峰、故障恢复、生产排查？
5. 这个技术点是否应该集中在 infrastructure，而不是散进 application/domain？

本项目里的经验可以压缩成几句话：

```text
注解不是装饰，是给框架的 contract。
constructor 不是样板，是对象依赖和 invariant 的入口。
configuration properties 不是配置搬运，是类型化边界。
transaction annotation 不是魔法，它依赖 proxy 和调用路径。
thread pool name 不是审美，是排障入口。
record 不是省 getter，是表达 immutable data carrier。
cache 不是 source of truth，它只是可失败的性能层。
```

## 14. 补充技术点地图：更多模块里的非重复细节

这一章补充上一轮没有充分覆盖的包：notification、consumer inbox、ledger、statement batch、repayment、Outbox XML、risk JDBC 和 API response mapping。

重点仍然是同一个问题：

```text
这个写法为什么存在？
删掉或换成直觉写法，会在什么地方出问题？
```

### 14.1 Lombok fluent getter，不等于 `@Data`

位置：

- `Notification`
- `OutboxEvent`
- `DelayJob`

本项目在一些非 record、但又有状态机行为的对象上使用：

```java
@Getter
@Accessors(fluent = true)
public final class OutboxEvent {
    private OutboxEventStatus status;

    public void markPublished(Instant publishedAt) {
        status = OutboxEventStatus.PUBLISHED;
    }
}
```

这里的技术取舍是：

- `@Getter` 只生成 getter，不生成 setter。
- `@Accessors(fluent = true)` 让调用方写 `event.status()`，保持和 record/domain 风格一致。
- 状态变化仍然只能通过 `markProcessing`、`markPublished`、`markFailed` 这类业务方法发生。

如果用 `@Data`：

- Lombok 会生成 setter。
- 外部代码可以直接 `event.setStatus(...)`。
- Outbox/DelayJob/Notification 的状态机被绕开。
- 还会生成 equals/hashCode，可能不符合 aggregate/state object 的 identity 语义。

所以这不是“要不要 Lombok”的二元问题，而是：

```text
只生成读方法可以减少样板代码。
生成写方法会削弱 domain/state machine 边界。
```

### 14.2 稳定 consumer name 是消息幂等契约

位置：

- `RequestNotificationService.CONSUMER_NAME`
- `RecordLedgerEntryService.CONSUMER_NAME`
- `consumer_inbox`

Consumer Inbox 的唯一键通常是：

```text
(consumer_name, event_id)
```

`consumer_name` 不是 Java class name，也不是随便起的日志标签。它是“这个逻辑消费者”的持久身份。

如果重构类名时顺手把它从：

```text
notification-v1
```

改成：

```text
notification-service-v2
```

旧 event 会被 Inbox 视为“新消费者第一次处理”：

```text
same event_id
different consumer_name
unique key does not conflict
side effect may run again
```

所以 consumer name 应该稳定，除非你明确要建立一个新的消费语义或重放版本。

### 14.3 `INSERT` + unique key 比 `SELECT then INSERT` 更适合 claim

位置：

- `ConsumerInboxMapper.xml`
- `MyBatisConsumerInboxRepository`
- Notification/Ledger consumer

Inbox claim 写法是直接 insert：

```xml
INSERT INTO consumer_inbox (consumer_name, event_id, processed_at)
VALUES (#{consumerName}, #{eventId}, #{processedAt})
```

Repository 捕获 `DuplicateKeyException`：

```java
try {
    return mapper.insert(...) == 1;
} catch (DuplicateKeyException exception) {
    return false;
}
```

为什么不先查？

```text
SELECT not exists
then INSERT
```

两个 consumer thread 可以同时看到不存在，然后都尝试 insert。最后仍然要靠 unique key 决胜。

直接 insert 的优势：

- 少一次 round trip。
- 没有 read-then-insert race。
- 数据库唯一键是最终裁判。
- duplicate 变成明确的 false 分支。

注意这里不要捕获所有 `DataAccessException`。只有 duplicate 表示“已经处理过”；连接失败、SQL 错误、表不存在都应该继续抛出，让 Kafka retry/DLT 感知真实故障。

### 14.4 Outbox/DelayJob 的 exponential backoff 位移写法

位置：

- `OutboxEvent.markFailed`
- `DelayJob.markFailed`

代码使用：

```java
long delaySeconds = Math.min(
        1L << Math.min(attempts - 1, 6),
        MAX_RETRY_DELAY_SECONDS
);
```

`1L << n` 是位移写法，含义是 `2^n`。

为什么要加两个 `Math.min`？

- 内层 `Math.min(attempts - 1, 6)` 限制指数，避免 attempts 很大时位移溢出。
- 外层 `Math.min(..., MAX_RETRY_DELAY_SECONDS)` 限制最大等待时间，避免可恢复任务睡太久。

如果每次失败都立刻重试：

```text
Kafka outage / bank API outage
-> workers tight loop retry
-> DB/Kafka/日志压力更大
```

如果 backoff 不设上限：

```text
temporary outage recovered
but event/job waits hours
```

这类代码看起来像数学技巧，其实是故障恢复节奏控制。

### 14.5 截断 `lastError` 是为了保留真正失败原因

位置：

- `OutboxEvent.truncate`
- `DelayJob.truncate`

错误信息来自异常，长度不可控。数据库列通常是 `VARCHAR(500)` 一类固定长度。

如果不先截断：

```text
original failure: Kafka timeout
markFailed writes huge stack/error text
DB update fails because last_error too long
worker reports DB truncation error
original Kafka timeout is hidden
```

本项目在 domain/state object 里截断，是为了让失败状态能稳定写回表中，后续 recoverer 和人工排查能看到原始错误的关键信息。

### 14.6 XML 中的 `&lt;=` 不是故意难看

位置：

- `OutboxEventMapper.xml`
- `DelayJobMapper.xml`
- `CardTransactionMapper.xml`

MyBatis XML 是 XML 文件，所以 SQL 里的 `<` 不能直接写：

```xml
AND next_attempt_at <= #{now}
```

这会破坏 XML 解析。

正确写法：

```xml
AND next_attempt_at &lt;= #{now}
```

如果忘记转义，通常不是 SQL 执行时报错，而是 mapper XML 加载或解析阶段失败。排查时要先看 XML 是否能被解析，再看 SQL 是否正确。

### 14.7 MyBatis `<foreach>` 可以安全生成批量 SQL

位置：`CardTransactionMapper.xml`

Statement generation 要把一批交易标记到同一张 statement：

```xml
UPDATE card_transactions
SET statement_id = CASE id
    <foreach collection="transactions" item="transaction">
        WHEN #{transaction.id} THEN #{transaction.statementId}
    </foreach>
    END
...
WHERE id IN
<foreach collection="transactions" item="transaction" open="(" separator="," close=")">
    #{transaction.id}
</foreach>
```

为什么不用 Java 循环逐条 update？

- 每条交易一次 DB round trip，批量账单时慢。
- 中间某条失败时更难判断整体状态。
- SQL 层看不出“这一批属于同一次 assignment”。

为什么不用字符串拼接？

- SQL injection 风险。
- 类型转换和转义要自己处理。
- 日志和 review 更难确认安全性。

`<foreach>` 的价值是：动态生成 SQL 结构，但每个值仍然通过 `#{}` 做 bind parameter。

### 14.8 MyBatis primitive type：`javaType="_int"`

位置：`StatementMapper.xml`

```xml
<arg column="transaction_count" javaType="_int"/>
```

`_int` 是 MyBatis 表示 primitive `int` 的写法。

这里 `transaction_count` 不应该为 null，所以 row record 里用 primitive `int`。

如果数据库列可能为 null，却映射到 primitive：

- null 无法表达。
- 可能触发映射错误，或被误当成 0。

如果业务上 null 和 0 有不同语义，应使用 `Integer`，并在 domain restore 时显式处理。

### 14.9 `ObjectNode` 手写 payload，避免 domain 泄漏成 Kafka contract

位置：

- `AuthorizationOutboxAdapter`
- `StatementOutboxAdapter`
- `RepaymentOutboxAdapter`
- `CardTransactionOutboxAdapter`

Outbound adapter 没有直接：

```java
objectMapper.writeValueAsString(domainEvent)
```

而是手工构造 payload：

```java
ObjectNode payload = objectMapper.createObjectNode();
payload.put("authorizationId", authorizationId.toString());
payload.put("amount", requestedAmount.amount().toPlainString());
payload.put("currency", requestedAmount.currency().getCurrencyCode());
```

这样做的原因：

- Kafka integration event 是跨上下文 contract。
- Domain event 是当前代码内部模型。
- 两者不能随便绑定。

如果直接序列化 domain event：

- domain 字段名重构会破坏 Kafka 消息。
- 新增内部字段可能意外暴露给 consumer。
- consumer 依赖当前 Java class 的结构，未来拆微服务更难。

金额用 `toPlainString()` 是另一个细节：consumer 再用 `new BigDecimal(String)` 解析，避免 JSON number 被错误转成 double。

### 14.10 Adapter 边界解析 UUID、Instant、Currency

位置：

- `CardTransactionLedgerListener`
- `RepaymentLedgerListener`
- Notification/Risk listeners

Listener 从 `JsonNode` 读取 text，再转成业务类型：

```java
UUID.fromString(eventReader.requiredText(payload, "cardTransactionId"))
new BigDecimal(eventReader.requiredText(payload, "amount"))
Currency.getInstance(eventReader.requiredText(payload, "currency"))
Instant.parse(eventReader.requiredText(payload, "postedAt"))
```

为什么在 adapter 层解析？

- application service 不需要知道 JSON 字段名。
- 解析失败说明消息 contract 坏了，应该走 Kafka retry/DLT。
- command object 可以保持 typed fields。

如果把 `String` 一直传到 service：

- service 到处充满 `UUID.fromString`、`Instant.parse`。
- 业务错误和消息格式错误混在一起。
- 同一个字段的解析规则可能在多个地方不一致。

### 14.11 先判断 `eventType`，再解析特定 payload

位置：`AuthorizationRiskFeatureListener`

Risk feature listener 只关心：

```text
authorization.approved
authorization.declined
```

同一个 topic 里也可能有合法但不关心的事件，例如：

```text
authorization.posted
authorization.expired
```

所以顺序应该是：

```text
read envelope
check eventType
only then parse event-specific payload fields
```

如果先按 approved/declined 的字段解析，再判断 event type：

```text
authorization.posted is a valid event
but lacks approvedAt/declinedAt
listener throws
record goes to DLT
```

这不是坏消息，只是不属于当前 consumer 的业务兴趣。这个顺序是 Kafka consumer 很常见的细节。

### 14.12 `YearMonth` 比 `minusDays(30)` 更适合账单月

位置：`StatementCycleService`

账单周期不是固定 30 天。

```java
YearMonth previousMonth = YearMonth.from(periodEnd).minusMonths(1);
LocalDate previousCloseDate = dayInMonth(previousMonth, properties.closeDayOfMonth());
```

为什么不用：

```java
periodEnd.minusDays(30)
```

因为月份长度不同：

- 2 月有 28/29 天。
- 4/6/9/11 月有 30 天。
- 1/3/5/7/8/10/12 月有 31 天。

`YearMonth` 表达“月份”这个概念，`LocalDate` 表达“某一天”。把这两个概念分开，日期逻辑更不容易写偏。

### 14.13 private record 适合绑定强相关局部值

位置：`StatementCycleService.BillingCycle`

```java
private record BillingCycle(
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate
) {
}
```

它的作用不是“为了 DDD 建值对象”，而是避免三个强相关日期在方法之间散传：

```java
generate(accountId, periodStart, periodEnd, dueDate)
```

参数都是 `LocalDate`，顺序传错编译器也发现不了。

用 record 之后：

```java
cycle.periodStart()
cycle.periodEnd()
cycle.dueDate()
```

调用点更可读，也降低传参错位风险。

### 14.14 `TreeSet` 与 `Set.copyOf`

位置：`JapaneseBusinessDayCalendar`

`TreeSet<LocalDate>` 让 holiday set 天然排序。功能上 `HashSet` 也能判断 contains，但调试和测试时顺序不稳定。

`Set.copyOf(holidays)` 用在遍历时追加替代休日：

```java
for (LocalDate holiday : Set.copyOf(holidays)) {
    ...
    holidays.add(substitute);
}
```

如果直接遍历原集合同时 add：

```text
ConcurrentModificationException
```

这不是多线程问题，而是 Java fail-fast iterator 对“遍历中结构修改”的保护。

### 14.15 `JdbcTemplate` adapter 里的 `Instant -> Timestamp`

位置：`JdbcRiskVelocityCounter`

JDBC 查询使用：

```java
Timestamp.from(since)
```

这个转换放在 infrastructure adapter，而不是 application service。

原因：

- `RiskAssessmentService` 只关心 `Instant since`。
- `JdbcTemplate` 和 `java.sql.Timestamp` 是 persistence 技术细节。
- 以后把 velocity counter 改成 MyBatis/Redis/ClickHouse，application service 不需要改。

`queryForObject(..., Integer.class, ...)` 返回的是包装类型 `Integer`。本项目把 null 兜底为 0：

```java
return count == null ? 0 : count;
```

虽然 `COUNT(*)` 正常不会返回 null，但这是 adapter 层的防御性写法：让授权主流程不因为 JDBC 包装值意外为 null 而 NPE。

### 14.16 `toUnmodifiableMap` 防止配置变成隐藏全局状态

位置：

- `AuthorizationService`
- `RiskAssessmentService`

配置从 YAML 读入后会转换成更适合运行时使用的 map：

```java
Collectors.toUnmodifiableMap(...)
```

如果保留可变 `HashMap`：

- 测试或其他代码可能误改阈值。
- 风控/授权结果会依赖运行中隐藏状态。
- 并发读写还可能引入线程安全问题。

不可变 map 让配置在启动后成为只读决策输入。

### 14.17 `Optional` 不直接进入 JSON response

位置：

- `RepaymentResponse`
- `AuthorizationResponse`
- `CardTransactionResponse`

Domain 里用 `Optional` 表达“可能没有”：

```java
repayment.receivedAt()
```

API DTO 里显式转成 nullable field：

```java
repayment.receivedAt().orElse(null)
```

原因：

- JSON 客户端不应该看到 Java `Optional` 的内部结构。
- `null` 在 REST response 中更直观地表达“这个阶段尚未产生”。
- DTO 层是 domain model 和 API contract 的翻译边界。

如果直接返回 domain object 或 Optional 字段：

- API contract 绑定 Java 实现细节。
- 客户端看到的 JSON 可能变成奇怪的 wrapper。
- domain getter 变化会影响外部 API。

### 14.18 overload mapping：`from(Statement)` 和 `from(StatementReadModel)`

位置：`StatementResponse`

同一个 response DTO 有两个静态工厂：

```java
StatementResponse.from(Statement statement)
StatementResponse.from(StatementReadModel statement)
```

这叫 method overloading。编译器根据参数静态类型选择方法。

为什么不写成：

```java
from(Object source)
```

因为 `Object` 会丢掉类型检查。你必须在方法里 `instanceof`，而且新增来源时编译器不会提醒你哪里没处理。

用 overload 的好处：

- domain 查询和 cached read model 查询都返回同一个 API contract。
- 两条 mapping 逻辑各自清晰。
- 字段缺失在编译期更容易暴露。

### 14.19 `getFirst()` 是 Java 21 API，但前提是非空保护

位置：`StatementService.totalAmount`

```java
Currency currency = transactions.getFirst().amount().currency();
```

`getFirst()` 来自 Java 21 `SequencedCollection`。

它比 `get(0)` 更表达意图：

```text
我要第一项，而不是关心随机访问 index 0
```

但它不是防空魔法。如果 list 为空，会抛异常。本项目在调用前已经拒绝空交易列表：

```java
if (transactions.isEmpty()) {
    throw new StatementGenerationRejectedException(...);
}
```

如果没有这层保护，空账单路径会在金额计算处以低层集合异常失败，错误语义不清楚。

### 14.20 `RoundingMode.CEILING` 是产品语义，不只是数学参数

位置：`StatementService.minimumPayment`

```java
setScale(2, RoundingMode.CEILING)
```

最低还款额按比例计算时可能产生超过两位小数。本项目使用 `CEILING`：

```text
向上取到 0.01
```

这比 `HALF_UP` 更保守，避免把最低还款金额舍入得更低。

如果使用 `HALF_UP`：

```text
1000.001 -> 1000.00
```

最低还款可能少收一分钱。真实产品规则要看合同和监管要求；当前项目用 `CEILING` 是为了表达“最低还款 floor/rate 不应因为舍入低估”。

### 14.21 `@Primary` 和 decorator coverage：历史 Card cache

位置：

- 历史版本的 `CachedCardRepository`
- `MyBatisCardRepository`
- 历史版本的 `CardSnapshotCacheConfiguration`

旧版本里 `CardRepository` 同时有 MyBatis 实现和 cache decorator。业务层应该注入 decorator：

```text
CardRepository -> CachedCardRepository -> MyBatisCardRepository
```

所以当时 `CachedCardRepository` 使用 `@Primary`。当前代码已经删除 Card snapshot cache，
`CardRepository` 只有 `MyBatisCardRepository` 这一个实现，不需要 `@Primary`。

如果没有 `@Primary`：

```text
Spring sees two CardRepository beans
dependency injection becomes ambiguous
```

旧版 `@Qualifier("cardSnapshotCache")` 也很重要，因为当时 statement cache 和 card cache
都是 `SnapshotCache` 类型。当前实现只保留窄版 `StatementReadService`，不再需要这类 qualifier。

如果不指定 qualifier：

```text
Spring cannot know which SnapshotCache bean to inject
or a future refactor may inject the wrong cache
```

这个点和上一轮的 `@Qualifier` 有重复，但 Card cache 是另一个很值得重复看的场景：同一个 interface 有真实 adapter 和 decorator 时，要明确 default bean。

### 14.22 Aggregate 不用 `@Data`：CreditAccount、CardTransaction、Repayment

位置：

- `CreditAccount`
- `CardTransaction`
- `Repayment`
- `Notification`
- `OutboxEvent`
- `DelayJob`

这些类都不是普通 DTO。它们有状态机或 invariant：

```text
CreditAccount.reserve/release/postAuthorized/applyRepayment
CardTransaction.markPosted/assignToStatement
Repayment.markReceived
Notification.markSent/recordDeliveryFailure
OutboxEvent.markProcessing/markPublished/markFailed
DelayJob.markProcessing/markDone/markFailed
```

所以可以用：

```java
@Getter
@Accessors(fluent = true)
```

但不要用：

```java
@Data
```

原因是 `@Data` 会生成 setter。setter 会让外部代码绕开状态转换：

```java
transaction.setStatus(POSTED);
transaction.setStatementId(statementId);
```

这样 domain invariant、domain event、Outbox append、row-lock 语义都会被绕开。

这是很重要的 Java/Lombok 习惯：减少样板代码可以，但不能把对象的保护门拆掉。

### 14.23 Mapper adapter 集中做 String、UUID、enum 转换

位置：

- `MyBatisCardRepository`
- `MyBatisCreditAccountRepository`
- `MyBatisCardTransactionRepository`
- `MyBatisRepaymentRepository`
- `MyBatisDelayJobRepository`

数据库 row 里经常是 string：

```text
id CHAR/VARCHAR
status VARCHAR
currency CHAR(3)
```

domain 里希望是 typed value：

```java
UUID
enum
Currency
Money
```

转换应该放在 repository adapter：

```java
UUID.fromString(row.id())
CardStatus.valueOf(row.status())
Currency.getInstance(row.currency())
new Money(row.amount(), currency)
```

如果让 service 直接处理 row：

- application layer 会依赖数据库列表示。
- 同一个转换规则会散落在多个 service。
- schema 改动更容易扩散。

Repository adapter 的职责就是把 persistence representation 翻译成 domain representation。

### 14.24 `jdbcType` 用于 nullable 参数

位置：

- `RepaymentMapper.xml`
- `NotificationMapper.xml`
- 各种 `*_at` 和 nullable id 字段

MyBatis 在设置 null 参数时，有时需要知道 JDBC type：

```xml
#{creditAccountId,jdbcType=CHAR}
#{receivedAt,jdbcType=TIMESTAMP}
```

为什么重要？

- 非 null 值可以从 Java object 推断类型。
- null 没有运行时类型。
- 不同 driver 对未知 null 的处理不完全一样。

本项目在关键 nullable 字段上显式写 `jdbcType`，例如 repayment `PENDING` 阶段还没有 `credit_account_id` 和 `received_at`。

如果省掉，可能本地 MySQL 能跑，但换 driver 或迁移数据库时出现参数类型错误。

### 14.25 `ON DUPLICATE KEY UPDATE` 是原子 upsert

位置：`CardRiskFeatureProjectionMapper.xml`

Risk feature projection 使用 MySQL upsert：

```sql
INSERT INTO card_risk_features (...)
VALUES (...)
ON DUPLICATE KEY UPDATE
  authorization_count = authorization_count + 1,
  approved_count = approved_count + CASE WHEN ... THEN 1 ELSE 0 END,
  last_decision_at = GREATEST(last_decision_at, #{decidedAt})
```

它解决的是 insert-or-increment race。

如果写成：

```text
SELECT existing row
if exists UPDATE count = count + 1
else INSERT row
```

并发 consumer 或 replay 可能出现：

- 两个线程都看到不存在。
- 一个 insert 成功，另一个 insert 冲突。
- 或两个线程读到同一个旧 count，再覆盖写回，丢计数。

`ON DUPLICATE KEY UPDATE` 把判断和更新交给数据库一条语句完成。

`GREATEST(last_decision_at, #{decidedAt})` 也很关键：Kafka 可能有迟到事件，迟到事件不应该把“最后决策时间”倒退。

### 14.26 `IN ()` 空列表保护

位置：`MyBatisCardTransactionRepository.assignStatement`

MyBatis `<foreach>` 生成动态 `IN (...)`：

```xml
id IN
<foreach collection="transactions" item="transaction" open="(" separator="," close=")">
    #{transaction.id}
</foreach>
```

如果 list 为空，可能生成非法 SQL：

```sql
id IN ()
```

所以 repository adapter 先判断：

```java
if (transactions.isEmpty()) {
    return;
}
```

这类保护属于“防低层语法错误”的技术习惯。它和业务规则无关，但能让空批次成为安全 no-op。

### 14.27 主表 duplicate 可幂等，子表 duplicate 应 rollback

位置：`MyBatisStatementRepository.insert`

Statement insert 分两步：

```text
insert statements
insert statement_lines
```

只有主表 `statements` 的 cycle unique conflict 可以当成“同一账期已经生成过”的幂等结果。

如果 `statement_lines` 插入时 duplicate：

```text
主表已插入
明细冲突
```

这说明数据状态不一致，不能吞掉异常。正确做法是让异常继续抛出，整个 transaction rollback。

这体现了异常处理粒度：

```text
不是所有 DuplicateKeyException 都是幂等成功。
只有符合当前 claim 语义的唯一键冲突才可以转成 false。
```

### 14.28 DelayJob properties 也应该 fail fast

位置：`DelayJobProperties`

配置类 compact constructor 可以做启动期防御：

```java
if (fixedDelayMs <= 0 || maxAttempts <= 0 || processingTimeoutSeconds <= 0) {
    throw new IllegalArgumentException(...);
}
```

如果不校验：

- `fixedDelayMs=0` 可能导致 scheduler 过度频繁运行。
- `maxAttempts=0` 让任务第一次失败就出现奇怪状态。
- `processingTimeoutSeconds=0` 让 worker 正在处理时立刻被 recoverer 认为超时。
- `workerPoolSize=0` 启动或提交任务时才失败。

这类错误越早失败越好。配置错误属于 startup-time failure，不应该等到第一笔 due job 才暴露。

### 14.29 为什么这一轮允许重复？

前面几轮更偏“把每个技术点讲清楚一次”。这一轮改成“学习覆盖率”目标：

```text
changed Java files: 174 / 226 = 77.0%
```

这已经超过 60% 的类覆盖目标。

为什么适合重复？

Spring / Java / 第三方库的学习和业务一致性不一样。

业务一致性更适合少量关键路径深挖：

```text
authorization -> row lock -> outbox -> delay job
```

技术习惯则需要在不同形态里反复遇到：

```text
@RestController
@Valid
record
port interface
@Mapper
@ConfigurationProperties
@Scheduled
@Transactional
DuplicateKeyException
Optional
ObjectMapper
KafkaTemplate future ack
```

第一次看到 `@Param`，你可能只记住“XML 要绑定名字”。
第二次在 Outbox mapper 看到，你会联想到多参数 claim。
第三次在 Statement mapper 看到，你会想到 batch SQL、`<foreach>` 和锁范围。

所以这一轮不再使用统一的文件头注释，而是把解释放回代码对应位置：

```text
@RestController / @RequestMapping 旁边解释 Spring MVC boundary
@Valid / @NotNull / @Param 旁边解释参数绑定和 fail fast
@ConfigurationProperties / @EnableConfigurationProperties 旁边解释配置绑定和 bean 注册
@Scheduled / @KafkaListener 旁边解释线程池、groupId、containerFactory
record compact constructor 旁边解释不可变输入和非 HTTP 路径防御
repository/port 方法旁边解释 Optional、insert-first claim、DuplicateKeyException
ObjectNode / ProducerRecord / KafkaTemplate.send().get(...) 旁边解释第三方库契约
```

### 14.30 贴近代码点的注释怎么读？

这轮新增注释遵循一个原则：

```text
看到语法点时，马上解释这个语法点为什么存在。
```

例如，一个 `@Mapper` 注释应该贴在 `@Mapper` 旁边：

```text
@Mapper 让 MyBatis 生成 runtime proxy。
如果没有它，Spring constructor injection 找不到 mapper 实现。
```

一个 `record compact constructor` 注释应该贴在 constructor 前：

```text
compact constructor 覆盖 controller、scheduler、Kafka、test 等所有创建路径。
如果只靠 @Valid，非 HTTP 路径仍可能构造坏对象。
```

一个 `DuplicateKeyException` 注释应该贴在 catch 分支：

```text
这个 duplicate key 是预期的幂等重复，不应该进入 Kafka retry/DLT。
其他数据库异常仍要抛出。
```

一个 `KafkaTemplate.send(...).get(...)` 注释应该贴在等待 ack 的地方：

```text
等待 broker ack 后才能把 Outbox 标记为 PUBLISHED。
如果 fire-and-forget，broker 实际失败时消息会永久丢失。
```

这些重复不是噪音，而是刻意形成技术反射：

```text
看到注解 -> 想到 Spring 扫描/proxy/binding 时机
看到 record -> 想到 immutable data carrier/constructor validation
看到 port -> 想到 dependency direction/testability
看到 mapper -> 想到 runtime proxy/XML parameter binding
看到 DuplicateKeyException -> 想到唯一键语义是否真的等价于幂等成功
```

### 14.31 当前覆盖盘点：够不够？

按“上一轮已提交的基础覆盖 + 这一轮工作区新增的贴近代码点注释”合并计算：

```text
covered Java files: 136 / 226 = 60.2%
```

这已经达到 60% 目标。更重要的是，这次不是靠文件头批量注释堆数字，而是把解释贴到注解、构造函数、mapper 方法、catch 分支和第三方库调用点旁边。

这份技术注释/文档已经覆盖了这些高价值形态：

| 区域 | 覆盖状态 | 代表点 |
| --- | --- | --- |
| Spring Boot / Gradle / YAML | 已覆盖 | starter、BOM、toolchain、env default、mapper-locations |
| Web API / DTO | 已覆盖 | `@RestController`、`@Validated`、`@Valid`、record、nullable response |
| Authorization | 高覆盖 | constructor injection、transaction proxy、configuration properties、Outbox adapter、expiry job、API response mapping、Locale.ROOT fingerprint |
| Card | 高覆盖 | record snapshot（历史 cache 设计）、cache decorator（已删除）、`@Primary`、Bean name、mapper proxy、row/domain separation |
| CreditAccount | 已补强 | aggregate 不用 `@Data`、row-lock mapper、repository port、derived available credit、Currency conversion |
| Transaction / Presentment | 已补强 | `@Valid`、command compact constructor、`@Transactional`、CardTransaction 状态机、`<foreach>` batch update |
| Repayment | 已补强 | API validation、domain Optional、auto-debit config、DelayJob adapter、Outbox adapter、repayment row/domain mapping |
| Statement | 已补强 | controller/scheduler 双入口、`YearMonth`、private record、business calendar、statement read cache、repository duplicate 粒度 |
| Notification | 高覆盖 | stable consumer name、fluent getter、eventType-before-payload、generic subject type、notification row/domain mapping |
| Ledger | 高覆盖 | append-only mapper、Inbox idempotency、typed JSON parsing、`BigDecimal(String)`、source type enum |
| Risk | 已补强 | Feign port、JdbcTemplate adapter、typed config、projection consumer name、atomic upsert、simulated external API |
| Outbox | 完整覆盖 | properties/configuration/port/event/repository/mapper/claimer/worker/recoverer/XML、backoff、lease、ack/finalize |
| Inbox | 完整覆盖 | consumer-level idempotency、insert-first claim、DuplicateKeyException -> false |
| DelayJob | 高覆盖 | handler/repository/type/mapper/row/claim/recoverer/properties/domain/scheduler contract |
| Cache | 已补强 | Caffeine L1、Redis L2、`StringRedisTemplate`、TTL jitter、single-flight、after-commit evict |
| MyBatis XML | 已补强 | `#{}`、constructor mapping、`&lt;=`, `jdbcType`, `<foreach>`, upsert |

按目录统计，合并覆盖大致是：

| 包 | 已覆盖 / 总数 |
| --- | ---: |
| `authorization` | 18 / 27 |
| `card` | 9 / 12 |
| `creditaccount` | 5 / 8 |
| `delayjob` | 11 / 13 |
| `infrastructure` | 14 / 14 |
| `ledger` | 7 / 12 |
| `messaging` | 16 / 22 |
| `monitoring` | 2 / 2 |
| `notification` | 11 / 14 |
| `repayment` | 12 / 30 |
| `risk` | 10 / 17 |
| `statement` | 13 / 37 |
| `transaction` | 7 / 17 |

仍然没覆盖的文件主要是极薄的状态、少数异常、少数同类 DTO 或业务注释已经足够的聚合。
现在已经不需要为了数字继续硬塞每个文件；下一步更合理的是：

```text
读到哪一个类觉得“不自然”
再把那一个类扩成更详细的局部解释
```

## 15. 建议复习路线

1. 先读 `MiniCardPaymentSystemApplication`、`build.gradle`、`application.yml`，理解应用如何启动。
2. 再读 `AuthorizationController` 和 `CreateAuthorizationRequest`，理解 Web boundary 和 validation。
3. 读 `AuthorizationService`，重点看 constructor injection、`@Transactional`、`Clock`、`Currency`。
4. 读 `AuthorizationMapper` 和 XML，理解 MyBatis proxy、`@Param`、constructor mapping、`#{}`。
5. 读 `WorkerExecutorConfiguration`、`PollingSchedulerConfiguration`、`DelayJobPoller`，理解 scheduler 和 worker pool 分离。
6. 读 `KafkaTopicsConfiguration`、`KafkaConsumerConfiguration`、`KafkaOutboxMessagePublisher`、`IntegrationEventReader`，理解 Kafka container、DLT、ack、JSON contract。
7. 读 `StatementReadService`、`StatementReadCacheProperties` 和 `docs/caching-and-rate-limiting-cn.md`，理解第三方 cache、TTL jitter、single-flight 和 transaction hook；再读 `RedisRiskVelocityCounter` 理解 Redis velocity 计数。
8. 读 `ExternalRiskClient`、`ExternalRiskGatewayAdapter`，理解 Feign、AOP 和 circuit breaker。
9. 读 `RequestNotificationService`、`RecordLedgerEntryService`、`ConsumerInboxMapper.xml`，理解 stable consumer name、Inbox claim 和 duplicate key。
10. 读 `OutboxEvent`、`DelayJob`、`OutboxEventMapper.xml`、`CardTransactionMapper.xml`，理解 backoff、XML escaping 和 MyBatis `<foreach>`。
11. 读 `StatementCycleService`、`JapaneseBusinessDayCalendar`、`StatementGenerationService`，理解 `YearMonth`、private record、`Set.copyOf`、`getFirst()` 和 rounding。
12. 读 `JdbcRiskVelocityCounter`、`AuthorizationOutboxAdapter`、Ledger listeners，理解 adapter 边界里的 SQL temporal conversion、ObjectNode payload 和 typed parsing。

每读一个点，都问一句：

```text
如果没有它，失败会发生在什么时间、什么线程、什么边界？
```

这会比单纯记住注解名更接近真实后端工程判断。
