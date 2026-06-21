# 本工程线程运行模型与生产排查学习笔记

这份文档只讲本工程启动后的线程结构、线程池、生命周期、常见状态和生产排查。通用 Java 并发基础，例如 `volatile`、CAS、AQS、`synchronized`、`ReentrantLock`、`ThreadPoolExecutor` 原理，建议单独放到另一份文档。

当前工程背景：

- Java 21 + Spring Boot MVC，未启用 virtual threads，因此主要是 Java platform thread。
- Web 容器是 Spring Boot 内嵌 Tomcat。
- 状态变更主要通过 MySQL transaction、unique constraint、`SELECT ... FOR UPDATE` row lock 保证正确性。
- 异步可靠性机制包括 Outbox、Consumer Inbox、DelayJob 和 Kafka listener。
- 本次已在本机用 IntelliJ IDEA 启动应用并采集 `jcmd Thread.print` / Actuator 快照，下面的线程数量既包含配置推断，也包含 2026-06-21 的本地实测结果。

## 0. 本地实测运行快照

采集时间：2026-06-21 12:43-12:46 JST。

启动状态：

- Spring Boot 3.5.14，Java 21.0.11，PID `47626`，由 IntelliJ IDEA 启动，内嵌 Tomcat 监听 `localhost:8080`。
- `/actuator/health` 返回 `UP`。
- 实测 `/actuator/metrics` 暴露了 `jvm.*`、`executor.*`、`hikaricp.*`、`spring.kafka.listener`、`tomcat.sessions.*`，没有暴露 `tomcat.threads.*`。
- 完整 thread dump 已保存到 `build/diagnostics/idea-jvm-2026-06-21-1243/thread-dump-after-authorization.txt`，共 1914 行。

Actuator 线程状态：

| 指标 | 实测值 |
| --- | ---: |
| `jvm.threads.live` | 97 |
| `jvm.threads.states?tag=state:runnable` | 27 |
| `jvm.threads.states?tag=state:waiting` | 13 |
| `jvm.threads.states?tag=state:timed-waiting` | 57 |
| `jvm.threads.states?tag=state:blocked` | 0 |

这些 Actuator state 是逐个 endpoint 查询的瞬时值；完整 thread dump 自己统计到
`RUNNABLE = 29`、`WAITING = 29`、`TIMED_WAITING = 42`、`BLOCKED = 0`。
两者采样时间不同，所以状态分布会有轻微差异；排查时应关注趋势和栈，而不是把一次快照当成精确恒等式。

Thread dump 线程类别：

| 线程类别 | 实测数量 | 说明 |
| --- | ---: | --- |
| Tomcat request worker | 10 | `http-nio-8080-exec-*`，空闲时等待 Tomcat task queue |
| Tomcat acceptor/poller | 2 | `http-nio-8080-Acceptor`、`http-nio-8080-Poller` |
| Spring Kafka listener container | 15 | 多个 listener container 叠加 concurrency 后的结果 |
| Kafka coordinator heartbeat | 15 | 每个 active Kafka consumer 通常有 heartbeat thread |
| Micrometer Kafka metrics | 16 | Kafka client metrics 采集相关线程 |
| Spring Kafka internal scheduler | 15 | `ThreadPoolTaskScheduler-1`，Kafka container 内部调度使用 |
| 本工程 scheduler | 4 | `outbox-scheduler-1`、`delay-job-scheduler-1/2`、`statement-batch-scheduler-1` |
| Outbox worker | 1 | 请求触发 Outbox publish 后 lazy 创建 |
| DelayJob worker | 0 | 采样期间没有 due job，因此 worker pool 未创建实际线程 |
| Kafka producer network | 1 | Outbox publish 使用的 producer 网络线程 |
| Hikari housekeeper | 1 | 连接池维护线程 |
| MySQL abandoned cleanup | 1 | MySQL JDBC driver 清理线程 |
| G1/JVM GC 相关线程 | 16 | GC worker、G1 concurrent/refine/service 等 |
| JIT compiler | 1 | IDEA 启动参数含 `-XX:TieredStopAtLevel=1`，本次 dump 里只有 `C1 CompilerThread0` |

实际请求观察：

- 使用新的 `Idempotency-Key` 发起 `POST /api/authorizations`，HTTP 200，curl 端到端耗时约 229ms。
- Actuator `http.server.requests` 记录 `/api/authorizations` 为 228ms，`/external-risk/assess` 为 105ms；Resilience4j `externalRisk` successful call 为 122ms。
- 请求返回 `APPROVED` 后，Outbox event `authorization.approved` 变为 `PUBLISHED`，Kafka listener 写入 `consumer_inbox`。
- `notification-v1` consumer 创建 `AUTHORIZATION_APPROVED` notification，`risk-feature-v1` consumer 更新 `card_risk_features`。
- 因此一次成功授权会横跨 Tomcat worker、scheduler、Outbox worker、Kafka listener、Hikari/MySQL connection，但金融状态一致性仍然靠 DB transaction 和 row lock，而不是 JVM monitor lock。

采样限制：

- thread dump 是采样，不是录屏。本次 dump 采集在请求和异步链路完成后，所以没有刚好截到 `/api/authorizations` 的业务栈、`/external-risk/assess` 的 `Thread.sleep(100ms)` 栈，或者 Outbox worker 等 Kafka ack 的瞬间。
- 这不是采集失败，而是 thread dump 的正常限制。请求耗时和 external-risk 调用用 HTTP metrics/Resilience4j 观察，异步成功用 Outbox/Inbox/业务 projection 表确认。

本次 thread dump 里的典型状态：

```text
"http-nio-8080-Poller" ... java.lang.Thread.State: RUNNABLE
  at sun.nio.ch.KQueue.poll(...)
  at org.apache.tomcat.util.net.NioEndpoint$Poller.run(...)
```

这个 `RUNNABLE` 不是在跑业务 CPU，而是在 native selector 上等网络事件。Kafka listener 线程也常见
`RUNNABLE`，栈在 `KafkaConsumer.poll` / `Selector.poll`，同样不等于 CPU 热点。

```text
"http-nio-8080-exec-1" ... java.lang.Thread.State: WAITING (parking)
  at java.util.concurrent.LinkedBlockingQueue.take(...)
  at org.apache.tomcat.util.threads.TaskQueue.take(...)
```

这个 `WAITING` 表示 Tomcat worker 空闲等待下一个请求。`outbox-worker-1` 在本次 dump 里也处于
`LinkedBlockingQueue.take`，含义是 Outbox 已发布完，worker 正在等下一批任务。

```text
"micrometer-kafka-metrics" ... java.lang.Thread.State: TIMED_WAITING (parking)
  at java.util.concurrent.ScheduledThreadPoolExecutor$DelayedWorkQueue.take(...)
```

这个 `TIMED_WAITING` 是带超时的周期性等待。Kafka metrics、scheduler fixed delay、HTTP keep-alive
和失败重试 backoff 都可能出现这个状态。

本次 `BLOCKED = 0`，所以没有真实 `BLOCKED` 业务栈可贴。真正的 Java `BLOCKED` 会显示
`java.lang.Thread.State: BLOCKED (on object monitor)`，并带有 `waiting to lock <...>` 和锁 owner。
MySQL `SELECT ... FOR UPDATE` row lock 等待通常不会显示成 Java `BLOCKED`，而是在 JDBC/socket 栈里等待。

## 1. 先建立整体线程地图

本工程不是只有 HTTP request thread。启动后大致有这些线程来源：

```text
mini-card JVM process
├── JVM internal threads
│   ├── GC threads
│   ├── JIT compiler threads
│   ├── Reference Handler / Common-Cleaner
│   └── Signal Dispatcher 等
├── Tomcat HTTP threads
│   ├── acceptor / poller
│   └── request worker: http-nio-8080-exec-*
├── Spring scheduler pools
│   ├── outbox-scheduler-*
│   ├── delay-job-scheduler-*
│   └── statement-batch-scheduler-*
├── Business worker pools
│   ├── outbox-worker-*
│   └── delay-job-worker-*
├── Kafka threads
│   ├── consumer listener threads
│   ├── producer network thread
│   └── admin / metadata / heartbeat 相关线程
├── DB / connection pool helper threads
│   └── Hikari housekeeper 等
└── Application request / listener / worker thread 使用 MySQL connection
```

最重要的生产视角：

- Tomcat request thread 处理同步 HTTP 请求。
- Scheduler thread 只应该做轻量 poll/claim。
- Worker thread 执行可能较慢的后台业务。
- Kafka listener thread 负责 poll record、调用 listener、成功后 ack offset。
- MySQL row lock 不会创建新的 Java thread；等待 row lock 的仍然是当前 request/worker/listener thread。

## 2. 线程清单

下面是本工程最核心的线程类别。数量里的“当前配置”来自 `application.yml` 和配置类；
Tomcat 默认值因为本工程没有显式配置，所以以本次 thread dump 看到的 `http-nio-8080-exec-*`
数量作为本地实测参考。生产环境仍建议显式配置 Tomcat max threads / accept count，并用
Actuator、thread dump 和压测结果一起确认。

| 线程类别 | 线程名通常类似 | 当前数量/并发 | 来源 | 主要职责 | 常见状态 |
| --- | --- | --- | --- | --- | --- |
| JVM internal | `GC Thread`, `G1 Conc#`, `C2 CompilerThread`, `Reference Handler`, `Common-Cleaner` | JVM 自行决定 | JDK | GC、JIT、引用清理、信号处理 | `RUNNABLE`, `WAITING`, `TIMED_WAITING` |
| Tomcat acceptor/poller | `http-nio-8080-Acceptor`, `http-nio-8080-Poller` | Tomcat 默认 | Embedded Tomcat | 接收 TCP 连接、NIO 事件轮询 | 多数时间等待 I/O，Java dump 里常见 `RUNNABLE` 或等待状态 |
| Tomcat request worker | `http-nio-8080-exec-*` | 未显式配置，使用 Tomcat/Spring Boot 默认 | `spring-boot-starter-web` | 处理 controller、filter、service、MyBatis、Feign 调用 | 空闲等待任务；处理请求时 `RUNNABLE`；阻塞外部 I/O 时也可能显示 `RUNNABLE` |
| Outbox scheduler | `outbox-scheduler-*` | pool size 1 | `outboxTaskScheduler` | 每 1s poll/claim publishable events，每 5s recover stuck events | 空闲 `TIMED_WAITING`；执行 SQL 时多为 `RUNNABLE` |
| DelayJob scheduler | `delay-job-scheduler-*` | pool size 2 | `delayJobTaskScheduler` | 每 1s poll due jobs，每 5s recover stuck jobs | 空闲 `TIMED_WAITING`；执行 SQL 时多为 `RUNNABLE` |
| Statement scheduler | `statement-batch-scheduler-*` | pool size 1 | `statementBatchTaskScheduler` | 每 60s 检查是否到关账批处理日 | 空闲 `TIMED_WAITING`；批处理中 `RUNNABLE` / DB I/O |
| Outbox worker | `outbox-worker-*` | pool size 4, queue 100 | `outboxWorkerExecutor` | 发布 Kafka，等待 broker ack，finalize Outbox 状态 | 空闲 `WAITING`；等待 ack 常见 `TIMED_WAITING`；DB/Kafka I/O 可能 `RUNNABLE` |
| DelayJob worker | `delay-job-worker-*` | pool size 4, queue 100 | `delayJobWorkerExecutor` | 执行授权过期、自动还款等业务 job，finalize job 状态 | 空闲 `WAITING`；执行业务时 `RUNNABLE`；DB row lock 等待常见 JDBC I/O 栈 |
| Notification Kafka listener | Spring Kafka consumer thread | 每个 listener container concurrency 2；当前 4 个 listener | `notificationKafkaListenerContainerFactory` | 消费 authorization / transaction / statement / repayment events 并创建 notification | poll 时等待；处理 record 时 `RUNNABLE`；retry backoff 时 `TIMED_WAITING` |
| Risk Kafka listener | Spring Kafka consumer thread | concurrency 3 | `riskFeatureKafkaListenerContainerFactory` | 消费 authorization decision events，更新风控特征投影 | 同上 |
| Ledger Kafka listener | Spring Kafka consumer thread | 每个 listener container concurrency 2；当前 2 个 listener | `ledgerKafkaListenerContainerFactory` | 消费 transaction / repayment events，记录 ledger projection | 同上 |
| Hikari helper | `HikariPool-* housekeeper` | 连接池内部 | HikariCP | 连接池维护 | 多数时间 `TIMED_WAITING` |
| Kafka producer/network | `kafka-producer-network-thread` 等 | Kafka client 内部 | Spring Kafka / Kafka client | 网络发送、metadata、broker ack | 网络 I/O 等待常见 `RUNNABLE` |

注意 Kafka listener 的数量：

- `notificationKafkaListenerContainerFactory` 设置 `concurrency=2`。
- 当前有 4 个 notification listener：authorization、card transaction、statement、repayment。
- 因此它不是“整个 Notification bounded context 总共 2 个线程”，而是每个 listener container 最多 2 个 consumer thread。
- `riskFeatureKafkaListenerContainerFactory` 是 1 个 listener，`concurrency=3`，对齐 source topic 的 3 个 partitions。
- `ledgerKafkaListenerContainerFactory` 设置 `concurrency=2`，当前有 card transaction 和 repayment 2 个 listener。

## 3. Java 线程状态在本工程里怎么理解

Thread dump 里常见状态有这些。这里不按教科书展开，而是直接绑定本工程。

| Java thread state | 在本工程里的典型含义 | 容易误解的点 |
| --- | --- | --- |
| `RUNNABLE` | 正在跑 Java 代码；或卡在 native socket read、MySQL JDBC、Kafka network I/O | `RUNNABLE` 不等于正在消耗 CPU。很多 I/O 等待在 Java 层也显示 `RUNNABLE` |
| `BLOCKED` | 等待进入 Java monitor，例如 `synchronized` 锁 | MySQL row lock 等待通常不是 Java `BLOCKED` |
| `WAITING` | worker thread 空闲等队列任务；线程间无超时等待 | 空闲 worker 是正常现象 |
| `TIMED_WAITING` | scheduler fixed delay sleep、`Thread.sleep`、Kafka backoff、`Future.get(timeout)`、限时等待 | 大量 `TIMED_WAITING` 不一定是故障，要看栈 |
| `TERMINATED` | 线程结束 | 普通 thread dump 里通常看不到已结束线程 |

最关键的判断：

```text
不要只看 state 名字。
一定要看线程名 + stack trace + 业务指标。
```

例如：

- `http-nio-8080-exec-*` 在 JDBC socket read：可能是慢 SQL、row lock、DB 网络或连接池问题。
- `outbox-worker-*` 在 `CompletableFuture.get(timeout)`：可能是在等待 Kafka broker ack。
- `delay-job-worker-*` 在 `CreditAccountMapper.findByIdForUpdate`：可能是在等 account row lock。
- Kafka listener 在 error handler backoff：可能 record 处理失败，正在重试。

## 4. Java platform thread 和 OS thread 的关系

当前工程没有启用 virtual threads，所以主要使用 platform threads。

Platform thread 的特点：

- 一个 Java platform thread 通常对应一个 OS native thread。
- 每个 native thread 有自己的 native stack，会占用 heap 之外的内存。
- 线程数上升会增加 native memory、context switch 和 safepoint 协调成本。
- `java.lang.Thread` 对象在 heap 上，但真正执行是 OS thread。

Thread dump 里通常能看到类似：

```text
"http-nio-8080-exec-7" #123 prio=5 os_prio=0 cpu=12.34ms elapsed=30.12s tid=0x... nid=0x2f03 runnable
```

其中：

- Java thread name：`http-nio-8080-exec-7`。
- `tid`：JVM 内部 thread 指针，不是业务 id。
- `nid`：native thread id，通常用十六进制显示。

Linux 上排 CPU 热线程时常见流程：

```bash
top -H -p <pid>
printf "%x\n" <decimal_thread_id>
jcmd <pid> Thread.print -l > threads.txt
```

然后用转换后的 hex id 找 `nid=0x...`。

macOS 上可以先用：

```bash
ps -M <pid>
jcmd <pid> Thread.print -l
```

注意：OS 看到的是 native thread 调度状态；Java thread dump 看到的是 JVM 视角。两者不是一一等价，所以排查时要结合 CPU、线程栈、GC、DB 和 Kafka 指标。

## 5. Tomcat 线程：同步 HTTP 请求从哪里来

本工程使用 Spring MVC 和内嵌 Tomcat。因为 `application.yml` 没有配置：

```yaml
server:
  tomcat:
    threads:
```

所以 request worker 数、accept count 等使用 Spring Boot/Tomcat 默认值。生产不要靠记忆默认值，应该显式配置并用 Actuator 确认。

### 5.1 启动生命周期

应用启动时：

1. `main` thread 启动 Spring Boot。
2. Spring 创建 controller、service、repository、mapper、Kafka listener container、scheduler、worker executor。
3. Embedded Tomcat 启动 connector。
4. Tomcat 创建接收连接和处理请求所需的线程。
5. 应用启动完成后，Tomcat request worker 多数时间等待请求。

### 5.2 一次授权请求使用哪个线程

请求：

```http
POST /api/authorizations
```

典型执行链：

```text
http-nio-8080-exec-N
-> HttpRequestLoggingFilter.doFilterInternal
-> AuthorizationController.authorize
-> AuthorizationService.authorize
-> MyBatis AuthorizationRepository.claim
-> MyBatis findByIdempotencyKeyForUpdate
-> RiskAssessmentService.assess
-> ExternalRiskClient.assess
-> CreditAccountMapper.findByIdForUpdate
-> update credit account / authorization / outbox / delay_jobs
-> AuthorizationResponse
```

这个过程中：

- Controller、Service、Repository 默认都在同一个 request thread 内执行。
- `@Transactional` 把 transaction resource 绑定到当前 thread。
- MyBatis SQL 使用当前 thread 借到的 DB connection。
- 如果这个 thread 等 DB、等 row lock、等外部 HTTP，它仍然占用 Tomcat request worker。

### 5.3 Tomcat request thread 什么时候是 `RUNNABLE`

常见场景：

- 正在执行 controller/service/domain Java 代码。
- 正在 JSON serialize/deserialize。
- 正在调用 MyBatis/JDBC。
- 正在 socket read 等 MySQL 响应。
- 正在调用 Feign 外部风控。

重要误区：

> Thread dump 里 request thread 显示 `RUNNABLE`，不代表它一定在吃 CPU。JDBC socket read、native network I/O 也可能显示 `RUNNABLE`。

如果 CPU 不高但很多 `http-nio-8080-exec-*` 都在 JDBC 或 Feign 栈上，重点不是 CPU，而是 DB/外部服务/连接池/row lock。

### 5.4 Tomcat request thread 什么时候是 `WAITING` 或 `TIMED_WAITING`

常见场景：

- 空闲 request worker 等待新任务。
- 等 DB connection pool 可用连接，可能表现为等待条件队列。
- 等 `Future` 或带 timeout 的外部调用结果。
- 本地模拟风控接口 `/external-risk/assess` 内部调用 `Thread.sleep(...)`，处理该接口的 Tomcat thread 会进入 `TIMED_WAITING`。

本工程有一个很适合学习的点：

- `risk.external.base-url` 默认是 `http://localhost:8080`。
- 授权请求在 `AuthorizationService` 里通过 Feign 调用 `/external-risk/assess`。
- 这个外部风控是同一个 Spring Boot 应用里的 `SimulatedExternalRiskController`。
- 因此本地一次授权可能同时占用：
  - 一个 `http-nio-8080-exec-*` 处理 `/api/authorizations`，等待 Feign 响应。
  - 另一个 `http-nio-8080-exec-*` 处理 `/external-risk/assess`，并在 `Thread.sleep(100ms)` 里 `TIMED_WAITING`。

这在本地学习很好理解，但生产里外部风控通常是独立服务。interview时可以说：本地模拟让调用链可运行，生产要关注外部服务 timeout、bulkhead、circuit breaker 和 request thread 占用。

### 5.5 Tomcat request thread 什么时候是 `BLOCKED`

`BLOCKED` 指等待 Java monitor lock，例如：

```java
synchronized (someObject) {
    ...
}
```

本项目的核心并发控制不靠 `synchronized`，所以大量 request thread `BLOCKED` 不是正常现象。可能原因：

- 新增代码引入了全局 Java 锁。
- 第三方库内部锁竞争。
- 日志、class loading、某些单例初始化路径卡住。

不要把 MySQL row lock 等待误读成 Java `BLOCKED`。等待 `SELECT ... FOR UPDATE` 的线程通常在 JDBC/MySQL socket 调用栈里。

## 6. Scheduler 线程：轻量 poll/claim，不做长业务

本工程有三个显式 scheduler pool：

| Scheduler bean | 线程名前缀 | pool size | 执行任务 | fixed delay |
| --- | --- | --- | --- | --- |
| `outboxTaskScheduler` | `outbox-scheduler-` | 1 | `OutboxPoller.pollPublishableEvents`, `OutboxRecoverer.recoverStuckEvents` | 1s / 5s |
| `delayJobTaskScheduler` | `delay-job-scheduler-` | 2 | `DelayJobPoller.pollDueJobs`, `DelayJobRecoverer.recoverStuckJobs` | 1s / 5s |
| `statementBatchTaskScheduler` | `statement-batch-scheduler-` | 1 | `StatementBatchPoller.closeDueBillingCycles` | 60s |

### 6.1 为什么 scheduler 要薄

本工程的设计原则：

```text
@Scheduled thread 只负责周期性醒来、短事务 claim、提交 worker。
长业务动作交给 worker pool。
```

好处：

- scheduler 不会被 Kafka ack、银行扣款、业务 row lock 长时间占住。
- claim transaction 很短，减少 DB lock 持有时间。
- worker 宕机后可通过 PROCESSING lease recover。
- 线程名清晰，thread dump 容易看出是 poller 还是 worker。

### 6.2 Outbox scheduler 的生命周期

`outbox-scheduler-*` 负责两个定时任务：

```text
每 1s:
OutboxPoller.pollPublishableEvents
-> OutboxClaimer.claimPublishableEvents
-> SELECT ... FOR UPDATE SKIP LOCKED
-> PENDING -> PROCESSING lease
-> commit
-> outboxWorkerExecutor.execute(...)

每 5s:
OutboxRecoverer.recoverStuckEvents
-> 查 PROCESSING 超时事件
-> markProcessingTimedOut
-> PENDING retry 或 DEAD
```

因为 pool size 是 1：

- poller 和 recoverer 不会在同一个实例内并发跑。
- 如果 poller 的 SQL 很慢，recoverer 会被延后。
- 这是可以接受的，因为 Outbox scheduler 只应该做短事务。
- 如果实际看到 `outbox-scheduler-*` 长时间卡住，说明 claim/recover SQL 或 DB 有问题，不应该先加线程数。

状态变化：

| 阶段 | 线程状态倾向 | 说明 |
| --- | --- | --- |
| fixed delay 等下一轮 | `TIMED_WAITING` | 正常空闲 |
| 执行 Java poller 逻辑 | `RUNNABLE` | 领取 events、构造 list |
| 执行 MyBatis SQL | `RUNNABLE` 或 native I/O | 可能在等 DB 响应或 row lock |
| submit worker task | `RUNNABLE` | queue 满时抛 `TaskRejectedException` |

### 6.3 DelayJob scheduler 的生命周期

`delay-job-scheduler-*` 负责：

```text
每 1s:
DelayJobPoller.pollDueJobs
-> DelayJobClaimer.claimDueJobs
-> SELECT ... FOR UPDATE SKIP LOCKED
-> PENDING -> PROCESSING lease
-> commit
-> delayJobWorkerExecutor.execute(...)

每 5s:
DelayJobRecoverer.recoverStuckJobs
-> 查 PROCESSING 超时 jobs
-> markProcessingTimedOut
-> retry 或 DEAD
```

这里 pool size 是 2，因为 poller 和 recoverer 可以合理并行：

- poller 继续领取新 due jobs。
- recoverer 同时修复超时 lease。
- 真正业务仍在 `delay-job-worker-*`。

状态判断和 Outbox scheduler 类似。大量 `delay-job-scheduler-*` 长时间在 JDBC 栈上，优先查 delay_jobs 表索引、DB 连接、锁等待。

### 6.4 Statement scheduler 的生命周期

`statement-batch-scheduler-*` 每 60s 醒来：

```text
StatementBatchPoller.closeDueBillingCycles
-> StatementBatchService.runDueBatch
-> 判断是否到关账日次日
-> 扫描候选 account
-> 每个 account 独立事务生成 statement
```

它和 Outbox/DelayJob 不同：

- `@Scheduled` 方法会进入 batch service。
- 但批处理内部按账户拆事务，避免一个大事务锁住过多数据。
- `statement.batch.max-accounts-per-run=100` 控制单轮规模。

生产风险：

- 如果候选账户很多，单线程 batch 可能跑很久。
- fixed delay 是“上一次执行结束后再等 60s”，不是固定时刻并发启动下一轮。
- thread dump 里看到 `statement-batch-scheduler-*` 长时间跑，要结合 batch 日、候选数、DB 慢查询判断。

## 7. Worker 线程：真正执行后台业务

本工程有两个显式 worker pool：

| Worker executor | 线程名前缀 | core/max | queue | 执行业务 |
| --- | --- | --- | --- | --- |
| `outboxWorkerExecutor` | `outbox-worker-` | 4 / 4 | 100 | Kafka publish + Outbox finalize |
| `delayJobWorkerExecutor` | `delay-job-worker-` | 4 / 4 | 100 | Authorization expiry、Auto repayment 等 DelayJob |

`core=max` 表示固定上限，不会因为突发 backlog 无限创建线程。`queue=100` 是显式背压边界。

### 7.1 Worker 线程什么时候创建和销毁

`ThreadPoolTaskExecutor` 的线程通常按需创建：

- 应用启动时 executor bean 初始化。
- 第一次有任务提交时开始创建 worker thread。
- 最多创建到 4 个。
- 因为 core=max，创建后通常长期保留，空闲时等待 queue task。
- shutdown 时配置了 `waitForTasksToCompleteOnShutdown=true` 和 `awaitTerminationSeconds=10`。

如果进程在 worker 执行中宕机：

- Outbox event 或 DelayJob 已经是 `PROCESSING`。
- recoverer 会在 lease timeout 后重新放回 retry 或转 DEAD。
- 这就是 PROCESSING lease 的意义。

### 7.2 Outbox worker 典型生命周期

一条 Outbox event 的线程路径：

```text
outbox-scheduler-1
-> claim event as PROCESSING
-> submit lambda to outboxWorkerExecutor

outbox-worker-N
-> OutboxWorker.publishClaimedEvent
-> KafkaOutboxMessagePublisher.publish
-> kafkaTemplate.send(record).get(timeout)
-> markPublished / markFailed
-> findByIdForUpdate(eventId)
-> update delivery state
```

状态变化：

| 阶段 | 线程状态倾向 | 说明 |
| --- | --- | --- |
| 空闲等任务 | `WAITING` | worker 在 queue 上等待 |
| 构造 Kafka record/header | `RUNNABLE` | CPU 很短 |
| 等 broker ack | `TIMED_WAITING` | `get(timeout)` 最多等 `send-timeout-ms=5000` |
| finalize 查 row lock | `RUNNABLE` / JDBC I/O | `findByIdForUpdate` 重新锁 Outbox row |
| Kafka 慢或不可用 | `TIMED_WAITING` / 异常重试 | 事件 markFailed 后按 retry policy 回到 PENDING 或 DEAD |

如果看到 4 个 `outbox-worker-*` 都在等待 Kafka ack：

- 说明 Outbox publish 能力被 Kafka ack 延迟限制。
- `outbox_events` 的 PENDING backlog 可能增长。
- 不要第一反应把 worker pool 调到很大；还要看 Kafka broker、network、topic partition、producer timeout、DB finalize 能力。

如果 worker queue 满：

- `OutboxPoller` 会捕获 `TaskRejectedException`。
- `OutboxWorker.markRejectedForRetry` 会把 event 放回 retry/DEAD。
- 这比无限堆积 Java heap 更适合金融后台。

### 7.3 DelayJob worker 典型生命周期

一条 DelayJob 的线程路径：

```text
delay-job-scheduler-N
-> claim job as PROCESSING
-> submit lambda to delayJobWorkerExecutor

delay-job-worker-N
-> DelayJobWorker.handleClaimedJob
-> dispatch by jobType
   -> AuthorizationExpiryDelayJobHandler
      -> AuthorizationExpiryService.expire
   -> AutoRepaymentDelayJobHandler
      -> AutoRepaymentService.debitStatement
-> markDone / markFailed
-> findByIdForUpdate(jobId)
-> update execution state
```

状态变化：

| 阶段 | 线程状态倾向 | 说明 |
| --- | --- | --- |
| 空闲等任务 | `WAITING` | worker 在 queue 上等待 |
| handler dispatch | `RUNNABLE` | 很短 |
| 执行业务 service | `RUNNABLE` / JDBC I/O | 可能锁 authorization、account、statement |
| 等 MySQL row lock | 常见 JDBC socket 栈，Java 状态未必是 `BLOCKED` | row lock 在 DB 内，不是 Java monitor |
| finalize job | `RUNNABLE` / JDBC I/O | 重新锁 delay_jobs row，防旧 worker 覆盖新 lease |

典型业务：

- `AUTHORIZATION_EXPIRY`：锁 authorization row，必要时锁 credit account row，释放 reserved amount，写 expired event。
- `AUTO_REPAYMENT`：先模拟 bank debit result，再复用 `RepaymentService.receive`，进入 repayment 的 idempotency、row lock 和 transaction boundary。

如果看到 `delay-job-worker-*` 长时间忙：

- 查是否有大量 due jobs。
- 查是否有 DB row lock 等待。
- 查失败重试是否让同一批坏 job 反复执行。
- 查 `delay_jobs` 是否出现大量 `PROCESSING` 或 `DEAD`。

## 8. Kafka listener 线程：consumer poll 和业务处理在一起

当前 Kafka 配置重点：

```yaml
spring.kafka.consumer.enable-auto-commit: false
spring.kafka.listener.ack-mode: record
```

这表示：

- listener 方法成功返回后，当前 record 才会 ack/commit offset。
- listener 抛异常时进入 `DefaultErrorHandler`。
- error handler 使用 `FixedBackOff(1000ms, 2)`，重试后仍失败则发到 DLT。
- Consumer side 的数据库副作用靠 Consumer Inbox 和唯一约束做 idempotency。

### 8.1 Listener 清单

| Bounded context | Listener class | Topic | Group id | Factory concurrency | 业务动作 |
| --- | --- | --- | --- | --- | --- |
| Notification | `AuthorizationNotificationListener` | authorization events | `mini-card-notification-v1` | 2 | 授权批准/拒绝通知 |
| Notification | `CardTransactionNotificationListener` | transaction events | `mini-card-notification-v1` | 2 | 交易入账通知 |
| Notification | `StatementNotificationListener` | statement events | `mini-card-notification-v1` | 2 | 账单生成通知 |
| Notification | `RepaymentNotificationListener` | repayment events | `mini-card-notification-v1` | 2 | 还款成功通知 |
| Risk | `AuthorizationRiskFeatureListener` | authorization events | `mini-card-risk-feature-v1` | 3 | 更新 card risk feature projection |
| Ledger | `CardTransactionLedgerListener` | transaction events | `mini-card-ledger-v1` | 2 | 记录 purchase posted ledger entry |
| Ledger | `RepaymentLedgerListener` | repayment events | `mini-card-ledger-v1` | 2 | 记录 repayment received ledger entry |

### 8.2 Kafka listener 生命周期

启动后：

1. Spring Kafka 创建 listener container。
2. 每个 container 按 concurrency 创建 consumer thread。
3. consumer 加入 consumer group。
4. consumer poll records。
5. 拉到 record 后，在同一个 listener thread 调用 `@KafkaListener` 方法。
6. listener 调用 application service，通常开启本地 DB transaction。
7. 成功返回后 ack 当前 record。
8. 异常时按 error handler 重试，最后进入 DLT。

状态变化：

| 阶段 | 线程状态倾向 | 说明 |
| --- | --- | --- |
| poll broker 等消息 | `RUNNABLE` / `TIMED_WAITING` | Kafka native/network poll 栈，不一定吃 CPU |
| 反序列化/读 event contract | `RUNNABLE` | `IntegrationEventReader` 解析 header/payload |
| 写 Consumer Inbox | `RUNNABLE` / JDBC I/O | 幂等 claim |
| 写 notification/risk/ledger 表 | `RUNNABLE` / JDBC I/O | 本地事务 |
| listener 失败 backoff | `TIMED_WAITING` | `FixedBackOff(1000ms, 2)` |
| 发送 DLT | Kafka producer/network I/O | 保留 original partition |

### 8.3 Kafka listener 和 partition 顺序

本工程 topics 都建 3 个 partitions。代码注释里明确：

- authorization event key 使用 authorizationId，保证同一 authorization 进入同一 partition。
- statement event key 使用 creditAccountId，保证同一账户账单顺序。
- repayment event key 使用 creditAccountId，方便同账户还款通知/对账投影按顺序处理。

interview重点：

> concurrency 提高的是不同 partition 的并行处理能力。同一个 partition 内仍由一个 consumer 顺序处理。要保证同一 aggregate 的事件顺序，关键是稳定 partition key，而不是让所有 consumer 串行。

### 8.4 Kafka listener 线程为什么不能无限慢

listener thread 慢会带来：

- 当前 partition 的后续 record 处理延迟。
- consumer lag 上升。
- error handler retry/backoff 占住 consumer thread。
- 如果处理时间超过 consumer group 心跳/轮询相关阈值，可能触发 rebalance。

本项目里 listener 做的是本地 DB 投影，不调用外部 provider，这是好的边界。通知真正发送 push/email 的能力未来如果加入，不应该直接塞进当前 listener transaction。

## 9. MySQL connection、row lock 和线程状态

### 9.1 DB connection 不等于 DB thread

在 Java 侧：

- request/worker/listener thread 从 HikariCP 借 connection。
- 当前 Java thread 使用这个 connection 执行 SQL。
- SQL 返回后 connection 归还池。

不是“每条 SQL 都创建一个 Java thread”。Hikari 可能有 housekeeper 等内部线程，但业务 SQL 仍在当前调用线程里跑。

### 9.2 Spring transaction 和线程绑定

`@Transactional` 默认把 transaction context 绑定到当前 thread。

这很重要：

- `AuthorizationService.authorize` 的 transaction 在 Tomcat request thread 上。
- `DelayJobWorker` 里 handler transaction 在 `delay-job-worker-*` 上。
- Kafka listener service 的 transaction 在 Kafka consumer thread 上。
- 如果你在一个 `@Transactional` 方法里手动开新线程，新线程不会自动继承原 transaction。

interview表达：

> Spring transaction 是 thread-bound 的。不要在一个事务里随便异步开线程去写库，否则 transaction boundary、connection、rollback 语义都会变得不清楚。

### 9.3 Row lock 等待不是 Java `BLOCKED`

本项目大量使用：

```sql
SELECT ... FOR UPDATE
SELECT ... FOR UPDATE SKIP LOCKED
```

例如：

- authorization idempotency loser 会 `findByIdempotencyKeyForUpdate` 等 winner 完成。
- 同一 credit account 的授权会在 `CreditAccountMapper.findByIdForUpdate` 串行额度检查。
- Outbox/DelayJob poller 用 `FOR UPDATE SKIP LOCKED` claim 不同 rows。
- worker finalize 前重新 `findByIdForUpdate` 校验 lease。

Java thread dump 里，等待 row lock 的线程通常不会显示 Java `BLOCKED`。它可能显示：

- `RUNNABLE`，栈在 MySQL driver socket read。
- `TIMED_WAITING` / `WAITING`，取决于 driver/连接池等待实现。

所以排 row lock 要结合：

- thread dump。
- MySQL `SHOW PROCESSLIST`。
- InnoDB lock wait。
- slow query。
- Hikari active/pending metrics。

## 10. 最典型的线程路径详解

### 10.1 授权请求线程路径

```text
http-nio-8080-exec-N
-> request_started log
-> Bean Validation / Jackson
-> AuthorizationController.authorize
-> AuthorizationService.authorize @Transactional
   -> insert/claim authorization by idempotency key
   -> SELECT authorization FOR UPDATE
   -> local policy check
   -> card lookup
   -> risk assessment
      -> local risk checks
      -> Feign external risk HTTP call
   -> SELECT credit_account FOR UPDATE
   -> update account reserved_amount
   -> update authorization APPROVED/DECLINED
   -> insert delay_jobs if approved
   -> append Outbox event
-> response
-> request_completed log
```

状态点：

- CPU 计算很短，多数延迟来自 DB 和外部 HTTP。
- 外部风控在 account row lock 之前，避免拿着 account lock 等慢调用。
- 同一账户并发授权会在 account row lock 串行。
- 相同 idempotency key 的并发请求，loser 会等 winner 的 authorization row。

生产关注：

- Tomcat busy threads 上升：如果 Actuator 暴露 `tomcat.threads.busy` 就直接看；否则看 `Thread.print` 里的 `http-nio-8080-exec-*` 和请求日志耗时。
- `hikaricp.connections.pending` 上升。
- request duration 上升。
- MySQL lock wait 上升。
- external risk timeout/circuit breaker。

### 10.2 Outbox 发布线程路径

```text
outbox-scheduler-1
-> claim up to 50 events with FOR UPDATE SKIP LOCKED
-> submit to outbox-worker queue

outbox-worker-N
-> publish to Kafka
-> wait broker ack up to 5s
-> lock outbox row
-> mark PUBLISHED or retry/DEAD
```

为什么拆成 scheduler + worker：

- claim transaction 很短。
- Kafka ack 不在 DB claim transaction 内等待。
- worker 挂了也有 PROCESSING lease recovery。
- worker queue 满时不会无限吃 heap，而是显式 retry。

### 10.3 DelayJob 执行线程路径

```text
delay-job-scheduler-N
-> claim due jobs with FOR UPDATE SKIP LOCKED
-> submit to delay-job-worker queue

delay-job-worker-N
-> dispatch jobType
-> execute business service
-> lock job row
-> mark DONE or retry/DEAD
```

为什么 DelayJob worker 可能比 Outbox worker 更容易卡 DB：

- Outbox worker 的长等待主要是 Kafka ack。
- DelayJob worker 可能进入业务 service，锁 authorization、credit account、statement、repayment 等业务表。
- 自动还款未来接真实银行 API 时，还可能等待外部银行结果。

### 10.4 Kafka listener 线程路径

```text
Kafka consumer thread
-> poll record
-> IntegrationEventReader.read
-> event type/version check
-> application service @Transactional
   -> Consumer Inbox claim
   -> write notification/risk/ledger projection
-> listener returns
-> commit/ack record
```

为什么 listener 里要做 Inbox：

- Kafka + MySQL 没有同一个本地事务。
- Kafka delivery 是 at-least-once。
- listener 可能在 DB 写成功后、offset commit 前崩溃。
- 重启后 record 会被重复投递。
- Consumer Inbox 用 `(consumerName, eventId)` 防重复副作用。

## 11. 启动后如何采集真实线程状态

先找 pid：

```bash
jps -l
```

采集 thread dump：

```bash
jcmd <pid> Thread.print -l > threads.txt
```

或：

```bash
jstack -l <pid> > threads.txt
```

查 Actuator thread 指标：

```bash
curl "http://localhost:8080/actuator/metrics/jvm.threads.live"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states?tag=state:runnable"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states?tag=state:waiting"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states?tag=state:timed-waiting"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states?tag=state:blocked"
curl "http://localhost:8080/actuator/metrics/hikaricp.connections.active"
curl "http://localhost:8080/actuator/metrics/hikaricp.connections.pending"
```

如果当前环境暴露 Tomcat thread metrics，再补查：

```bash
curl "http://localhost:8080/actuator/metrics/tomcat.threads.current"
curl "http://localhost:8080/actuator/metrics/tomcat.threads.busy"
```

本次本机实测没有 `tomcat.threads.*`，所以 Tomcat 线程要靠 thread dump 里的
`http-nio-8080-*` 观察。

快速过滤线程名：

```bash
rg 'http-nio|outbox-|delay-job-|statement-batch|kafka|Hikari|GC Thread|CompilerThread' threads.txt
```

如果要排 CPU：

```bash
top -H -p <pid>
printf "%x\n" <decimal_tid>
rg "nid=0x<hex_tid>" threads.txt
```

如果要排阻塞：

```bash
rg -n 'BLOCKED|WAITING|TIMED_WAITING|parking|socketRead|executeQuery|CompletableFuture|get' threads.txt
```

## 12. 常见生产问题怎么从线程切入

### 12.1 API 延迟上升，Tomcat busy threads 很高

先看：

```bash
curl "http://localhost:8080/actuator/metrics/hikaricp.connections.pending"
jcmd <pid> Thread.print -l
```

如果 `tomcat.threads.busy` 在当前环境可用，再一起采集。

判断：

- 多数 `http-nio-8080-exec-*` 在 `AuthorizationService` + MySQL driver：查 DB、row lock、慢 SQL。
- 多数在 Feign/external risk：查外部风控 latency、timeout、circuit breaker。
- 多数在 JSON/logging：查大 payload 或日志 I/O。
- 多数 `BLOCKED`：查 Java monitor lock，不要误判成 DB row lock。

### 12.2 Hikari pending 很高

含义：

- 很多 Java thread 想借 DB connection 但池里没有空闲连接。

可能原因：

- SQL 慢或 row lock 等待。
- transaction 太长。
- Tomcat/Kafka/worker 并发总量超过 DB pool 承载。
- 外部调用被放进 transaction 内。

本项目已经把 authorization 的 external risk call 放在 account row lock 之前，这是为了缩短关键锁区。但它仍在 `AuthorizationService.authorize` 的 transaction 方法内部，所以生产进一步优化时可以讨论是否把慢外部调用放到事务外层，前提是保持 idempotency 和审计语义清楚。

### 12.3 Outbox backlog 上升

看线程：

- `outbox-scheduler-*` 是否卡在 claim SQL。
- `outbox-worker-*` 是否都在等 Kafka ack。
- Kafka producer/network thread 是否异常。

看数据：

- `outbox_events` 中 `PENDING` 是否增长。
- `PROCESSING` 是否长期不动。
- `DEAD` 是否增长。

判断：

- Scheduler 卡：DB 查询/索引/锁问题。
- Worker 卡：Kafka broker ack 或 network 问题。
- Queue 满：worker publish 能力低于 claim 速度。

### 12.4 DelayJob backlog 上升

看线程：

- `delay-job-scheduler-*` 是否正常醒来。
- `delay-job-worker-*` 是否都忙。
- busy worker 是在 authorization expiry、repayment、还是 finalize job。

看数据：

- `delay_jobs` 中 due `PENDING`。
- `PROCESSING` 是否超过 lease timeout。
- `DEAD` 是否增加。

判断：

- 很多 due jobs 但 worker 空闲：poll/claim 有问题。
- worker 全忙且 DB pending 高：业务 SQL/row lock 问题。
- worker 全忙且失败重试：坏 job 或外部依赖失败。

### 12.5 Kafka consumer lag 上升

看：

- listener thread 是否在 DB transaction 里慢。
- listener 是否在 error handler backoff。
- DLT 是否增加。
- GC pause 是否导致 listener 停顿。
- DB pool 是否被 HTTP/worker 抢满。

常见修复方向：

- 优化 listener 的 DB 写入。
- 确认 Inbox/唯一约束没有造成热点。
- 调整 listener concurrency，但不能超过 partition/DB/CPU 承载。
- 对坏消息进入 DLT，不要无限阻塞 partition。

### 12.6 大量线程 `BLOCKED`

先别看 DB。Java `BLOCKED` 主要是 monitor lock。

排查：

- thread dump 会显示 `waiting to lock <...>` 和 owner thread。
- 找同一个 monitor 被多少线程等。
- 查是否新增 `synchronized`、静态初始化、全局对象锁。

本项目正常路径不应该大量 `BLOCKED`，因为并发正确性靠 DB row lock 和 idempotency，不靠 JVM 大锁。

### 12.7 大量线程 `WAITING` / `TIMED_WAITING`

不一定是坏事。

正常例子：

- scheduler fixed delay：`TIMED_WAITING`。
- idle worker：`WAITING`。
- Kafka consumer poll/backoff：可能 `TIMED_WAITING`。
- Hikari housekeeper：`TIMED_WAITING`。

异常例子：

- 大量 request thread 等 DB connection。
- 大量 request thread 等外部 HTTP。
- 大量 worker thread 等 Kafka ack timeout。
- 大量 thread sleep 来自本地模拟外部风控，说明测试流量把模拟接口也压住了。

## 13. interview重点回答模板

### Q1：这个工程启动后有哪些业务相关线程？

回答：

> 同步入口是 Tomcat request threads，处理 controller/service/MyBatis。异步机制有三类 scheduler：Outbox、DelayJob、Statement batch。Outbox 和 DelayJob 又各自有 worker pool，scheduler 只做短事务 claim，worker 做 Kafka publish 或业务 job。Kafka listener container 还有 consumer threads，负责消费事件并在本地事务里写 Inbox 和 projection。除此之外还有 Hikari、Kafka client 和 JVM internal threads。

### Q2：Tomcat request thread 和 OS thread 什么关系？

回答：

> 当前没有启用 virtual threads，所以 Tomcat request thread 是 Java platform thread，通常一对一映射到 OS native thread。每个 native thread 有自己的 stack，占用 native memory。Thread dump 里的 `nid` 可以和 OS 工具里的 native thread id 对应，用来排 CPU 热线程。

### Q3：为什么等待 MySQL row lock 不一定显示 `BLOCKED`？

回答：

> Java `BLOCKED` 是等待 Java monitor lock。MySQL row lock 在数据库内部，Java thread 通常是在 JDBC socket read 或 driver 调用里等待结果，所以 thread dump 可能显示 `RUNNABLE`。排 row lock 要结合 MySQL lock wait、slow query、Hikari pending 和业务日志。

### Q4：为什么 `@Scheduled` 不直接处理所有任务？

回答：

> 因为 scheduler thread 应该短小稳定。Outbox/DelayJob 先用短事务 claim，把 row 改成 PROCESSING lease，commit 后交给 worker pool。Kafka ack、业务 row lock、银行扣款这些慢动作都在 worker 中。worker 挂了有 lease recoverer，queue 满了可以 retry/DEAD，不会让 scheduler 卡死或无限堆 heap。

### Q5：Outbox worker 和 DelayJob worker 有什么区别？

回答：

> Outbox worker 主要是 reliable publication，等待 Kafka broker ack，然后 finalize Outbox row。DelayJob worker 是未来业务动作执行器，比如授权过期释放额度、自动还款，它会进入具体 application service 和业务表 row lock。两者都是 bounded worker pool，但风险不同：Outbox 更关注 Kafka ack/backlog，DelayJob 更关注业务锁和 retry/DEAD。

### Q6：Kafka listener concurrency 怎么理解？

回答：

> Kafka listener concurrency 是每个 listener container 的 consumer thread 数。它提高不同 partition 的并行处理能力，但同一个 partition 内仍保持顺序。要保证同一 aggregate 顺序，关键是 partition key，例如 authorizationId 或 creditAccountId，而不是让所有事件单线程消费。

### Q7：为什么本项目不使用 `synchronized` 保证授权并发？

回答：

> `synchronized` 只能保护单个 JVM 进程内的线程，不能保护多实例。授权并发要靠 MySQL unique constraint 选 idempotency winner，并用 `SELECT ... FOR UPDATE` 锁住 credit account row，在同一个 transaction boundary 内检查额度和更新余额。Java thread model 要懂，但金融状态一致性要靠数据库事务兜底。

### Q8：如果线程池满了怎么办？

回答：

> 本项目 worker queue 是 bounded。Outbox/DelayJob poller submit 被拒绝时，不是无限扩容线程或无限堆积内存，而是把 event/job 标记为失败并进入 retry/DEAD 策略。生产上这属于 backpressure，需要同时看 backlog、worker busy、DB/Kafka latency，再决定扩容、限流或优化慢依赖。

### Q9：Virtual threads 适合这里吗？

回答：

> 当前工程没启用 virtual threads。Virtual threads 对大量阻塞 I/O 请求可能有帮助，但不会消除 DB row lock、连接池、Kafka partition、外部服务吞吐这些瓶颈。金融后台即使用 virtual threads，也仍然需要 bounded DB pool、idempotency、row lock、timeout、bulkhead 和可靠恢复。

## 14. 复习清单

你应该能回答：

- 启动后哪些线程是 Tomcat 的，哪些是 scheduler，哪些是 worker，哪些是 Kafka listener。
- 为什么 request thread 会被 DB、Feign、row lock 占住。
- `RUNNABLE` 为什么不等于一定在吃 CPU。
- Java `BLOCKED` 和 MySQL row lock waiting 的区别。
- Scheduler thread 为什么要薄。
- Outbox publish 为什么放到 worker，而不是业务主交易或 scheduler 里。
- DelayJob 的 PROCESSING lease 如何应对 worker 宕机。
- Kafka listener thread 什么时候 ack offset，为什么需要 Consumer Inbox。
- platform thread 和 OS native thread 如何对应。
- 线程数过多为什么会造成 native memory 和 context switch 问题。
- 生产上如何用 `jcmd Thread.print`、Actuator、Hikari metrics、Kafka lag 和 MySQL lock wait 联合排查。
