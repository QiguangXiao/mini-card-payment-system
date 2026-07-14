# JVM 内存、GC、线程模型与生产排查（运行时统一笔记）

> 本文合并自两份旧文档：`jvm-monitoring-learning-cn.md`（JVM 内存/GC/监控/排查）与
> `thread-runtime-learning-cn.md`（线程模型/线程状态/线程路径）。合并时去掉两者重复的本地实测快照、
> 线程状态解释、排查 Runbook、interview 问答和复习清单，并**对齐代码**：旧文档把 statement 批处理
> 当成"单个 `statement-batch-scheduler` 每 60s 扫描"，但 PR #1 已把它扁平化成 **claimable job**
> （`billing-cycle-scheduler` 每天触发 + `statement-job-scheduler` 的 claim/recover + `statement-job-worker`），
> 旧的 `StatementBatchPoller/Service` 类已删除。原两份已归档在 `docs/archive/`。

> 关键词：JVM, heap, young/old gen, metaspace, GC roots, G1, safepoint, thread dump, platform thread,
> RUNNABLE/BLOCKED/WAITING, lease, row lock, Hikari, Actuator, ヒープ, スレッドダンプ。

工程背景：Java 21 + Spring Boot 3.5（MVC/内嵌 Tomcat），MyBatis/MySQL、Kafka、Actuator；未启用 virtual threads（主要是 platform thread）；金融状态一致性靠 MySQL unique constraint + transaction + `SELECT ... FOR UPDATE` row lock，不靠 JVM `synchronized`。

---

## 1. 本地实测运行快照（2026-06-21 12:43 JST）

IntelliJ 启动，PID `47626`，Temurin OpenJDK `21.0.11+10-LTS`，内嵌 Tomcat `:8080`，`/actuator/health`=`UP`。`VM.flags`：`-XX:+UseG1GC`、`InitialHeapSize≈256MiB`、`MaxHeapSize≈4GiB`、`G1HeapRegionSize=2MiB`、`-XX:TieredStopAtLevel=1`（IDEA 快启参数，少做高层 JIT）。default profile，时区 `Asia/Tokyo`。

一次真实 `POST /api/authorizations`（curl 端到端 ~229ms，Actuator `http.server.requests` 228ms；内部模拟风控 `/external-risk/assess` 105ms，Resilience4j `externalRisk` 122ms；返回 `APPROVED`）前后的 JVM 变化：

| 指标 | 请求前 | 请求后 | 观察 |
| --- | ---: | ---: | --- |
| heap used (`GC.heap_info`) | 87006K | 99363K | 一次请求+Actuator查询+异步发布后短增 ~12MiB |
| Metaspace used | 76692K | 79691K | Kafka producer/metrics 等类进一步加载 |
| `jvm.gc.pause` | count 5 | count 6 | 触发 1 次 G1 young GC，无 Full GC |
| `jvm.threads.live` | 93 | 97 | Outbox publish 后 lazy 创建 worker、Kafka producer/network/metrics 线程 |

请求后 `jvm.threads.states`：`RUNNABLE=27`、`WAITING=13`、`TIMED_WAITING=57`、`BLOCKED=0`（这是逐 endpoint 查询的瞬时值；完整 thread dump 自己统计是 `RUNNABLE=29/WAITING=29/TIMED_WAITING=42/BLOCKED=0`——**两者采样时刻不同，排查应关注趋势和栈，不要把一次快照当精确恒等式**）。

thread dump（1914 行）的线程类别（已对齐当前结构）：

| 线程类别 | 实测 | 说明 |
| --- | ---: | --- |
| Tomcat request worker `http-nio-8080-exec-*` | 10 | 空闲等 Tomcat task queue |
| Tomcat acceptor/poller | 2 | `http-nio-8080-Acceptor` / `-Poller` |
| Spring Kafka listener / heartbeat / metrics / 内部 scheduler | ~60 | 多个 listener container × concurrency + 各 consumer 的 heartbeat/metrics 叠加 |
| 本工程 scheduler | 多个 | `outbox-scheduler-*`、`delay-job-scheduler-*`、`billing-cycle-scheduler-*`、`statement-job-scheduler-*`、`notif-delivery-scheduler-*`（采集当时 statement 调度尚为单一 `statement-batch-scheduler`，现已拆为 billing-cycle + statement-job 两个池） |
| Outbox worker | 1 | 请求触发 publish 后 lazy 创建 |
| DelayJob worker | 0 | 采样期间无 due job，pool 未创建实际线程 |
| Hikari housekeeper / MySQL cleanup / Kafka producer network | 各 1 | |
| G1/JVM GC、JIT compiler | ~17 | GC worker、G1 conc/refine/service、`C1 CompilerThread0` |

Hikari `active=0, idle=10, pending=0`（本次请求结束无连接池积压）。**一个监控坑**：本机 `/actuator/metrics` 只暴露了 `tomcat.sessions.*`，没有 `tomcat.threads.current/busy`；遇到 404 不要误判 Tomcat 没线程，改用 `jcmd Thread.print` 看 `http-nio-8080-exec-*` 和请求日志耗时。

---

## 2. JVM 内存结构

```text
JVM Process
├── Heap ── Young(Eden + Survivor0/1) + Old
├── Non-Heap ── Metaspace + Code Cache + JVM internal
├── Thread Stacks
├── Direct / Native Buffer
└── OS process memory（RSS）
```

- **Heap**：普通 Java object 主场。本项目典型：`CreateAuthorizationRequest`、`AuthorizationCommand`、`Money`、`Authorization`、`RiskAssessmentRequest/RiskDecision`、`AuthorizationRow/OutboxEventRow`（MyBatis row）、domain event、Jackson 的 String/char[]/byte[]、Kafka record/header/byte[]。Heap 不是越大越好：太小频繁 GC/OOM，太大 Full GC 暂停更痛、容器里挤压 native memory。
- **Young gen**：短命对象（大多数请求对象在此出生和死亡）。一次授权里的 JSON 中间对象、DTO/command、`requestFingerprint()` 的 canonical string + `MessageDigest` + byte[]/hex、MyBatis 结果、`AuthorizationResponse`——请求快完成就在下次 Young GC 回收。
- **Old gen**：长期对象——Spring singleton bean（`AuthorizationService`、`OutboxPoller`…）、配置对象、MyBatis mapper proxy/mapped statement/type handler、HikariCP datasource、Kafka client/listener container、线程池及其队列、`Currency` 等 JDK cache。请求对象**也可能**进 Old（被 row lock 卡住、外部风控慢、worker queue 积压、被 static map/cache/ThreadLocal 持有）——"进 Old"本身不是 bug，关键看 Full GC 后能否下降。
- **Thread Stack**：每线程一份，存 stack frame/local variable/operand stack/return address。**Java object 在 heap，stack 上是引用和基本类型值；只要 stack frame 还引用某 object，它就是 live，GC 不能回收**（thread stack 是 GC Root 来源之一）。线程太多不只费 CPU：每个 platform thread 有 native stack（占 heap 外内存），safepoint 协调成本上升，大量阻塞线程往往是 DB pool/row lock/外部 HTTP/Kafka 瓶颈的信号。
- **Metaspace**：类元数据（native memory，不在 heap）。随 Spring 自动配置类、应用类、MyBatis proxy、AOP/Feign/Resilience4j 代理类、第三方库类增加；稳定运行后应趋平。动态生成 class/classloader 泄漏会 Metaspace OOM。
- **Code Cache**：JIT 编译后的 native code（`AuthorizationService.authorize`、`Money.add`、mapper proxy、listener dispatch 等热点）。排 CPU 时要知道热点已是 native code。
- **Direct/Native Buffer**：heap 外内存（Kafka/NIO/MySQL driver/HTTP client）。现象：heap used 不高但 RSS/容器内存高——可能是 direct buffer、thread stack、Metaspace、Code Cache、native lib 或 mmap。**`heap used` 只是 JVM 内一部分，`RSS` 是整个进程 OS 视角占用**。

### 2.1 一次授权请求的对象图

```text
Tomcat request thread stack
└── AuthorizationController.authorize
    ├── CreateAuthorizationRequest / AuthorizationCommand
    └── AuthorizationService.authorize
        ├── Authorization.request → UUID, Money(BigDecimal,Currency), ArrayList<DomainEvent>
        ├── RiskAssessmentRequest / Card / CreditAccount
        ├── Approved/DeclinedDomainEvent
        ├── OutboxEvent
        └── AuthorizationResponse
```

大多数对象 request scoped，正常很快死亡；Spring bean、mapper proxy、client、线程池才是长期对象。

---

## 3. GC 核心

- **GC 回收的是 unreachable object，不是"没用对象"**。GC Roots：活跃线程 stack 的 local reference、static field、JNI reference、class metadata 引用、synchronized monitor 持有对象。**内存泄漏本质 = 业务上已不需要，但技术上仍被某 Root 链引用**。常见泄漏链：`static Map → list → payload`、`ThreadLocalMap → value → 大对象`、`ThreadPool queue → lambda → OutboxEvent`、`cache → domain snapshot`。
- **分代假说**：多数对象很快死，活过一段的往往继续活很久 → Young（高频创建回收）+ Old（长期）。
- **TLAB**（Thread Local Allocation Buffer）：每线程从 Eden 拿私有分配区，降低分配锁竞争；但不减少总 allocation rate。
- **Safepoint**：STW GC 时应用线程要停到安全点。影响：API 响应短暂停顿、Kafka ack 延迟、scheduler 触发变晚、Outbox publish 变慢。**GC pause 不破坏 MySQL transaction 原子性，但放大 latency / timeout / consumer lag / backlog**。
- **G1 的几种 GC**：Young GC（回收 young region，暂停短）；Concurrent Mark（并发标记 old 的 live object）；Mixed GC（回收 young + 部分 old）；Full GC（回收压力太大或并发回收失败的兜底，暂停更重，线上高度警惕）。确认实际 GC：`jcmd <pid> VM.flags` / `VM.command_line`，或启动 `-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags`。

### 3.1 流量与延迟上升时内存为什么涨

核心公式：

```text
并发中的请求数 ≈ RPS × 平均请求耗时(秒)
请求相关 live heap ≈ 并发请求数 × 单请求对象图大小
```

100 RPS × 0.1s ≈ 10 个请求同时活；耗时变 2s → 100 RPS × 2s ≈ 200 个；1000 RPS × 2s ≈ 2000 个。**即使单请求只占几十 KB，live object 也快速上升**。本项目放大耗时的点：外部风控模拟延迟 `risk.external.simulated-latency-millis: 100`、`findByIdForUpdate` 的账户 row lock 串行、Hikari 耗尽排队、Kafka/DB 慢导致后台队列增长。

流量上升不只是"每秒多些对象"，而是**对象生命周期变长**：更多 request thread 同时持有 DTO/domain object → DB/row lock/外部风控让请求等待 → 等待中对象无法回收 → Eden 更快填满、Young GC 更频繁 → 多次存活后晋升 Old → Old 升高触发 concurrent marking 和 mixed GC → 若回收跟不上分配，长暂停/Full GC/`OutOfMemoryError`。

> **反向事实/判 leak**：流量上升导致内存上升不一定是 leak。先看 allocation rate 和 live set；请求慢、锁等待、外部调用会让短命对象活得更久、增加 promotion。**Full GC 后 old gen 仍持续增长、且流量回落仍不降**，才更像 leak 或 unbounded retention——再用 class histogram / heap dump / dominator tree 找 retained size 最大的对象和引用链。

后台积压的内存边界：Outbox/DelayJob 的 backlog 主要在 MySQL 表里（`PENDING` 不等于同量 Java object）；但每轮 claim 出的 list、worker queue 里排队的 task、Kafka producer 等 ack 的 buffer 仍占 heap。worker pool `core=max`（如 outbox/delay 各 4）+ 有界 queue（100）+ 满则 `TaskRejectedException` 放回 retry，正是防止无限创建线程/堆积 heap。

---

## 4. 线程模型

### 4.1 线程地图

```text
mini-card JVM
├── JVM internal：GC / JIT / Reference Handler / Common-Cleaner / Signal Dispatcher
├── Tomcat：acceptor / poller / request worker(http-nio-8080-exec-*)
├── Spring scheduler 池：outbox-scheduler-* / delay-job-scheduler-* /
│                        billing-cycle-scheduler-* / statement-job-scheduler-* / notif-delivery-scheduler-*
├── Business worker 池：outbox-worker-* / delay-job-worker-* / statement-job-worker-* / notif-delivery worker
├── Kafka：consumer listener / producer network / admin/metadata/heartbeat
└── DB helper：Hikari housekeeper 等（业务 SQL 仍在调用线程里跑）
```

核心视角：Tomcat thread 处理同步 HTTP；scheduler thread 只做轻量 poll/claim；worker thread 执行慢的后台业务；Kafka listener thread 负责 poll record + 调 listener + ack offset；**MySQL row lock 不创建新 Java thread——等待 row lock 的仍是当前 request/worker/listener thread**。

### 4.2 Java 线程状态（绑定本工程理解）

| 状态 | 本工程典型含义 | 易误解 |
| --- | --- | --- |
| `RUNNABLE` | 正在跑 Java 代码；**或**卡在 native socket read / MySQL JDBC / Kafka network I/O | **`RUNNABLE` ≠ 正在吃 CPU**，很多 I/O 等待在 Java 层也是 `RUNNABLE` |
| `BLOCKED` | 等待进入 Java monitor（`synchronized`） | **MySQL row lock 等待通常不是 Java `BLOCKED`** |
| `WAITING` | worker 空闲等队列任务；无超时等待 | 空闲 worker 正常 |
| `TIMED_WAITING` | scheduler fixed delay sleep、`Thread.sleep`、Kafka backoff、`Future.get(timeout)` | 大量 `TIMED_WAITING` 不一定故障，要看栈 |

> 不要只看 state 名字，**一定看 线程名 + stack trace + 业务指标**。例：`http-nio-8080-exec-*` 在 JDBC socket read → 慢 SQL/row lock/连接池；`outbox-worker-*` 在 `CompletableFuture.get(timeout)` → 等 Kafka ack；`delay-job-worker-*` 在 `CreditAccountMapper.findByIdForUpdate` → 等 account row lock。

### 4.3 platform thread 与 OS thread

未启用 virtual threads，主要是 platform thread：一个 Java platform thread ≈ 一个 OS native thread，各有 native stack（占 heap 外内存）；线程数上升增加 native memory、context switch、safepoint 成本。thread dump 行 `"http-nio-8080-exec-7" #123 ... nid=0x2f03 runnable` 里 `nid` 是 native thread id（十六进制）。Linux 排 CPU 热线程：`top -H -p <pid>` → `printf "%x\n" <decimal_tid>` → 在 `jcmd Thread.print -l` 里找 `nid=0x...`。OS 视角与 JVM 视角不一一等价，要结合 CPU/栈/GC/DB/Kafka。

### 4.4 Tomcat request 线程

`application.yml` 现在按单实例小项目显式配置 Tomcat `max=80/min-spare=10/max-connections=2048/accept-count=50/connection-timeout=3s/keep-alive-timeout=30s` 和 Hikari `maximum=10/minimum-idle=5/connection-timeout=1s/validation-timeout=500ms`，避免沿用 Tomcat 200 workers 同时争用 10 个 DB connections，也避免 Hikari 默认等待 30s。注意 `accept-count` 不是 worker 满后的业务队列：Tomcat 先接受并持有最多 2048 个连接，达到上限后 50 才是 OS listen backlog；只有拿到 80 个 worker 之一的请求才会进入业务代码并开始等待 Hikari。生产大流量（例如 4 vCPU/8 GiB、8+ API Pods）可从 Tomcat `200~300` threads、Hikari `20~30/Pod` 起步，但必须按 RDS 总连接预算和压测校准；LB idle timeout=60s 时后端 keep-alive 可从 65s 起步，避免连接复用边界产生间歇 502。详见 `traffic-rate-limiting-and-capacity-cn.md`。一次授权的执行链全部在**同一个** request thread 内：`http-nio-8080-exec-N → HttpRequestLoggingFilter → AuthorizationController → AuthorizationService(@Transactional) → MyBatis claim / findByIdempotencyKeyForUpdate → RiskAssessmentService → Feign ExternalRiskClient → CreditAccountMapper.findByIdForUpdate → update account/auth/outbox/delay_jobs → response`。`@Transactional` 把事务资源绑到当前线程，MyBatis 用该线程借的连接；该线程等 DB/row lock/外部 HTTP 时仍占用 Tomcat worker。

一个本地学习点：`risk.external.base-url` 默认 `http://localhost:8080`，授权请求经 Feign 调**同一应用**的 `SimulatedExternalRiskController`，所以本地一次授权可能同时占用两个 `http-nio-8080-exec-*`：一个处理 `/api/authorizations` 等 Feign 响应，另一个处理 `/external-risk/assess` 并在 `Thread.sleep(100ms)` 里 `TIMED_WAITING`。生产里外部风控是独立服务，要关注 timeout/bulkhead/circuit breaker/request thread 占用。

大量 request thread `BLOCKED` 不正常（核心并发不靠 `synchronized`）——查新增全局 Java 锁/第三方库内部锁/单例初始化卡住；别把 MySQL row lock 等待误读成 Java `BLOCKED`。

### 4.5 Scheduler 线程：薄

| Scheduler bean | 线程名前缀 | pool | 任务 | fixed delay |
| --- | --- | --- | --- | --- |
| `outboxTaskScheduler` | `outbox-scheduler-` | 1 | `OutboxPoller.poll` / `OutboxRecoverer.recover` | 1s / 5s |
| `delayJobTaskScheduler` | `delay-job-scheduler-` | 2 | `DelayJobPoller.poll` / `DelayJobRecoverer.recover` | 1s / 5s |
| `billingCycleTaskScheduler` | `billing-cycle-scheduler-` | 1 | `BillingCycleScheduler`（每天触发 reconciliation 心跳，`StatementCycleService` 补建缺失 close cycle 的分片 job） | daily cron(JST) |
| `statementJobTaskScheduler` | `statement-job-scheduler-` | 2 | `StatementJobDispatcher` 的 claim / recover | 1s / 10s |
| `notificationDeliveryTaskScheduler` | `notif-delivery-scheduler-` | 2 | 通知投递 poll / recover | 见投递文档 |

> 设计原则：**`@Scheduled` thread 只周期性醒来、短事务 claim、提交 worker；长业务交给 worker pool**。好处：scheduler 不被 Kafka ack/银行扣款/业务 row lock 长时间占住；claim 事务短，减少 DB lock 持有；worker 宕机可经 PROCESSING lease recover；线程名清晰。outbox pool=1（poller 与 recoverer 不并发，发布也偏轻）；delay/statement pool=2（poll 与 recover 可并行）。**若看到 `*-scheduler-*` 长时间卡 JDBC 栈，先查 claim/recover SQL、索引、DB 锁，不要先加线程数**。

> 注意：statement 现在是 **claimable job**（cron planner 建分片 → dispatcher claim → `statement-job-worker-*` 逐账户小事务出账），不再是旧的"单 scheduler 60s 扫描全部账户"。分片/扇出/账户级故障隔离见 `claimable-jobs-cn.md`。

### 4.6 Worker 线程：执行后台业务

| Worker executor | 线程名前缀 | core/max | queue | 业务 |
| --- | --- | --- | --- | --- |
| `outboxWorkerExecutor` | `outbox-worker-` | 4/4 | 100 | Kafka publish + Outbox finalize |
| `delayJobWorkerExecutor` | `delay-job-worker-` | 4/4 | 100 | Authorization expiry 等纯 DB 小事务 job |
| `autoRepaymentDelayJobWorkerExecutor` | `auto-repay-worker-` | 4/4 | 100 | AUTO_REPAYMENT：调外部银行网关（Feign），brownout 只钉本池 |
| `statementJobWorkerExecutor` | `statement-job-worker-` | 有界 | 有界 | 一个分片的逐账户出账 |

`core=max` 固定上限不无限扩；queue 有界是显式背压。线程按需创建、长期保留、空闲等 queue task；shutdown `waitForTasksToCompleteOnShutdown=true`。进程在 worker 执行中宕机 → row 仍 `PROCESSING` → recoverer 在 lease timeout 后放回 retry/DEAD（PROCESSING lease 的意义）。

**Outbox worker** 典型状态：空闲 `WAITING` → 构造 record `RUNNABLE` → 等 broker ack `TIMED_WAITING`（`get(timeout)` 最多 `send-timeout-ms=5000`）→ finalize `findByIdForUpdate` 的 JDBC I/O。若 4 个 `outbox-worker-*` 都在等 ack，说明 publish 能力被 Kafka ack 限制，`PENDING` backlog 会增长——**别第一反应把 pool 调大**，先看 broker/network/partition/producer timeout/DB finalize。

**DelayJob worker** 比 Outbox worker 更容易卡 DB：它进入业务 service，锁 authorization/account/statement/repayment。`AUTO_REPAYMENT` 已拆到 `auto-repay-worker-*` 专用池——它同步等银行网关（Feign read-timeout 2s 兜底），银行 brownout 时看到本池全员 `TIMED_WAITING` 在 socket read 属预期，授权过期在 `delay-job-worker-*` 不受影响。久忙时查 due jobs 量、row lock 等待、坏 job 反复重试、`PROCESSING`/`DEAD` 增长。

### 4.7 Kafka listener 线程

`enable-auto-commit: false` + `ack-mode: record`：listener 成功返回后才 ack 当前 record；异常进 `DefaultErrorHandler`（`FixedBackOff(1000ms,2)` 重试后进 DLT）；副作用靠 Consumer Inbox + 唯一约束幂等。

| Context | Listener | Group | concurrency |
| --- | --- | --- | --- |
| Notification | Authorization/CardTransaction（2 个） | `mini-card-notification-v1` | 2/容器 |

> concurrency 是**每个 listener container** 的 consumer thread 数，不是"整个 context 总共 N 个线程"。concurrency 提高的是不同 partition 的并行；**同一 partition 内仍由一个 consumer 顺序处理**。listener 只写本地 Notification/Inbox，不调外部 provider。

### 4.8 MySQL connection、row lock 与线程状态

- **DB connection ≠ DB thread**：request/worker/listener thread 从 Hikari 借 connection 执行 SQL，返回后归还；不是每条 SQL 一个 Java thread。
- **Spring transaction thread-bound**：`@Transactional` 把事务 context 绑到当前线程（授权事务在 Tomcat thread、DelayJob handler 事务在 `delay-job-worker-*`、listener 事务在 Kafka consumer thread）。**在 `@Transactional` 方法里手开新线程写库，新线程不继承原事务**——transaction boundary/connection/rollback 语义会乱。
- **Row lock 等待不是 Java `BLOCKED`**：等 `SELECT ... FOR UPDATE` 的线程通常显示 `RUNNABLE`（MySQL driver socket read）或 `TIMED_WAITING/WAITING`（取决于 driver/池实现）。排 row lock 要结合 thread dump + `SHOW PROCESSLIST` + InnoDB lock wait + slow query + Hikari active/pending。

### 4.9 典型线程路径

```text
授权：http-nio-8080-exec-N → AuthorizationService.authorize @Transactional
  → claim by idempotency key → SELECT auth FOR UPDATE → policy/card/risk(含 Feign 外部风控)
  → SELECT credit_account FOR UPDATE → update account/auth → insert delay_jobs(若 approved) → append Outbox → response
  关注：Tomcat busy / hikari pending / request duration / MySQL lock wait / external risk timeout。外部风控在 account lock 之前，避免拿着 account lock 等慢调用。

Outbox：outbox-scheduler-1 claim(FOR UPDATE SKIP LOCKED) → 提交 outbox-worker queue
  → outbox-worker-N publish → 等 ack ≤5s → lock outbox row → markPublished / retry / DEAD

DelayJob：delay-job-scheduler-N claim → delay-job-worker-N dispatch(jobType) → 业务 service → lock job row → markDone/retry/DEAD

Kafka listener：consumer thread poll → IntegrationEventReader.read → @Transactional( Inbox claim → 写 notification/risk 投影 ) → ack record
  为何要 Inbox：Kafka+MySQL 非同一本地事务、at-least-once，listener 可能 DB 写成功后 offset commit 前崩溃 → 重投 → Inbox(consumerName,eventId) 防重复副作用。
```

---

## 5. 监控与诊断

### 5.1 liveness / readiness 与 Actuator

```text
GET /api/health                → public liveness（只证明 HTTP 应用还活着，不查 DB/Kafka/JVM）
GET /actuator/health{,/liveness,/readiness}   → readiness 组含 db
```

`HealthController`（`monitoring.api`）的 `/api/health` 故意不查依赖：public liveness 只回答"进程是否活着"，DB 抖动不该让容器反复重启；JVM/GC/thread/dependency health 属 management plane，交给 Actuator/Micrometer。

> **DB 挂了 liveness 要失败吗？** 不一定。DB 暂时不可用通常应让 **readiness 失败**（把实例摘出流量、保留进程等依赖恢复），而不是 liveness 失败（更适合进程内不可恢复问题）。Full GC/deadlock/线程池耗尽可能导致 readiness 失败或超时；但别让一个昂贵的 liveness endpoint 自己制造故障；heapdump/threaddump/env 等高危 endpoint 不该公开暴露。

### 5.2 关键 metrics（JVM 与业务一起看）

```bash
curl ".../actuator/metrics/jvm.memory.used?tag=area:heap"     # 也看 area:nonheap
curl ".../actuator/metrics/jvm.gc.pause"
curl ".../actuator/metrics/jvm.threads.live"
curl ".../actuator/metrics/jvm.threads.states?tag=state:blocked"   # runnable/waiting/timed-waiting
curl ".../actuator/metrics/jvm.buffer.memory.used"
curl ".../actuator/metrics/hikaricp.connections.active"           # 和 .pending
curl ".../actuator/metrics/process.cpu.usage"
# tomcat.threads.current/busy 若暴露则查；本机只暴露 tomcat.sessions.*，改看 Thread.print 的 http-nio-8080-exec-*
```

> JVM metrics 说"应用累不累"，业务 metrics 说"支付链路有没有积压/失败"——要一起看：Outbox `PENDING/PROCESSING/DEAD`、DelayJob due backlog/`DEAD`、Kafka consumer lag/DLT、Authorization approve/decline/timeout、Risk external timeout/circuit breaker、Statement 分片处理数/耗时/失败、worker queue depth/rejected。典型关联链：`GC pause↑ → request latency↑ → Tomcat busy↑ → Hikari pending↑ → 授权 timeout/retry↑ → Outbox/DelayJob/Kafka backlog↑`；或 `MySQL row lock wait↑ → request thread 等待 → heap live set↑ → young 晋升 old → GC pause↑`。

### 5.3 诊断命令

```bash
jps -l                                   # 找 pid
jcmd <pid> VM.flags | VM.command_line | GC.heap_info
jstat -gcutil <pid> 1000 10
jcmd <pid> GC.class_histogram             # 可能触发停顿，高峰谨慎
jcmd <pid> Thread.print -l > threads.txt  # 或 jstack -l
jcmd <pid> JFR.start name=mini-card settings=profile duration=120s filename=mini-card.jfr
jcmd <pid> GC.heap_dump mini-card.hprof   # 可能很大/停顿；金融数据含 cardId/payload/token，按机密处理
jcmd <pid> VM.native_memory summary       # 需启动 -XX:NativeMemoryTracking=summary
rg 'http-nio|outbox-|delay-job-|billing-cycle|statement-job|notif-delivery|kafka|Hikari|GC Thread|CompilerThread' threads.txt
```

启动诊断参数（本地学习）：`-Xms512m -Xmx512m -XX:+UseG1GC -Xlog:gc*:file=logs/gc.log:... -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=./dumps`。**容器里别只设 `-Xmx` 等于容器内存**，JVM 还要 native memory（thread stack/Metaspace/Code Cache/direct buffer/GC internal）：用 `-XX:MaxRAMPercentage=70` 或显式 `-Xms/-Xmx`，保证 `Xmx + native + OS overhead < 容器内存 limit`。

---

## 6. 生产排查 Runbook

**先判断影响面**：单实例还是全部？所有 API 还是只 authorization/statement/repayment？症状是 latency/error/OOM/CPU/GC pause/consumer lag/DB lock？最近有无发布/配置/流量/schema 变化？**先采集低风险指标**（jvm.memory/gc.pause/threads.states、hikari pending、`Thread.print`、`request_started/completed` 日志、worker retry/DEAD、listener exception/DLT、risk timeout/breaker），**再采集 JVM 诊断**（heap_info、class_histogram、JFR；需要时 heap_dump）。

按症状分诊：

| 症状 | 切入 |
| --- | --- |
| Heap 涨 | Young GC 后是否回落、Full GC 后 old 是否回落、RPS/latency 是否同升、有无 DB lock/pending/Kafka lag/Outbox backlog。`heap_info`→`class_histogram`→`heap_dump`(dominator tree / retained size / 谁持有最多 byte[]/char[]/HashMap / 有无 ThreadLocalMap/unbounded queue/static cache) |
| Full GC 频繁 | heap 太小 / allocation rate 高 / old live set 大 / 大 JSON / 请求等待致晋升 / leak。看 GC log + `jvm.gc.pause` 与 p95/p99 是否同步 + old 是否 Full GC 后降 + thread dump 是否多线程卡 DB/HTTP/Kafka |
| Heap 不高但容器内存高 | direct buffer / Metaspace / thread stack / Code Cache / native lib。`VM.native_memory summary` + `jvm.buffer.memory.used` + `jvm.threads.live`（线程异常高→线程泄漏/重复创建 executor/client/连接池配错） |
| `OOM: heap space` | 留 heap dump + GC log；**别先盲目加 `-Xmx`**；先判尖峰/外部卡死/队列积压/真 leak |
| `OOM: unable to create native thread` | 非 heap 不够，是 native memory/OS thread limit。看 `jvm.threads.live`、是否重复创建 executor/Kafka/Feign client、容器 pids/ulimit。本项目 worker pool 有界（outbox/delay 各 4，scheduler/listener 固定），线程仍持续增长→怀疑新代码创建了未关闭的 executor/client |
| CPU 高 | 分清 GC CPU vs 业务 CPU：`top -H -p <pid>` + `Thread.print` + JFR。GC 占用高→回 GC/heap；业务线程高→找 stack；MySQL 慢则 Java CPU 不高但 latency 高 |
| 大量 `BLOCKED` | Java monitor lock（非 DB row lock）。thread dump 看 `waiting to lock <...>` 和 owner，查新增 synchronized/静态初始化/全局锁 |
| 大量 `WAITING`/`TIMED_WAITING` | 多为正常（idle worker、scheduler fixed delay、Kafka poll、Hikari housekeeper）；异常是大量 request thread 等 DB connection/外部 HTTP，或 worker 等 ack timeout |
| Hikari pending 高 | 慢 SQL/row lock/长事务/并发总量超 DB pool/外部调用进了事务。结合慢 SQL、lock wait、事务持有时间。本项目已把外部风控放在 account row lock 之前缩短关键区 |
| Outbox/DelayJob backlog | scheduler 卡 claim SQL（DB/索引/锁）vs worker 卡（Outbox→Kafka ack/network；DelayJob→业务 row lock/坏 job 重试）vs queue 满（publish/执行能力 < claim 速度） |
| Kafka consumer lag | listener 是否 DB 慢/backoff、DLT 是否增、GC pause、DB pool 是否被抢满。修复：优化 listener DB 写、确认 Inbox/唯一键无热点、调 concurrency（不超 partition/DB/CPU）、坏消息进 DLT 不阻塞 partition |
| Readiness 失败 | 先确认依赖 health，**不要立刻重启所有实例** |

---

## 7. interview 高频问答

**JVM 内存结构怎么讲**：heap（业务对象）、thread stack（方法调用/local reference）、metaspace（class metadata）、code cache（JIT native code）、direct/native memory（NIO/Kafka）。线上不能只看 heap，还看 RSS、线程数、direct buffer、GC pause。

**一次请求对象都在哪**：`/api/authorizations` 产生 `CreateAuthorizationRequest`→`AuthorizationCommand`→`Money`/`Authorization`/`RiskAssessmentRequest`→MyBatis row/domain→domain event→Outbox；主要在 heap，请求线程 stack 上的 local reference 存在时 GC 不能回收。

**请求量上升为何 GC 压力大**：RPS↑提高 allocation rate；耗时也↑则并发请求数按 `RPS×latency` 增长；处理中/等待 DB/外部的请求对象仍 reachable，Young GC 收不掉，进 Survivor/Old，触发更多 mixed/Full GC，表现为延迟抖动。

**怎么判 memory leak**：不看一次 heap used，看 GC 后趋势。短期锯齿正常；Full GC 后 old 长期不降且流量回落仍增长才像 leak；再用 class histogram/heap dump/dominator tree 找 retained size 最大对象和引用链。

**GC pause 会破坏交易一致性吗**：不会破坏 MySQL transaction 原子性、不丢已 commit 数据；但放大 latency→客户端 timeout、Kafka ack 变慢、scheduler 延后、Outbox/DelayJob backlog↑。所以 GC 指标要和业务积压指标一起看。

**为什么不靠 `synchronized` 保证授权并发**：JVM lock 只保护单进程内线程，不能跨多实例。授权靠 DB unique constraint 选 idempotency winner + `SELECT ... FOR UPDATE` 锁 credit account row，在一个 transaction boundary 内检查并更新额度。thread dump 仍重要，但并发正确性兜底在 MySQL 事务和约束。

**为什么 `RUNNABLE` 不等于吃 CPU / 为什么 row lock 不显示 `BLOCKED`**：JDBC socket read、native network I/O 在 Java 层也是 `RUNNABLE`；Java `BLOCKED` 专指等 Java monitor，MySQL row lock 在 DB 内部，线程在 JDBC/socket 栈等结果。排 row lock 结合 MySQL lock wait/slow query/Hikari pending。

**为什么 `@Scheduled` 不直接处理所有任务**：scheduler thread 应短小稳定；Outbox/DelayJob/StatementJob 先短事务 claim 成 PROCESSING lease，commit 后交 worker pool；Kafka ack/业务 row lock/银行扣款这些慢动作在 worker；worker 挂有 lease recoverer，queue 满 retry/DEAD，不让 scheduler 卡死或堆 heap。

**Outbox worker 和 DelayJob worker 区别**：Outbox worker 主要等 Kafka broker ack 再 finalize；DelayJob worker 进入具体业务 service 和业务表 row lock（如授权过期释放额度、自动还款）。都是 bounded pool，但风险不同（Kafka ack/backlog vs 业务锁/retry/DEAD）。

**G1/ZGC 怎么选**：Java 21 生产常从 G1 起（吞吐与暂停均衡）；低延迟可评估 ZGC，但不能只换 GC，还要看 allocation rate/对象生命周期/heap size/CPU/容器内存/业务 p99，用压测和 JFR/GC log 验证。

**ThreadLocal 为何易泄漏**：value 挂在线程的 ThreadLocalMap 上，Web 容器线程长期存活，请求结束不 remove 则大对象一直被线程引用，GC Roots 仍可达。

**Virtual threads 适合这里吗**：当前未启用。对大量阻塞 I/O 可能有帮助，但不会消除 DB row lock/连接池/Kafka partition/外部服务吞吐这些瓶颈；金融后台即使用也仍需 bounded DB pool、idempotency、row lock、timeout、bulkhead、可靠恢复。

---

## 8. 复习清单

- Heap/Young/Old/Metaspace/Thread Stack/Direct Memory 的区别；Java object 在 heap、local reference 在 stack；GC Roots 与 reachability。
- Young/Mixed/Full GC 基本过程；流量上升如何抬高 allocation rate 与 live set；为什么 latency 上升让短命对象活得更久；leak vs 正常 allocation spike。
- GC pause 对支付 API/Kafka/Outbox/DelayJob 的影响；Actuator/GC log/JFR/heap dump/thread dump 的用途。
- 启动后哪些线程是 Tomcat/scheduler/worker/Kafka listener；为什么 request thread 会被 DB/Feign/row lock 占住；`RUNNABLE` ≠ 吃 CPU；Java `BLOCKED` vs MySQL row lock waiting。
- scheduler 为什么薄；Outbox publish 为什么在 worker 而非主交易/scheduler；PROCESSING lease 如何应对 worker 宕机；Kafka listener 何时 ack、为何需要 Inbox。
- platform thread 与 OS native thread 的对应；线程过多为何造成 native memory/context switch 问题；heap 高/RSS 高/线程高/CPU 高/DB pool 高/Kafka lag 高分别怎么切入；JVM lock 与 DB row lock 的边界。

> **建议单独成文的并发主题**（本文不展开）：Java Memory Model / happens-before / visibility；`synchronized`/`volatile`/CAS/AQS；`ReentrantLock`/`Semaphore`/`CountDownLatch`/`CompletableFuture`；`ThreadPoolExecutor` 参数与拒绝策略；Spring `@Async`/scheduler/Kafka listener concurrency；Java 21 virtual threads 的收益与边界；以及"为什么本项目用 MySQL row lock 解决跨实例金融并发，而不是 JVM lock"。这与 `paypay-card-jd-alignment-review` 里建议补强的"并发/分布式理论深度"是同一块。
