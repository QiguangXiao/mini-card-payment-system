# Claimable Job 三大家族对比：DelayJob / Outbox / StatementJob（Claude）

> 关键词：可领取任务, 数据库队列, 租约, 竞争消费者, 退避重试, 死信, 分片扇出,
> claimable job, database-backed queue, lease, competing consumers,
> exponential backoff, dead letter, sharded fan-out,
> ジョブ取得(ジョブしゅとく), リース, デッドレター, シャーディング。

本文把项目里**三套“数据库支撑的可领取任务”机制**放在一起对比：通用延迟任务
`delayjob`、可靠发布 `messaging/outbox`、账单批处理 `statement` job。它是
`statement-job-design-cn.md`（单家族深入）和 `async-workflows-comparison-cn.md`（横向）的
**对比强化版**——重点回答“**它们哪里一样、哪里故意不一样、为什么**”，并附真实 gap 与硬核面试问答。

阅读建议：先看 §1.1 的统一模型，再看 §1.5 的**总对比表**（全文核心），其余是展开。

---

## 第一部分：实际实现

### 1.1 三家族共享的“claimable job”参考模型

三者都是同一个抽象的不同实例——**database-backed work queue（数据库支撑的工作队列）**：

```text
状态机（所有家族通用）：
  PENDING ──claim(短事务)──► PROCESSING(lease)
                               │
        ┌──────────────────────┼───────────────────────┐
        ▼                      ▼                        ▼
   terminal(DONE/PUBLISHED)   重试回 PENDING          DEAD
   (业务/发布成功)            (失败且未到上限)        (达到 maxAttempts)
        ▲
   [Recoverer] 扫描 lease 超时的 PROCESSING，按一次失败处理 ──► 重试 / DEAD
```

七条共有不变量（interview 时这就是“统一答案”）：

1. **短事务 claim + `FOR UPDATE SKIP LOCKED`**：多 pod 并发领取不同 row，天然 competing consumers。
2. **claim 提交后才执行**：`PENDING->PROCESSING` 先 commit，再把 job 交给 worker pool。
   **执行/等 broker 期间不持 job row lock**。
3. **lease（租约）不是所有权**：PROCESSING 带截止时间；worker 宕机后 recoverer 放回重试。
4. **finalize 重校验 lease token**：`lockCurrentLease` 重新 `FOR UPDATE` 取行，比对 token，
   防迟到的老 worker 覆盖新状态。
5. **有界重试 → DEAD**：达到 `maxAttempts` 转 DEAD，阻断 poison job。
6. **创建幂等**：靠唯一键保证“同一逻辑任务只入队一次”。
7. **平台资源隔离**：独立 scheduler 线程池（只 poll/claim）+ 独立 worker 线程池（跑业务），
   命名线程便于排障；`@ConditionalOnProperty` 开关。

### 1.2 家族 A：`delayjob`（通用延迟业务动作）

**4 个执行者拆分**，职责单一：

| 类 | 职责 |
|---|---|
| `DelayJobPoller` | `@Scheduled` 周期 poll，提交 `delayJobWorkerExecutor` |
| `DelayJobClaimer` | `@Transactional` 短事务，`FOR UPDATE SKIP LOCKED` 领 PENDING → PROCESSING lease |
| `DelayJobWorker` | 按 `jobType` 经 `EnumMap` 派发到 `DelayJobHandler`；成功 `markDone`，失败 `markFailed`；finalize 独立短事务 + lease-token 重校验 |
| `DelayJobRecoverer` | `@Scheduled`+`@Transactional` 扫 lease 超时 PROCESSING，`markProcessingTimedOut` |

- **一个 job = 一个聚合的一次未来动作**（point action at time T）。`DelayJob` 带
  `jobType / aggregateType / aggregateId / scheduledAt`。
- **当前两种 jobType**：`AUTHORIZATION_EXPIRY`（7 天未 capture 自动释放额度）、`AUTO_REPAYMENT`（到期口座振替）。
- **业务方创建**：如 `AutoRepaymentDelayJobScheduler.scheduleAutoRepayment` 在账单生成同事务里
  `insertIfAbsent`，`scheduledAt = dueDate`。
- **创建幂等**：`uk_delay_jobs_aggregate UNIQUE (job_type, aggregate_type, aggregate_id)` —— 一张
  statement 只会有一个 AUTO_REPAYMENT。
- **lease 单列复用**：`next_attempt_at` 既是首次执行时间，又在 PROCESSING 时充当 lease deadline +
  finalize token。
- **退避**：`markFailed` 指数退避 `min(2^(n-1), 60s)`。
- **派发表**：`DelayJobHandler` 列表启动时转 `EnumMap<DelayJobType, handler>`；缺 handler 走失败路径
  而非静默 DONE。

### 1.3 家族 B：`messaging/outbox`（可靠消息发布）

**4 个执行者拆分**，与 delayjob **同构**：

| 类 | 职责 |
|---|---|
| `OutboxPoller` | `@Scheduled` poll，提交 `outboxWorkerExecutor` |
| `OutboxClaimer` | 短事务领 PENDING → PROCESSING lease |
| `OutboxWorker` | publish 到 Kafka 等 broker ack；finalize 独立短事务 + lease-token 重校验；成功 `markPublished` |
| `OutboxRecoverer` | 扫 lease 超时，`markProcessingTimedOut` |

- **一个 row = 一条要发布的消息**。**没有业务 handler**，worker 的“业务”就是
  `KafkaOutboxMessagePublisher.publish` 同步等 ack。
- **业务方在状态变更同事务写入**（transactional outbox），无 `scheduledAt`：`next_attempt_at=createdAt`，立即可发。
- **lease 单列复用**：同样用 `next_attempt_at`。
- **退避**：与 delayjob 完全对称 `min(2^(n-1), 60s)`。
- **终态是 `PUBLISHED`**（不是 DONE）。
- 详见姊妹篇 `event-outbox-messaging-design-claude-cn.md`。

### 1.4 家族 C：`statement` job（账单分片批处理）

**刻意合并成 1 个 dispatcher**（+ planner + daily scheduler + handler）：

| 类 | 职责 |
|---|---|
| `BillingCycleScheduler` | `@Scheduled(cron, zone=Asia/Tokyo)` 每天触发一次 |
| `StatementCycleService` | **planner**：判断是否关账日，按 `ceil(账户数/targetAccountsPerJob)` 算 shardCount，`INSERT IGNORE` 建 N 个分片 |
| `StatementJobDispatcher` | **1 个类**完成 poll→短事务 claim→提交 `statementJobWorkerExecutor`→finalize→recover |
| `StatementJobHandler` | 处理一个 shard：`findAccountIdsForJob`(CRC32 % shardCount) 取账户，**逐账户各开小事务**生成账单，统计 generated/skipped/failed |

- **一个 job = 一个分片，覆盖很多账户（fan-out）**。`StatementJob` 带 cycle 身份
  `period_start/period_end/due_date` + `shard_no/shard_count` + 结果计数器。
- **daily cron 触发创建**，执行靠 claimable job 并发跑。
- **创建幂等**：`uk_statement_jobs_cycle_shard UNIQUE (period_start, period_end, shard_no)`。
- **lease 多列（更丰富）**：`claimed_by / claimed_at / claim_until / claim_token`；
  `claim_until` 作 lease deadline，`claim_token` 作 finalize owner token。比 delay/outbox
  多了“谁在处理、何时开始”的运维可见性，也避免把 timestamp 当 ownership。
- **无退避列**：`markFailed` 直接回 PENDING（下个 ~1s poll 立即重领）或 DEAD。
- **故障隔离在账户级**：单账户失败不回滚整个 shard（每账户独立小事务）。

### 1.5 总对比表（全文核心）

| 维度 | DelayJob | Outbox | StatementJob |
|---|---|---|---|
| **本质** | 延迟执行未来业务动作 | 可靠发布消息 | 周期账单批处理 |
| **一个 job 的粒度** | 1 聚合 1 动作 | 1 条消息 | 1 分片 N 账户（fan-out） |
| **类拆分** | 4 类（Claimer/Poller/Worker/Recoverer） | 4 类（同构） | **1 类 dispatcher** + planner + handler |
| **谁创建** | 业务方 `insertIfAbsent` | 业务方同事务 insert | daily cron → planner `INSERT IGNORE` |
| **触发时机** | `scheduledAt`（未来时刻） | 立即 | 关账日（日历） |
| **创建幂等键** | uk(job_type,agg_type,agg_id) | eventId（消费侧去重） | uk(period_start,period_end,shard_no) |
| **lease 列** | `next_attempt_at` + `lease_token` | `next_attempt_at` + `lease_token` | **多列** `claimed_by/at/until` + `claim_token` |
| **退避** | 指数 min(2^(n-1),60s) | 指数（对称） | **无退避**，立即回 PENDING |
| **终态** | DONE / DEAD | PUBLISHED / DEAD | DONE / DEAD |
| **有业务 handler** | 有（按 jobType 派发） | 无（只 publish） | 有（账户级循环） |
| **计数器** | 无 | 无 | 有（processed/generated/skipped/failed） |
| **worker 池** | `delayJobWorkerExecutor` | `outboxWorkerExecutor` | `statementJobWorkerExecutor` |
| **scheduler 池** | `delayJobTaskScheduler`(2) | `outboxTaskScheduler`(**1**,保序) | `statementJobTaskScheduler`(2) |
| **DB 索引** | idx(status,next_attempt_at,created_at) | idx(status,next_attempt_at,created_at) | 见 claim 排序 created_at,shard_no,id |

> **一句话记忆**：三者是同一个 claimable-job 骨架的三种皮肤。
> **delayjob = 定时做一件事；outbox = 可靠发一条消息；statementjob = 分片跑一大批**。

### 1.6 平台执行资源（为什么 scheduler 池 ≠ worker 池）

- `PollingSchedulerConfiguration`：每种机制一个 `ThreadPoolTaskScheduler`，**只跑 poll/claim/recover**。
  outbox 用单线程（发布保序更简单），其余 pool=2（poll 与 recover 各一）。
- `WorkerExecutorConfiguration`：每种机制一个 `ThreadPoolTaskExecutor`（core=max=有界），**跑真正业务**。
  `queueCapacity` 满 → `TaskRejectedException` → 放回 retry；`waitForTasksToCompleteOnShutdown=true`。
- **为什么分开**：`@Scheduled` 线程若直接跑长业务/等 broker，会拖住下一轮调度；三机制各用各的池，
  一种 backlog 不会饿死另一种。命名前缀让 thread dump / metrics 一眼区分。

---

## 第二部分：优点与真实 gap

### 2.1 优点

1. **一套心智模型覆盖三种场景**：claim-lease-recover 骨架统一，读懂一个就读懂三个。
2. **正确性细节齐备**：SKIP LOCKED 竞争领取、claim 后才执行不持锁、lease-token 防迟到覆盖、
   recoverer 兜底卡死、有界重试 → DEAD、创建幂等。
3. **平台资源隔离干净**：scheduler/worker 分池、按机制隔离、命名线程、优雅关闭、开关可控。
4. **取舍有意识**：框架（delay/outbox）拆 4 类换复用与可测；一次性领域批（statement）合 1 类换可读。
   `framework-vs-one-off` 的分野是加分项，不是不一致。

### 2.2 真实 gap（只列主要的，不硬凑）

1. **DEAD 全家族缺可观测与重放（最该补）**。三者达到 maxAttempts 转 DEAD 后都只是躺在表里，
   **没有指标、告警或“DEAD → 重新入队”的运维入口**。授权过期没释放额度、自动扣款没扣、
   某分片没出账——目前只能靠人去查表。至少要 `DEAD` 计数指标 + requeue 路径。

2. **完成态行无保留策略**。`delay_jobs` 的 DONE、`statement_jobs` 的 DONE、`outbox_events` 的
   PUBLISHED 都**永不清理**，表无界增长。`delay_jobs` 还有个微妙点：`uk(job_type,agg_type,agg_id)`
   让一个聚合的 DONE 行**永久占用唯一键**（这本身是 idempotency，想要的），所以清理时要么保留
   key、要么迁冷表，不能裸删。需要归档/分区裁剪 job。

3. **StatementJob：单账户失败连坐整个分片**。`StatementJobHandler` 只要
   `failedAccountCount>0` 就把**整个 shard** 标失败 → 回 PENDING 重领。由于账户级幂等，重跑安全
   但**浪费**（每次重扫整片，已出账账户走 skipped）；更糟的是一个**永久失败**的账户会把整片
   反复 cycle 到 DEAD，且 DEAD 在 shard 粒度，**看不出是哪个账户坏了**。更好做法：失败账户单独
   落表/单独重试，不连坐好账户。

4. **StatementJob 无退避**。失败分片立即回 PENDING，下个 ~1s poll 就重领；持久失败会**高频 spin**
   直到 maxAttempts，而 delay/outbox 都有指数退避。属于三家族间的一处不对称，统一上 backoff 更稳。

（顺带：polling 的秒级延迟、DB 当队列的吞吐上限，是**有意取舍**而非 gap，见第四部分。）

---

## 第三部分：通用领域知识 / 设计模式 / 最佳实践

- **Database-backed work queue（队列表 / transactional job queue）**：用一张表 + 状态列当队列。
  优点：入队与业务状态**同库同事务**（无 dual-write）、运维简单、`SKIP LOCKED` 即得竞争消费；
  缺点：轮询延迟、表增长、不适合极高吞吐。
- **Competing Consumers + `SELECT … FOR UPDATE SKIP LOCKED`**：多消费者并发领取互不重叠，
  无需额外 MQ 中间件。MySQL 8.0+ / PostgreSQL 都支持 SKIP LOCKED。
- **Lease / Visibility Timeout（租约 / 可见性超时）**：领取者只拿一段时间的处理权；超时被回收。
  等价于 SQS 的 visibility timeout、Kafka 的 max.poll.interval。
- **Optimistic lease token（乐观租约令牌）**：finalize 前比对令牌。轻量家族
  （delay/outbox/notification）用随机 `lease_token`；statement 用随机 `claim_token`。
  `next_attempt_at`/`claim_until` 只回答“什么时候可 recover”，token 才回答“这次 finalize 是否仍属于我”。
- **Claim-then-execute（先领取再执行）**：claim 短事务提交后才干活，避免持 row lock 跑长业务/等外部。
- **At-least-once execution + Idempotent handler = Effectively-once**：lease 到期但老 worker 仍活会
  导致重复执行，所以 handler 必须幂等（`AUTO_REPAYMENT` 用确定性幂等键 + 银行侧去重；
  `AUTHORIZATION_EXPIRY` 用聚合状态检查“已释放则跳过”）。
- **Idempotent enqueue（幂等入队）**：唯一键保证“同一逻辑任务只一行”——`insertIfAbsent` /
  `INSERT IGNORE` / `ON DUPLICATE KEY`。
- **Bounded worker pool + Backpressure（有界线程池 + 背压）**：core=max 固定、队列有界、满则拒绝并
  requeue，避免把 DB 压垮。
- **Exponential backoff + Dead Letter（指数退避 + 死信）**：可恢复故障退避重试，poison job 进 DEAD。
  退避要设上限，避免恢复后睡太久。
- **Sharding / Fan-out（分片扇出）**：大批量用 `hash(key) % shardCount` 切片并行（statement 的
  `CRC32(account_id) % shardCount`），每片再拆账户级小事务隔离失败。
- **Time-triggered vs Event-triggered**：cron（billing daily）负责**何时开始**，claimable job 负责
  **并发执行 + 重试 + 恢复**——两者组合，不互相替代。
- **Scheduler thread ≠ Worker thread**：调度线程只触发，业务在 worker 池，防止慢任务饿死调度。
- **Single-writer for ordering**：outbox scheduler 单线程，发布更易保序（虽 SKIP LOCKED 也不会重复）。
- **可注入 `Clock`**：时间是依赖，便于测试固定 now、统一 billing 时区（Asia/Tokyo）。
- **Framework vs one-off 的分解判断**：复用的框架值得拆类（可测/复用），一次性领域批值得合类（可读）。

---

## 第四部分：面试问答（含硬核追问 + 强补充）

### Q1. 为什么用数据库表当队列，而不是直接上 Kafka/RabbitMQ？
**答**：因为“任务计划”要和业务状态**同事务**落地，避免 dual-write。账单生成与“到期扣款计划”、
授权批准与“7 天过期计划”必须同生共死——写 MQ 是另一个系统，无法和 MySQL 原子提交。
DB 表 + `SKIP LOCKED` 直接得到竞争消费、重试、恢复，运维也简单。
- **硬核追问：DB 当队列的缺点？什么时候必须换？**
  轮询有秒级延迟；表会增长（要归档）；DB 是单点瓶颈，**极高吞吐/低延迟**场景（每秒数万任务）
  会把 DB 压垮，那时应上专用 MQ + 消费者。
- **强补充**：本项目这种万级/十万级 jobs/day、秒级延迟可接受的场景，DB 队列是**最优性价比**；
  真要扩，发布器可换成 CDC 拖 outbox 表，消费侧不动。

### Q2. PROCESSING lease 是什么？为什么 delay/outbox/notification 用 `lease_token`，statement 用多列 claim？
**答**：lease 是“限时处理权”。delay/outbox/notification 一个 row 就是**单一工作单元**，
所以保留轻量模型：`next_attempt_at` 表示下次可执行/lease deadline，`lease_token` 表示本轮 owner。
statement 是**分片 fan-out**，需要 `claimed_by/claimed_at/claim_until` 这种“谁在处理、何时开始、何时超时”
的运维可见性，并新增 `claim_token` 作为真正 owner token。
- **硬核追问：为什么 statement 不继续用时间戳当 token？**
  因为 Java `Instant` 和 MySQL `TIMESTAMP(6)` 有精度边界，timestamp 更适合作为 deadline；
  随机 token 更适合回答“这次 finalize 是否仍属于我”。同理，delay/outbox/notification 也不再用
  `next_attempt_at` 兼任 token。

### Q3. 为什么 claim 和 execute 要分成两段事务？
**答**：claim 短事务把 `PENDING->PROCESSING` 提交后**立刻放锁**，再交 worker 干活。
否则跑长业务（statement 上千账户）或等 Kafka ack 期间会**一直持 job row lock**，
高峰期行锁 + 连接池一起爆。
- **硬核追问：execute 成功但 finalize 事务失败呢？**
  job 仍是 PROCESSING，lease 到期被 recoverer 放回 PENDING → **重新执行**。这正是 at-least-once，
  靠 handler 幂等兜底。outbox 同理会重发，消费侧 Inbox 去重。

### Q4. 多 pod 部署，一个 job 会不会被执行两次？到底是 at-least-once 还是 exactly-once？
**答**：claim 用 `FOR UPDATE SKIP LOCKED` 提交 PROCESSING，正常情况下只有一个 pod 领到。
但**lease 到期而老 worker 其实还活着**时，recoverer 会放回、另一 worker 重做 → **可能执行两次**。
所以是 **at-least-once 执行 + 幂等 handler = effectively-once**。
- **硬核追问：具体怎么保证 handler 幂等？**
  `AUTO_REPAYMENT`：确定性幂等键 `auto-debit:<statementId>` + 银行 gateway 按 key 去重 +
  RepaymentService 的 INSERT-first claim。`AUTHORIZATION_EXPIRY`：执行前查聚合状态，
  “已释放/已 capture”直接幂等跳过。
- **强补充**：lease 解决的是“卡死可见性”，**不是“绝不重复”**——别把 lease 当锁来保证 exactly-once。

### Q5. worker pool 满了、或应用正在 shutdown，job 会怎样？
**答**：`outboxWorkerExecutor.execute` 抛 `TaskRejectedException` → poller 调 `markRejectedForRetry`
→ job 从 PROCESSING 放回 retry/DEAD，不会卡在 PROCESSING。shutdown 时
`waitForTasksToCompleteOnShutdown=true` 尽量跑完在途任务，剩下的靠 lease 恢复。
- **强补充**：worker 池 core=max 有界 + 队列有界，是**对 DB 的背压**——宁可让 job 稍后重试，
  也不让无限并发把 `credit_accounts` 行锁和连接池打满。

### Q6. 为什么 DelayJob/Outbox 拆 4 类，StatementJob 合 1 类？这不是不一致吗？
**答**：不是，是 **framework-vs-one-off** 的有意取舍。delay/outbox 是**跨 domain 复用的平台机制**，
被多个 handler 消费 → 拆成单一职责 4 类，换独立可测与复用；statement job 是**单一消费者的领域批**，
合 1 个 dispatcher 端到端一眼读完，拆 4 类反而是 over-engineering。
- **硬核追问：那怎么保证“两种打包”不退化成“两套各有 bug 的实现”？**
  关键是**正确性语义必须一致**：三者共享 §1.1 那七条不变量，只是类数量不同。新 domain 要加任务时
  也只能从这两种参考形状里选，不许发明第三种 naive `@Scheduled`。

### Q7. statement 为什么要分片？单条 job 扫全表不行吗？shardCount 怎么定、改了会怎样？
**答**：百万账户单事务/单 worker 不可行（锁太久、失败全回滚）。用
`CRC32(account_id) % shardCount` 扇出并行，每片再按账户开**小事务**隔离失败。
shardCount = `ceil(账户数 / targetAccountsPerJob)`，在建 job 时算一次并**存到 job 上**。
- **硬核追问：建 job 后又来了新账户（补录 backdated 交易）怎么办？**
  周期已关账，账户集合基本稳定；即便有新账户，它用 job 上**已存的 shardCount** 做 hash，
  仍**恰好落到某一个 shard**，不会漏也不会重。
- **强补充**：分片数固定在创建时，避免“处理中途 shardCount 变化导致同账户落到不同片”的重复出账。

### Q8. 怎么防止同一周期/同一聚合被重复建 job？
**答**：唯一键 + 幂等插入。delay：`uk(job_type, aggregate_type, aggregate_id)` + `insertIfAbsent`；
statement：`uk(period_start, period_end, shard_no)` + `INSERT IGNORE`。scheduler 多实例或当天重跑都
不会产生重复任务。
- **强补充**：这等价于“**exactly-once enqueue**”——入队侧靠唯一键做到精确一次，执行侧再靠幂等做到
  effectively-once，两段分别治理。

### Q9. 为什么 outbox 的 scheduler 是单线程，其他是 2？
**答**：outbox 只发消息，单线程 poll 更易保持发布顺序，且发布本身轻。delay/statement 的 poll 与
recover 想并行一点，用 pool=2。
- **硬核追问：单线程 poll 会不会成为发布瓶颈？**
  poll 只做“领取 + 提交 worker”，真正发 Kafka 在 `outboxWorkerExecutor` 多线程里；
  单线程调度足够喂饱 worker 池。要再扩就加 worker 线程，不必加 scheduler 线程。

### Q10. job 进了 DEAD 之后呢？
**答**：（这是当前 gap）三家族 DEAD 都只是停在表里，**缺指标/告警/重放入口**。生产应有
`status=DEAD` 计数指标、告警，以及把 DEAD 改回 PENDING 的运维路径（类似消费侧 DLT 的重放）。
- **强补充**：消费侧已有 Kafka DLT，但**生产/执行侧没有对等物**——这是三家族共同要补的工程化部分。

### Q11. 这套和 Quartz / Spring `@Scheduled` / K8s CronJob 有什么区别？
**答**：`@Scheduled` 只负责“周期触发”，不带分布式领取/重试/恢复；Quartz 也能 DB-backed 但更重、
偏“调度”而非“工作队列”；K8s CronJob 是容器级定时。本项目是**轻量自建的 claimable job**：
`@Scheduled` 触发轮询，真正的并发/租约/重试/恢复由 Claimer/Worker/Recoverer 承载。
- **强补充**：billing 用 `cron`（何时开始）+ statement job（如何并发执行）**组合**，正好示范
  “time-trigger 与 work-queue 各司其职”。

### Q12. 时间为什么从注入的 `Clock` 取，而不是 `Instant.now()`？
**答**：可测试（固定 now 测 lease 超时、退避、关账日判定）+ 统一时区（billing 用 Asia/Tokyo，
JST 月末日切不能用 UTC）。时间是**依赖**，不该硬编码进业务。

---

## 第五部分：一句话总结

DelayJob / Outbox / StatementJob 是**同一个 claimable-job 骨架的三种皮肤**：
短事务 SKIP LOCKED 领取、claim 后才执行、lease + 令牌防覆盖、recoverer 兜底、有界重试 → DEAD、
创建幂等、scheduler/worker 分池。差异都是**被场景逼出来的有意取舍**（单动作 vs 发消息 vs 分片扇出；
4 类框架 vs 1 类一次性；单列 lease vs 多列 lease；有退避 vs 无退避）。
真正待补的工程化是三块：**DEAD 的可观测与重放、完成态行的保留清理、statement 分片的账户级失败隔离与退避**。
读代码顺序建议：`DelayJob*`（最纯）→ `Outbox*`（同构 + 发消息）→ `StatementJob*`（合并 + 扇出）。
