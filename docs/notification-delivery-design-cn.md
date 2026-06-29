# Notification 真实投递设计（app push + email）

> 关键词：通知意图 vs 投递, per-channel delivery, claimable job, FOR UPDATE SKIP LOCKED,
> PROCESSING lease, lease token, 指数退避, DEAD, 幂等, at-least-once, 下游去重, effectively-once,
> Resilience4j, TimeLimiter, CircuitBreaker, Retry, intra-attempt vs durable retry, recipient resolver,
> 配信(はいしん), 冪等性(べきとうせい)。

本文说明 `notification` 模块从“只记录要通知”重构为“能真实投递（app push + email，外部接口先模拟）”
的完整设计：它如何**对齐项目已有的 message / poller 机制**，对那套机制的**评价**，渠道/模板/类型三者的
关系，幂等性能做到什么程度（为什么只能“至少一次 + 下游去重”、不可能完美 exactly-once），Resilience4j
如何承担 timeout / 重试 / 断路，以及“还没有 User 聚合时收件人怎么 stub”。

相关文档：
- 事件 / Outbox / Inbox / Kafka 可靠投递与消费幂等细节见 [events-outbox-inbox-kafka-cn.md](events-outbox-inbox-kafka-cn.md)。
- claimable job 家族对比（DelayJob / Outbox / StatementJob，本投递是它的第三个实例）见 [claimable-jobs-cn.md](claimable-jobs-cn.md)。

---

## 1. 目标与范围

**之前**：authorization / card_transaction / repayment / statement 四个 Kafka 事件经 listener 进入
`RequestNotificationService`，只写一行 `notifications`（状态恒为 PENDING），**没有任何东西真的把它发出去**。
`Notification` 聚合上甚至有 `markSent` / `recordDeliveryFailure` 一整套生命周期方法，但**零调用者**——是
“声称有、其实不跑”的死代码。

**现在**：一条通知按收件人启用的渠道**扇出（fan-out）**成多条 `notification_deliveries`，每条由一套
claimable-job 回路（poller → claim → worker → recoverer）独立投递，worker 通过 Resilience4j 包裹的渠道
sender 调用（当前是模拟的）外部 push/email provider，成功标 `SENT`，失败按指数退避重试，超限进 `DEAD`。

范围内：多渠道扇出、可靠投递回路、Resilience4j（timeout+retry+circuit breaker）、幂等、模拟 provider、
收件人 stub。范围外（留接缝）：真实 push/email SDK、真实 User/Customer 聚合与渠道偏好、多语言模板引擎、
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

重构前 `notifications.template` 列名是错配的——它存的其实是 `NotificationType` 枚举，不是渲染模板（已在
迁移 `0006` 里正名为 `notification_type`，把“template”这个词腾出来）。本设计严格区分三者：

| 概念 | 含义 | 例子 | 落点 |
| --- | --- | --- | --- |
| `NotificationType` | **发生了什么**（语义事件） | `CARD_TRANSACTION_POSTED`、`STATEMENT_CLOSED` | 由 listener 翻译事件得到 |
| `NotificationChannel` | **怎么触达用户** | `APP_PUSH` / `EMAIL` | 收件人偏好决定扇出几条 |
| template（渲染） | **怎么把它说出来** | push 标题/正文、email 主题/正文 | `NotificationTemplateRenderer` 按 (type, channel) 渲染 |

一条 `type` 会按收件人启用的多个 `channel` 扇出成多条投递；每条投递在发送时按 (type, channel) 选 template 渲染成
`NotificationContent(title, body)`。新增渠道（SMS/LINE）= 加 enum + 注册一个 sender + 模板补一种形态，
worker/状态机/表结构都不动。

---

## 4. 数据模型

### 4.1 `notifications`（意图，迁移 0007 后）

只剩意图字段：`id, source_event_id(唯一), subject_type, subject_id, recipient_key, notification_type,
created_at, updated_at`。迁移 `0007` 删掉了原先的 `status / delivery_attempts / last_error / sent_at`
四个投递列及其 CHECK——它们属于 per-channel 投递，已搬到下表。

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
  worker 渲染模板、解析收件人时**不必 JOIN notifications**，也为“未来把投递拆成独立微服务”留好边界。
  这与 `outbox_events` 把 payload/partitionKey 直接存在行上、而不是回查聚合，是同一思路。
- **`UNIQUE(notification_id, channel)`**：投递在创建期已被 Inbox + `source_event_id` 唯一键双重保护，
  这条唯一键是 belt-and-suspenders——*如果* 将来创建路径改写出 bug 导致并发重复建投递，唯一键兜底。

---

## 5. 投递回路与组件

### 5.1 角色分工

| 组件 | 职责 | 对标 |
| --- | --- | --- |
| `RequestNotificationService` | 创建意图 + 按渠道扇出投递（同一事务） | — |
| `NotificationRecipientResolver`(stub) | recipientKey → 收件人(渠道+地址) | 未来 User 域接缝 |
| `NotificationDeliveryClaimer` | 短事务 `FOR UPDATE SKIP LOCKED` 领取 PENDING、打 lease | `OutboxClaimer` |
| `NotificationDeliveryPoller` | `@Scheduled` 扫描 → 提交 worker pool | `OutboxPoller` |
| `NotificationDeliveryWorker` | 解析收件人+渲染+调 provider（事务外）→ finalize（短事务+校验 lease） | `OutboxWorker` |
| `NotificationDeliveryRecoverer` | `@Scheduled` 把 lease 超时的 PROCESSING 放回 | `OutboxRecoverer` |
| `ResilientNotificationSender` | 按渠道路由 + 套 Resilience4j 的“发一次”门面 | `KafkaOutboxMessagePublisher`(端口实现) |
| `NotificationChannelSender`(push/email) | 单渠道 provider 原始调用（当前模拟） | — |
| `NotificationTemplateRenderer` | (type, channel) → 渲染内容 | — |

### 5.2 状态机

```
NotificationDelivery：
  PENDING ──claim──▶ PROCESSING(lease=now+30s) ──provider ack──▶ SENT(终态)
                          │
                          ├── provider 失败/超时/断路 且 attempts<8 ──▶ PENDING（nextAttemptAt=now+2^(n-1)s, ≤60s）
                          ├── attempts≥8 ─────────────────────────────▶ DEAD(终态, 等人工/admin 重放)
                          └── lease 超时（worker 宕机）──recover──▶ 按一次失败处理（同上）
```

退避与 maxAttempts 直接复用 `OutboxEvent` 的实现：`delay = min(2^(attempts-1), 60s)`，`maxAttempts=8`。

### 5.3 完整调用链（端到端）

```
业务事务(posting/statement/...) ─Outbox→Kafka─▶ XxxNotificationListener
  → RequestNotificationService.request(@Transactional):
       inbox.claim(notification-v1, eventId)             # 消费幂等第一道
       notifications.insertIfAbsent(intent)              # source_event_id 唯一键第二道
       recipient = resolver.resolve(recipientKey)        # stub：默认 push+email
       deliveries = [pendingFor(intent, ch) for ch in recipient.channels()]  # 扇出
       deliveryRepo.insertAll(deliveries)                # 与意图同事务提交
  ── 后台 ──
  NotificationDeliveryPoller(每1s):
     claimer.claim() → [PENDING→PROCESSING lease, 提交]
     executor.submit(worker.handleClaimedDelivery(d))
  NotificationDeliveryWorker(worker 线程, 事务外):
     addr = resolver.resolve(recipientKey).addressFor(channel)
     content = renderer.render(type, channel, subjectId)
     receipt = resilientSender.send(dispatch)            # Resilience4j: timeout+retry+breaker
     [短事务] lockCurrentLease → markSent(receipt) / markFailed → update
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
`NotificationDispatch.idempotencyKey` 一路传到 sender；模拟 sender（`SimulatedChannelSender`）用一个
`ConcurrentHashMap<idemKey, receipt>` 演示 provider 的去重承诺——重复键直接返回原 receipt、不产生第二次副作用。

**结论（直接回答你的问题）**：是的，带外部副作用只能做到 **at-least-once 投递 + 下游按幂等键去重 =
effectively-once**。真正的端到端 exactly-once 需要 MySQL 和外部网关共同参与一个分布式事务，现实中不存在，
也不值得（2PC 的可用性代价远大于“让下游幂等”）。这正是 `OutboxEvent` 注释里那句“整体语义仍是 at-least-once,
consumer 必须去重”在投递侧的对应。

> 注：如果某个真实 provider **不提供**幂等键去重（少数短信/邮件网关），就退化为“可能偶发重复”，
> 这时要么接受、要么自建“已发送指纹表”在调用前 check-and-set（又会引入一次它自己的崩溃窗口，只是更小）。
> 没有银弹——只能把重复概率压到崩溃窗口那一瞬。

---

## 7. Resilience4j：timeout + 重试 + 断路（以及“两层重试”）

外部 provider 调用由 `ResilientNotificationSender` 用 **programmatic Decorators** 包裹，组合顺序（由外到内）：

```
Retry( CircuitBreaker( TimeLimiter( CompletableFuture.supplyAsync(provider.send, senderExecutor) ) ) )
```

- **TimeLimiter（`timeout-duration: 2s`）**：单次 provider 调用的**硬超时**。必须让调用跑在另一个线程
  （`notificationSenderExecutor`）上，TimeLimiter 才能在超时时放弃等待并取消任务；*如果* 在 worker 线程内同步
  阻塞，就只有“软超时”、无法真正打断慢调用。超时计为一次失败。
- **CircuitBreaker（**每渠道一个**：`notificationPush` / `notificationEmail`）**：失败率/慢调用率超阈值就 OPEN，
  快速 fail（`CallNotPermittedException`）。**按渠道隔离**是刻意的：*如果* push 和 email 共用一个断路器，
  email 网关 brownout 会把 push 也一起熔断，明明 push 是好的。
- **Retry（`notificationDelivery`：max-attempts=3, 指数退避 200ms×2）**：对 provider 抖动做**快速、内存级**重试。

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

---

## 8. 收件人解析：没有 User 聚合时的 stub 建议

现在 `recipientKey` 还是账户/卡线索（authorization/transaction 用 `cardId`，repayment/statement 用
`creditAccountId`）——这是“还没有 User/Customer 聚合”的已知缺口。我的建议是**把这个缺口收口到唯一一个接缝**：
`NotificationRecipientResolver` 端口。

当前 stub（`StubNotificationRecipientResolver`）：

```
resolve(recipientKey) → NotificationRecipient(
    customerId = recipientKey,                         // 暂时等同
    channelAddresses = { APP_PUSH: "push-token-"+key,  // 合成的确定性地址
                         EMAIL:    "user-"+key+"@example.com" })
```

**为什么这样 stub 是对的（而不是把 app key/email 写死在 worker 或 sender 里）**：

- 渠道扇出、地址解析、渠道偏好（“用户关了 email”）天然都属于“收件人”这件事，集中在 resolver 一处。
- 接真实用户模型时，**只换这一个实现**：改成 `cardId/accountId → customerId → 查联系方式 + 渠道偏好`，
  其余（投递状态机、worker、sender、模板、表结构）**一行不动**。
- 它也是**微服务拆分**时的数据边界：投递服务要么通过事件复制一份联系方式（推荐，异步、抗故障），
  要么同步调 customer 服务（会把投递可用性绑死在 customer 服务上，不推荐）。

地址/app key 既然是 stub，就**合成确定性值**而非随机：便于测试断言、便于日志对照。push token / email 真值在
真实系统里属于敏感信息，本设计**不落在 `notification_deliveries` 行上**（行里只存 `recipient_key`），发送时
才即时解析——避免把 PII 快照进投递表。

---

## 9. 模拟外部接口

`SimulatedChannelSender`（push/email 共用基类）替代真实 SDK，刻意做三件用于演示/教学的事：

1. **按 `idempotencyKey` 去重** → 证明 §6.3 的 effectively-once；
2. **可注入失败率**（`simulated-failure-rate-percent`）→ 驱动 Resilience4j 重试/断路与投递的退避/DEAD；
3. **可注入延迟**（`simulated-latency-millis`）→ 让 TimeLimiter 的超时可被观察到。

接真实 provider 时只换这层（可用项目已有的 OpenFeign，或 WebClient），上层全不动。默认 `failure=0`、
`latency=20ms`，所以默认行为是“都成功”；演示故障路径时把这两个值调大即可。

---

## 10. 模拟 trace（多角度）

### 10.1 Happy path：一条 card_transaction.posted 扇出两条投递

```
listener → request(): inbox.claim ✓; insert intent N; resolve→{push,email};
           insertAll([D_push(PENDING), D_email(PENDING)]) 同事务提交
poller#1: claim [D_push,D_email] → 各 PROCESSING(lease=now+30s)，提交
worker(D_push): render("Transaction posted","...txn-1...")；resilientSender.send → push-provider ack(R1)
                短事务: lockCurrentLease ✓(PROCESSING & lease 未变) → markSent(R1) → SENT
worker(D_email): 同上 → SENT(R2)
结果: 两条 SENT，用户收到 1 条 push + 1 封 email。
```

### 10.2 失败 → 退避 → DEAD（email 网关一直挂）

```
worker(D_email) 第1次: Resilience4j 内部重试 3 次全超时 → 抛异常
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
- 收件人/联系方式收口在 resolver 一个接缝（本设计新增），拆分时换成“事件复制 + 本地只读副本”。
- 拆分动作：把 `NotificationDeliveryPoller` 换成“消费自有 topic 的 delivery consumer”即可，投递状态机/sender/
  模板**不动**。当前用 DB poller 而非再发一条 Kafka，是因为单体阶段 poller 更简单、且复用了既有 claimable job
  基础设施；这是 *有意* 的阶段选择，不是缺陷。

---

## 13. 配置项（`application.yml`）

```yaml
notification.delivery:
  enabled: true                  # 关掉=只建 PENDING 投递、不外发（迁移/排查时隔离副作用）
  fixed-delay-ms: 1000           # poller 扫描间隔
  recovery-fixed-delay-ms: 5000  # recoverer 扫描间隔
  batch-size: 50
  processing-timeout-seconds: 30 # lease 超时；必须 > 一次(含 R4j 重试)发送+finalize 的正常耗时
  max-attempts: 8                # 超过进 DEAD
  worker-pool-size: 4
  worker-queue-capacity: 100
  sender-threads: 4              # TimeLimiter 异步执行池（应 ≥ worker-pool-size）
  simulated-latency-millis: 20   # 模拟 provider 延迟（调大演示 TimeLimiter 超时）
  simulated-failure-rate-percent: 0  # 调大演示重试/断路/DEAD

resilience4j:
  circuitbreaker.instances: { notificationPush: {...}, notificationEmail: {...} }  # 每渠道独立
  retry.instances.notificationDelivery: { max-attempts: 3, 指数退避 200ms×2 }
  timelimiter.instances.notificationDelivery: { timeout-duration: 2s }
```

线程与池：scheduler（`notificationDeliveryTaskScheduler`，2 线程，只 poll/claim）/ worker pool
（`notificationDeliveryWorkerExecutor`，调 provider+finalize）/ sender pool（`notificationSenderExecutor`，
给 TimeLimiter 跑异步调用）三者分离，互不挤占，命名前缀便于 thread dump 排障。

---

## 14. 取舍与后续

- **Notification 退成纯意图**：放弃了“在一行就能看出整体投递状态”的便利，换来多渠道下语义正确。若需要“整体
  状态”用于查询，可在 notification 上加一个**派生 rollup**（由投递 finalize 时回写），本设计未做，作为后续。
- **provider 仍是模拟**：真实化只换 sender 实现 + Resilience4j 阈值调参；OpenFeign 已在依赖中。
- **resolver 是 stub**：等真实 User/Customer 聚合落地再换实现，渠道偏好（用户关某渠道）也在那时接入。
- **DEAD 的处理**：当前只停重试、记 `last_error`，靠人工/admin。后续可加一个 admin 重放端点（把 DEAD 改回
  PENDING）和告警。
- **可观测性**：worker/recoverer 已打结构化日志（`notification_delivery_sent/failed/recovered`）；后续可加
  Micrometer 计数器（按 channel/outcome）和 DEAD 积压告警，与 risk 模块的 `MeterRegistry` 用法看齐。

---

## 15. 一句话总结

把“通知”拆成**不可变意图**与**per-channel 投递**两层，投递复用项目已验证的 **claimable job** 回路
（SKIP LOCKED 领取 + lease + 校验 token finalize + recover），外部调用用 **Resilience4j**（超时+断路+快速重试）
包裹、并与投递状态机的 **durable 重试**形成两层防线；幂等上承认“带外部副作用只能 at-least-once + 下游按
`deliveryId` 去重 = effectively-once”，把唯一的 User-域缺口收口在 `NotificationRecipientResolver` 一个接缝里。
```
