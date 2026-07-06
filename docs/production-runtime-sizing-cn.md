# Production Runtime Sizing Notes

> 目标：从 Tomcat request threads 开始，讲清楚本项目当前线程/JVM配置、生产面对更多数据时怎么调，以及小/中/大数据量下可以用什么配置单作为起点。
>
> 这不是“背默认值”的文档。真正生产要用 load test、Actuator、Hikari metrics、Kafka lag、MySQL slow query/lock wait 和 GC log 验证。下面的数字是 starting point，不是 SLA 承诺。

## 1. 先看本项目当前是什么状态

本项目是 Java 21 + Spring Boot MVC + embedded Tomcat + MyBatis + MySQL + Kafka + Redis + Actuator。当前没有启用 virtual threads，所以主要是 Java platform thread。金融正确性靠 MySQL `transaction boundary`、unique constraint、`SELECT ... FOR UPDATE` row lock、Outbox/Inbox/DelayJob，而不是靠 JVM 本地锁。

当前静态配置可以分成两类：

| 配置面 | 当前项目状态 | 生产含义 |
| --- | --- | --- |
| Tomcat request threads | `application.yml` 没有显式 `server.tomcat.*` | 本地学习可以依赖 Spring Boot/Tomcat 默认值；生产应显式写出，避免升级后默认值变化或团队误解 |
| Hikari DB pool | `application.yml` 没有显式 `spring.datasource.hikari.*` | Hikari 常见默认 `maximumPoolSize=10`，本地够用；生产必须按实例数和 DB 承载能力算总连接 |
| JVM heap/GC | 没有提交生产 `JAVA_TOOL_OPTIONS` 或 `jvmArgs` | 运行时会由 JDK/IDE/容器默认 ergonomics 决定；生产容器必须显式限制 heap、打开 GC log |
| Outbox scheduler | `outboxTaskScheduler = 1` | 只是薄 trigger，负责 poll/recover；真正 publish 在 worker pool |
| DelayJob scheduler | `delayJobTaskScheduler = 2` | poller/recoverer 分摊调度线程；业务处理在 worker pool |
| Billing cycle scheduler | `billingCycleTaskScheduler = 1` | 每天创建 statement jobs，不直接跑全部账户出账 |
| Statement job scheduler | `statementJobTaskScheduler = 2` | dispatch/recover 调度线程；真正生成账单在 worker pool |
| Outbox worker | `worker-pool-size=4`, `queue=100`, `batch-size=50` | Kafka publish 并发有限、有 queue 背压 |
| DelayJob worker | `worker-pool-size=4`, `queue=100`, `max-per-run=100` | 授权过期、自动还款等 future business action 有独立 worker |
| Statement job worker | `worker-pool-size=4`, `queue=100`, `max-per-run=8`, `target-accounts-per-job=1000` | 出账按 durable jobs 分片，不把百万账户塞进一个事务；max-per-run 取 2x pool，避免排队 job 白烧 300s lease |
| Kafka topics | 每个主要 topic `partitions=3`, local replica=1 | 本地演示 consumer concurrency；生产要提高 replicas 和 partitions |
| Kafka listener concurrency | notification=2, risk=3, ledger=2（`messaging.consumers.*.concurrency`） | concurrency 不能超过 topic partitions 后继续有效扩展 |
| Actuator | 暴露 `health/info/metrics`，不暴露高风险 endpoint | 适合生产基础监控；heapdump/env 等不应公开 |

> [!IMPORTANT]
> 当前 2026-06-27 本机 `localhost:8080` 没有应用进程监听，所以这份文档没有采集新的 live `jcmd VM.flags` / thread dump。仓库已有 2026-06-21 的历史实测：IDEA 启动时 Java 21.0.11、G1GC、initial heap 约 256 MiB、max heap 约 4 GiB、一次授权后 live threads 约 97。那是本地样本，不是生产配置。

## 2. Tomcat threads 要和 DB pool 一起看

Tomcat request thread 是同步 Spring MVC 请求的入口。例如：

```text
request A -> http-nio-8080-exec-1 -> AuthorizationController
          -> AuthorizationService
          -> MyBatis/Hikari/MySQL
          -> Outbox row
          -> HTTP response
```

如果 Tomcat `max-threads=300`，但 Hikari `maximum-pool-size=20`，那么最多只有 20 个请求能同时拿到 DB connection，其余 request threads 会等连接。表现是：

- Tomcat busy threads 高。
- `hikaricp.connections.pending` 高。
- HTTP p95/p99 上升。
- CPU 不一定高，因为很多线程在等 DB connection、row lock、socket I/O。

所以 Tomcat 不是越大越好。它应该大到能吸收短时网络抖动和少量慢请求，但不能大到把后端 DB、Kafka、Redis 压穿。

一个更实用的关系是：

```text
Tomcat max threads
  >= 同时处理的 HTTP 请求数
  > Hikari connections
  但不能远大于所有下游资源的可承载并发

Hikari total connections
  = app instance count * per-instance maximum-pool-size
  必须小于 MySQL/RDS 可承载连接数，并给 migration、admin、batch 留余量
```

## 3. 当前项目建议先显式写出的生产配置

下面不是要求立刻改 `application.yml`，而是生产部署时应该通过 profile/env 显式声明的核心配置面。

```yaml
server:
  tomcat:
    threads:
      max: 160
      min-spare: 20
    accept-count: 100
    max-connections: 4096
  connection-timeout: 3s

spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      minimum-idle: 10
      connection-timeout: 1000
      validation-timeout: 1000
      idle-timeout: 600000
      max-lifetime: 1800000
  kafka:
    producer:
      acks: all
      properties:
        enable.idempotence: true
        delivery.timeout.ms: 10000
        request.timeout.ms: 3000
    listener:
      ack-mode: record
  cloud:
    openfeign:
      client:
        config:
          external-risk:
            connect-timeout: 300
            read-timeout: 800

outbox:
  publisher:
    batch-size: 50
    worker-pool-size: 4
    worker-queue-capacity: 100

delay-jobs:
  scheduler:
    max-per-run: 100
    worker-pool-size: 4
    worker-queue-capacity: 100

statement:
  batch:
    target-accounts-per-job: 1000
  jobs:
    # max-per-run 保持约 2x worker-pool-size：claim 即开始烧 PROCESSING lease，
    # 领得远多于线程数时，队尾 job 可能没开跑就被 recoverer 收回重发（幂等无害但白干）。
    max-per-run: 8
    worker-pool-size: 4
    worker-queue-capacity: 100

resilience4j:
  bulkhead:
    instances:
      externalRisk:
        max-concurrent-calls: 4
        max-wait-duration: 0
```

JVM 容器起点：

```bash
JAVA_TOOL_OPTIONS="
  -XX:+UseG1GC
  -XX:MaxRAMPercentage=65
  -XX:InitialRAMPercentage=65
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/dumps
  -Xlog:gc*:file=/logs/gc.log:time,uptime,level,tags
"
```

如果 task/container memory 是 2 GiB，`MaxRAMPercentage=65` 大约给 heap 1.3 GiB。剩余空间留给 thread stack、metaspace、code cache、direct buffer、Kafka/Netty/native memory、OS overhead。

> [!WARNING]
> 不要把 `-Xmx` 设置成等于容器 memory limit。heap 只是 JVM 进程的一部分。线程越多，thread stack 和 native memory 也越多；Kafka client、TLS、NIO direct buffer 也会吃 heap 外内存。

## 4. 小 / 中 / 大配置单

这里用“数据量 + 流量 + 依赖规模”一起分层。信用卡系统不是只看 account rows，也要看 authorization TPS、statement batch、Outbox backlog 和 Kafka consumer lag。

### 4.1 Small：学习项目到小生产

适用场景：

- 1 万到 10 万 cards/accounts。
- authorizations 峰值 5 到 50 RPS。
- statement/repayment 批处理可以在低峰慢慢跑。
- 目标是稳定、便宜、容易排查。

| 配置项 | 推荐起点 | trade-off |
| --- | ---: | --- |
| App instances | 2 | 至少跨 AZ/节点；比 1 个实例更接近生产，但成本增加 |
| Container/task | 1 vCPU / 2 GiB | Spring Boot + Kafka + Redis client 比纯 HTTP 服务更吃内存 |
| JVM | G1, heap 1.0-1.3 GiB | GC 简单稳定；heap 太小会频繁 young GC |
| Tomcat `max-threads` | 80-120 | 足够应付轻量峰值；过大只会让请求在 DB 前排队 |
| Tomcat `accept-count` | 50-100 | 短峰可排队；太大让客户端等太久，不如快速失败 |
| Hikari per instance | 10-15 | 两个实例总连接 20-30，适合小 RDS/MySQL |
| Outbox worker | 2-4, queue 100 | Kafka publish 足够；queue 不宜太大，避免 backlog 被藏在内存 |
| DelayJob worker | 2-4, queue 100 | 授权过期、自动还款可以异步慢跑 |
| Statement worker | 2-4, queue 100 | 低峰出账即可；不用为了月末一次任务把常驻成本拉高 |
| Kafka partitions | 3 | 与当前项目一致，方便本地到生产理解 |
| Kafka listener concurrency | 1-3 | 不超过 partitions；先看 lag 再加 |
| Redis | 小规格，maxmemory + eviction | 只缓存可重建 read model，不承载资金正确性 |
| MySQL/RDS | 2 vCPU / 4-8 GiB 起 | 优先看 slow SQL、lock wait、buffer pool 命中 |

Small 环境 YAML 片段：

```yaml
server:
  tomcat:
    threads:
      max: 100
      min-spare: 10
    accept-count: 100
    max-connections: 2048

spring:
  datasource:
    hikari:
      maximum-pool-size: 12
      connection-timeout: 1000

outbox.publisher:
  worker-pool-size: 4
  worker-queue-capacity: 100

delay-jobs.scheduler:
  worker-pool-size: 4
  worker-queue-capacity: 100

statement.jobs:
  max-per-run: 8
  worker-pool-size: 4
  worker-queue-capacity: 100
```

### 4.2 Medium：有明显日常流量和批处理压力

适用场景：

- 10 万到 100 万 cards/accounts。
- authorization 峰值 100 到 500 RPS。
- statement jobs、Outbox、Kafka consumer lag 已经需要监控和告警。
- API 和 batch 可能开始需要分离部署。

| 配置项 | 推荐起点 | trade-off |
| --- | ---: | --- |
| App instances | 3-6 | 横向扩展 HTTP 和 consumer；但会放大 DB total connections |
| Container/task | 2 vCPU / 4 GiB | 更适合 Spring MVC + Kafka listener + worker pools |
| JVM | G1, heap 2.0-2.6 GiB | 比 Small 更少 GC；仍保留足够 native memory |
| Tomcat `max-threads` | 150-250 | 可承接更多并发；必须配合 timeout 和 rate limit |
| Tomcat `accept-count` | 100-200 | 短时排队；过大导致 p99 难看 |
| Hikari per instance | 20-30 | 6 个实例就是 120-180 连接，必须确认 MySQL/RDS |
| Outbox worker | 4-8, queue 200 | 提高 publish 并发；Kafka ack 慢时会占线程 |
| DelayJob worker | 4-8, queue 200 | 自动还款/过期释放并发提高；注意 row lock 和 bank gateway timeout |
| Statement worker | API 内 4-8；独立 batch service 可 8-12 | 出账会碰 account rows，最好和 API 服务隔离 |
| Kafka partitions | 6-12 | 给 consumer group 扩展空间；partition 过多也增加 broker metadata 和 rebalancing 成本 |
| Kafka listener concurrency | 每实例 2-4 | 全组总 concurrency 接近 partitions 即可 |
| Redis | 主从/托管 Redis，明确 timeout | Redis 故障应降级为 DB 回源或 risk fail-open/fail-closed 策略 |
| MySQL/RDS | 4-8 vCPU / 16-32 GiB | 重点优化索引、短事务、lock ordering，再扩 pool |

Medium 环境 YAML 片段：

```yaml
server:
  tomcat:
    threads:
      max: 200
      min-spare: 30
    accept-count: 150
    max-connections: 4096
  connection-timeout: 3s

spring:
  datasource:
    hikari:
      maximum-pool-size: 25
      minimum-idle: 10
      connection-timeout: 800
      leak-detection-threshold: 3000

outbox.publisher:
  batch-size: 100
  worker-pool-size: 8
  worker-queue-capacity: 200

delay-jobs.scheduler:
  max-per-run: 200
  worker-pool-size: 8
  worker-queue-capacity: 200

statement:
  batch:
    target-accounts-per-job: 1000
  jobs:
    max-per-run: 16
    worker-pool-size: 8
    worker-queue-capacity: 200
```

> [!TIP]
> Medium 阶段最有价值的架构动作通常不是“把 Tomcat 开到 500”，而是把 API service 和 batch/consumer-heavy service 分开部署。代码可以是同一个 artifact，不同 profile 开关不同 scheduler/listener。这样 statement 月末出账不会抢授权 API 的 CPU、DB connections 和 worker queue。

### 4.3 Large：百万到千万级账户，持续高峰

适用场景：

- 100 万到 1000 万+ cards/accounts。
- authorization 峰值 500 到 2000+ RPS。
- statement batch 是真正的离线/准实时生产作业。
- Kafka、DB、Redis、应用都需要独立容量规划和压测。

| 配置项 | 推荐起点 | trade-off |
| --- | ---: | --- |
| API instances | 8+ | 水平扩展入口；要靠 DB row lock/idempotency 保护跨实例一致性 |
| API container/task | 4 vCPU / 8 GiB | 能承载更多 Tomcat/Kafka/Hikari/native memory |
| JVM | G1 heap 4-5 GiB；低延迟场景评估 ZGC | 大 heap 降低 GC 频率，但可能增加 pause 和 dump 成本 |
| Tomcat `max-threads` | 250-400 | 大量阻塞 I/O 时有用；高于下游能力会制造内部排队 |
| Tomcat `accept-count` | 100-300 | 只吸收短峰；系统过载时应配合 ALB/API gateway 快速失败 |
| Hikari per API instance | 30-50 | 8 个实例就是 240-400 连接；不能超过 RDS/Proxy 设计 |
| Outbox worker | 8-16, queue 300-500 | publish backlog 更快排空；Kafka broker 慢时仍要限流 |
| DelayJob worker | 8-16, queue 300-500 | bank/external gateway 必须有 timeout、bulkhead、circuit breaker |
| Statement workers | 独立 batch service 16-64 总 worker | 不建议和授权 API 混跑；按账户 shard、DB lock wait、batch window 调整 |
| Kafka partitions | 24-96 | 给多实例 consumer group 扩展；单 aggregate 顺序仍靠 key |
| Kafka replicas | 3 | 生产基础；local 的 replica=1 只适合学习 |
| Redis | Cluster 或托管 HA | 热 key、TTL jitter、stampede control 必须监控 |
| MySQL/RDS | 16+ vCPU，多 AZ，必要时读写拆分/分库策略 | 核心写一致性仍在主库；读扩展不能解决 row lock 热点 |

Large API 环境 YAML 片段：

```yaml
server:
  tomcat:
    threads:
      max: 300
      min-spare: 50
    accept-count: 200
    max-connections: 8192
  connection-timeout: 2s

spring:
  datasource:
    hikari:
      maximum-pool-size: 40
      minimum-idle: 20
      connection-timeout: 500
      validation-timeout: 500
      leak-detection-threshold: 2000

outbox.publisher:
  batch-size: 200
  worker-pool-size: 12
  worker-queue-capacity: 300

delay-jobs.scheduler:
  max-per-run: 300
  worker-pool-size: 12
  worker-queue-capacity: 300

statement.jobs:
  enabled: false # API profile 不跑 statement dispatcher，避免月末 batch 抢授权 API 资源。
```

Large batch service 起点：

```yaml
server:
  tomcat:
    threads:
      max: 50

spring:
  datasource:
    hikari:
      maximum-pool-size: 40

statement:
  batch:
    target-accounts-per-job: 2000
  jobs:
    max-per-run: 32
    worker-pool-size: 16
    worker-queue-capacity: 500
```

API profile 关闭 `statement.jobs.enabled` 后，batch service 再用单独 profile 打开 dispatcher 和 worker。这样比把 worker pool 调到很小更清晰，因为“这个进程不负责这类任务”是部署职责，不是线程数问题。

## 5. 关键 trade-off

### 5.1 Tomcat threads：多接请求 vs 更早背压

加大 Tomcat threads 的收益：

- 短时慢 I/O 下，不会太快耗尽 request worker。
- 有些请求只读 cache 或很快返回，能和慢 DB 请求并行。

代价：

- 每个 platform thread 都有 stack，增加 native memory。
- 下游 DB pool、Redis、Kafka 慢时，更多线程只是一起等待。
- p99 会从“快速失败”变成“排队很久后失败”。

生产答法：

> 我会让 Tomcat threads 足够覆盖目标 in-flight HTTP 请求，但不会让它远大于 DB/Kafka/外部服务可承载并发。过载时靠 timeout、rate limit、bulkhead 和有限 accept queue 背压，而不是无限接请求。

### 5.2 Hikari pool：更多 DB 并发 vs 压垮 MySQL

加大 Hikari 的收益：

- 减少 `connections.pending`。
- 慢 SQL 比例不高时，可以提升吞吐。

代价：

- 多实例下总连接数线性放大。
- MySQL 线程、buffer、lock contention 增加。
- 如果根因是慢 SQL 或长事务，加 pool 只是把更多压力推给 DB。

生产答法：

> Hikari pending 高时我不会只加 pool。我会先看 SQL latency、lock wait、transaction length 和 MySQL CPU。如果连接被长事务占住，要缩短 `transaction boundary`；如果 DB 还有余量，再逐步加 pool。

### 5.3 Worker pool：更快清 backlog vs 隐藏拥塞

Outbox、DelayJob、Statement jobs 都是 bounded worker pool。这样做的目的不是让队列永远不满，而是让压力显性化。

加大 worker 的收益：

- Outbox publish backlog 更快下降。
- DelayJob due jobs 更快执行。
- Statement batch window 更短。

代价：

- Kafka ack、DB row lock、外部 bank/risk gateway 会成为新瓶颈。
- queue 太大时，内存里堆很多 Runnable，报警反而变晚。
- worker 太多会和 Tomcat request threads 抢 CPU/DB connections。

生产答法：

> 我会把 worker queue 设成有限容量，让 backlog 主要留在数据库/Kafka 这种 durable system 里，而不是藏在 JVM 内存。queue 满时走 retry/lease recovery，比 OOM 更可控。

### 5.4 Kafka partitions/concurrency：并行消费 vs 顺序和运维成本

提高 partitions 的收益：

- 同一个 consumer group 可以有更多并发。
- backlog 可以更快消费。

代价：

- 单个 key 的顺序仍然只能在一个 partition 内保证。
- partitions 太多会增加 broker metadata、rebalance、文件句柄和监控成本。
- concurrency 超过 partitions 后会空闲，不会继续提高吞吐。

本项目的关键点：

- authorization event key 应保证同一 authorization 的事件进入同一 partition。
- statement/repayment 这种按账户顺序更敏感的事件，key 应优先考虑 `creditAccountId`。
- Consumer Inbox 仍然必须存在，因为 Kafka retry/duplicate delivery 无法和 MySQL 本地事务合成一个原子提交。

### 5.5 JVM heap：更大内存 vs 更长诊断和 GC成本

加大 heap 的收益：

- 缓解 allocation spike。
- 减少 young GC 频率。
- 给 Caffeine L1 cache、JSON 序列化、批处理对象更多空间。

代价：

- heap dump 更大，事故时下载/分析更慢。
- 如果 old gen 累积，GC pause 和回收成本可能上升。
- 容器总内存不变时，heap 过大挤压 native memory，可能触发 container OOM。

生产答法：

> 我会先看 allocation rate、GC pause p95/p99、old gen after GC、RSS 和 thread count。heap 不够才加；如果是 unbounded queue、cache 或 ThreadLocal 泄漏，加 heap 只是推迟事故。

## 6. 数据量变大时，本项目优先调哪里

### 6.1 Authorization hot path

优先顺序：

1. 保持 external risk 在 account row lock 之前，避免 request A 持有账户锁等待外部 I/O。
2. 用 OpenFeign timeout 限制 external risk 单次等待时间。
3. 用 Resilience4j semaphore bulkhead 限制事务内 external risk 并发，`max-concurrent-calls < Hikari maximumPoolSize - headroom`。
4. 保持 idempotency unique constraint，让 duplicate request 只等待/读取 winner 状态。
5. `card_risk_features` long-window profile 会给通过 cheap rules 的授权增加一次按主键 DB read；
   Hikari pending 高时要把它和 external risk latency/bulkhead、MyBatis SQL 一起看。
6. Tomcat busy 高但 Hikari pending 不高时，看 external risk latency、Redis timeout、JSON serialization 和 CPU。
7. Outbox backlog 高时，调 Outbox worker/Kafka，而不是把 Kafka publish 放回主事务。

当前项目选择的是 Path B：保留单事务授权语义，用 timeout + semaphore bulkhead 夹住外部风控 brownout 的爆炸半径。
更彻底的 Path A 是两阶段 authorization：短事务 claim PENDING，事务外 risk，再用 owner token/lease 二次 finalize。
Path A 可以让风控完全不占 DB connection，但要重新设计 duplicate loser 的同步等待、PROCESSING/lease/recover 语义。
因此它应该由 `risk.external.latency`、`risk.external.fallback`、`risk.external.bulkhead.rejected`
和 Hikari pending 的实测结果触发，而不是为了“事务外调用”这个原则先行重构。

### 6.2 Statement batch

当前 `target-accounts-per-job=1000` 是学习项目的清晰起点。数据量变大时：

- 不要把一个 job 改成处理几十万账户。
- 提高 shard/job 数量，让失败可重试、可观察、可局部恢复。
- 先分离 batch service，再加 statement worker。
- 看每个 account 出账耗时、DB lock wait、statement line insert batch、job failure ratio。

建议：

| 账户量 | `target-accounts-per-job` | worker 总量 | 说明 |
| ---: | ---: | ---: | --- |
| 10 万 | 1000 | 4-8 | 约 100 个 jobs，低峰跑完即可 |
| 100 万 | 1000-2000 | 16-32 | 独立 batch service，观察 DB write pressure |
| 1000 万 | 2000-5000 | 64+ 分批/分区 | 需要更完整的 batch platform、分区表和作业编排 |

### 6.3 Outbox/Inbox/Kafka

数据量变大后，Outbox 常见瓶颈不是 Java CPU，而是：

- Kafka broker ack 慢。
- Outbox claim SQL 慢。
- `PROCESSING` lease 超时恢复太频繁。
- consumer group lag 高。
- DLT 没有及时处理。

调参顺序：

1. 先看 Outbox table index、claim SQL、batch size。
2. 再看 Kafka producer timeout、broker latency、topic partitions。
3. 再加 Outbox worker。
4. 最后才考虑拆 topic、拆 publisher service。

## 7. 生产必须有的观测清单

HTTP/Tomcat：

- `http.server.requests` p50/p95/p99 by URI/status。
- Tomcat busy/current threads，如果当前环境没有暴露，就用 `jcmd Thread.print` 看 `http-nio-*`。
- 4xx/5xx、timeout、client abort。

DB/Hikari：

- `hikaricp.connections.active`
- `hikaricp.connections.pending`
- `hikaricp.connections.timeout`
- connection acquire time。
- MySQL slow query、lock wait、deadlock、buffer pool hit ratio。

External risk：

- `risk.external.latency` by outcome。
- `risk.external.fallback` by reason。
- `risk.external.bulkhead.rejected`。
- Resilience4j circuit breaker state/open count。
- bulkhead saturation 与 Hikari pending 是否同时上升。

Risk velocity：

- `risk.velocity.redis.unavailable`。
- `risk.velocity.fallback.allow` by source。
- 短时间连续 `fallback.allow` 要报警；这表示 velocity 正在按显式 fail-open policy 放行。
- 这个报警和 external risk 告警语义不同：external risk 是最终风险判定依赖，当前 fail-closed；
  Redis velocity 是辅助高频信号，当前 fail-open，但必须可观测。

Risk feature projection：

- `card_risk_features` read latency / error rate。
- projection freshness：`now - last_decision_at` 的分布。
- historical profile decline count。
- Hikari pending 是否随 projection read QPS 上升。生产上可考虑 read replica 或 profile cache。

JVM：

- heap used、old gen after GC、metaspace、non-heap。
- `jvm.gc.pause` p95/p99。
- thread count、blocked/waiting/timed-waiting。
- GC log、JFR、heap dump 开关。

Kafka/async：

- producer send latency、error rate。
- consumer lag by group/topic/partition。
- Outbox PENDING/PROCESSING/DEAD count。
- DelayJob PENDING/PROCESSING/DEAD count。
- StatementJob PENDING/PROCESSING/FAILED/DONE count。
- DLT message count 和 replay runbook。

Business：

- authorization approval/decline rate。
- idempotency conflict/replay count。
- row lock wait hotspots by account/card。
- statement batch completion time。
- repayment success/failure/retry rate。

## 8. 压测验证方式

不要只压一个 `/api/health`。本项目至少要压这些路径：

| 场景 | 为什么压它 | 重点看 |
| --- | --- | --- |
| `POST /api/authorizations` 正常批准 | 最核心写路径 | Tomcat, Hikari, row lock, external risk, Outbox |
| 同一个 `Idempotency-Key` 重试 | duplicate request | unique constraint, replay latency |
| 同一 account 并发授权 | row lock 热点 | lock wait, p99, decline correctness |
| external risk 变慢/不可用 | brownout 防护 | timeout, bulkhead rejection, fallback, Hikari headroom |
| Kafka 暂停/变慢 | Outbox recovery | Outbox backlog, PROCESSING lease, retry |
| Redis 变慢/不可用 | risk velocity/cache degradation | timeout, fallback allow metric, DB pressure |
| statement batch 大量账户 | 批处理 | job shard, worker queue, DB write pressure |

最小压测命令思路：

```text
1. 固定 10 分钟 normal load，确认 p95/p99、GC、Hikari pending 稳定。
2. 提高到 2x peak，观察是否开始排队。
3. 注入 Kafka/Redis/DB 慢依赖，确认 timeout 和 backlog 行为。
4. 压同一 account，确认 row lock 让状态正确，而不是靠 JVM lock。
5. 压完后检查 Outbox/Inbox/DelayJob/StatementJob 是否有 DEAD 或长期 PROCESSING。
```

## 9. interview 回答模板

### Q1: 这个项目当前 Tomcat 和 JVM 怎么配？

> 当前项目没有显式提交生产级 `server.tomcat.*` 和 `JAVA_TOOL_OPTIONS`，所以本地运行主要依赖 Spring Boot/Tomcat/JDK 默认 ergonomics。项目显式配置的是后台执行资源：Outbox scheduler 1 线程、DelayJob scheduler 2 线程、BillingCycle scheduler 1 线程、StatementJob scheduler 2 线程；Outbox/DelayJob/Statement job worker 默认都是 4 线程、queue 100。生产我会显式配置 Tomcat threads、Hikari pool、JVM heap/GC log，并用 Actuator 和 `jcmd` 验证。

### Q2: 为什么不直接把 Tomcat threads 开很大？

> 因为 Tomcat threads 只是入口并发。授权请求真正会碰 Hikari connection、MySQL row lock、external risk、Outbox/Kafka。Tomcat 开很大但 DB pool 很小，只会让更多线程等连接，p99 变差。生产要把 Tomcat、Hikari、worker pool、Kafka concurrency 和 DB capacity 一起调。

### Q3: 数据量从小到大最先改什么？

> 小流量先显式配置和监控；中等流量先分离 API 和 batch/consumer-heavy service，控制 Hikari total connections；大流量要提高 Kafka partitions、独立 statement batch workers、优化 DB 索引和事务边界。资金一致性仍靠 idempotency、row lock 和 transaction boundary，不靠单 JVM 内存状态。

### Q4: JVM heap 应该怎么估？

> 容器里不要让 heap 等于 memory limit。我通常给 heap 60%-70%，剩下留给 thread stack、metaspace、direct buffer、Kafka client、native library 和 OS。然后看 GC pause、old gen after GC、RSS、thread count 和 heap dump/JFR，而不是只盯 `Xmx`。

## 10. 一句话总结

生产配置不是“Tomcat 线程越多、DB pool 越大、heap 越大越好”。对本项目这种金融后端，更正确的调参顺序是：

```text
明确 transaction boundary
  -> 缩短 row lock 时间
  -> 显式 Tomcat/Hikari/JVM/worker/Kafka 配置
  -> 用 bounded queue 和 timeout 做背压
  -> 用 Actuator + DB/Kafka/Redis 指标验证
  -> 再按 small/medium/large 逐步扩容
```
