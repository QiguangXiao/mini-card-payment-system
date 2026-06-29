# Notification 机制分层梳理与精简评估

> 本文只做两件事：(1) 把当前 `notification` 模块**按层**讲清楚现在是什么样；
> (2) 给出**能缩减哪些类、缩减后长什么样、缩与不缩的取舍**。
> 不改任何代码，是一份决策用的梳理报告。

---

## 0. 一句话结论

`notification` 是全项目最大的模块（**41 个类**），但它其实是**两个子系统**拼起来的：

- **意图层（intent，13 类）**：把 Kafka 业务事件翻译成"该给谁发哪种通知"并幂等落库。**轻、对齐 JD、该留。**
- **投递层（delivery，28 类）**：把意图按渠道扇出、用一套可靠投递状态机 + Resilience4j 真的"发出去"（provider 当前是模拟的）。**重、离 JD 核心最远、是这次精简的主战场。**

投递层同时叠了三层本可不要的复杂度：它是 claimable-job 模式的**第 4 份拷贝**、Resilience4j 的**第 2 处展示**、以及一整套**只为驱动前两者而造的模拟 provider 脚手架**。

---

## 1. 全景：两个子系统的边界

```
 Kafka 业务事件 (authorization / transaction / statement / repayment)
        │
        ▼
┌─────────────────────────────────────────────────────────────┐
│  意图层 INTENT（13 类，原本就有，轻）                          │
│  4 个 Kafka listener → RequestNotificationService            │
│    · Inbox 幂等 claim（at-least-once 去重）                    │
│    · 写 1 行 notifications（不可变"意图"）                      │
│    · 按收件人渠道 fan-out 出 N 行 notification_deliveries      │  ← 扇出是两层的接缝
└─────────────────────────────────────────────────────────────┘
        │  (同一事务内写入 deliveries)
        ▼
┌─────────────────────────────────────────────────────────────┐
│  投递层 DELIVERY（28 类，后加，重）                            │
│  poller → claimer(短事务打 lease) → worker pool               │
│    → 收件人解析 + 模板渲染 + ResilientSender(Resilience4j)     │
│    → 模拟 push/email provider → 回执 → markSent / 退避 / DEAD  │
│  recoverer 回收 lease 超时的 PROCESSING 行                     │
└─────────────────────────────────────────────────────────────┘
```

**关键观察**：两层的唯一耦合点是 `RequestNotificationService` 里那段"按渠道扇出建 delivery 行"。
**砍掉投递层，只需把这段扇出删掉**，意图层完全独立自洽。

---

## 2. 分层类清单

### 2.1 意图层 INTENT（13 类 — 所有方案都保留）

| 子层 | 类 | 角色 |
| --- | --- | --- |
| 入站适配 | `AuthorizationNotificationListener` | 授权事件 → 通知请求 |
| | `CardTransactionNotificationListener` | 入账事件 → 通知请求 |
| | `StatementNotificationListener` | 账单事件 → 通知请求 |
| | `RepaymentNotificationListener` | 还款事件 → 通知请求 |
| 应用用例 | `RequestNotificationService` | Inbox 幂等 + 写意图 +（当前）扇出投递 |
| | `RequestNotificationCommand` | listener→service 的输入 record |
| 意图领域 | `Notification` | 不可变意图聚合 |
| | `NotificationType` | 发生了什么（5 个枚举值） |
| | `NotificationSubjectType` | 关联业务对象类型 |
| | `NotificationRepository` | 意图仓储端口 |
| 意图持久化 | `MyBatisNotificationRepository` | `insertIfAbsent`（source_event_id 唯一） |
| | `NotificationMapper` | MyBatis 接口 |
| | `NotificationRow` | 行映射 |

### 2.2 投递层 DELIVERY（28 类 — 精简对象）

| 子层 | 类 | 角色 | 备注 |
| --- | --- | --- | --- |
| 投递领域 | `NotificationDelivery` (208 行) | per-channel 投递聚合 + 状态机 | 与 `OutboxEvent` 同构 |
| | `NotificationDeliveryStatus` | PENDING/PROCESSING/SENT/DEAD | |
| | `NotificationDeliveryRepository` | 投递仓储端口（claim/recover/finalize） | |
| | `NotificationChannel` | APP_PUSH / EMAIL | |
| | `NotificationContent` | 渲染后的 title+body | record |
| | `NotificationDispatch` | 交给 sender 的一次发送请求 | record |
| | `NotificationRecipient` | 解析后的收件人+各渠道地址 | record |
| | `ProviderReceipt` | provider 回执 | record |
| | `NotificationDeliveryException` | 统一失败异常 | |
| 端口（接缝） | `NotificationSender` | 发送门面端口（含弹性） | 单实现 |
| | `NotificationChannelSender` | 单渠道发送端口 | |
| | `NotificationRecipientResolver` | 收件人解析端口 | 单实现 |
| | `NotificationTemplateRenderer` | 模板渲染端口 | 单实现 |
| 投递引擎（第 4 套 claimable job） | `NotificationDeliveryClaimer` | 短事务打 lease | ≈ `OutboxClaimer` |
| | `NotificationDeliveryWorker` | 事务外副作用 + finalize 校验 lease | ≈ `OutboxWorker` |
| | `NotificationDeliveryPoller` | 定时 poll+submit | ≈ `OutboxPoller` |
| | `NotificationDeliveryRecoverer` | 回收 stuck PROCESSING | ≈ `OutboxRecoverer` |
| | `NotificationDeliveryProperties` | 投递配置 record | ≈ `OutboxProperties` |
| | `NotificationDeliveryConfiguration` | 配置绑定 + sender 线程池 | |
| 发送弹性（第 2 处 Resilience4j） | `ResilientNotificationSender` (130 行) | per-channel TimeLimiter+CircuitBreaker+Retry | |
| 渠道实现 + 模拟脚手架 | `SimulatedChannelSender` | 故障/延迟注入 + 幂等去重基类 | 演示用 |
| | `SimulatedPushNotificationSender` | 29 行纯样板子类 | |
| | `SimulatedEmailNotificationSender` | 29 行纯样板子类 | |
| 模板 / 收件人 | `DefaultNotificationTemplateRenderer` | switch 出文案 | |
| | `StubNotificationRecipientResolver` | 合成地址、默认全渠道 | User 域缺口的收口点 |
| 投递持久化 | `MyBatisNotificationDeliveryRepository` | 投递仓储实现 | |
| | `NotificationDeliveryMapper` | MyBatis 接口 | |
| | `NotificationDeliveryRow` | 行映射 | |

### 2.3 模块外的关联资产（投递层专属）

- 线程池/调度：`WorkerExecutorConfiguration#notificationDeliveryWorkerExecutor`、
  `PollingSchedulerConfiguration#notificationDeliveryTaskScheduler`、
  `NotificationDeliveryConfiguration#notificationSenderExecutor`
- 配置：`application.yml` 的 `notification.delivery.*` 与 Resilience4j
  `notificationPush` / `notificationEmail` / `notificationDelivery`（circuitbreaker/retry/timelimiter）
- 数据库：`notification_deliveries` 表 + 迁移 `0006`（重命名 template→notification_type）`0007`（把投递列搬出 notifications）
- MyBatis XML：`NotificationDeliveryMapper.xml`

---

## 3. 端到端数据流（当前）

```
1. Kafka 事件到达 XxxNotificationListener
2. → RequestNotificationService.request(command)
3.   → inboxRepository.claim(consumer, eventId)         // 重复事件在此短路
4.   → notificationRepository.insertIfAbsent(intent)     // source_event_id 唯一兜底
5.   → recipientResolver.resolve(key)                    // stub：默认 push+email
6.   → 每渠道 NotificationDelivery.pendingFor(...) → deliveryRepository.insertAll()  // 同事务
   ──────────────（以下是投递层，异步）──────────────
7. NotificationDeliveryPoller @Scheduled
8.   → claimer.claim(...)（短事务 FOR UPDATE SKIP LOCKED → markProcessing lease）
9.   → workerExecutor.execute(worker.handleClaimedDelivery)
10.    → recipientResolver.resolve → addressFor(channel)
11.    → templateRenderer.render(type, channel) → NotificationContent
12.    → resilientSender.send(dispatch)                  // 事务外，Resilience4j 包裹
13.      → 按 channel 选 SimulatedXxxSender → 模拟延迟/失败/幂等去重 → ProviderReceipt
14.    → markSent / markFailed（独立短事务 + lease token 校验）
15. NotificationDeliveryRecoverer @Scheduled 回收 lease 超时行
```

---

## 4. 精简方案（三档）

### 方案 A — 激进：删掉整个投递层，回到"只记录意图"

**删**：2.2 的 28 个类 + 2.3 的全部关联资产（表/迁移回滚或保留空表/Resilience4j 配置/三个线程池 bean/XML）。
**改**：`RequestNotificationService` 删掉第 5–6 步的扇出，只保留 Inbox 幂等 + 写意图。
**留**：意图层 13 类。

缩减后形态：

```
listener → RequestNotificationService
             → Inbox claim（at-least-once 去重）
             → 写 1 行 notifications（意图）
（没有真实外发；"投递"是显式声明的 deferred 能力）
```

- 模块 **41 → 13 类**；claimable-job 引擎 **4 → 2 套**（Outbox + DelayJob）；Resilience4j 回到只在 risk 一处。
- **取舍**：
  - ✅ 最对齐 JD —— "通知是 eventual-consistency 下游，用 Outbox/Inbox 保证不丢不重"这条叙事干净有力，且这正是面试会问的点。
  - ✅ 把"四套引擎"降回"两套"，顺手消掉 §3.2 的过度设计质疑。
  - ✅ 删掉"为演示而注入故障"的脚手架（这类代码进 main 本身是减分项）。
  - ⚠️ 失去一个完整的"可靠投递 + 多渠道 + 弹性"案例。但它**与 Outbox 同构**，讲 Outbox 时就能把这套机制讲透，并不缺这块拼图。
  - ⚠️ 要诚实表述："投递留了接缝、当前不实现"，而不是假装能发。这比之前"有 markSent 却没人调用的死代码"更诚实。

> 适合：目标是"减少过度设计、聚焦 JD 主线"。**这是我推荐的方案。**

### 方案 B — 中度：保留投递闭环，砍掉弹性与模拟脚手架

**删**：`ResilientNotificationSender`、`NotificationSender`（门面端口）、`SimulatedChannelSender` 的故障/延迟注入、
把 `SimulatedPush/EmailNotificationSender` 合并为 1 个参数化 sender、Resilience4j 的三段配置、`notificationSenderExecutor`。
**改**：`NotificationDeliveryWorker` 直接依赖按 channel 路由的 `NotificationChannelSender`（EnumMap），失败抛异常→走状态机 durable 退避。
**留**：投递表、per-channel 扇出、claimer/poller/worker/recoverer、template renderer、recipient resolver。

缩减后形态：保留"可靠投递状态机 + 多渠道扇出"，去掉"intra-attempt 弹性"和"为演示注入故障"。约 **28 → 20 类**。

- **取舍**：
  - ✅ 仍保留多渠道扇出 + 可靠投递这条主干。
  - ⚠️ 第 4 套 claimable job **依然在**（四套引擎不变），过度设计的主因没消除。
  - ⚠️ 同时失去 Resilience4j 在通知侧的展示价值。
  - ❌ **性价比最差的中间态**：既没把引擎降回两套，又丢了弹性亮点。除非你明确"想保留多渠道投递、但讨厌 Resilience4j 那层"，否则不建议。

### 方案 C — 不动：当作 distributed-delivery showcase 保留

**取舍**：
- ✅ 是一个**完整、自洽、注释质量很高**的"可靠投递 + per-channel 断路 + 多渠道扇出"案例，真要展开能讲很深。
- ⚠️ 代价是：模块最重、四套引擎、第 2 处 Resilience4j、模拟脚手架进 main —— 与"减少过度设计"的目标直接冲突。

> 适合：你把"通知真实投递"当成想主动展示的亮点，且愿意承担"看起来在堆量"的观感。

### 附：与方案无关的微清理

无论 B/C，`SimulatedPushNotificationSender` 与 `SimulatedEmailNotificationSender`（各 29 行，只差 `channel()`/`providerName()`）
可合并成 1 个参数化 sender、用 `@Bean` 注册两次。省 1 个类，属于边角优化。

---

## 5. 取舍总表

| 维度 | A 激进（删投递层） | B 中度（砍弹性） | C 不动 |
| --- | --- | --- | --- |
| notification 类数 | 41 → **13** | 41 → ~33 | 41 |
| claimable-job 引擎 | **4 → 2** | 4（不变） | 4 |
| Resilience4j 处数 | 2 → **1** | 2 → 1 | 2 |
| 对齐 JD（聚焦正确性主线） | **最强** | 中 | 弱（广度有余） |
| 保留的 showcase | Outbox/Inbox 幂等 | 多渠道可靠投递 | 投递+弹性+多渠道 |
| 诚实度 | 高（明说 deferred） | 高 | 高 |
| 工作量 | 中（删类+回滚迁移+改 service+删测试） | 中大（要改 worker 接线） | 0 |
| 风险 | 低（意图层独立自洽） | 中（动接线易引入 bug） | 0 |

---

## 6. 建议

1. **优先 A**：与你"减少过度设计、对齐 JD"的目标最契合，且意图层独立、风险低。
2. **不建议 B**：中间态，投入不小却没解决主要质疑。
3. **C 仅在**你确实想把"通知真实投递"当王牌主动讲时保留。

> 若选 A，落地顺序建议：先删投递层类与配置 → 改 `RequestNotificationService` 去掉扇出 →
> 删/改对应测试（`NotificationDeliveryTest`、各 listener test 里对 delivery 的断言）→
> 处理 `notification_deliveries` 表（加一条回滚迁移，或留空表并在文档标注弃用）→ `./gradlew test`。
