# Notification 真实投递设计（app push + email）

> 关键词：通知意图 vs 投递, per-channel delivery, claimable job, FOR UPDATE SKIP LOCKED,
> PROCESSING lease, lease token, 指数退避, DEAD, 幂等, at-least-once, 下游去重, effectively-once,
> Resilience4j, CircuitBreaker, RateLimiter, Retry, intra-attempt vs durable retry, delivery sender,
> 配信(はいしん), 冪等性(べきとうせい)。

本文说明 `notification` 模块从“只记录要通知”重构为“能真实投递（app push + email，外部接口先模拟）”
的完整设计：它如何**对齐项目已有的 message / poller 机制**，对那套机制的**评价**，渠道/模板/类型三者的
关系，幂等性能做到什么程度（为什么只能“至少一次 + 下游去重”、不可能完美 exactly-once），Resilience4j
如何承担快速重试 / 断路，以及“还没有 User 聚合时 sender 怎么合成收件地址”。

相关文档：
- 事件 / Outbox / Inbox / Kafka 可靠投递与消费幂等细节见 [events-outbox-inbox-kafka-cn.md](events-outbox-inbox-kafka-cn.md)。
- claimable job 家族对比（DelayJob / Outbox / StatementJob，本投递是它的第三个实例）见 [claimable-jobs-cn.md](claimable-jobs-cn.md)。

---

## 1. 目标与范围

**之前**：authorization / card_transaction 两类 Kafka 事件经 listener 进入
`RequestNotificationService`，只写一行 `notifications`（状态恒为 PENDING），**没有任何东西真的把它发出去**。
`Notification` 聚合上甚至有 `markSent` / `recordDeliveryFailure` 一整套生命周期方法，但**零调用者**——是
“声称有、其实不跑”的死代码。

**现在**：一条通知按当前系统已实现的渠道**扇出（fan-out）**成多条 `notification_deliveries`，每条由一套
claimable-job 回路（poller → claim → worker → recoverer）独立投递，worker 通过 Resilience4j 包裹的渠道
sender 调用（当前是模拟的）外部 push/email provider，成功标 `SENT`，失败按指数退避重试，超限进 `DEAD`。

范围内：多渠道扇出、可靠投递回路、Resilience4j（retry+circuit breaker）、幂等、模拟 provider、
sender 内部的地址/文案组装。范围外（留接缝）：真实 push/email SDK、真实 User/Customer 聚合与渠道偏好、多语言模板引擎、
投递结果回查 API。

---

## 2. 机制对齐与评价：直接复用 claimable job，不另起炉灶

项目里已经有**两套**成熟的“数据库工作队列”：`messaging/outbox`（Outbox→Kafka 可靠发布）和 `delayjob`
（延迟任务）。两者形状完全一致，`docs/claimable-jobs-cn.md` 把它统称 **claimable job**：

```
状态机：PENDING ──claim──▶ PROCESSING(lease) ──成功──▶ 终态(PUBLISHED/DONE)
                                  ├──失败未超限──▶ PENDING（退避重试）
                                  └──失败超限────▶ DEAD（人工）
回路：poll → claim（短事务 + FOR UPDATE SKIP LOCKED）→ worker pool 执行副作用
      → finalize（重新校验 lease token）→ recover（lease 超时放回）
```

### 2.1 我对这套机制的评价：valid，而且正是投递该用的形状

通知投递的本质 = “定时扫描待发 → 调外部接口（有副作用、会失败、会慢）→ 记录结果 → 失败重试 / 死信”。
这与 Outbox 的“扫描待发事件 → 发 Kafka（有副作用、会失败、会慢）→ 记 PUBLISHED → 重试 / DEAD”
**同构**。我把投递实现成这套模式的**第三个实例**，逐条评价它为什么对：

- **claim 是短事务（`NotificationDeliveryClaimer`）** — ✅ 正确且关键。claim 只做 `PENDING→PROCESSING`
  并提交；**provider 调用绝不进这个事务**。
  *如果* 把外部 HTTP 调用放进 claim 事务，provider 的 latency（甚至 brownout 几秒）会被放大成 MySQL
  row lock 的持有时间，把别的写入（甚至别的 pod 的 claim）全堵住。这是“别在事务里做 I/O”的教科书理由。
- **PROCESSING 是 lease 不是所有权（`...Recoverer`）** — ✅ 必需。worker/pod 在“已 claim、未 finalize”
  之间宕机，*如果* 没有 recoverer，这条投递永远停在 PROCESSING、永远不可见，可靠投递链路就断了。
- **finalize 校验 lease token（`NotificationDeliveryWorker.lockCurrentLease`）** — ✅ 防并发覆盖的命门。
  迟到的旧 worker 不能覆盖 recoverer/新 worker 的结果。token 用独立 UUID `lease_token` 做乐观比较，
  现在和 OutboxWorker / DelayJobWorker 完全一致。
- **副作用在事务外、finalize 在另一个短事务（`TransactionOperations`）** — ✅ 直接照搬 `OutboxWorker`：
  等 provider 回执的耗时不占 DB 事务，只在写状态时短暂开事务。
- **bounded worker pool + 队列满回退（`notificationDeliveryWorkerExecutor` + poller 捕获 `TaskRejectedException`）**
  — ✅ 一种机制的 backlog 不会占满别的机制的线程。

### 2.2 我对机制提的一处“修正/补充”：投递需要独立的 per-channel 状态行

Outbox 的工作单元（一行 `outbox_events`）天然自洽——payload 已序列化、partitionKey 已定。但通知的旧设计
把“投递状态”塞在 `Notification` 聚合上，这在多渠道下**不成立**：同一条通知可能 push 成功、email 仍在重试，
**单个 status 字段无法同时表达两个渠道的进度**。

所以我做的结构性修正是：**把投递生命周期从 notification 搬到一张新表 `notification_deliveries`，一渠道一行**。
`Notification` 退回成不可变“意图”。这不是推翻 claimable job 机制，而是给它配一个**正确粒度的工作单元**——
机制不变，工作单元从“一条通知”细化为“一条(通知,渠道)投递”。旧聚合上的 `markSent`/`recordDeliveryFailure`
不是删掉，而是**搬到了正确的层**（`NotificationDelivery`）。

---

## 3. 三个正交概念：type / channel / template

早期设计里 `notifications.template` 列名是错配的——它存的其实是 `NotificationType` 枚举，不是渲染模板
（已正名为 `notification_type`，把“template”这个词腾出来；当前 changelog 已合并，
`0001-initial-schema.sql` 直接就是最终结构）。本设计严格区分三者：

| 概念 | 含义 | 例子 | 落点 |
| --- | --- | --- | --- |
| `NotificationType` | **发生了什么**（语义事件） | `AUTHORIZATION_APPROVED`、`CARD_TRANSACTION_POSTED` | 由 listener 翻译事件得到 |
| `NotificationChannel` | **怎么触达用户** | `APP_PUSH` / `EMAIL` | 当前按已实现 enum 全量扇出 |
| template（渲染） | **怎么把它说出来** | push 标题/正文、email 主题/正文 | sender 内部用轻量模板 helper 组装 |

一条 `type` 会按当前系统支持的多个 `channel` 扇出成多条投递；每条投递在发送时由对应
`NotificationDeliverySender` 按 (type, channel) 组装标题/正文。新增渠道（SMS/LINE）= 加 enum + 注册一个
sender + 模板 helper 补一种形态，worker/状态机/表结构都不动。未来如果接入用户渠道偏好，再增加
`NotificationChannelSelector` 一类的创建期选择器，而不是提前恢复 resolver/renderer/dispatch 多层端口。

---

## 4. 数据模型

### 4.1 `notifications`（不可变意图）

只剩意图字段：`id, source_event_id(唯一), subject_type, subject_id, recipient_key, notification_type,
created_at, updated_at`。相比早期单表设计，删掉了 `status / delivery_attempts / last_error / sent_at`
四个投递列及其 CHECK——它们属于 per-channel 投递，已搬到下表（当前 changelog 已合并，
`0001-initial-schema.sql` 直接包含两张表的最终结构）。

### 4.2 `notification_deliveries`（投递，新表）

```sql
notification_deliveries(
  id, notification_id(FK→notifications),
  channel,                 -- APP_PUSH / EMAIL
  notification_type, subject_id, recipient_key,  -- notification 的 immutable 快照(denormalized)
  status,                  -- PENDING / PROCESSING / SENT / DEAD
  attempts, next_attempt_at,   -- next_attempt_at = lease deadline(WHEN 到期，供 recoverer 扫描)
  lease_token,             -- lease identity(WHO 持有，CHAR(36) UUID，供 worker finalize 校验)
  last_error, provider_message_id, sent_at,
  created_at, updated_at,
  UNIQUE(notification_id, channel),                       -- 同一(通知,渠道)不可能两条
  INDEX(status, next_attempt_at, created_at)              -- 覆盖 poller 扫描路径
)
```

两个刻意的设计点：

- **denormalize 三个快照列**（`notification_type / subject_id / recipient_key`）：让一条投递成为**自洽工作单元**，
  sender 组装文案、合成/查询地址时**不必 JOIN notifications**，也为“未来把投递拆成独立微服务”留好边界。
  这与 `outbox_events` 把 payload/partitionKey 直接存在行上、而不是回查聚合，是同一思路。
- **`UNIQUE(notification_id, channel)`**：投递在创建期已被 Inbox + `source_event_id` 唯一键双重保护，
  这条唯一键是 belt-and-suspenders——*如果* 将来创建路径改写出 bug 导致并发重复建投递，唯一键兜底。

---

## 5. 投递回路与组件

### 5.1 角色分工

| 组件 | 职责 | 对标 |
| --- | --- | --- |
| `RequestNotificationService` | 创建意图 + 按当前支持渠道扇出投递（同一事务） | — |
| `NotificationDeliveryClaimer` | 短事务 `FOR UPDATE SKIP LOCKED` 领取 PENDING、打 lease | `OutboxClaimer` |
| `NotificationDeliveryPoller` | `@Scheduled` 扫描 → 提交 worker pool | `OutboxPoller` |
| `NotificationDeliveryWorker` | 按 channel 找 sender，事务外发送 → finalize（短事务+校验 lease） | `OutboxWorker` |
| `NotificationDeliveryRecoverer` | `@Scheduled` 把 lease 超时的 PROCESSING 放回 | `OutboxRecoverer` |
| `NotificationDeliverySender`(push/email) | 单渠道地址/文案组装 + Feign provider 调用（当前回环模拟） | — |
| `ResilientCallHelper` | 只封装 Retry + RateLimiter + CircuitBreaker 的重复装饰代码 | — |
| `NotificationProviderClient` | OpenFeign HTTP client；timeout 在 HTTP client 层配置 | `ExternalRiskClient` |
| `SimulatedNotificationProviderController` | provider-side idempotency / latency / failure demo | `SimulatedExternalRiskController` |

### 5.2 状态机

```
NotificationDelivery：
  PENDING ──claim──▶ PROCESSING(lease=now+30s) ──provider ack──▶ SENT(终态)
                          │
                          ├── 本地限流/worker pool 拒绝(HTTP 尚未发生)
                          │      ──▶ PENDING(nextAttemptAt=now+fixedDelay, attempts 不变)
                          ├── transient 失败(超时/5xx/断路) 且 attempts<8 ──▶ PENDING（nextAttemptAt=now+2^(n-1)s, ≤60s）
                          ├── attempts≥8 ─────────────────────────────▶ DEAD(终态, 等人工/admin 重放)
                          ├── permanent 失败(provider 4xx, ErrorDecoder 转成
                          │   NotificationDeliveryPermanentException) ──▶ DEAD(终态, 只记录本次失败，不耗完整预算)
                          └── lease 超时（worker 宕机）──recover──▶ 按一次 transient 失败处理（同上）
```

退避与 maxAttempts 直接复用 `OutboxEvent` 的实现：`delay = min(2^(attempts-1), 60s)`，`maxAttempts=8`。

### 5.3 完整调用链（端到端）

```
业务事务(authorization/posting) ─Outbox→Kafka─▶ Authorization/CardTransactionNotificationListener
  → RequestNotificationService.requestNotification(@Transactional):
       inbox.claim(notification-v1, eventId)             # 消费幂等第一道
       notifications.insertIfAbsent(intent)              # source_event_id 唯一键第二道
       channels = NotificationChannel.values()           # 当前 demo：APP_PUSH + EMAIL 全量扇出
       deliveries = [pendingFor(intent, ch) for ch in channels]              # 扇出
       deliveryRepo.insertAll(deliveries)                # 与意图同事务提交
  ── 后台 ──
  NotificationDeliveryPoller(每1s):
     claimer.claim() → [PENDING→PROCESSING lease, 提交]
     executor.submit(worker.handleClaimedDelivery(d))
  NotificationDeliveryWorker(worker 线程, 事务外):
     providerMessageId = senderByChannel[channel].send(delivery)
       # sender 内部：合成/查询地址 + 组装文案 + Retry(CircuitBreaker(RateLimiter(provider.send)))
     [短事务] lockCurrentLease → markSent(providerMessageId) / markFailed(transient) / markPermanentFailed(4xx) → update
  NotificationDeliveryRecoverer(每5s): 捞 lease 超时的 PROCESSING，按失败处理
```

---

## 6. 幂等性：为什么只能“至少一次 + 下游去重”，做不到完美 exactly-once

这是本设计最该讲清的一点。系统里有**三段**需要幂等，强度递减：

### 6.1 生产者 → 通知意图：可做到“恰好一条通知”

两道闸：
1. **Inbox claim**（`consumer_inbox`，消费者 `notification-v1`）：Kafka at-least-once 重复投递同一 `eventId`，
   只有第一次 claim 成功，后续直接跳过。
2. **`notifications.source_event_id` 唯一键**：即便 Inbox 数据被清理/迁移出错，唯一键仍挡住重复创建。

因为意图创建是纯 DB 操作、且在一个本地事务里，这一段能保证**一条事件 ⇒ 恰好一条通知意图**。

### 6.2 通知意图 → 投递记录：可做到“恰好一组投递”

投递记录和意图在**同一个事务**里写（`request()` 里 `insertIfAbsent` 成功后紧接 `insertAll`）。要么一起提交、
要么一起回滚；重复事件在 6.1 已被挡掉，根本到不了这里。`UNIQUE(notification_id, channel)` 再兜一层。
所以**一条意图 ⇒ 每个渠道恰好一条投递**。

### 6.3 投递 → 外部 provider：只能 at-least-once，靠下游去重逼近 effectively-once

到了“调外部接口”这一步，**理论上就不可能 exactly-once**了。根因：MySQL 的状态更新和“调 provider 这个外部
副作用”**无法纳入同一个原子事务**（没有跨 MySQL 与 push/email 网关的分布式事务）。一定存在一个崩溃窗口：

```
崩溃窗口 trace：
  t0  worker: provider.send(idemKey=D) → provider 接受、真的发了一条 push，返回 receipt R
  t1  worker 所在 pod 在“拿到 R 之后、markSent 事务提交之前”宕机          ← 关键窗口
  t2  lease 到期；Recoverer 把 D 从 PROCESSING 放回 PENDING
  t3  另一个 worker 重新领取 D，再次 provider.send(idemKey=D)
```

t3 必然“再发一次”。我们能控制的不是“要不要再发”，而是“再发会不会让用户收到第二条”：

> **把稳定幂等键 `idempotencyKey = deliveryId` 透传给 provider，由 provider 按键去重。**
> 于是 t3 的重复请求拿到的是 t0 那条的**同一个回执**，用户侧只收到一条 ⇒ **effectively-once（有效恰好一次）**。

代码落点：`NotificationDelivery.idempotencyKey()` 返回稳定的 `delivery id`（多次重试不变）；
`NotificationDeliverySender.send(delivery)` 经 `NotificationProviderClient` 把它一路传到模拟 provider；
`SimulatedNotificationProviderController` 用一个
`ConcurrentHashMap<provider + idemKey, providerMessageId>` 演示 provider 的去重承诺——重复键直接返回原
message id、不产生第二次副作用。

**结论（直接回答你的问题）**：是的，带外部副作用只能做到 **at-least-once 投递 + 下游按幂等键去重 =
effectively-once**。真正的端到端 exactly-once 需要 MySQL 和外部网关共同参与一个分布式事务，现实中不存在，
也不值得（2PC 的可用性代价远大于“让下游幂等”）。这正是 `OutboxEvent` 注释里那句“整体语义仍是 at-least-once,
consumer 必须去重”在投递侧的对应。

> 注：如果某个真实 provider **不提供**幂等键去重（少数短信/邮件网关），就退化为“可能偶发重复”，
> 这时要么接受、要么自建“已发送指纹表”在调用前 check-and-set（又会引入一次它自己的崩溃窗口，只是更小）。
> 没有银弹——只能把重复概率压到崩溃窗口那一瞬。

---

## 7. Resilience4j：重试 + 断路（以及“两层重试”）

外部 provider 调用由每个 `NotificationDeliverySender` 内部通过 `ResilientCallHelper` 包裹。helper 很薄，
只收住重复的 **programmatic Decorators** 代码，组合顺序（由外到内）：

```
Retry( CircuitBreaker( RateLimiter( provider.send ) ) )
```

**组装从内向外（先包 RateLimiter，最后包 Retry），执行从外向内**：`decorated.get()` 先进 Retry 的循环，
每一轮 attempt 依次问 CircuitBreaker（断路 OPEN 吗）→ RateLimiter（本 pod 有 permit 吗）→ 都放行才轮到
Feign 真正发 HTTP。谁先拒绝，更内层就一步都不执行。一次 `helper.call()` 只有四种出口，区分标准是
**provider HTTP 是否真的发生过**——它决定 durable attempts 预算被谁消耗：

| 出口 | helper 抛给 worker 的类型 | worker 动作 | attempts |
| --- | --- | --- | --- |
| 成功 | 正常返回 providerMessageId | `markSent` → SENT | 不变 |
| 本地限流拒绝（HTTP 未发生） | `NotificationDeliveryThrottledException` | `rescheduleWithoutAttempt` → PENDING（+fixed-delay） | **不变** |
| provider 4xx（ErrorDecoder 判为永久失败） | `NotificationDeliveryPermanentException` | `markPermanentFailed` → DEAD | +1（仅记录，结局已定） |
| 断路 OPEN / 超时 / 5xx / R4j 重试耗尽 | `IllegalStateException`（包装） | `markFailed` → 退避回 PENDING 或 DEAD | +1 |

逐步展开的执行编号 trace 见 `ResilientCallHelper.call` 的 javadoc；下面按组件解释每一层为什么放在那个位置。

- **RateLimiter（每渠道一个：`notificationPush` / `notificationEmail`）**：这是出站
  **client-side throttling**，限制本 pod 打 provider 的速率。它不适合做入站全局限流：
  入站 API 限流要跨实例共享状态和 TTL，本项目用 Redis Lua token bucket；notification 出站配额
  通常可以按 pod 数摊分，放在本地内存里少一次 Redis 往返，也不会让 Redis 故障影响通知发送。
  `timeout-duration=0` 表示抢不到许可就 fail-fast，不占住 worker thread 等令牌。helper 将
  `RequestNotPermitted` 翻译成 `NotificationDeliveryThrottledException`；worker finalize 时释放 lease，
  按 `fixed-delay-ms` 延后 `next_attempt_at`，但不增加 `attempts`，因为 provider HTTP 尚未发生。
  `notification.delivery.throttled` counter 用来观察持续超配额，生产报警还应结合 oldest PENDING age。
- **为什么 CircuitBreaker 包在 RateLimiter 外面**：这样 breaker OPEN 时可以先快速失败，不消耗
  本地 rate-limit permit。代价是 RateLimiter 的 `RequestNotPermitted` 会经过 breaker，所以
  `notificationPush` / `notificationEmail` 的 `circuitbreaker.ignore-exceptions` 必须包含它；
  这样我方主动节流不会污染 provider health 统计。`timeout-duration=0` 也很关键，否则等待令牌的时间
  会被外层 breaker 看见，slow-call 指标就不再只代表 provider。
- **CircuitBreaker（**每渠道一个**：`notificationPush` / `notificationEmail`）**：失败率/慢调用率超阈值就 OPEN，
  快速 fail（`CallNotPermittedException`）。**按渠道隔离**是刻意的：*如果* push 和 email 共用一个断路器，
  email 网关 brownout 会把 push 也一起熔断，明明 push 是好的。
- **slow-call 阈值要小于 read timeout**：本项目 Feign read-timeout=2s，slow-call-duration-threshold=1s，
  因此 1s~2s 是"慢但成功"观测带；超过 2s 的等待由 Feign 硬切断并计为失败。若两者都设 2s，
  慢调用会先被 read timeout 截胡，slow-call 统计就形同虚设。
- **Retry（`notificationDelivery`：max-attempts=3, 指数退避 200ms×2）**：对 provider 抖动做**快速、内存级**重试。
- **Retry 忽略 `RequestNotPermitted`**：RateLimiter 满说明本 pod 已经超过 provider quota，
  不是 provider brownout；同一轮马上 retry 只会继续抢不到许可，应交给 durable retry/backoff。
- **Timeout 不放在 helper 里**：HTTP provider 的 connect/read timeout 由 OpenFeign/HTTP client 配置。
  这里不使用 TimeLimiter，因为它需要额外线程池和 async future；为了一个 worker 调用把同步链路重新复杂化，
  得不偿失。慢调用仍可通过 CircuitBreaker 的 slow-call 阈值被统计和隔离。
- **Retry 忽略 `CallNotPermittedException`**：断路器已经 OPEN 时，继续 retry 只会快速失败多次；
  这种情况直接交给 worker 的 durable retry/DEAD 状态机更清楚。
- **4xx 是 permanent failure**：`NotificationProviderFeignConfiguration` 的 ErrorDecoder 把 provider 4xx 转成
  `NotificationDeliveryPermanentException`；R4j Retry 不重试它，CircuitBreaker 不把它计入 provider brownout，
  worker 也直接把 delivery 标成 DEAD。5xx/timeout/连接失败才按 transient failure 走 R4j retry 和 durable retry。
- **Feign 自身不重试**：保持 Spring Cloud OpenFeign 默认 `Retryer.NEVER_RETRY`，让重试预算只在 R4j 一层；
  否则会变成 R4j 次数 × Feign 次数的真实 HTTP 请求。真实 provider 接入后还应分类 4xx/5xx：
  4xx 多半是确定性 contract/config failure，不应像 timeout/5xx 一样重试。

### 7.1 为什么是“两层重试”，它们分工不同

| | Resilience4j Retry | 投递状态机（attempts/退避/DEAD） |
| --- | --- | --- |
| 粒度 | **一次** worker 处理内 | **跨**多次调度、跨 pod、跨重启 |
| 时长 | 毫秒级（几百 ms） | 秒~分钟级（退避到 60s，最多 8 次） |
| 存活 | 纯内存，pod 死就没了 | **durable**，存在 DB，pod 死了别的 pod 接着来 |
| 用途 | 抹平瞬时抖动（一次超时、偶发 5xx） | 扛较长 outage、最终 DEAD 兜底 |

也就是说：worker 处理一条投递时，Resilience4j 先在内部快速重试 3 次（≈ 几百 ms）；若仍失败，异常冒泡到 worker，
`markFailed` 让这条投递**退避后回到 PENDING**，等下一轮 poller 再来（这才是 durable 的那层）。两层叠加既能
快速抹平抖动，又能用便宜的内存重试**减少**打到 DB 的 durable 重试次数。这一点在面试里是很好的“纵深”话术。

> 取舍：Resilience4j 的 3 次 × 投递的 8 次，最坏情况下对 provider 的总尝试是相乘的。我把 Resilience4j 次数
> 压得很小（3），就是因为它只该抹瞬时抖动；扛长时间故障是 durable 那层的事。

### 7.2 一次 send 的执行时序：lambda 从创建到执行（谁做了什么）

第一次读 sender 里这段代码，最容易困惑的是"到底哪一行真正发了 HTTP"：

```java
return resilientCallHelper.call(
        "notificationEmail",
        () -> notificationProviderClient.send(request).providerMessageId()
);
```

答案：**这一行没有发**。理解它的关键是把"组装"和"执行"分成两个时刻：

1. **lambda 是装着代码的对象，创建 ≠ 执行**。`() -> ...` 只是 new 了一个 `Supplier` 对象——一个"信封"，
   里面装着冻结的代码，并捕获了外面已构造好的 `request` 引用（闭包）。`Supplier` 只有一个方法 `get()`，
   信封里的代码只在某人调 `get()` 时才运行。sender 把信封交给 helper，等于说："这是发送的方法，
   什么时候执行、执行几次，由你决定。"
2. **`decorateSupplier` 也不执行，只包装**。每包一层返回一个新 Supplier，其 `get()` 先做自己的事
   （问断路器/限流器要许可 / 捕获异常决定重试），再调内层 `get()`。三层套完得到 `decorated`，仍然 0 次 HTTP。
3. **`decorated.get()` 是唯一的执行触发点**。逐步展开见 `ResilientCallHelper.call` 的 javadoc；
   真实 HTTP 次数是 0 次（限流 / 断路 OPEN / 4xx permanent）、1 次（成功）或最多 3 次（transient 重试）。

一次"第 1 次失败、第 2 次成功"的完整时间线：

```text
sender.send(delivery)
 1. 组装 request（地址/标题/正文/幂等键）              —— 纯内存，无网络
 2. 创建 lambda 信封（捕获 request）                   —— 无网络
 3. helper.call("notificationEmail", 信封)
    3.1 从 registry 取 notificationEmail 限流器/断路器（共享有状态实例）和 notificationDelivery 重试规则
    3.2 套三层娃得到 decorated                          —— 仍无网络
    3.3 decorated.get()                                —— 从这里开始动真格
        尝试1: 断路器 CLOSED 放行、限流器有许可，拆信封：Feign 代理序列化 request，HTTP POST，
               controller 注入失败抛 500，FeignException 抛回，断路器记一次失败
        Retry: 不在 ignore 名单、attempt<3，睡 200ms
        尝试2: 断路器再次放行，再取一次限流许可，第二次 HTTP（同一个幂等键！），controller 成功返回 messageId，
               断路器记一次成功，Retry 循环结束
    3.4 返回 messageId
 4. sender 返回给 worker，worker 开 finalize 短事务 markSent
```

角色分工一句话版：**sender** 决定"做什么"（组装 request、创建信封），**helper** 决定"怎么执行"
（取实例、套娃、触发）；**Retry 对象**是循环加退避，**CircuitBreaker 对象**是门卫加统计员
（按名字取的共享实例，跨 delivery 累积滑动窗口）；**Feign 代理**是信封被拆开时真正干活的人——
`NotificationProviderClient` 接口没有实现类，启动时由 OpenFeign 生成动态代理，负责注解转 URL、
JSON 序列化、HTTP 收发。这就是装饰器模式：不改信封里的代码，靠层层包裹叠加行为；lambda 是两侧解耦的交接物。

两个自测问题：① 为什么 `request` 要在 lambda 外先构造好？（构造不需要重试保护，且三次重试必须共用
同一份内容和同一个幂等键）② 删掉两行 `decorateSupplier` 直接 `providerCall.get()` 会怎样？
（照样能发，但失败一次就抛给 worker，没有快重试，断路器也永远不知道这次调用发生过。）

### 7.3 Feign per-client 配置：ErrorDecoder 挂在哪、和 yml 什么分工

`NotificationProviderFeignConfiguration` 不出现在 yml 里，挂载点是 client 接口上的注解属性：
`@FeignClient(name = "notification-provider", configuration = NotificationProviderFeignConfiguration.class)`。
机制：Spring Cloud OpenFeign 给**每个命名 client 一个独立的子 ApplicationContext**，组装代理时按
"先查该 client 的子容器，再退回全局默认"的顺序找 Encoder/Decoder/ErrorDecoder/Retryer；
这个配置类的 Bean 只注册进 `notification-provider` 的子容器，所以 external-risk 完全不受影响。

两个易踩的点：

- **配置类故意不标 `@Configuration`**。标了会被主容器 component scan 扫成全局 Bean，
  ErrorDecoder 会作用到所有 Feign client，"4xx 翻译成通知专用异常"就泄漏进 risk 链路。
- **同一个 client 有两条配置通道，按名字合并**：yml 的
  `spring.cloud.openfeign.client.config.notification-provider` 管数字类配置（connect/read timeout），
  `configuration = X.class` 管要写代码的组件（ErrorDecoder）。运行时 ErrorDecoder 的触发点在 Feign
  代理内部：响应 2xx 走 Decoder 反序列化正常返回；响应 ≥400 改调 `ErrorDecoder.decode`，
  返回的异常从代理抛出，再进入 §7.2 的 Retry/CircuitBreaker/RateLimiter 链。

顺带回答"`ignore-exceptions` 里的异常类为什么配在 yml 而不是代码"：这是 resilience4j-spring-boot
的主流做法——一条重试策略的全部旋钮（次数/退避/异常分类）与其他 R4j 实例同址，运维可读可调。
代价是类名字符串没有编译期检查，兜底有两层：启动时按名加载类（异常类改名漏改 yml 会启动失败）；
若整行误删，4xx 只是多被快重试 3 次，最终仍会 DEAD（helper 对 PermanentException 原样上抛，
worker 的 `markPermanentFailed` 不依赖重试层）。追求编译期检查的替代写法是 `RetryConfigCustomizer`
Bean——"数字放 yml 是 per-environment tuning、异常分类是代码语义"两种归类都有拥趸，本项目选同址内聚。

---

## 8. 收件地址：没有 User 聚合时为什么先放在 sender 内

现在 `recipientKey` 还是卡线索（authorization/transaction 两类事件都传 `cardId`）——这是
“还没有 User/Customer 聚合”的已知缺口。当前代码选择**不再提前抽
`NotificationRecipientResolver` 端口**，而是在两个 sender 内合成确定性地址：

```text
APP_PUSH sender: "push-token-" + recipientKey
EMAIL sender:    "user-" + recipientKey + "@example.com"
```

这样做不是说生产代码应该把地址拼死，而是承认当前项目还没有 User 域、联系人表、设备 token 表、渠道偏好表。
在这个规模下，保留 resolver/recipient DTO 会让代码像在给不存在的变化付定金。未来真实需求出现时，再按需求补：

- 只需要用户渠道偏好：在 `RequestNotificationService` 前加 `NotificationChannelSelector`，决定扇出哪些 channel。
- 需要真实联系方式：在具体 sender 内查 contact/device token，或者拆出真正的 contact gateway。
- 需要模板引擎/多语言：把现在包私有的 `NotificationMessageTemplates` 替换成真实模板服务。
- 它也是**微服务拆分**时的数据边界：投递服务要么通过事件复制一份联系方式（推荐，异步、抗故障），
  要么同步调 customer 服务（会把投递可用性绑死在 customer 服务上，不推荐）。

地址/app key 既然是 stub，就**合成确定性值**而非随机：便于测试断言、便于日志对照。push token / email 真值在
真实系统里属于敏感信息，本设计**不落在 `notification_deliveries` 行上**（行里只存 `recipient_key`），发送时
才即时解析——避免把 PII 快照进投递表。

---

## 9. 模拟外部接口

`NotificationProviderClient` 是 OpenFeign client，默认通过 `provider-base-url=http://localhost:8080`
回环调用本应用里的 `SimulatedNotificationProviderController`。两个 sender 只负责合成地址/文案并调用 Feign client。
模拟 controller 刻意做三件用于演示/教学的事：

1. **按 `provider + idempotencyKey` 去重** → 证明 §6.3 的 effectively-once；
2. **可注入失败率**（`simulated-failure-rate-percent`）→ 驱动 Resilience4j 重试/断路与投递的退避/DEAD；
3. **可注入延迟**（`simulated-latency-millis`）→ 让 Feign read timeout、slow-call CircuitBreaker 与 lease/retry 的关系可被观察到。

接真实 provider 时替换 Feign 的 base URL / endpoint wrapper，worker/状态机不动。默认 `failure=0`、
`latency=20ms`，所以默认行为是“都成功”；演示故障路径时把这两个值调大即可。

---

## 10. 模拟 trace（多角度）

### 10.1 Happy path：一条 card_transaction.posted 扇出两条投递

```
listener → request(): inbox.claim ✓; insert intent N; channels={APP_PUSH,EMAIL};
           insertAll([D_push(PENDING), D_email(PENDING)]) 同事务提交
poller#1: claim [D_push,D_email] → 各 PROCESSING(lease=now+30s)，提交
worker(D_push): sender 合成 token+文案；Retry(CB(RL(provider.send))) → push-provider ack(R1)
                短事务: lockCurrentLease ✓(PROCESSING & lease 未变) → markSent(R1) → SENT
worker(D_email): 同上 → SENT(R2)
结果: 两条 SENT，用户收到 1 条 push + 1 封 email。
```

### 10.2 失败 → 退避 → DEAD（email 网关一直挂）

```
worker(D_email) 第1次: Resilience4j 内部重试 3 次全失败/断路 → 抛异常
   markFailed: attempts=1 → PENDING, nextAttemptAt=now+1s
poller 第2轮(≥1s后): 再 claim D_email → ... 失败 → attempts=2, +2s
... 指数退避 1,2,4,8,16,32,60,60s ...
第8次失败: attempts=8 ≥ maxAttempts → DEAD（停止自动重试，等人工/admin 重放）
其间 D_push 早已 SENT —— 单渠道故障不影响另一渠道，这正是 per-channel 行的价值。
```

### 10.3 崩溃 → 恢复（§6.3 的窗口）

```
worker(D_push): provider.send(idem=D_push) → 已发出、得 R1；pod 在 markSent 提交前宕机
（D_push 仍是 PROCESSING，lease=t0+30s）
Recoverer(t0+31s): 捞到超时 PROCESSING → markProcessingTimedOut → attempts=1, PENDING
poller: 重新 claim D_push → worker 再 send(idem=D_push)
   → 模拟 provider 命中去重表，返回**同一个 R1**，不再发第二条 push
   → markSent(R1) → SENT。用户只收到 1 条。 effectively-once 成立。
```

### 10.4 重复事件（Kafka 重投）

```
同一 eventId 第二次到达 listener → request(): inbox.claim 返回 false → 整个方法早退。
不创建第二条意图、不创建第二批投递。 §6.1/§6.2 生效。
```

---

## 11. 竞态分析（多角度）

- **多 pod 同时 poll**：`FOR UPDATE SKIP LOCKED` 让每条 PENDING 只被一个 pod 锁到，其余 pod 跳过该行，
  天然水平扩展，不会两 pod 处理同一投递。
- **迟到 worker vs recoverer**：worker A 在 D 上超时被 Recoverer 放回、又被 worker B 领取（新 lease）；
  此时 A 才慢悠悠回来想 markSent。`lockCurrentLease` 比较 `lease_token`：A 持的是旧 token，与当前行的新 token
  不符 → 返回 null → A 的 finalize **被丢弃**，不会覆盖 B。*如果* 不校验 token，A 可能把 B 正在处理
  （甚至已失败重试）的投递错误标成 SENT。
- **lease token 为什么用独立 UUID，而不是 `nextAttemptAt` 时间戳**（一个真实踩过的坑）：`nextAttemptAt` 是
  `Instant.now(clock)`（`Clock.systemUTC()`，Linux 上**纳秒**精度），但 `next_attempt_at` 是 `TIMESTAMP(6)`
  **微秒**列。claim 返回的是**内存**对象（纳秒），worker finalize 时**回读 DB**（微秒截断），两者 `equals` 会
  **确定性地为 false**。后果：provider 已成功、worker 却误判 "lease changed" 不写 SENT → recoverer 反复重试 →
  最终 **DEAD**（一条其实已送达的通知）。独立 `lease_token`（CHAR(36) UUID 精确比较）既不受时间戳精度影响，
  也避免"同一微秒两次 claim 撞同一时间戳令牌"。同样的独立 token 现在也用于 `outbox` / `delayjob`，
  三个轻量 claimable job 家族都不再用 `nextAttemptAt` 当 owner token。
- **worker 池满**：poller 提交时抛 `TaskRejectedException` → `markRejectedForRetry` 立刻把投递放回退避重试，
  而不是让它干等到 lease 超时才被 Recoverer 捡回（更快回到队列）。
- **provider 去重表与并发**：`computeIfAbsent` 保证同一 idemKey 并发只生成一个 receipt（模拟 provider 的幂等）。
- **创建期并发**：靠 §6.1/§6.2 的 Inbox + 两个唯一键，重复创建不可能穿透。

---

## 12. 微服务拆分就绪

通知投递已经具备“拆成独立服务”的全部前提，且本设计没有引入新的反向耦合：

- listener 只依赖 Kafka integration event contract，不依赖生产方 domain class（既有）。
- 投递自带快照列，worker 不 JOIN 别的聚合（本设计新增）。
- 收件人/联系方式当前是 demo 合成值；拆分时可换成“事件复制 + 本地只读副本”或 sender 内 contact gateway。
- 拆分动作：把 `NotificationDeliveryPoller` 换成“消费自有 topic 的 delivery consumer”即可，投递状态机/sender/
  模板**不动**。当前用 DB poller 而非再发一条 Kafka，是因为单体阶段 poller 更简单、且复用了既有 claimable job
  基础设施；这是 *有意* 的阶段选择，不是缺陷。

---

## 13. 配置项（`application.yml`）

```yaml
notification.delivery:
  provider-base-url: http://localhost:8080  # Feign 回环调用本地模拟 provider；生产改成真实 provider wrapper
  enabled: true                  # 关掉=只建 PENDING 投递、不外发（迁移/排查时隔离副作用）
  fixed-delay-ms: 1000           # poller 扫描间隔
  recovery-fixed-delay-ms: 5000  # recoverer 扫描间隔
  batch-size: 50
  processing-timeout-seconds: 30 # lease 超时；必须 > 一次(含 R4j 重试)发送+finalize 的正常耗时
  max-attempts: 8                # 超过进 DEAD
  worker-pool-size: 4
  worker-queue-capacity: 100
  simulated-latency-millis: 20   # 模拟 provider 延迟（调大演示 slow-call 断路/lease 关系）
  simulated-failure-rate-percent: 0  # 调大演示重试/断路/DEAD

resilience4j:
  circuitbreaker.instances: { notificationPush: {...}, notificationEmail: {...} }  # 每渠道独立
  ratelimiter.instances:    { notificationPush: {...}, notificationEmail: {...} }  # 每渠道本 pod 出站配额
  retry.instances.notificationDelivery: { max-attempts: 3, 指数退避 200ms×2,
                                          ignore RequestNotPermitted + CallNotPermittedException
                                                 + NotificationDeliveryPermanentException }

spring.cloud.openfeign.client.config.notification-provider:
  connect-timeout: 300
  read-timeout: 2000
```

线程与池：scheduler（`notificationDeliveryTaskScheduler`，2 线程，只 poll/claim）/ worker pool
（`notificationDeliveryWorkerExecutor`，调 provider+finalize）分离，互不挤占，命名前缀便于 thread dump 排障。
通知发送不再有额外 sender pool；provider 超时交给 Feign/HTTP client，避免为了 TimeLimiter 把同步链路拆成
async future。

---

## 14. 取舍与后续

- **Notification 退成纯意图**：放弃了“在一行就能看出整体投递状态”的便利，换来多渠道下语义正确。若需要“整体
  状态”用于查询，可在 notification 上加一个**派生 rollup**（由投递 finalize 时回写），本设计未做，作为后续。
- **provider 仍是模拟**：真实化只换 Feign base URL / endpoint wrapper + Resilience4j 阈值调参；worker/状态机不动。
- **地址/偏好仍是 demo**：等真实 User/Customer 聚合落地，再在创建期加入 channel selector，或在 sender 内查 contact/device token。
- **DEAD 的处理**：当前只停重试、记 `last_error`，靠人工/admin。后续可加一个 admin 重放端点（把 DEAD 改回
  PENDING）和告警。
- **可观测性**：worker/recoverer 已打结构化日志（`notification_delivery_sent/failed/recovered`），
  throttling 另有 `notification.delivery.throttled` counter；生产还应对 oldest PENDING age 和 DEAD backlog 告警。

---

## 15. 一句话总结

把“通知”拆成**不可变意图**与**per-channel 投递**两层，投递复用项目已验证的 **claimable job** 回路
（SKIP LOCKED 领取 + lease + 校验 token finalize + recover），外部调用用 **Resilience4j**（断路+快速重试）
包裹、并与投递状态机的 **durable 重试**形成两层防线；幂等上承认“带外部副作用只能 at-least-once + 下游按
`deliveryId` 去重 = effectively-once”。当前没有 User 域，所以 sender 内合成地址；未来有真实偏好/联系方式时再加
channel selector 或 contact gateway。
```
