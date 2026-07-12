# 流量、限流与容量瓶颈学习手册

> 关键词：流量，生产速率，消费速率，调用速率，吞吐，延迟，并发，排队，背压，
> rate limiting，throughput，latency，concurrency，queueing，backpressure，
> semaphore，bulkhead，circuit breaker，Little's Law。

这份文档把当前项目中分散在 HTTP、Redis、MySQL、Kafka、Outbox、DelayJob、Statement、
Notification 和 Resilience4j 里的速率配置放到同一张图中，重点回答：

1. 每条线路每秒能生产多少、领取多少、真正处理多少？
2. 哪些是我们主动设置的保护闸门，哪些是流量上来后被动暴露的瓶颈？
3. 当前默认值是否彼此匹配，QPS 放大后最先在哪里排队？

> [!IMPORTANT]
> 本文数字是对当前代码和 `application.yml` 的静态容量分析，不是压测结果或 SLA。
> 凡是写成 `≈` 的吞吐都依赖真实延迟；上线前必须用指标和压测验证。

---

## 1. 先分清五个容易混淆的概念

| 概念 | 回答的问题 | 项目例子 |
| --- | --- | --- |
| rate / QPS | 单位时间允许多少次 | API 每 IP 持续 `10 req/s`；通知每渠道 `20 calls/s` |
| concurrency | 同一时刻允许多少个在执行 | external risk semaphore `4`；worker pool `4` |
| queue capacity | 执行资源满后最多暂存多少 | 各 worker queue 默认 `100` |
| timeout | 一次占用资源最长等多久 | risk read timeout `800ms`；notification `2s` |
| circuit breaker | 下游近期不健康时是否继续调用 | externalRisk / notification / bankDebit OPEN `10s` |

它们不能互相替代：

- `RateLimiter` 控制频率，不保证并发小。20 calls/s 的请求若每次耗时 2 秒，仍可能需要 40 并发。
- `Semaphore/Bulkhead` 控制并发，不保证每秒速率。4 并发、每次 10ms 时理论上可达约 400 calls/s。
- `Thread pool` 既是执行资源也是并发上限，但不是精确 provider quota。
- `Queue` 只把等待移进内存，不增加处理能力；队列越大，过载时延迟越长。
- `CircuitBreaker` 不是限流器。它根据失败/慢调用历史快速失败，保护故障下游。
- `Retry` 不是免费可靠性。一次上游请求最多制造多次下游调用，会放大流量。

最常用的粗估公式：

```text
并发数 L ≈ 吞吐 λ × 平均耗时 W              (Little's Law)

并发闸门可支持的吞吐 ≈ concurrency / 平均耗时

一条 pipeline 的稳定吞吐
≈ min(入口允许速率, 每级 worker 能力, 下游 quota, DB/Kafka/Redis 能力)
```

例如 external risk 有 4 个 permit：

```text
正常模拟延迟 100ms：4 / 0.1s ≈ 40 calls/s/Pod
接近 read timeout 800ms：4 / 0.8s ≈ 5 calls/s/Pod
```

这只是并发与耗时推导的上界，不包含 Redis、MySQL、序列化和线程调度成本。

---

## 2. 当前单实例资源总表

### 2.1 HTTP、数据库和 JVM 入口

项目现在显式采用“单实例本地学习 / 小项目”配置；virtual threads 仍未开启：

| 资源 | 当前值 | 类型 | 评价 |
| --- | ---: | --- | --- |
| Tomcat max threads | `80` | 被动并发边界 | 小项目主动收窄，避免默认 200 个线程在 10 个 DB 连接前深排队 |
| Tomcat min spare | `10` | 预热线程 | 保留少量热线程 |
| Tomcat max connections | `2048` | 已接受连接上限 | 80 workers 之外仍可持有大量等待连接，不是业务吞吐 |
| Tomcat accept count | `50` | OS listen backlog | max-connections 满后才参与，再满才拒绝新连接 |
| Tomcat connection timeout | `3s` | 等 request line | 防已接受连接迟迟不提交 HTTP request line；不是 controller 总 timeout |
| Tomcat keep-alive timeout | `30s` | 空闲连接复用 | 显式与 3s 解耦；生产必须和 LB idle timeout 协调 |
| Hikari maximum/minimum idle | `10 / 5` | DB 并发边界 | 当前最重要的共享被动瓶颈，现已显式写出 |
| Hikari connection timeout | `1s` | DB 等待上限 | 池耗尽后快速失败，不沿用默认 30s 深排队 |
| Hikari validation timeout | `500ms` | 连接校验等待 | 给 1s 获取连接总预算保留余量 |
| Hikari idle/max lifetime | `10m / 30m` | 连接生命周期 | 小项目稳定起点，生产需小于 DB/LB 强制断连时间 |
| Virtual threads | 未开启 | 线程模型 | 当前都是 platform thread；即使开启也不会增加 DB/锁/下游容量 |

一个实例内可能同时产生 DB 需求的执行单元远多于 10：

```text
Tomcat request threads                 最多 80
Kafka listener threads                 当前最多 15
Outbox workers                         4
DelayJob DB workers                    4
Auto-repayment workers                 4
Statement workers                      4
Notification delivery workers          4
                                      ──
后台 worker 合计                       20
```

Kafka 的 15 不是一个全局 concurrency 值，而是当前 listener containers 的上限合计：

```text
Notification: 4 个 listener × concurrency 2 = 8
Risk feature: 1 × 3 = 3
Ledger:       2 × 2 = 4
合计最多 15 个 consumer threads
```

不同 listener 订阅不同 topic，因此不能把 `notification.concurrency=2` 误读成整个 Notification
只有两个线程。每个 topic 仍受 3 partitions 上限约束。

> [!WARNING]
> 80 个 Tomcat 线程、15 个 consumer 线程和 20 个 worker 并不等于系统能同时做 115 次 DB 工作。
> 它们最终共享约 10 个 Hikari connection。连接池满后，CPU 可能不高，但 p99、consumer lag 和
> job backlog 会同时上升。

Tomcat 过载时要按四层理解，不能把 `accept-count` 当成业务 request queue：

```text
1. threads.max=80
   → 最多 80 个 request workers 真正执行 controller；只有这些线程会进入 Hikari 等待。

2. max-connections=2048
   → Tomcat 仍可接受并持有更多连接；未拿到 worker 的请求停在 connector/executor 层，
     尚未开始 Hikari connection-timeout 倒计时。

3. accept-count=50
   → 只有 max-connections 已满时，才作为 OS listen backlog；再满后新连接才可能被拒绝。

4. 业务背压
   → authorization token bucket 主动返回 429；已进入业务但 DB pool 耗尽则在 1s 后返回 503。
```

#### 小项目与生产大流量推荐起点

下面的“大流量”是假设 API/consumer/batch 已开始分开部署、API Pod 约 `4 vCPU / 8 GiB`、
至少 8 个实例的起始区间，不是所有生产系统通用答案：

| 配置面 | 当前小项目推荐 | 生产大流量起点 | 约束条件 |
| --- | ---: | ---: | --- |
| Tomcat max threads / API Pod | `80` | `200~300` | 阻塞 MVC 起点；不能远超 DB/外部依赖承载能力 |
| Tomcat accept count | `50` | `100~200` | 仅在 max-connections 满后作为 OS backlog；太大可能延迟明确拒绝 |
| Tomcat max connections | `2048` | `4096~8192` | socket 容量，不等于业务 QPS |
| Tomcat connection timeout | `3s` | `2~5s` | 只等待 request line，不是业务 timeout |
| Tomcat keep-alive timeout | `30s` | 示例 `65s` | 若 LB idle=60s，后端略大于 LB，避免复用竞态产生间歇 502 |
| Hikari / API Pod | `10` | `20~30` | 例如 8 Pods × 25 = 200，必须纳入 RDS 总连接预算 |
| Hikari minimum idle | `5` | `10~15` | 不必让所有潜在连接永久常驻 |
| Hikari connection timeout | `1000ms` | `500~1000ms` | 必须短于 HTTP SLO；consumer/batch 共池时还要评估 retry→DLT |
| Hikari validation timeout | `500ms` | `250~500ms` | 小于获取连接预算，避免 aliveness check 吃满预算 |
| externalRisk permits / API Pod | `4` | `8~12` | 只有 provider quota、Hikari headroom 和压测都支持才增加 |
| Kafka partitions / hot topic | `3` | `24~48` 起步 | 按峰值 event rate、单 partition 实测吞吐、顺序 key 决定 |
| consumer concurrency / Pod | `2~3` | `3~6` | 全 group 总 concurrency 不必超过 partitions |
| Outbox workers / Pod | `4` | `8~16` | Kafka ack 与 DB finalize 都必须有余量 |
| Statement workers | `4` 同进程 | 独立部署总计 `16~32` | 以 batch window、每账户耗时和 DB lock wait 校准 |

生产连接预算示例：

```text
8 API Pods × 25 connections       = 200
4 consumer Pods × 15 connections  = 60
2 batch Pods × 20 connections     = 40
migration/admin/headroom           = 30
总预算                             = 330
```

只有 RDS/RDS Proxy、MySQL CPU/IO 和 slow-query/lock-wait 压测都能承受时，这组数字才成立。
如果 API、consumer、batch 仍混在同一 Pod，就不能把三份连接预算简单叠加到一个 pool。

异步与限流的两档起点：

| 配置面 | 当前小项目推荐 | 生产大流量示例 | 前提 |
| --- | ---: | ---: | --- |
| API limiter / authenticated client | burst `20`，持续 `10/s` | burst `100`，持续 `50/s` | 必须来自 client SLA/merchant tier，并另设系统全局保护 |
| Outbox batch / workers / Pod | `50 / 4` | `100~200 / 8~16` | Kafka ack 和 DB finalize 压测通过 |
| DelayJob max-per-run / Pod | `16` | `32` | 两类 worker 各 8；最好先实现 capacity-aware claim |
| Notification batch / workers / Pod | `40 / 4` | 示例 `160 / 16` | 假设每渠道 80/s/Pod、约 100ms provider latency |
| Notification limiter / channel / Pod | `20/s` | 示例 `80/s` | 全局 quota 1000/s ÷ 10 Pods × 80% headroom |
| Statement max-per-run / workers | `8 / 4` | 每 Pod `16 / 8` 或独立总 worker `16~32` | capacity-aware claim、batch window 和 DB lock wait 验证 |

Notification 生产示例的计算不是固定行业值：

```text
每渠道每 Pod quota = 1000 global calls/s ÷ 10 Pods × 0.8 = 80/s
两个渠道总 delivery rate = 80 + 80 = 160/s/Pod
若平均 provider latency=100ms，需要并发 ≈ 160 × 0.1 = 16 workers/Pod
```

真实 push/email 常由不同 provider 承担，应分别使用各自 quota、延迟和失败率计算。

### 2.2 Scheduler 与 worker pool

| 机制 | poll/recover scheduler | worker | queue | 单轮领取 | poll 间隔 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Outbox | `1` | `4` | `100` | `50` | `1s` |
| DelayJob | `2` | DB `4` + auto-repay `4` | 各 `100` | 混合 `16` | `1s` |
| Notification delivery | `2` | `4` | `100` | `40` | `1s` |
| Statement jobs | `2` | `4` | `100` | `8` | `1s` |
| Billing cycle | `1` | 无专用 worker | 不适用 | 每天补建周期/jobs | 每日 01:00 JST |

这些 poller 都是 `fixedDelay`：一轮方法结束后再等 1 秒，不是严格整点每秒触发。
单轮领取上限只是“最多 claim 多少”，不代表 worker 真能处理这么多。

queue 满后的语义也不完全相同：Notification 在 provider HTTP 前被拒绝会延后且不增加 attempts；
Outbox 和 DelayJob 的 worker rejection 会进入普通 `markFailed`，增加 attempts。也就是说持续本机过载
可能让健康的 Outbox/DelayJob 消耗 retry budget，极端时进入 DEAD；这也是 claim 数量必须贴近
executor 可用 capacity 的原因。

稳定吞吐应该写成：

```text
实际 throughput
= min(单轮领取速率, worker 数 / 单任务平均耗时, 下游速率, DB capacity)
```

### 2.3 Kafka

| 配置 | 当前值 | 作用 |
| --- | ---: | --- |
| 业务 topic partitions | 每个 `3` | 每个 group 对该 topic 的有效并行上限 |
| DLT partitions | 每个 `3` | 保留 source partition |
| local replicas | `1` | 本地单 broker；不是生产高可用配置 |
| Notification concurrency | 每个对应 listener `2` | 每个 topic 最多并行消费 2 个 partition |
| Risk concurrency | `3` | 对齐 authorization topic 的 3 partitions |
| Ledger concurrency | 每个对应 listener `2` | transaction/repayment topic 各最多 2 |
| ack mode | `record` | 一条 listener 成功后推进一条 offset |
| consumer auto commit | `false` | 不按后台定时器提前提交 |
| consumer retry | 首次 + `2` 次 | 普通异常每次间隔 `1s` 后再进 DLT |
| contract failure | `0` 次 retry | `EventContractException` 直接 DLT |
| producer acks | `all` | 等 ISR 确认，提高 durability |
| producer max in-flight | `5` | idempotent producer 下兼顾 pipeline 和顺序 |
| producer request timeout | `3s` | 单次 broker 响应上限 |
| producer delivery timeout | `10s` | producer 内一次 send 的总预算 |

项目没有显式配置 `max.poll.records`、producer `linger.ms/batch.size` 等吞吐旋钮。
这对学习项目合理，但生产 sizing 不能把库默认值当成经过验证的容量决策。

### 2.4 Claim、lease、recover 与 retry 时间预算

| 机制 | processing lease | recover 扫描 | 单次外部等待 | max attempts |
| --- | ---: | ---: | ---: | ---: |
| Outbox | `30s` | `5s` | Kafka send `5s` | `10` |
| DelayJob | `60s` | `5s` | bank read timeout `2s` | `10` |
| Notification | `30s` | `5s` | provider read timeout `2s`，一次 durable attempt 内最多 3 calls | `8` |
| Statement | `300s` | `10s` | 每账户 DB transaction | `10` |

lease 不是处理速率，但决定拥塞时能否正确恢复。必须满足：

```text
lease > queue wait + 正常执行时间 + finalize 时间
```

当前注释主要按“执行时间”配置 lease，却没有把多轮 claim 累积出的 queue wait 纳入公式。
因此 capacity-aware claim 比单纯继续加长 lease 更重要：claim 越早，越早开始烧 lease。

进程宕机时，已经 claim 但仍在 worker queue 中的任务也会随内存一起消失；DB 中却仍是
`PROCESSING`，只能等 lease + recover scan：

```text
Outbox crash recovery       最坏约 30s lease + 最多 5s 扫描间隔
DelayJob crash recovery     最坏约 60s lease + 最多 5s 扫描间隔
Notification crash recovery 最坏约 30s lease + 最多 5s 扫描间隔
Statement crash recovery    最坏约 300s lease + 最多 10s 扫描间隔
```

这是 durable lease 的预期行为，不是消息丢失；代价是“提前 claim 越多，单 Pod 崩溃后暂时不可见的任务越多”。
本轮把 DelayJob `max-per-run` 从 100 降到 16，会缩小一次 crash 的 blast radius，但类型倾斜时仍可能
一次 claim 16 条 AUTO_REPAYMENT，等于单个 4-thread pool 并发的 4 倍。最终仍应按 job type 和
executor available capacity 领取。

---

## 3. 三类“限流”必须分开讲

### 3.1 API 系统保护：Redis token bucket

调用链：

```text
POST /api/authorizations
→ AuthorizationRateLimitInterceptor
→ RedisTokenBucketRateLimiter Lua
→ allow 或 429 + Retry-After
```

| 配置 | 当前值 | 精确语义 |
| --- | ---: | --- |
| enabled | `true` | 只保护 POST authorization |
| key | client IP | 每个调用方一个 Redis bucket |
| capacity | `20` | 空闲后可瞬时放行 20 个 burst |
| refill | `10/s` | 单调用方长期持续 10 req/s |
| Redis timeout | `1s` | Redis 调用等待上限 |
| Redis 故障 | fail-open | 放行并计数，不让保护组件变成全站开关 |
| trust XFF | `false` | 本地直连安全；LB 后若不调整会把所有用户看成 LB IP |

这是主动限流，目标是保护应用和 DB，不是业务拒绝。超限返回 HTTP 429，尚未解析 body、
开启授权事务或取得账户锁。

合理性判断：

- `20 burst + 10/s/IP` 适合学习和小流量演示，不能当生产统一阈值。
- 生产更合理的 key 是认证后的 client/merchant identity；IP 可能 NAT 聚合，也可能被攻击者分散。
- 在可信 LB 后必须正确处理 client IP。若 `trust-forwarded-for=false` 且应用只看到 LB IP，
  所有客户会共享一个 10 req/s 的“全局桶”，这是部署错误，不是有意容量设计。
- fail-open 保可用性合理，但 Redis brownout 时系统会失去主动入口保护，必须依靠告警、网关和物理池边界。

### 3.2 业务风控 velocity：Redis sliding window

```text
每张 cardId 一个 ZSET
窗口 60 秒
max-authorizations-per-window = 3
Lua 先加入本次，再统计
count > 3 时 decline
```

因此前三次可以继续，第 4 次开始得到 `VELOCITY_EXCEEDED`。这是每卡业务风险规则，不是系统
只能处理 `3/60 = 0.05 QPS`。一百万张不同卡仍可能同时产生很高总 QPS。

Redis 故障时这里也 fail-open，而且刻意不 fallback 到 MySQL `COUNT(*)`，避免 Redis 故障把流量
全部倒灌主库。这个取舍合理：velocity 是辅助风险信号，额度和 external risk 仍然生效。

### 3.3 Notification provider quota：本地 RateLimiter

| channel | limit | refresh | 等 permit |
| --- | ---: | ---: | ---: |
| APP_PUSH | `20 calls/s/Pod` | `1s` | `0`，无 permit 立即失败 |
| EMAIL | `20 calls/s/Pod` | `1s` | `0`，无 permit 立即失败 |

每个 Notification 当前 fan-out 成 APP_PUSH + EMAIL 两条 delivery，所以正常稳定上限约为：

```text
20 notification events/s/Pod
= 20 push calls/s + 20 email calls/s
```

worker 没拿到 permit 时没有调用 provider，因此：

- 不增加 durable `attempts`。
- 释放 lease，按 poll `fixed-delay=1s` 延后。
- 不污染 CircuitBreaker 的 provider failure rate。

这是合理的 client-side throttling，但它是每 Pod 本地状态：

```text
集群 provider 调用上限 ≈ Pod 数 × 20/channel
```

如果 provider 给的是全局 100 TPS quota，扩容 Pod 时必须重新分摊并留安全余量，或者改成共享/网关级限流。

### 3.4 Cache 和 single-flight：不是限流，但会改变回源流量

Statement GET 的负载整形配置：

| 配置 | 当前值 | 对流量的影响 |
| --- | ---: | --- |
| L1 Caffeine TTL | `30s` | 同 Pod 热读不访问 Redis/MySQL |
| L1 maximum size | `1000` | 主动限制 heap 中 key 数，不限制 QPS |
| L2 Redis TTL | `5m` | 跨 Pod 共享 read snapshot |
| TTL jitter | `30s` | 避免大量 key 同时过期形成 avalanche |
| rebuild lock TTL | `2s` | L2 miss 时跨 Pod single-flight |
| loser wait | `5 × 20ms = 100ms` | 等 winner 填缓存的最大额外延迟，之后 fail-open 回源 |
| tombstone TTL | `10s` | 防迟到旧快照覆盖还款后的新版本 |
| L1 invalidation broadcast | `false` | 当前跨 Pod stale 主要靠 30s L1 TTL 收敛 |

`rebuild lock` 控制的是同一个 cache key 同时回源的并发，不是全局 QPS。锁失败、等待超时或 Redis
不可用都会 fail-open 回 MySQL；因此 miss storm 最终仍需要 Hikari/DB 容量和入口限流兜底。

---

## 4. Semaphore、线程池和锁：控制的是并发

### 4.1 External risk semaphore bulkhead

| 配置 | 当前值 |
| --- | ---: |
| max concurrent calls | `4/Pod` |
| max wait | `0`，满时立即 fallback |
| connect timeout | `300ms` |
| read timeout | `800ms` |

授权方法从第一次 MySQL idempotency claim 开始就处于 `@Transactional` 中；external risk 虽然在
`credit_accounts FOR UPDATE` 之前，但调用期间仍持有事务、Hikari connection 和 authorization row lock。
所以 semaphore=4 的真实作用是：external risk brownout 时最多钉住 4 条授权事务，给 Hikari 留余量。

它是刻意主动并发限制。满时不是 HTTP 429，而是 fail-closed：

```text
BulkheadFullException
→ fallback
→ authorization DECLINED: EXTERNAL_RISK_UNAVAILABLE
```

正常模拟延迟 `100ms` 时，external risk 聚合上界约 `40 calls/s/Pod`；接近 `800ms` timeout 时降到
约 `5 calls/s/Pod`。超过并发 4 的请求不会排队，而会快速业务拒绝。

评价：保护 Hikari 的方向正确，但“事务内同步外部 I/O”仍是当前授权主链路最大的结构性容量限制。
如果未来要提升吞吐，先讨论缩短/拆分事务边界或独立 risk capacity，不是先把 permit 和 Hikari 一起调大。

### 4.2 Worker pool 是 coarse-grained bulkhead

| 外部/业务动作 | 并发上限 | 为什么分池 |
| --- | ---: | --- |
| Kafka Outbox publish | `4` | broker 慢不占其他机制线程 |
| 纯 DB DelayJob | `4` | row lock 并发受控 |
| Bank auto repayment | `4` | 银行 brownout 不拖授权过期任务 |
| Statement generation | `4` | 月末批处理不无限压 DB |
| Notification provider | `4` | provider 慢不无限创建线程 |

这五个池彼此隔离是合理的；但它们仍共享同一个 Hikari 和同一 JVM，所以不是完整资源隔离。
真正生产中可通过不同 profile/部署单元把 API、consumer、statement batch 分开，再分别配置 DB pool 和 CPU。

### 4.3 MySQL row lock 是 correctness serialization

同一 `creditAccountId` 的 authorization、posting、repayment、statement 更新会在账户行锁上串行。
这是有意为之的正确性边界，但它会自然形成热点 key 的被动瓶颈：

```text
同账户吞吐 ≈ 1 / 该账户锁内事务耗时
```

加 Pod、Tomcat thread 或 Kafka partition 都不能让同一账户的资金写入并行。能做的是：

- 把 external call 和不必要查询移出 row-lock critical section。
- 保持固定锁顺序和正确索引。
- 对异常热点账户/client 做业务限流。
- 观测 lock wait，而不是用 Redis 锁替换数据库资金事实。

---

## 5. CircuitBreaker 和 Retry 如何改变调用速率

### 5.1 CircuitBreaker 总表

| 实例 | window / minimum | 打开条件 | OPEN | HALF_OPEN | 说明 |
| --- | --- | --- | ---: | ---: | --- |
| externalRisk | 5 / 3 calls | failure ≥ 50% | `10s` | `2` | 与 semaphore 一起 fail-closed |
| notificationPush | 10 / 5 | failure ≥ 50% 或 slow ≥ 100% | `10s` | `3` | slow threshold `1s` |
| notificationEmail | 10 / 5 | 同上 | `10s` | `3` | channel 独立，故障不连坐 |
| bankDebit | 10 / 5 | failure ≥ 50% 或 slow ≥ 100% | `10s` | `3` | slow threshold `1s` |

notification/bank 的 HTTP read timeout 是 `2s`，所以 `1s~2s` 的成功调用也能被记录为 slow call。
CircuitBreaker OPEN 后，新调用快速失败，降低故障期实际出站速率；它不是正常态 quota。

### 5.2 Retry budget

Notification 一次 durable worker attempt 内使用：

```text
Retry(CircuitBreaker(RateLimiter(providerCall)))
max attempts = 3（总调用最多 3 次，不是 1+3）
wait = 200ms, 400ms
```

所以一个 delivery 最坏可能消耗 3 个 provider permits。若每次都接近 2 秒 read timeout：

```text
一次 durable attempt 最坏约 2s × 3 + 0.2s + 0.4s = 6.6s
4 个 worker 的故障期原始能力约 4 / 6.6 ≈ 0.6 delivery/s
```

实际 CircuitBreaker 通常会在累计到足够失败后打开，避免长期维持这个最坏状态。

跨进程 durable retry：

| 机制 | max attempts | backoff |
| --- | ---: | --- |
| Outbox | `10` | `1,2,4,8,16,32,60...s` |
| DelayJob | `10` | `1,2,4,8,16,32,60...s` |
| Notification delivery | `8` | `1,2,4,8,16,32,60s` |
| Statement job | `10` | 当前失败后立即回 PENDING，无 backoff |
| Kafka consumer | 总调用 `3` | 固定 `1s` × 2 |

Bank debit 刻意没有 Resilience4j Retry：资金调用只保留 DelayJob durable retry，复用同一个
idempotency key，避免“框架 retry × job retry”相乘。这个取舍合理。

Statement job 没 backoff 是当前风险点：永久失败分片会每秒附近重新 claim，快速烧 attempt，和正常批次抢 DB。

---

## 6. 四条主要线路的生产与消费速率

### 6.1 Authorization 主链路

```text
client
→ per-IP token bucket: burst 20, sustained 10/s
→ Tomcat: max 80 threads
→ Hikari: max 10 connections
→ transaction/idempotency row
→ Redis velocity: 第 4 次/卡/60s 风控拒绝
→ external risk: semaphore 4, 100ms normal / 800ms timeout
→ credit_account FOR UPDATE
→ Outbox row + DelayJob row
→ commit
```

限制公式：

```text
单 client/IP：主动上限 10 req/s（短 burst 20）

多 client 单 Pod 聚合：
min(
  Tomcat capacity,
  Hikari/事务耗时,
  externalRisk 4 / risk latency,
  MySQL lock/IOPS
)
```

当前模拟 external risk 100ms 时，约 40 risk calls/s/Pod 是最清晰的主动聚合闸门。
但 Hikari 只有 10，且一条授权事务横跨多次 SQL + Redis + external HTTP，因此真实授权吞吐会低于简单公式。

流量继续增加时的退化顺序：

1. 单 IP 超过 10/s：主动返回 429。
2. 多 IP 并发超过 risk permits：快速 fail-closed，业务拒绝增加。
3. 其他请求/worker 同时抢 DB：Hikari pending 上升，p99 增长。
4. 热点集中到同一账户：row lock wait 串行化。
5. MySQL CPU/IO/slow SQL 达限：所有同步与异步线路一起变慢。

#### Hikari 连续耗尽 5 秒：HTTP 与 Kafka 分叉 trace

前提：statement batch 与 authorization 洪峰叠加，10 个连接全部占用，池在 `T+0s~T+5s`
都没有空闲连接。

HTTP authorization 分支：

```text
T+0s  POST /api/authorizations 进入 @Transactional
      DataSourceTransactionManager 尝试拿 Hikari connection

T+1s  connection-timeout 到期
      → 顶层 CannotCreateTransactionException
      → GlobalExceptionHandler 返回 503 DATABASE_UNAVAILABLE
      → Retry-After: 1

T+2s  客户端复用原 Idempotency-Key 重试
      → 不能生成新 key；否则原请求若处于 outcome unknown，可能绕过同一幂等身份
```

反向事实：如果没有专门 handler，异常会落到 catch-all 500；客户端无法区分“暂时没有 DB capacity，
可安全重试”和真正的内部程序错误。当前 503 只覆盖无法创建事务/无法取得 JDBC connection，
不会把 commit outcome unknown 等所有事务异常粗暴标成可重试。

Kafka Ledger consumer 分支：

```text
T+0s~1s  CardTransactionLedgerListener attempt 1
         RecordLedgerEntryService @Transactional 拿连接超时
         → CannotCreateTransactionException（普通 retryable exception）

T+1s~2s  Kafka FixedBackOff 1s
T+2s~3s  attempt 2 再等 Hikari 1s后失败
T+3s~4s  Kafka FixedBackOff 1s
T+4s~5s  attempt 3 再次失败

T+5s     retry 耗尽
         → record 写入 mini-card.ledger.dlt.v1
         → 原 offset 推进，后续消息继续
```

连锁后果：当前 DLT 只有写入，没有 monitor/告警/replay；该交易的 `ledger_entries` projection 缺失。
Statement generation 检测到 posted transaction 缺 ledger entry 后抛 retryable
`StatementGenerationException`，而 statement job 当前没有 backoff，可能快速重领、烧完 attempts 后让分片进入 DEAD。

这就是 Hikari 从默认 30s 改为 1s 的核心 trade-off：

```text
30s：更偏向阻塞等待，线程/partition 被钉住，但短期 DB 压力可能只表现为 latency/lag 并自愈。
1s：更快释放 HTTP/worker 资源，却会更快把持续约 5s 的资源竞争转成 503 或 DLT。
```

当前小项目保留 1s，是为了让过载快速、可观察；接受它的生产前提是补齐 DLT 告警和受控 replay。
更完整的生产方案是把 API、consumer、statement/batch 分部署并分别 sizing DataSource；若仍共池，
再评估只对 DB connection acquisition failure 使用更长的 exception-aware Kafka backoff，不能全局拖长 poison message retry。

### 6.2 Authorization → Outbox → Kafka → consumers

```text
Authorization transaction 约生产 1 条 Outbox event
→ Outbox poll 最多 claim 50/s/Pod
→ 4 workers 等 Kafka ack
→ authorization topic: 3 partitions
→ Notification concurrency 2
→ Risk projection concurrency 3
```

Outbox 正常态上限：

```text
min(50 events/s poll intake, 4 / Kafka ack latency, Hikari finalize capacity)
```

若 Kafka ack 10ms，worker 理论能力高于 50/s，此时 poll batch 是主动上限；若每次卡到 send timeout 5s，
4 workers 只能约 0.8 attempts/s，backlog 会进入 MySQL outbox 表，而不是拖慢 authorization commit。

Kafka consumer 的瓶颈通常不是“线程数字本身”，而是 listener 内的 DB transaction。当前全部 consumer、
HTTP 和 worker 共用 Hikari 10，流量上来后 DB pool/SQL 会比 3 partitions 更早形成共享压力；只有 DB 足够快且
consumer lag 仍增长时，才考虑增加 partitions/concurrency。

### 6.3 Notification 线路

```text
1 integration event
→ 1 Notification intent
→ 2 delivery rows（push + email）
→ poll claim 最多 40 deliveries/s
→ worker concurrency 4
→ 每渠道 RateLimiter 20/s
→ provider HTTP 20ms normal，timeout 2s
```

正常模拟配置下：

```text
worker raw capacity ≈ 4 / 0.02s = 200 deliveries/s
poll intake cap ≈ 40 deliveries/s
provider quota cap = 20 push + 20 email = 40 deliveries/s
稳定业务事件吞吐 ≈ 20 notification events/s/Pod
```

因此当前正常态真正瓶颈是**刻意主动设置的 provider RateLimiter**，不是 worker pool。
poll batch 已从 50 调到 40，与当前 push 20/s + email 20/s 的每 Pod quota 对齐，正常态不再天然以
约 10 deliveries/s 的差额填充 queue；真实延迟、retry 和渠道分布仍可能造成积压，最终方案仍应在
dispatcher 提交前感知 executor capacity。

### 6.4 Statement 与 auto repayment

Statement：

```text
每天 01:00 JST reconciliation
→ 每 job 目标 1000 accounts
→ 每轮 claim 8 jobs
→ 4 workers
→ 每个账户独立 transaction
```

它没有固定 accounts/s 限流。若每账户平均 10ms，粗估 `4/0.01=400 accounts/s`；若 50ms，约 80/s。
百万账户分别约需 42 分钟或 3.5 小时，必须用真实 SQL/锁等待压测验证。

风险在于 dispatcher 每秒都能继续 claim 8 个，而不是只在“4 个 worker 空闲”时 claim。
queue=100 时最多可能出现约 4 个运行 + 100 个已 claim 排队，300 秒 lease 从 claim 时就开始烧。
如果单 job 很慢，队尾 job 尚未开始就可能被 recover，形成重复工作。`max-per-run=2×pool` 只限制单轮，
没有限制多轮累计已 claim 数。这是当前 statement capacity 配置中最需要修正的点。

Auto repayment：

```text
DelayJob 一轮混合 claim 最多 16
→ AUTO_REPAYMENT 路由到专用 4-thread pool
→ bank timeout 2s
→ bankDebit CircuitBreaker
→ 无 RateLimiter、无 R4j Retry
```

正常速率由 `4 / bank latency` 决定；持续 timeout 时约 2 calls/s，随后 breaker OPEN 快速失败。
专用线程池隔离是合理的，但真实银行若有明确 TPS contract，应该增加出站 quota limiter。
还款日批量到期时，16 仍可能提前 claim 多于 4 个 auto-repayment 可执行任务，但已比原 100 明显收敛；
队列拒绝仍会消耗 DelayJob attempts，所以 capacity-aware claim 仍是代码层容量缺口。

---

## 7. 主动限制与被动瓶颈清单

### 7.1 刻意主动限制

| 闸门 | 目的 | 超限表现 | 当前判断 |
| --- | --- | --- | --- |
| API token bucket 20/10s | 保护授权入口 | 429 | 合理，但 key/阈值需按部署校准 |
| Card velocity 3/60s | 风控异常用卡 | business decline | 合理的业务规则，不是系统 QPS |
| externalRisk semaphore 4 | 保护 Hikari/外部服务 | fail-closed decline | 方向合理，暴露事务内 HTTP 容量代价 |
| Notification 20/s/channel | provider quota | reschedule，不加 attempts | 合理；多 Pod 总 quota 要重算 |
| 固定 worker pool 4 | 舱壁与并发控制 | queue/reject/retry | 合理，但 claim 量和可用 slot 未对齐 |
| Kafka partitions 3 | 并行与顺序边界 | consumer lag | 本地学习合理，生产需按 lag sizing |
| CircuitBreaker | brownout 快速失败 | fallback / durable retry | 组合基本合理 |
| Timeout | 限制资源占用时间 | exception/fallback | risk 较短；DB connection 已显式收紧为 1s |

### 7.2 被动成为瓶颈

| 瓶颈 | 为什么是被动的 | 典型信号 |
| --- | --- | --- |
| Hikari 10 | 小项目显式边界，但仍由所有 workload 共享 | pending↑、HTTP/Kafka/job 同时变慢 |
| MySQL writer | 所有资金状态和异步状态都落同一库 | CPU/IO↑、slow SQL、lock wait |
| 热点 credit account row | correctness 要求同账户串行 | 单 key latency 高，加 Pod 无效 |
| 事务内 external risk | 没拿 account lock，但仍占 DB transaction/connection | risk latency 与 Hikari active 同涨 |
| 本地模拟 provider 回环 localhost:8080 | worker HTTP 又占本应用 Tomcat thread | 通知/银行压力反过来挤 API；生产外部部署不会完全相同 |
| Worker queue | 只缓存等待，不增加服务率 | queue 接近 100、lease aging、reject |
| Kafka hot partition | 同 key 必须落同 partition | 单 partition lag，其他 partition 空闲 |
| Redis hot key/Lua | 单 IP/card 极热时同 key 操作串行 | Redis latency、command CPU |
| JVM CPU/GC | 所有角色同进程竞争 | GC pause、CPU、runnable threads |

---

## 8. 当前最可能的瓶颈排序

### 当前默认/模拟环境

1. **单调用方流量**：API token bucket 主动限制为 10 req/s/IP。
2. **单卡流量**：velocity 主动限制为 3 次/60 秒，第四次业务拒绝。
3. **多调用方授权聚合**：external risk semaphore 4 + 100ms 模拟延迟，约 40 calls/s/Pod。
4. **异步通知**：每渠道 20 calls/s，约 20 notification events/s/Pod，会先于 worker raw capacity 达限。
5. **共享资源竞争**：Hikari 10 是 HTTP、Kafka、worker、statement 共用的被动总闸门。

### QPS 持续放大后

最可能先出现的系统级瓶颈是：

```text
事务内 external risk / Hikari 连接
→ MySQL writer 与热点 row lock
→ Outbox/Notification/consumer backlog
→ Kafka partitions（前提是 DB 已不再是瓶颈）
```

为什么不是先说 Tomcat：即使收窄到 80 个 request threads，也足以把只有 10 个 DB connections 的后端排满；
Tomcat 容量更大只会让排队更深，不会增加资金写吞吐。

为什么不是先说 Kafka：主请求只写 Outbox，不等 Kafka；Kafka 慢首先表现为 durable backlog。
但 backlog 长期增长会增加表容量、恢复时间和业务数据新鲜度风险，最终仍要触发告警/降载。

为什么不是先说 Redis：两个 Redis 限流器都 fail-open，Redis 故障不会直接阻断授权；风险是保护失效后
更多请求落到 external risk/MySQL，因此要监控 degraded counter。

---

## 9. 合理性评审：应该保留、可以调整、需要补齐

### 应该保留

- API 系统保护与 card velocity 业务规则分层，分别返回 429 与 risk decline。
- external risk `timeout + semaphore bulkhead + circuit breaker + fail-closed`。
- Notification `Retry(CB(RateLimiter(call)))`，并忽略本地 throttle 对 breaker/retry 的污染。
- Bank debit 只用 CircuitBreaker + durable retry，不叠加内存 Retry。
- 各类 worker 分池、bounded queue、claim lease、`SKIP LOCKED`。
- 同账户 row lock correctness，不为 QPS 换 eventual consistency。

### 可以调整

- Tomcat/Hikari 已按小项目显式配置；生产按 Pod 角色和 RDS 总连接预算重新 sizing，不能机械照抄。
- API limiter key 从 IP 演进为 authenticated client/merchant，并按可信代理配置真实源地址。
- Notification `batch-size=40` 已与当前每 Pod 总 provider quota 40/s 对齐；生产按全局 quota/Pod 数重算。
- 根据真实 provider quota / Pod 数重算本地 RateLimiter，扩容不能自动把全局 quota 一起扩大。
- 根据 lag 增加 Kafka partitions，而不是只改 concurrency。

### 需要补齐

- **capacity-aware claim**：poller 应按 executor 可用 slot 领取，或者不在 submit 前把大量行改成 PROCESSING。
- **statement backoff**：失败分片不要每轮立即重领。
- **workload isolation**：生产把 API、Kafka consumers、statement/batch 至少按 profile 分开部署和 sizing。
- **DLT/DEAD/backlog 运维闭环**：指标、告警、受控 replay。
- **真实压测证据**：当前没有可引用的最大 QPS、p95/p99 或 saturation point。

> [!CAUTION]
> 不建议直接把 Hikari 10 改成 50、external risk permit 4 改成 40。
> 这可能只是让更多事务同时打 MySQL/外部风控。正确顺序是测出 DB、risk 和锁等待曲线，缩短事务，
> 再按实例数和数据库总连接预算调整。

---

## 10. 应该监控什么，才能证明谁是瓶颈

| 层 | 关键指标 | 如何判断 |
| --- | --- | --- |
| HTTP | RPS、429、503、p50/p95/p99、Tomcat busy | 429 是主动限流；503 DATABASE_UNAVAILABLE 是 DB capacity 快速失败 |
| API limiter | denied、Redis unavailable | 区分真实超限和保护层降级 |
| Risk | latency、bulkhead rejected、fallback reason | permit 满、断路或 provider 慢 |
| Hikari | active、idle、pending、acquire time、`database_connection_unavailable` WARN | pending > 0 或该日志出现表示 DB 连接成为瓶颈 |
| MySQL | QPS、slow SQL、row lock wait、deadlock、IOPS | 区分 SQL 慢和热点锁 |
| Outbox | PENDING/PROCESSING/DEAD、oldest age、publish latency | 生产速率是否持续大于 publish 速率 |
| Kafka | per-group/per-partition lag、rebalance、DLT | 是总并发不足还是单 partition 热点 |
| Notification | PENDING age、throttled、provider latency、CB state | quota 限制还是 provider brownout |
| DelayJob | due backlog、queue reject、DEAD、oldest overdue | worker/银行/DB 哪一层不够 |
| Statement | jobs by status、accounts/s、cycle completion time | 能否在 batch window 内完成 |
| JVM | CPU、GC pause、heap、thread states | CPU/GC 是否真的先于外部资源达限 |

判断 backlog 是否失控，不能只看数量：

```text
backlog growth rate = production rate - consumption rate

drain time ≈ backlog / (recovery consumption rate - current production rate)
```

如果恢复后的消费率仍不大于持续生产率，系统永远排不空。

---

## 11. 压测场景与预期瓶颈

| 场景 | 变量 | 预期看到什么 |
| --- | --- | --- |
| 单 IP authorization ramp | 1→30 RPS | 20 burst 后稳定约 10/s，其余 429 |
| 多 IP、不同账户 | 10→100 RPS | risk bulkhead reject/fallback、Hikari pending |
| Hikari 人为占满 5s | HTTP + Ledger consumer | HTTP 1s→503；consumer 约5s烧完3次→Ledger DLT |
| 多 IP、同一账户 | 并发 20 | account row lock 串行，不得 over-approve |
| external risk 700ms | 固定 20 RPS | 4 permits 约支持 5.7/s，其余 fail-closed |
| Kafka 停止 | 持续 authorization | API 可提交，Outbox backlog/oldest age 上升 |
| provider 正常且通知灌入 | >20 events/s | notification throttled/PENDING age 上升 |
| provider 2s/5xx | delivery backlog | Retry 放大后 breaker OPEN，worker 先饱和 |
| due auto repayment 洪峰 | 1000 jobs | 4 专用 workers、queue reject、bank CB |
| statement 100万账户 | 10/50ms 每账户 | 验证 42min/3.5h 粗估与 lease 是否足够 |

压测不能只看成功 QPS，还要验证：

- 同一 idempotency key 没有重复占额度。
- 同一账户没有 over-approve。
- Outbox/Inbox 没有丢事件或重复副作用。
- 429、risk decline、provider throttle 没有混成同一种失败。
- DB pool 耗尽明确返回 503，状态变更请求复用原 Idempotency-Key 重试。
- 压力解除后 backlog 能在可接受时间内排空。

---

## 12. interview 回答模板

### Q1：现在谁是主瓶颈？

> 单调用方先被 Redis token bucket 主动压到 10 RPS；多调用方聚合后，当前单 Pod 的 external risk
> semaphore 只有 4，100ms 延迟时约 40 calls/s，而且调用仍在 authorization transaction 内占用
> Hikari connection。系统显式限制为 10 个 DB connections，却同时跑 HTTP、Kafka 和五类 worker，
> 所以持续放量后共享 Hikari/MySQL 会成为第一组被动瓶颈；同账户热点还会被 row lock 单独串行化。

### Q2：为什么不直接加线程和连接？

> 线程和连接只增加并发进入下游的数量，不增加 MySQL IOPS、外部 risk quota 或单账户锁吞吐。
> 如果瓶颈是 DB/锁，盲目扩池只会增加排队、锁等待和超时。我会先看 Hikari pending、SQL latency、
> row-lock wait 和 risk latency，再缩短事务、隔离 workload，最后按数据库总连接预算调整。

### Q3：RateLimiter、Semaphore、CircuitBreaker 有什么区别？

> RateLimiter 控制每秒频率，Semaphore 控制同时在途数量，CircuitBreaker 根据近期健康状态决定是否
> 快速失败。当前 API token bucket 是跨 Pod 的入站频率限制；external risk bulkhead 是每 Pod 并发 4；
> notification breaker/provider limiter 分别负责故障隔离和正常 quota，不能互换。

### Q4：异步化后就没有瓶颈了吗？

> 没有。异步把同步延迟变成 durable backlog，使主事务不等 Kafka/provider，但生产率长期大于消费率时
> backlog 仍会无界增长。必须监控 lag、oldest age、DEAD/DLT 和 drain time，并对 producer 限流或扩 consumer。

### Q5：当前配置最值得先改什么？

> 第一不是把数字调大；当前已把小项目 Hikari/Tomcat 显式化，下一步要做压测并修正 poller 提前 claim 远多于
> worker slot 的问题；为 statement failure 增加 backoff；生产部署把 API、consumer 和 batch 隔离。

---

## 13. 最终记忆图

```text
入口速率
  Redis token bucket: 20 burst / 10 req/s/client
        ↓
同步授权并发
  Tomcat 80 → Hikari 10 → external risk semaphore 4 → account row lock 1/key
        ↓
异步生产
  Outbox claim ≤50/s → 4 Kafka workers → 3 partitions
        ↓
异步消费
  Notification 2/topic | Risk 3 | Ledger 2/topic
        ↓
通知外发
  4 workers → push 20/s + email 20/s → ≈20 notifications/s/Pod

主动闸门：token bucket / velocity / semaphore / RateLimiter / bounded pool / timeout / breaker
被动瓶颈：Hikari / MySQL / row lock / hot partition / queue aging / JVM / loopback provider
```

最重要的一句话：

> 系统吞吐由最慢的一层决定；限流是在最慢层被压垮之前主动拒绝，瓶颈则是没有主动设计好时被动排队。

---

## 14. 配置与代码定位

| 主题 | 配置/代码入口 |
| --- | --- |
| Kafka client、topic/group、worker 数字、Resilience4j | `src/main/resources/application.yml` |
| API token bucket | `RateLimitProperties`、`RedisTokenBucketRateLimiter`、`AuthorizationRateLimitInterceptor` |
| Card velocity | `RiskProperties`、`RedisRiskVelocityCounter`、`RiskAssessmentService` |
| External risk semaphore/breaker | `ExternalRiskGatewayAdapter` |
| Notification retry/limiter/breaker | `ResilientCallHelper`、`NotificationDeliveryWorker` |
| Bank breaker | `BankDebitGatewayAdapter` |
| Worker pools | `WorkerExecutorConfiguration` |
| Scheduler pools | `PollingSchedulerConfiguration` |
| Outbox intake/worker | `OutboxPoller`、`OutboxWorker`、`OutboxEvent` |
| DelayJob intake/worker | `DelayJobPoller`、`DelayJobWorker`、`DelayJob` |
| Statement claim/worker | `StatementJobDispatcher`、`StatementJob` |
| Kafka consumer concurrency/retry | `KafkaConsumerConfiguration`、各 `@KafkaListener` |
| Statement cache load shaping | `StatementReadCacheProperties`、`StatementReadService` |
