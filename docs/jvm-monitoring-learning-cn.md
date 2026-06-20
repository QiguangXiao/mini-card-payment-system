# JVM Monitoring 与面试学习笔记

这份文档解释当前项目如何观察 JVM 运行状态，以及 PayPay Card / 金融后台面试里常见的追问点。

当前选择：

```text
GET /api/health                 -> public liveness，只证明 HTTP 应用还活着
GET /actuator/health            -> Spring Boot Actuator health
GET /actuator/health/liveness   -> JVM 进程是否应该被重启
GET /actuator/health/readiness  -> 实例是否应该接收流量
GET /actuator/info              -> Java / OS / app 信息
GET /actuator/metrics           -> 可查询 metrics 列表，包括 JVM metrics
```

## 1. 为什么不把 JVM 信息放进 `/api/health`

`HealthController` 位于 `monitoring.api`，它现在只做一件事：

```http
GET /api/health
```

返回：

```json
{"status":"OK"}
```

这个 endpoint 是轻量 `liveness`。它不查 MySQL，不查 Kafka，也不计算 JVM 内存。

原因：

- `liveness` 只回答“进程是不是还活着”。如果这个接口太重，短暂 DB 抖动可能导致应用被错误重启。
- JVM memory、GC、thread、dependency health 属于 operational diagnostics，应该交给 Actuator / Micrometer。
- 面试里可以说：业务 API contract 和运维诊断面要分开，避免把内部运行细节暴露给普通业务调用方。

## 2. 当前 Actuator 配置

位置：

- `src/main/resources/application.yml`

核心配置：

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
  metrics:
    tags:
      application: ${spring.application.name}
```

设计取舍：

- 只暴露 `health`、`info`、`metrics`，本地学习够用。
- `readiness` 明确包含 `db`，因为 MySQL 是授权、入账、还款等状态变更的核心依赖。
- 不暴露 `heapdump`、`threaddump`、`env`、`beans` 等高风险 endpoint。
- 真实生产环境通常还要加认证、内网访问控制，或者把 management port 放到只允许监控系统访问的网络里。

## 3. 本地怎么查 JVM metrics

启动应用后可以用：

```bash
curl http://localhost:8080/actuator/metrics
```

常见 JVM 指标：

```bash
curl "http://localhost:8080/actuator/metrics/jvm.memory.used"
curl "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap"
curl "http://localhost:8080/actuator/metrics/jvm.memory.max?tag=area:heap"
curl "http://localhost:8080/actuator/metrics/jvm.gc.pause"
curl "http://localhost:8080/actuator/metrics/jvm.threads.live"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states"
curl "http://localhost:8080/actuator/metrics/jvm.classes.loaded"
curl "http://localhost:8080/actuator/metrics/jvm.buffer.memory.used"
```

Actuator 返回 JSON。以 `jvm.memory.used` 为例，重点看：

- `name`：metric 名称。
- `measurements`：当前数值。
- `availableTags`：还能按哪些维度过滤，例如 `area=heap`、`id=G1 Old Gen`。

## 4. 面试重点：Heap、Non-Heap、Metaspace

JVM memory 常见分区：

- `heap`：业务对象主要存在这里，比如 controller request DTO、domain object、MyBatis 查询结果。
- `non-heap`：JVM 内部结构，不属于普通 Java object heap。
- `metaspace`：类元数据，属于 non-heap。大量动态代理、类加载泄漏可能导致 metaspace 增长。
- `direct buffer`：NIO / Netty / Kafka client 可能使用 off-heap buffer，不一定体现在 heap 使用量里。

面试回答可以这样组织：

> 我不会只看 RSS 或 heap used。金融后台延迟敏感，应该同时看 heap 使用趋势、GC pause、thread 数、direct buffer 和业务请求延迟。heap 一直上升且 full GC 后不下降，才更像 memory leak；短时间锯齿状增长和回落通常是正常 allocation / GC 行为。

## 5. 面试重点：GC pause 和支付链路

`jvm.gc.pause` 关注 GC 暂停时间。

在本项目里，GC pause 可能影响：

- `/api/authorizations` 的响应延迟。
- OutboxWorker 发布 Kafka 的节奏。
- DelayJobWorker 处理授权过期或自动还款的时效。
- Kafka consumer 处理 notification / risk / ledger event 的延迟。

可以这样回答：

> GC pause 不会直接破坏 MySQL transaction 的原子性，但会放大 latency、导致 request timeout、Kafka consumer heartbeat 延迟、scheduler 扫描变慢。金融后台要把 GC 指标和业务 latency、Outbox pending 数、DelayJob pending/dead 数一起看。

## 6. 面试重点：Thread 与线程池

常用指标：

```bash
curl "http://localhost:8080/actuator/metrics/jvm.threads.live"
curl "http://localhost:8080/actuator/metrics/jvm.threads.states"
```

排查思路：

- `BLOCKED` 很多：可能有锁竞争，或者某些同步代码持锁太久。
- `WAITING/TIMED_WAITING` 很多：不一定是问题，线程池 worker 等任务时很常见。
- live threads 持续上涨：可能线程泄漏，或者线程池/客户端重复创建。

本项目的相关点：

- Outbox 和 DelayJob 使用 worker pool，线程池大小和队列容量会影响吞吐与背压。
- 并发正确性主要靠 MySQL row lock 和 idempotency，不靠 JVM `synchronized`。
- 单实例内线程数正常，不代表多实例下没有并发问题；金融状态变化仍然要靠数据库事务和唯一约束兜底。

## 7. Liveness 和 Readiness 的区别

查询：

```bash
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```

区别：

- `liveness`：实例是否应该被重启。只有进程卡死、无法恢复时才应该失败。
- `readiness`：实例是否应该接收流量。启动中、依赖暂时不可用、线程池耗尽时可以失败，表示先别分配新请求。

面试容易问：

> 数据库挂了，liveness 要不要失败？

更稳的回答：

> 不一定。DB 暂时不可用通常不应该让容器反复重启，应该让 readiness 失败，把实例从流量里摘掉，同时保留进程等待依赖恢复。liveness 失败更适合进程内部不可恢复的问题。

## 8. 支付后台应该把 JVM 指标和业务指标一起看

JVM metrics 能告诉你运行时压力，但不能直接说明业务是否正确。

对这个项目，后续可以继续补的业务 metrics：

- Outbox `PENDING` / `PROCESSING` / `DEAD` 数量。
- DelayJob due backlog 和 `DEAD` 数量。
- Authorization approve / decline count。
- Kafka listener 处理成功、失败、DLT 数量。
- statement batch 每次关账账户数、交易数、耗时。

面试表达：

> JVM metrics 解释“应用运行得累不累”，业务 metrics 解释“支付链路有没有积压或失败”。金融后台排障要把两者关联起来看，比如 GC pause 上升后是否带来 authorization latency 上升、Outbox backlog 增长、Kafka consumer lag 增长。

## 9. 为什么不用自定义 `/api/jvm`

虽然可以用 `ManagementFactory` 写一个 controller 返回 JVM memory/thread 信息，但当前项目不这么做。

理由：

- Actuator / Micrometer 是 Spring Boot 标准做法，指标命名、tag、导出方式都更统一。
- 自定义接口容易遗漏 tag、单位、安全控制和未来 Prometheus/Grafana 对接。
- `/api/**` 更像业务 API；JVM 诊断属于 management plane。

如果未来要接 Prometheus，可以再加入 `micrometer-registry-prometheus`，并暴露：

```text
GET /actuator/prometheus
```

当前先不加，是为了避免引入还没有实际使用的外部监控链路。
