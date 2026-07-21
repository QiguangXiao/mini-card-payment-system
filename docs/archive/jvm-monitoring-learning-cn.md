# JVM 核心概念、GC 与线上排查学习笔记

> **归档对齐说明（2026-07）**：本文的概念、诊断步骤和 interview 推导已逐段对齐当前实现。Kafka 现只有 authorization/transaction 两个通知 listener，历史 Risk/Ledger projection consumer 已删除；后台现有五组 worker pool 和五组 scheduler pool。2026-06-21 的 IDEA/JVM 数据保留为**历史采样**，只说明当时观测方法，不能替代当前配置或生产 sizing。现行 JVM/监控文档以 [jvm-threads-runtime-cn.md](../jvm-threads-runtime-cn.md) 为准。

这份文档把 JVM 基础、GC、内存增长模拟、线上排查和interview回答放在一起讲，并尽量绑定到本工程的真实类、请求路径、线程池和 Actuator 配置。

当前工程背景：

- Java 21, Spring Boot 3.5.14, Spring MVC/Tomcat, MyBatis, MySQL, Kafka, Actuator。
- 主要业务链路包括 authorization、presentment posting、statement、repayment、notification 和 external/velocity risk；项目不再维护 historical risk projection 或 ledger projection consumer。
- Outbox、Inbox、DelayJob 已经是当前可靠性机制，不是未来 TODO。
- 本次已在本机用 IntelliJ IDEA 启动 mini-card 应用并采集运行快照；本机 Java 是 Temurin OpenJDK 21.0.11。

## 历史本地运行快照（不可当作当前配置）

采集时间：2026-06-21 12:43-12:46 JST。

运行方式：

- MySQL/Kafka 使用 `docker compose` 本地容器。
- Spring Boot 通过 IntelliJ IDEA 启动，PID 是 `47626`，命令行包含 `idea_rt.jar`，Tomcat 监听 `localhost:8080`。
- 运行时 Java 是 `21.0.11+10-LTS`，Spring Boot 版本是 `3.5.14`，Kafka client 版本是 `3.9.2`。
- `/actuator/health` 返回 `UP`，readiness/liveness 组都可用。
- 当时原始采集文件写到未纳入版本控制的 `build/diagnostics/idea-jvm-2026-06-21-1243/`；当前工作区已没有该目录，因此本文保留的是采样摘要，不能把路径当作仍可复查的证据附件。

`jcmd <pid> VM.system_properties` 关键项：

- `java.version = 21.0.11`，`java.runtime.version = 21.0.11+10-LTS`。
- `java.vm.name = OpenJDK 64-Bit Server VM`，`java.vm.vendor = Eclipse Adoptium`。
- `file.encoding = UTF-8`，`sun.stdout.encoding = UTF-8`，`sun.stderr.encoding = UTF-8`。
- `user.timezone = Asia/Tokyo`，`user.country = JP`，`user.language = en`。
- 没有看到 `spring.profiles.active`，所以本次是 Spring Boot default profile。

`jcmd <pid> VM.flags` 关键项：

- `-XX:+UseG1GC`，当前使用 G1。
- `-XX:InitialHeapSize=268435456`，初始 heap 约 256 MiB。
- `-XX:MaxHeapSize=4294967296`，最大 heap 约 4 GiB。
- `-XX:G1HeapRegionSize=2097152`，G1 region size 是 2 MiB。
- `-XX:CICompilerCount=4`，`-XX:ConcGCThreads=3`。
- `-XX:TieredStopAtLevel=1`，这是 IDEA 快速启动常见参数，会减少高层 JIT 优化以换启动速度。

一次真实 `POST /api/authorizations` 请求的观察：

- 使用新的 `Idempotency-Key = idea-jvm-monitoring-20260621-1246-bda87f3b-0082-4c64-9ce6-4b651ae048e8`。
- 请求字段包括 `cardId`、`amount`、`currency`、`merchantId`、`merchantCountry`、`cardholderCountry`。
- `/api/authorizations` curl 端到端耗时约 229ms；Actuator `http.server.requests` 记录该 URI 为 228ms。
- 内部模拟风控 `/external-risk/assess` 由同一应用处理，Actuator 记录该 URI 为 105ms；Resilience4j `externalRisk` successful call 为 122ms。
- 响应状态是 `APPROVED`，`postedAt = null`，因为 authorization 还没有进入 presentment posting。
- Outbox event `16b91248-a13d-492e-8cd1-973620f24f5a` 随后变为 `PUBLISHED`，`attempts = 0`。
- Kafka consumer 通过 Consumer Inbox 记录 `notification-v1`，说明 notification consumer 成功处理了该 event。
- 当时旧版 `notifications` 行把 `AUTHORIZATION_APPROVED` 和 `PENDING` 放在一起；当前模型已拆成无投递状态的 `notifications` intent，以及每渠道 `notification_deliveries.PENDING`。无论新旧，这一步成功都只代表 durable work 已创建，不代表短信/邮件已经送达。

请求导致的 JVM 变化：

| 指标 | 请求前 | 请求后 | 观察 |
| --- | ---: | ---: | --- |
| `jcmd GC.heap_info` used | 87006K | 99363K | 一次请求、Actuator 查询和异步发布后，heap 短时增加约 12 MiB |
| `jcmd GC.heap_info` Metaspace used | 76692K | 79691K | 请求后 Kafka producer/metrics 等类和代理路径进一步加载 |
| `jvm.memory.used?area=heap` | 86.73 MiB | 95.92 MiB | Actuator 聚合值与 `jcmd` 采样口径不同，但趋势一致 |
| `jvm.memory.used?area=nonheap` | 104.35 MiB | 107.99 MiB | class metadata、code cache、metrics 相关对象增加 |
| `jvm.gc.pause` | count 5, total 0.032s, max 0.013s | count 6, total 0.039s, max 0.013s | 触发 1 次 G1 young GC，没有看到 Full GC |
| `jvm.threads.live` | 93 | 97 | Outbox publish 后 lazy 创建 worker、Kafka producer/network/metrics 等线程 |

JVM/Actuator 快照：

- 请求后 `jvm.threads.live = 97`。
- 请求后 `jvm.threads.states`：`RUNNABLE = 27`、`WAITING = 13`、`TIMED_WAITING = 57`、`BLOCKED = 0`。
- 请求后 `jcmd <pid> GC.heap_info` 显示 G1 heap total 137216K、used 99363K；Metaspace used 79691K。
- Thread dump 是采样时刻，不是全程录像；本次 dump 没有刚好截到 `/external-risk/assess` 的 `Thread.sleep(100ms)` 栈，但 HTTP metrics 和 Resilience4j metrics 已经证明该调用发生且成功。

当时的线程池快照（采样后代码已经演进）：

- 当时采到了 `outboxTaskScheduler = 1`、`delayJobTaskScheduler = 2` 和旧 statement scheduler；当前名称和完整拓扑见第 2.4 节。
- `outboxWorkerExecutor` 在真实 Outbox publish 后从 0 增长到 1，说明 worker thread 是按任务 lazy 创建的。
- `delayJobWorkerExecutor = 0`，因为采样期间没有 due DelayJob 需要执行。
- Hikari `active = 0`、`idle = 10`、`pending = 0`，本次请求结束后没有 DB connection pool 积压。

DB schema drift 检查：

- 已检查 `mini_card` 实际表的 columns、indexes、constraints。
- 当时没有发现阻断 authorization、Outbox、Consumer Inbox 或 notification 的缺失字段、索引、唯一约束或 check constraint；该结论只属于 2026-06-21 的本地库快照。
- 因此本次没有执行 DB schema 同步。实际库里 `credit_accounts.posted_balance` 带 `DEFAULT 0.00`，这是历史本地兼容差异，不属于缺失项，也不影响本次链路。

一个容易踩坑的监控点：

- 本次 `/actuator/metrics` 实际只暴露了 `tomcat.sessions.*`，没有暴露 `tomcat.threads.current` 和 `tomcat.threads.busy`。
- 因此本文中提到 Tomcat thread metrics 时，要理解为“如果当前环境暴露这些 metrics 就查它”；本工程当前实测更可靠的方式是看 `jcmd Thread.print` 里的 `http-nio-8080-*` 线程和请求日志耗时。

## 0. 多线程要不要放在 JVM 文档里

建议分两层：

- 这份 JVM 文档必须包含多线程的 JVM 交叉点：thread stack、GC Roots、safepoint、`BLOCKED` / `WAITING` / `TIMED_WAITING`、Tomcat request thread、Kafka listener thread、Outbox/DelayJob worker pool、线程数过多导致 native memory 增长。
- Java 并发本身建议单独再做一份 doc：`synchronized`、`volatile`、JMM、CAS、AQS、`ReentrantLock`、`ThreadPoolExecutor`、`CompletableFuture`、virtual threads、Spring `@Async`、线程安全集合、DB row lock 和 JVM lock 的边界。

interview表达可以这样说：

> JVM 和多线程不能完全拆开，因为每个 Java thread 都有 stack，stack 上的 local reference 是 GC Root，会影响对象是否可回收。但并发控制是另一个主题。本项目的金融状态一致性主要靠 MySQL unique constraint、transaction boundary 和 `SELECT ... FOR UPDATE` row lock，不靠 JVM `synchronized`。

## 1. JVM 在这个项目里负责什么

JVM 不是只负责“跑 Java 代码”。在本工程里，它同时承担：

- 加载和验证 class：例如 `AuthorizationService`、`AuthorizationController`、MyBatis mapper、Kafka listener。
- 管理 heap object：DTO、domain object、MyBatis row object、event payload、Kafka record、log string。
- 管理 thread stack：Tomcat 请求线程、scheduler 线程、五组业务 worker 线程、Kafka listener 线程。
- 编译热点代码：JIT compiler 会把频繁执行的 Java bytecode 编译成本地机器码。
- GC：回收不再可达的 heap object，并尽量控制暂停时间。
- 管理非堆内存：Metaspace、Code Cache、direct buffer、线程栈、JNI/native memory。
- 提供诊断能力：Actuator/Micrometer、JFR、`jcmd`、`jstack`、GC log、heap dump。

一个很重要的边界：

- MySQL row lock、Kafka broker backlog、DB index、network timeout 不属于 JVM 内存结构。
- 但这些外部等待会让 Java thread 卡住，从而延长 stack 上引用的 heap object 生命周期，间接导致 young object 晋升到 old generation。

## 2. JVM 内存结构总览

常见interview图可以这样理解：

```text
JVM Process
├── Heap
│   ├── Young Generation
│   │   ├── Eden
│   │   ├── Survivor 0
│   │   └── Survivor 1
│   └── Old Generation
├── Non-Heap
│   ├── Metaspace
│   ├── Code Cache
│   └── JVM internal memory
├── Thread Stacks
├── Direct / Native Buffer
└── OS process memory, also visible as RSS
```

### 2.1 Heap

Heap 是普通 Java object 主要存在的地方。

本项目中会进入 heap 的典型对象：

- `CreateAuthorizationRequest`：HTTP body 反序列化后的 request DTO。
- `AuthorizationCommand`：controller 转给 application layer 的 command。
- `Money`：金额 value object，包装 `BigDecimal` 和 `Currency`。
- `Authorization`：授权 aggregate root。
- `RiskAssessmentRequest`、`RiskDecision`：风控输入和结果。
- `AuthorizationRow`、`OutboxEventRow`：MyBatis 从数据库行映射出来的中间对象。
- `AuthorizationApprovedDomainEvent` 等 domain event。
- Jackson 序列化/反序列化产生的 `String`、`char[]`、`byte[]`、map/list 等临时对象。
- Kafka producer/consumer 相关的 record、header、serialized byte array。

Heap 不是越大越好：

- 太小：频繁 GC，容易 OOM。
- 太大：Full GC 或老年代回收暂停可能更痛，容器里还会挤压 native memory。
- 生产中通常要结合 container memory limit、GC pause 目标、allocation rate、业务延迟一起调。

### 2.2 Young Generation

Young generation 适合短命对象。大多数请求对象都应该在这里出生和死亡。

一次 `POST /api/authorizations` 里的短命对象包括：

- request body 解析产生的 JSON 中间对象。
- `CreateAuthorizationRequest`、`AuthorizationCommand`。
- `requestFingerprint()` 里的 canonical string、`MessageDigest`、`byte[]`、hex string。
- `RiskAssessmentRequest`。
- MyBatis 查询结果对象。
- 返回给 API 的 `AuthorizationResponse`。

如果请求很快完成，这些对象在下一次 Young GC 时就会被回收。

### 2.3 Old Generation

Old generation 保存活得更久的对象。

本项目里的长期对象包括：

- Spring singleton bean：`AuthorizationService`、`RiskAssessmentService`、`OutboxPoller`、`DelayJobPoller`。
- 配置对象：`OutboxProperties`、`DelayJobProperties`、`KafkaTopicsProperties`。
- MyBatis mapper proxy、mapped statement、type handler。
- HikariCP datasource 和连接池对象。
- Kafka producer/consumer client、listener container。
- `ThreadPoolTaskExecutor`、`ThreadPoolTaskScheduler` 和内部队列。
- `Currency` 等 JDK cache 里的对象。

请求对象也可能进入 Old generation：

- 请求被 DB row lock 卡住。
- 外部风控调用变慢。
- Kafka listener 处理很慢。
- worker queue 积压导致对象引用在队列里待很久。
- 某个 static map、cache、list、ThreadLocal 一直持有请求对象引用。

所以“对象进入 Old generation”本身不是 bug，关键看 Full GC 或 old GC 后是否还能下降。

### 2.4 Thread Stack

每个 Java thread 都有自己的 stack。Stack 存的是 stack frame、local variable、operand stack 和 return address 等。

注意一个常见误区：

- Java object 通常在 heap 上。
- Stack 上保存的是引用和基本类型值。
- 只要 stack frame 里还引用着某个 heap object，这个 object 就是 live object，GC 不能回收。

### 2.4 当前线程来源与容量边界

本项目的线程来源：

- Tomcat request thread：处理 `/api/authorizations` 等 HTTP 请求。
- `outbox-scheduler-*`：周期性扫描 Outbox。
- `outbox-worker-*`：发布 Kafka 并 finalize Outbox。
- `delay-job-scheduler-*`：周期性扫描 DelayJob。
- `delay-job-worker-*`：处理授权过期等纯 DB DelayJob。
- `auto-repay-worker-*`：处理会调用 bank debit provider 的自动扣款，避免 brownout 钉住普通 DelayJob 池。
- `billing-cycle-scheduler-*`：每天按账期规划 statement jobs。
- `statement-job-scheduler-*` / `statement-job-worker-*`：dispatch/recover 并执行分片出账。
- `notif-delivery-scheduler-*` / `notif-delivery-worker-*`：claim/recover durable delivery，并调用通知 provider。
- Kafka listener thread：`AuthorizationNotificationListener` 和 `CardTransactionNotificationListener` 各 `concurrency=2`，单进程最多 4 个 consumer threads。
- Hikari/MySQL driver、Kafka client、JVM 自身也会创建后台线程。

当前显式上限不能直接相加成吞吐：Tomcat `max=80`、两个 Kafka listener container 最多 4 个 consumer threads、五个 worker pool 各 4 线程、五个 scheduler pool 共 8 线程，但所有需要 DB 的路径共享 Hikari `maximum-pool-size=10`。线程增加只会提高“可等待的并发”；若 Hikari、MySQL、provider quota 或 Kafka partitions 已饱和，更多线程会增加 stack/native memory 和排队时间。

线程太多时，问题不只在 CPU：

- 每个 platform thread 都有 native stack，会占用 heap 之外的内存。
- 线程越多，safepoint 协调成本越高。
- 大量阻塞线程可能表示 DB pool、row lock、外部 HTTP 或 Kafka 出现瓶颈。

### 2.5 Metaspace

Metaspace 保存类元数据，属于 native memory，不在 Java heap 内。

本项目中 Metaspace 会随这些内容增加：

- Spring Boot 自动配置类。
- Controller、Service、Domain、Mapper、Kafka listener 等应用类。
- MyBatis mapper proxy。
- Spring AOP、Feign、Resilience4j 等可能产生的代理类。
- Jackson、validation、Kafka 等第三方库类。

常见问题：

- 生产上频繁动态加载 class 或热部署不释放 classloader，可能导致 Metaspace OOM。
- 普通 Spring Boot 单体应用稳定运行后，Metaspace 应该趋于平稳。

### 2.6 Code Cache

Code Cache 存 JIT 编译后的 native code。请求量升高后，热点方法会被 JIT 编译，例如：

- `AuthorizationService.authorize`
- `AuthorizationCommand.requestFingerprint`
- `Money.add` / `Money.subtract`
- mapper proxy 调用路径
- Kafka listener dispatch

JIT 是 JVM 性能的核心之一。线上排查 CPU 时，不要只看 Java 源码，还要知道热点路径可能已经被编译成 native code。

### 2.7 Direct Buffer 和 Native Memory

Direct buffer 是 heap 外内存。Kafka、NIO、MySQL driver、HTTP client 都可能使用 native buffer。

现象：

- Actuator 里 heap used 不高。
- OS RSS 或 container memory 却很高。
- 可能是 direct buffer、thread stack、Metaspace、Code Cache、native library 或 memory mapped file。

排查时要区分：

```text
heap used 只是 JVM 内的一部分
RSS / container memory 是整个进程在 OS 视角占用的内存
```

## 3. 从一次授权请求看对象如何产生

以 README 里的请求为例：

```http
POST /api/authorizations
Idempotency-Key: checkout-request-123
Content-Type: application/json
```

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

对象和引用链大致如下：

```text
Tomcat request thread stack
└── AuthorizationController.authorize(...)
    ├── CreateAuthorizationRequest
    ├── AuthorizationCommand
    └── AuthorizationService.authorize(...)
        ├── Authorization.request(...)
        │   ├── UUID
        │   ├── Money(BigDecimal, Currency)
        │   └── ArrayList<AuthorizationDomainEvent>
        ├── RiskAssessmentRequest
        ├── Card
        ├── CreditAccount
        ├── AuthorizationApprovedDomainEvent / DeclinedDomainEvent
        ├── OutboxEvent
        └── AuthorizationResponse
```

这里要抓住两个点：

- Thread stack 是 GC Root 的来源之一。请求没结束前，stack frame 上的 local references 会让对象保持 live。
- 大多数对象都是 request scoped，正常情况下很快死亡；Spring bean、mapper proxy、client、thread pool 才是长期对象。

## 4. 请求上升时，为什么内存会上升

核心公式：

```text
并发中的请求数 ~= RPS * 平均请求耗时(秒)
请求相关 live heap ~= 并发中的请求数 * 单请求对象图大小
```

假设单请求对象图很小，但请求耗时从 100ms 变成 2s：

- 100 RPS * 0.1s = 约 10 个请求同时活着。
- 100 RPS * 2s = 约 200 个请求同时活着。
- 1000 RPS * 2s = 约 2000 个请求同时活着。

即使每个请求只占几十 KB，live object 数也会快速上升。

本项目里会放大请求耗时的点：

- `RiskAssessmentService` 会调用外部风控 gateway，配置里模拟延迟是 `risk.external.simulated-latency-millis: 100`。
- `CreditAccountRepository.findByIdForUpdate` 会拿账户 row lock，同一账户的并发授权会串行等待。
- MySQL connection pool 如果耗尽，请求线程会等待连接。
- Kafka 或 DB 慢时，Outbox/DelayJob worker 会慢，后台队列可能增长。
- 日志、JSON、异常堆栈、retry payload 都会额外分配对象。

### 4.1 低流量时

低流量下，一次授权请求通常是：

1. Tomcat thread 收到请求。
2. Jackson 创建 DTO 和字符串/数字对象。
3. Controller 创建 `AuthorizationCommand`。
4. Service 创建 `Authorization`、`Money`、`RiskAssessmentRequest`。
5. MyBatis 创建 row object/domain object。
6. 状态变化产生 domain event。
7. 同一 DB transaction 内写 authorization、credit account、outbox、delay job。
8. 返回 response。
9. 请求 stack frame 退出，大量对象变成 unreachable。
10. 下一次 Young GC 回收这些短命对象。

表现：

- Heap used 呈锯齿状上涨和回落。
- Young GC 比较轻。
- Old generation 稳定。
- GC pause 不明显影响业务延迟。

### 4.2 请求突然上升时

请求上升后，变化不是“每秒多一些对象”这么简单，而是对象生命周期也会变长：

1. 更多 Tomcat request thread 同时工作。
2. 每个 thread stack 都引用自己的 DTO、command、domain object。
3. DB connection、row lock、外部风控让部分请求等待。
4. 等待中的请求对象无法被 GC。
5. Eden 更快被填满，Young GC 更频繁。
6. 如果对象在多次 Young GC 后仍然 live，可能被晋升到 Old generation。
7. Old generation 占用升高后，G1 会启动 concurrent marking 和 mixed GC。
8. 如果回收跟不上分配，可能出现长暂停、Full GC、`OutOfMemoryError`。

interview回答重点：

> 流量上升导致内存上升，不一定是 leak。先看 allocation rate 和 live set。请求慢、锁等待和外部调用会让短命对象活得更久，增加 promotion 到 old generation 的概率。Full GC 后 old gen 仍持续增长，才更像 leak 或 unbounded retention。

### 4.3 Outbox 和 DelayJob 积压时

Outbox 和 DelayJob 的 backlog 主要在 MySQL 表里，不会全部进入 heap。

当前配置：

```yaml
outbox.publisher:
  batch-size: 50
  worker-pool-size: 4
  worker-queue-capacity: 100

delay-jobs.scheduler:
  max-per-run: 16
  worker-pool-size: 4
  worker-queue-capacity: 100

notification.delivery:
  batch-size: 40
  worker-pool-size: 4
  worker-queue-capacity: 100
```

这意味着：

- Poller 每轮只 claim 有界数量。
- Worker pool 固定大小，避免无限创建线程。
- Worker queue 有容量限制，满了会触发 `TaskRejectedException`，代码会把 event/job 放回 retry。
- DB 里的 `PENDING` 积压是业务/可靠性指标，不等于 heap 中有同样数量的 Java object。

但内存仍然会受影响：

- 每轮 claim 出来的 `OutboxEvent` / `DelayJob` list 是 heap object。
- Worker queue 里排队的 task 会持有 event/job 引用。
- Kafka producer 等待 ack 时会持有 request 和 buffer。
- 如果代码未来改成一次性加载大量 events/jobs，就会制造明显 heap 压力。

### 4.4 Kafka listener 积压时

Kafka consumer lag 本身在 broker 上，但 listener 拉到本地后会产生：

- `ConsumerRecord`
- key/value/header byte array
- deserialized `IntegrationEvent`
- business command/domain object
- inbox row、notification intent、notification delivery object

当前 listener concurrency：

- `AuthorizationNotificationListener`: 2，订阅 authorization topic。
- `CardTransactionNotificationListener`: 2，订阅 transaction topic。
- 两个 topic 当前各 3 partitions，所以每个 container 配到大于 3 不会再增加有效消费并行度；单进程当前最多 4 个活跃 consumer threads。

listener 通过 Inbox 幂等后，在同一 DB transaction 内创建 notification intent 和各渠道 delivery rows，不直接调用 provider。concurrency 提高可以提高事件落库能力，但也会提高同时活跃的线程、stack、record buffer 和 DB 连接需求；provider 吞吐由后续 `notif-delivery-worker-*`、每渠道 RateLimiter 和 provider latency 另行决定。interview里要强调：consumer concurrency 不能越过 topic partitions、Hikari/MySQL 和幂等写入能力的整体瓶颈。

## 5. GC 核心概念

### 5.1 GC 回收的不是“没用对象”，而是 unreachable object

GC 判断对象是否可回收，核心是 reachability。

常见 GC Roots：

- 当前活跃线程 stack 上的 local reference。
- static field。
- JNI reference。
- class metadata 相关引用。
- synchronized monitor 持有的对象。

只要从 GC Roots 能一路引用到某个 object，它就是 reachable，不能被回收。

所以内存泄漏的本质通常是：

```text
业务上已经不需要
但技术上仍然被某个 Root 链路引用
```

常见引用链：

```text
static Map -> value list -> request payload
ThreadLocalMap -> ThreadLocal value -> large object
ThreadPool queue -> Runnable lambda -> OutboxEvent
Kafka callback -> byte[] payload
cache -> key/value -> domain snapshot
```

### 5.2 Generational Hypothesis

多数 Java object 有两个特点：

- 大部分对象很快死亡。
- 活过一段时间的对象往往会继续活很久。

所以 JVM 通常把 heap 分代：

- Young generation：高频创建和回收短命对象。
- Old generation：保存长期对象。

本项目的 DTO、command、response、row object 大多应该在 Young generation 死亡。Spring bean、mapper、线程池、Kafka client 等长期对象会留在 Old generation。

### 5.3 TLAB

TLAB 是 Thread Local Allocation Buffer。每个线程从 Eden 里拿一小块私有分配区，普通对象分配时无需频繁竞争全局锁。

本项目在高并发下：

- 多个 Tomcat request thread 会并发创建 DTO/domain object。
- TLAB 能降低分配锁竞争。
- 但 TLAB 不能减少总 allocation rate，只是让分配更快。

### 5.4 Safepoint

GC 需要在 safepoint 协调线程。Stop-The-World GC 时，应用线程要暂停到安全点。

影响：

- `/api/authorizations` 响应会短暂停顿。
- Kafka listener 可能延迟 ack。
- Scheduler 触发可能变晚。
- Outbox publish 节奏可能变慢。

GC pause 不会破坏 MySQL transaction 的原子性，但会放大 latency、timeout、consumer lag 和后台 backlog。

### 5.5 Minor GC、Mixed GC、Full GC

以 G1 的思路理解：

- Young GC：回收 young region，暂停通常较短。
- Concurrent Mark：并发标记 old generation 中的 live object，大部分时间应用线程仍运行。
- Mixed GC：同时回收 young 和部分 old region。
- Full GC：回收压力太大或并发回收失败时的兜底，暂停通常更重，线上应高度警惕。

Java 21 server JVM 常见默认 GC 是 G1，但不要只靠记忆。实际线上要通过下面命令确认：

```bash
jcmd <pid> VM.flags
jcmd <pid> VM.command_line
```

或启动时打开 GC log：

```bash
-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags
```

## 6. GC 如何处理流量上升，模拟一次过程

下面是一个接近本项目的场景，不是实测数字。

### 6.1 初始状态

应用刚启动：

- Spring bean、mapper proxy、Kafka client、Hikari datasource 进入长期对象集合。
- Metaspace 加载 Spring、MyBatis、Kafka、项目类。
- Thread pool 创建基础线程，或者按需创建 worker thread。
- Heap used 升到一个 baseline 后趋于稳定。

### 6.2 低流量请求

1. 请求进入 Tomcat thread。
2. `CreateAuthorizationRequest`、`AuthorizationCommand`、`Money` 等对象通常在 Eden 分配。
3. 请求很快完成。
4. Thread stack frame 弹出，对象不再 reachable。
5. Young GC 时这些对象直接被回收。

这时图像像锯齿：

```text
heap used
  ^
  |      /\      /\      /\
  |     /  \    /  \    /  \
  |____/    \__/    \__/    \____> time
        Young GC Young GC Young GC
```

### 6.3 流量和延迟同时上升

1. RPS 上升，Eden 分配速度变快。
2. 外部风控或 DB row lock 让请求耗时变长。
3. 活跃 thread stack 持有更多 request object。
4. Young GC 发生时，正在处理的请求对象仍 reachable。
5. 这些对象被复制到 Survivor。
6. 多次 Young GC 后仍 live 的对象被 promoted 到 Old。

图像变成：

```text
heap used
  ^
  |        /\        /\        /\
  |       /  \      /  \      /  \
  |______/    \____/    \____/    \___
  | old baseline slowly rises -------------
  +----------------------------------------> time
```

如果请求只是临时变慢，流量回落后 Old generation 可能慢慢下降。如果 Full GC 后仍降不下来，需要怀疑 retention。

### 6.4 回收跟不上分配

更严重时：

1. Eden 很快填满，Young GC 非常频繁。
2. 大量对象因为请求阻塞而 live，复制成本上升。
3. Old generation 持续升高。
4. G1 concurrent marking 启动，但业务分配速度仍然很快。
5. Mixed GC 回收不够。
6. JVM 触发 Full GC。
7. Full GC 后仍没有足够空间，就可能抛出 `java.lang.OutOfMemoryError: Java heap space`。

排查关键不是只问“为什么 GC”，而是问：

- allocation rate 为什么高？
- live object 为什么多？
- 哪些引用让对象活得太久？
- 是请求慢导致的临时 retention，还是真正 leak？
- DB/Kafka/外部 HTTP 是否让 thread 堵住？

## 7. 本工程 JVM 监控入口

当前代码没有自定义 `HealthController`，也没有 `/api/health`。健康检查和 JVM metrics 统一交给
Actuator/Micrometer，避免再维护一套与 Spring Boot health model 脱节的公开接口：

```text
GET /actuator/health
GET /actuator/health/liveness
GET /actuator/health/readiness
GET /actuator/metrics
```

边界是：liveness 只包含进程自身的 `livenessState`，DB 抖动不应触发容器重启；readiness 显式包含
`readinessState + db`，MySQL 不可用时应停止接收资金状态变更流量。Kafka/Redis 当前不在 readiness
group 中，因此 `/actuator/health/readiness=UP` 不能证明异步通知或 cache/velocity 全部健康。

当前 Actuator 配置位于 `src/main/resources/application.yml`：

```yaml
management:
  endpoints:
    web:
      exposure:
        include:
          - health
          - info
          - metrics
  endpoint:
    health:
      probes:
        enabled: true
      group:
        readiness:
          include:
            - readinessState
            - db
      show-details: never
```

可用入口：

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/actuator/info
curl http://localhost:8080/actuator/metrics
```

常用 JVM metrics：

```bash
curl "http://localhost:8080/actuator/metrics/jvm.memory.used"
curl "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap"
curl "http://localhost:8080/actuator/metrics/jvm.memory.max?tag=area:heap"
curl "http://localhost:8080/actuator/metrics/jvm.gc.pause"
curl "http://localhost:8080/actuator/metrics/jvm.threads.live"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states?tag=state:runnable"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states?tag=state:waiting"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states?tag=state:timed-waiting"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states?tag=state:blocked"
curl "http://localhost:8080/actuator/metrics/jvm.classes.loaded"
curl "http://localhost:8080/actuator/metrics/jvm.buffer.memory.used"
```

常用 Spring/Hikari 指标也应一起看：

```bash
curl "http://localhost:8080/actuator/metrics/hikaricp.connections.active"
curl "http://localhost:8080/actuator/metrics/hikaricp.connections.pending"
curl "http://localhost:8080/actuator/metrics/process.cpu.usage"
curl "http://localhost:8080/actuator/metrics/system.cpu.usage"
```

如果当前环境暴露 Tomcat thread metrics，也可以查：

```bash
curl "http://localhost:8080/actuator/metrics/tomcat.threads.current"
curl "http://localhost:8080/actuator/metrics/tomcat.threads.busy"
```

但本次本机实测的 metrics 列表只有 `tomcat.sessions.*`，没有 `tomcat.threads.*`。
如果返回 404，不要误判成 Tomcat 没有线程；改用 `jcmd <pid> Thread.print` 看
`http-nio-8080-exec-*`、`http-nio-8080-Poller`、`http-nio-8080-Acceptor`。

Actuator 返回里要看：

- `measurements`：当前值。
- `availableTags`：可过滤的 tag，例如 `area=heap`、`id=G1 Old Gen`、thread `state`。
- 单位：bytes、seconds、count 不要混着比较。

## 8. 本地 JVM 诊断命令

先找到 pid：

```bash
jps -l
```

看 JVM 参数和堆：

```bash
jcmd <pid> VM.flags
jcmd <pid> VM.command_line
jcmd <pid> GC.heap_info
jstat -gcutil <pid> 1000 10
```

看 class histogram：

```bash
jcmd <pid> GC.class_histogram
```

注意：class histogram 可能触发一次停顿，生产高峰期要谨慎。

看线程：

```bash
jcmd <pid> Thread.print
jstack <pid>
```

抓 JFR：

```bash
jcmd <pid> JFR.start name=mini-card settings=profile duration=120s filename=mini-card.jfr
```

或手动 dump：

```bash
jcmd <pid> JFR.start name=mini-card settings=profile
jcmd <pid> JFR.dump name=mini-card filename=mini-card.jfr
jcmd <pid> JFR.stop name=mini-card
```

抓 heap dump：

```bash
jcmd <pid> GC.heap_dump mini-card.hprof
```

生产注意：

- Heap dump 可能非常大，也可能造成明显停顿。
- 先确认磁盘空间和敏感数据处理策略。
- 金融系统里 heap dump 可能包含 cardId、merchantId、request payload、token 等敏感信息，要按机密数据处理。

看 native memory：

```bash
jcmd <pid> VM.native_memory summary
```

这个命令需要启动时开启 Native Memory Tracking，例如：

```bash
-XX:NativeMemoryTracking=summary
```

## 9. 建议的启动诊断参数

本地学习可以这样启动：

```bash
JAVA_TOOL_OPTIONS="-Xms512m -Xmx512m -XX:+UseG1GC -Xlog:gc*:file=logs/gc.log:time,uptime,level,tags -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps" ./gradlew bootRun
```

生产容器里不要只设置 `-Xmx` 等于容器内存。JVM 还需要 native memory：

- Thread stack。
- Metaspace。
- Code Cache。
- Direct buffer。
- GC internal data。
- JVM/native library。

容器常见思路：

```bash
-XX:InitialRAMPercentage=50
-XX:MaxRAMPercentage=70
```

或直接设定：

```bash
-Xms1g -Xmx1g
```

关键是确保：

```text
Xmx + native memory + OS/process overhead < container memory limit
```

## 10. 常见线上问题和排查路线

### 10.1 Heap used 一直涨

先判断是正常波动还是 leak。

看：

- Young GC 后是否回落。
- Full GC 或 old GC 后 old generation 是否回落。
- RPS 和 latency 是否同时上升。
- 是否有 DB lock wait、connection pending、Kafka lag、Outbox backlog。

排查：

```bash
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram
jcmd <pid> GC.heap_dump mini-card.hprof
```

分析 heap dump 时看：

- Dominator tree。
- Retained size。
- 谁持有最多 `byte[]`、`char[]`、`String`、`HashMap`、`ArrayList`。
- 是否有 `ThreadLocalMap`、unbounded queue、static cache、listener buffer。

结合本项目的风险点：

- 未来如果给 `AuthorizationService` 增加本地 cache，必须有容量和过期策略。
- Outbox/DelayJob 不能一次性加载全表。
- Statement batch 不能一次性把所有账户和交易塞进内存，应继续用 `target-accounts-per-job=1000` 做 durable shard，并由 `StatementJobHandler` 按账户短事务处理；不要把一个分片放大成全量账期作业。
- Kafka listener 不能把失败 record 无限保存在内存，应进入 retry/DLT 或 DB inbox。

### 10.2 Full GC 很频繁

可能原因：

- Heap 太小。
- Allocation rate 太高。
- Old generation live set 太大。
- 大对象或大 JSON payload 过多。
- 请求等待导致对象晋升。
- 内存泄漏。

排查顺序：

1. 看 GC log：Young GC、Mixed GC、Full GC 的频率和暂停。
2. 看 `jvm.gc.pause` 和业务 p95/p99 latency 是否同步。
3. 看 old gen used 是否 Full GC 后下降。
4. 看 thread dump 是否很多线程卡在 DB、HTTP、Kafka。
5. 看 heap dump 的 retained object。

### 10.3 Heap 不高但容器内存高

可能原因：

- Direct buffer。
- Metaspace。
- Thread stack。
- Code Cache。
- Native library。
- OS page cache 或 memory mapped file。

排查：

```bash
jcmd <pid> VM.native_memory summary
curl "http://localhost:8080/actuator/metrics/jvm.buffer.memory.used"
curl "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:nonheap"
curl "http://localhost:8080/actuator/metrics/jvm.threads.live"
```

如果线程数异常高，要看是否有线程泄漏、重复创建 executor/client、连接池配置错误。

### 10.4 `OutOfMemoryError: Java heap space`

处理顺序：

1. 保留 heap dump 和 GC log。
2. 不要先盲目加大 `-Xmx`。
3. 先确认是否是短期流量尖峰、外部依赖卡死、队列积压、或真正 leak。
4. 如果是容量不足，再结合 traffic model 调整 heap、实例数、限流、批大小。

本项目里的典型诱因：

- 高频授权请求加外部风控慢。
- 同一账户 row lock 等待导致大量 request object 存活。
- Kafka/Outbox 处理慢导致后台 task 引用排队。
- 将来如果做导出/报表，一次性加载大量 statement items。

### 10.5 `OutOfMemoryError: Metaspace`

常见原因：

- 动态生成 class 太多。
- classloader 泄漏。
- 热部署或插件机制没有释放旧 classloader。

本项目目前没有复杂插件机制，风险较低。若出现：

```bash
jcmd <pid> VM.classloader_stats
```

不同 JDK/权限下命令可用性可能不同，可以退回 JFR 或 heap dump 分析 classloader。

### 10.6 `OutOfMemoryError: unable to create native thread`

这不是 Java heap 不够，通常是 native memory 或 OS thread limit 问题。

看：

- `jvm.threads.live`
- thread dump 是否有大量同名前缀线程。
- 是否重复创建 `ThreadPoolTaskExecutor`、Kafka client、Feign client。
- 容器 pids limit、ulimit、内存限制。

本项目设计上 worker pool 是 bounded：

- `outbox-worker-*` 固定 4。
- `delay-job-worker-*`、`auto-repay-worker-*`、`statement-job-worker-*`、`notif-delivery-worker-*` 各固定 4，queue 各 100。
- Outbox/DelayJob/BillingCycle/StatementJob/NotificationDelivery 五组 scheduler pool 合计 8 个调度线程。
- 两个 Kafka listener container 的 concurrency 各固定为 2。

如果线上线程数仍持续增长，优先怀疑新增代码创建了未关闭的 executor/client。

### 10.7 CPU 很高

先分清是 GC CPU 还是业务 CPU。

看：

```bash
top -H -p <pid>
jcmd <pid> Thread.print
jcmd <pid> JFR.start name=cpu settings=profile duration=60s filename=cpu.jfr
```

判断：

- 如果 GC thread 占用高，回到 GC log 和 heap 分析。
- 如果业务线程高，找具体 stack。
- 如果 Kafka listener 或 worker 高，结合 backlog、DLT、重试日志。
- 如果 MySQL 慢，Java CPU 可能不高但 latency 高。

### 10.8 线程大量 `BLOCKED`

`BLOCKED` 通常表示等待 Java monitor lock。

本项目当前并发正确性主要不靠 `synchronized`，所以如果看到大量 `BLOCKED`：

- 查是否有第三方库内部锁。
- 查日志框架、connection pool、Kafka client。
- 查未来新增代码是否用了全局锁或大锁。

注意：等待 MySQL row lock 的线程在 Java thread dump 里不一定是 `BLOCKED`，更可能是在 JDBC socket read 或 driver 调用里 `RUNNABLE` / `WAITING`。所以要结合 MySQL lock wait 和 slow query。

### 10.9 线程大量 `WAITING` / `TIMED_WAITING`

这不一定是问题。

正常情况：

- worker thread 等待队列任务。
- scheduler thread sleep 到下一次 fixed delay。
- Kafka consumer poll。
- Hikari housekeeper。

异常情况：

- 大量 request thread 等 DB connection。
- 外部 HTTP 调用无 timeout 或 timeout 太长。
- `CompletableFuture` / latch / blocking queue 等待永远不返回。

### 10.10 DB 连接池耗尽

现象：

- Tomcat busy threads 上升。
- Hikari active connections 达到上限。
- Hikari pending 上升。
- Request latency 上升。
- Heap live object 上升，因为请求线程一直持有对象。

排查：

```bash
curl "http://localhost:8080/actuator/metrics/hikaricp.connections.active"
curl "http://localhost:8080/actuator/metrics/hikaricp.connections.pending"
jcmd <pid> Thread.print -l > threads.txt
```

如果当前环境暴露 `tomcat.threads.busy`，再把它和 Hikari pending 放在同一张图里看。
本次本机实测没有暴露 `tomcat.threads.*`，所以 Tomcat worker 是否繁忙主要通过
thread dump 和 `request_completed durationMs` 日志判断。

结合 MySQL：

- 慢 SQL。
- lock wait。
- transaction 持有时间。
- 是否在 account row lock 内调用了慢外部服务。

本项目把 external risk 放在 account row lock 之前，这是为了缩短账户锁 critical section；但 `authorize` 仍是单个 `@Transactional` 用例，winner authorization row 已加锁，事务和 Hikari connection 也跨越 external risk 调用。同 idempotency key 的 loser 可能因此继续等待 winner。看到 Hikari pending 时不能只检查 account row lock，还要把 external risk latency、bulkhead 拒绝和事务/authorization row lock 持有时间放在一起看。

### 10.11 Kafka consumer lag 上升

JVM 角度看：

- listener thread 是否被 GC pause 影响。
- listener 是否阻塞在 DB 或 inbox 写入。
- DLT 是否增加。
- 反序列化是否抛大量异常。
- heap 是否因为 record buffer 或重试对象增长。

业务角度看：

- authorization/transaction 通知意图是否延迟创建。
- `notification_deliveries` 是否在 `PENDING/PROCESSING` 积压，或进入 `DEAD`。
- DLT 是否持续增加；当前没有自动 replay/monitor consumer，必须由告警和 runbook 接管。

interview表达：

> Kafka lag 是业务处理能力和 broker backlog 的信号，不是单纯 JVM 内存问题。但 GC pause、DB pool exhaustion、listener concurrency 配置都会影响 consumer lag。

## 11. Liveness 和 Readiness

当前选择：

```text
GET /actuator/health            -> Spring Boot Actuator health
GET /actuator/health/liveness   -> livenessState；不包含 DB
GET /actuator/health/readiness  -> readinessState + db；决定是否继续接流量
```

interview常问：

> 数据库挂了，liveness 要不要失败？

稳妥回答：

> 不一定。DB 暂时不可用通常不应该让容器反复重启。应该让 readiness 失败，把实例从流量里摘掉，同时保留进程等待依赖恢复。liveness 失败更适合进程内部不可恢复的问题。

JVM 角度补充：

- Full GC、deadlock、线程池耗尽可能导致 readiness 失败或请求超时。
- 但不要让一个昂贵的 liveness endpoint 自己制造故障。
- heapdump、threaddump、env 等高风险 endpoint 不应该公开暴露。

## 12. JVM 指标必须和业务指标一起看

JVM metrics 解释“应用运行得累不累”，业务 metrics 解释“支付链路有没有积压或失败”。

本项目建议一起看：

- `jvm.memory.used`
- `jvm.gc.pause`
- `jvm.threads.live`
- Tomcat busy threads：如果当前 Actuator 暴露 `tomcat.threads.busy` 就直接查；否则用 `Thread.print` 里的 `http-nio-8080-exec-*` 和请求日志估算。
- `hikaricp.connections.active`
- `hikaricp.connections.pending`
- Outbox `PENDING` / `PROCESSING` / `DEAD`
- DelayJob due backlog / `DEAD`
- Kafka consumer lag / DLT 数量
- NotificationDelivery `PENDING` / `PROCESSING` / `SENT` / `DEAD` 和 provider latency/rate-limit rejection
- Authorization approve / decline count
- Risk external latency / fallback / bulkhead rejection / circuit breaker state
- Statement batch 每次处理账户数、交易数、耗时

典型关联：

```text
GC pause 上升
-> request latency 上升
-> Tomcat busy threads 上升
-> Hikari pending 上升
-> Authorization timeout 或 retry 上升
-> Outbox/DelayJob/Kafka backlog 上升
```

或：

```text
MySQL row lock wait 上升
-> request thread 等待
-> heap live set 上升
-> young object 晋升 old
-> GC pause 上升
```

## 13. 生产排障 Runbook

### 13.1 先判断影响面

问这些问题：

- 是单实例还是所有实例？
- 是所有 API，还是只有 authorization、statement 或 repayment 路径？
- 是 latency、error rate、OOM、CPU、GC pause、consumer lag 还是 DB lock？
- 最近是否有发布、配置变更、流量活动、Kafka backlog、DB schema/SQL 变化？

### 13.2 采集低风险指标

```bash
curl "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap"
curl "http://localhost:8080/actuator/metrics/jvm.gc.pause"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states"
curl "http://localhost:8080/actuator/metrics/hikaricp.connections.pending"
jcmd <pid> Thread.print -l > threads.txt
```

同时看日志：

- `request_started` / `request_completed` 的 duration。
- Outbox worker retry/DEAD。
- DelayJob worker retry/DEAD。
- NotificationDelivery retry/DEAD、每渠道 RateLimiter/Retry/CircuitBreaker。
- Kafka listener exception / DLT。
- Risk external timeout / circuit breaker。

### 13.3 再采集 JVM 诊断

```bash
jcmd <pid> GC.heap_info
jcmd <pid> Thread.print > threads.txt
jcmd <pid> GC.class_histogram > class-histogram.txt
jcmd <pid> JFR.start name=incident settings=profile duration=120s filename=incident.jfr
```

需要 heap dump 时再做：

```bash
jcmd <pid> GC.heap_dump incident.hprof
```

### 13.4 根据症状采取动作

- Heap 快满但 Full GC 后下降：可能是尖峰，先限流、扩容、降低批大小、排查外部依赖慢。
- Heap 快满且 Full GC 后不下降：抓 heap dump，查 retention。
- CPU 高且 GC 占比高：先看 allocation rate、old gen、对象分配热点。
- CPU 高但 GC 不高：抓 JFR/线程栈找业务热点。
- Thread busy 高、DB pending 高：查连接池、慢 SQL、row lock、事务边界。
- Kafka lag 高：查 listener、DB 写入、DLT、GC pause、partition/concurrency。
- Readiness 失败：先确认依赖 health，不要立刻重启所有实例。

## 14. interview高频问答

### JVM 内存结构怎么讲

回答模板：

> JVM 主要看 heap、thread stack、metaspace、code cache、direct/native memory。业务对象通常在 heap，方法调用和 local reference 在 thread stack，class metadata 在 metaspace，JIT 编译结果在 code cache，NIO/Kafka 等可能用 direct memory。线上不能只看 heap，还要看 RSS、线程数、direct buffer 和 GC pause。

### 一次请求里的对象都在哪里

回答模板：

> 以本项目 `/api/authorizations` 为例，HTTP body 进入后会产生 `CreateAuthorizationRequest`，controller 创建 `AuthorizationCommand`，application service 创建 `Money`、`Authorization`、`RiskAssessmentRequest`，MyBatis 查询会产生 row/domain object，状态转换会产生 domain event，最后写 Outbox。这些对象主要在 heap；请求线程 stack 上保存 local references，引用存在时 GC 不能回收它们。

### 请求量上升为什么会导致 GC 压力

回答模板：

> RPS 上升会提高 allocation rate；如果请求耗时也上升，并发中的请求数会按 `RPS * latency` 增长。正在处理或等待 DB/外部服务的请求对象仍然 reachable，Young GC 回收不了，可能进入 Survivor 或 Old。Old generation 增大后会触发更多 mixed GC 或 Full GC，表现为延迟抖动。

### 怎么判断 memory leak

回答模板：

> 不看一次 heap used，而看 GC 后趋势。短期锯齿是正常分配和回收；Full GC 或 old GC 后 old generation 长期不下降，且流量回落仍增长，才像 leak。然后用 class histogram、heap dump、dominator tree 找 retained size 最大的对象和引用链。

### GC pause 会不会破坏交易一致性

回答模板：

> GC pause 不会破坏 MySQL transaction 的原子性，也不会让已经 commit 的数据丢失。但它会放大 latency，导致客户端 timeout、Kafka listener ack 变慢、scheduler 扫描延后、Outbox/DelayJob backlog 增长。所以金融后台要把 GC 指标和业务积压指标一起看。

### 为什么本项目不靠 `synchronized` 保证授权并发

回答模板：

> JVM lock 只能保护单进程内的线程，不能保护多实例。授权这种金融状态变更要靠数据库唯一约束拿 idempotency winner，并用 `SELECT ... FOR UPDATE` 锁住 credit account row，在一个 transaction boundary 内检查和更新额度。JVM thread dump 仍然重要，但并发正确性的兜底在 MySQL 事务和约束。

### G1、ZGC 怎么选

回答模板：

> Java 21 常见生产默认会从 G1 开始，因为吞吐和暂停控制比较均衡。低延迟场景可以评估 ZGC，但不能只换 GC；还要看 allocation rate、对象生命周期、heap size、CPU、容器内存、业务 p99。对支付后台，GC pause 对 timeout 和 consumer lag 很敏感，要用压测和 JFR/GC log 验证。

### ThreadLocal 为什么容易泄漏

回答模板：

> ThreadLocal value 挂在线程的 ThreadLocalMap 上。Web 容器线程池线程是长期存活的，如果请求结束不 remove，大对象可能一直被线程引用。即使业务上请求结束了，GC Roots 仍能从 live thread 找到 value，所以不能回收。

### Heap dump 为什么要小心

回答模板：

> Heap dump 可能触发停顿，占用大量磁盘，还可能包含敏感业务数据，比如 cardId、merchantId、request payload、event payload。生产要先评估影响，必要时对 dump 做访问控制和脱敏管理。

## 15. 本项目可以继续增强的监控

当前已经有 Actuator metrics，但业务指标还可以继续补：

- Outbox 各状态数量。
- DelayJob due backlog、PROCESSING timeout、DEAD 数量。
- Kafka consumer lag、DLT 数量。
- Authorization approve/decline/timeout count。
- Risk external call latency、timeout、circuit breaker state。
- Statement batch size、duration、failure count。
- 五组 worker queue depth、rejected count；尤其区分普通 DelayJob 与 auto repayment、Kafka listener 与 provider delivery。

如果未来接 Prometheus，可以加入 `micrometer-registry-prometheus`，暴露：

```text
GET /actuator/prometheus
```

当前没有加入，是为了不引入还没实际使用的外部监控链路。

## 16. 复习清单

必须掌握：

- Heap、Young、Old、Metaspace、Thread Stack、Direct Memory 的区别。
- Java object 在 heap，local reference 在 stack。
- GC Roots 和 reachability。
- Young GC、Old/Mixed GC、Full GC 的基本过程。
- 流量上升如何导致 allocation rate 和 live set 上升。
- 为什么 latency 上升会让短命对象活得更久。
- Memory leak 和正常 allocation spike 的区别。
- GC pause 对支付 API、Kafka、Outbox、DelayJob 的影响。
- Actuator、GC log、JFR、heap dump、thread dump 的用途。
- Heap 高、RSS 高、线程高、CPU 高、DB pool 高、Kafka lag 高分别怎么切入。
- JVM lock 和 DB row lock 的边界。

建议单独成文的多线程主题：

- Java Memory Model, happens-before, visibility。
- `synchronized`、`volatile`、CAS、AQS。
- `ReentrantLock`、`Semaphore`、`CountDownLatch`、`CompletableFuture`。
- `ThreadPoolExecutor` 参数、拒绝策略、queue 背压。
- Spring MVC request thread、`@Async`、scheduler、Kafka listener concurrency。
- Java 21 virtual threads 的收益和边界。
- 本项目为什么用 MySQL row lock 解决跨实例金融并发，而不是 JVM lock。
