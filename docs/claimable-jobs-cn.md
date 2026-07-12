# Claimable Job 三大家族：DelayJob / Outbox / StatementJob

> 本文合并自三份旧文档：`claimable-job-families-comparison-claude-cn.md`（三家族对比）、
> `statement-job-design-cn.md`（StatementJob 单家族深入）、`async-workflows-comparison-cn.md`
> （异步机制横向对比）。合并时**改正了一处明显过时**：`async-workflows` 把 statement 批处理
> 描述成"日历驱动、没有 Claimer/Recoverer、不是 claimable job 的 `StatementBatchPoller/Service`"，
> 但 PR #1 已把它**扁平化**成真正的 claimable job（`statement_jobs` 表 + `StatementJobDispatcher`，
> 带 claim/lease/recover），旧的 `StatementBatch*` 类已删除。原三份已归档在 `docs/archive/`。
> 消息发布细节（Outbox→Kafka→消费者）见 `events-outbox-inbox-kafka-cn.md`；端到端业务流程见
> `implementation-walkthrough-cn.md`。

> 关键词：可领取任务, 数据库队列, 租约, 竞争消费者, 退避重试, 死信, 分片扇出, claimable job,
> database-backed queue, lease, competing consumers, exponential backoff, sharding,
> ジョブ取得, リース, デッドレター。

阅读建议：先看 §1 统一模型，再看 §3 总对比表（全文核心），其余是展开。

---

## 1. 统一模型：三家族共享的 "claimable job"

项目里有三套**数据库支撑的可领取任务**——通用延迟任务 `delayjob`、可靠发布 `messaging/outbox`、
账单分片批处理 `statement` job。三者是同一个抽象 **database-backed work queue** 的不同实例：

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

调度回路：poll（定时扫描）→ claim（短事务 + FOR UPDATE SKIP LOCKED）
        → 提交 worker pool 执行 → finalize（重校验 lease token 后标终态/重试/DEAD）
        → recover（lease 超时放回）
```

**七条共有不变量**（interview 时这就是"统一答案"）：

1. **短事务 claim + `FOR UPDATE SKIP LOCKED`**：多 pod 并发领取不同 row，天然 competing consumers。
2. **claim 提交后才执行**：`PENDING->PROCESSING` 先 commit，再交给 worker pool。**执行/等 broker 期间不持 job row lock**。*反向事实*：若把 claim 和长业务放一个大事务，长业务会长时间持有 job row lock，其他实例什么也领不到。
3. **lease 不是所有权**：PROCESSING 带截止时间；worker 宕机后 recoverer 放回重试。*反向事实*：没有 recoverer，任务会永久卡死。
4. **finalize 重校验 lease token**：`lockCurrentLease` 重新 `FOR UPDATE` 取行比对 token，防迟到的老 worker 覆盖新状态（乐观并发）。*反向事实*：不校验 token，一个 lease 过期后才返回的 worker 可能把别人正在处理的任务错标成 DONE。
5. **有界重试 → DEAD**：达到 `maxAttempts` 转 DEAD，阻断 poison job 无限重试。
6. **创建幂等**：唯一键保证"同一逻辑任务只入队一次"（`insertIfAbsent` / `INSERT IGNORE`）。
7. **平台资源隔离**：独立 scheduler 线程池（只 poll/claim/recover）+ 独立 worker 线程池（跑业务），命名线程便于排障，`@ConditionalOnProperty` 开关（见 §5）。

---

## 2. 三个家族

### 2.1 家族 A：`delayjob`（通用延迟业务动作）

**4 个执行者，职责单一**：

| 类 | 职责 |
|---|---|
| `DelayJobPoller` | `@Scheduled` 周期 poll，按 jobType 分池提交：`AUTO_REPAYMENT`（外部银行调用）→ `autoRepaymentDelayJobWorkerExecutor`，其余（纯 DB 小事务）→ `delayJobWorkerExecutor`——银行 brownout 只钉住专用池，不拖授权额度释放 |
| `DelayJobClaimer` | 短事务 `FOR UPDATE SKIP LOCKED` 领 PENDING → PROCESSING lease |
| `DelayJobWorker` | 按 `jobType` 经 `EnumMap<DelayJobType, handler>` 派发；成功 `markDone`/失败 `markFailed`/确定性失败（`DelayJobPermanentException`，如银行 4xx）`markPermanentFailed` 直接 DEAD 不烧重试；finalize 独立短事务 + lease-token 重校验 |
| `DelayJobRecoverer` | `@Scheduled` 扫 lease 超时，`markProcessingTimedOut` |

- **一个 job = 一个聚合的一次未来动作**（point action at time T）。`DelayJob` 带 `jobType / aggregateType / aggregateId / scheduledAt`。
- **两种 jobType**：`AUTHORIZATION_EXPIRY`（7 天未 capture 自动释放额度）、`AUTO_REPAYMENT`（到期口座振替自动扣款）。
- **业务方创建**：如 `AutoRepaymentDelayJobScheduler.scheduleAutoRepayment` 在账单生成同事务里 `insertIfAbsent`，`scheduledAt = dueDate`。
- **创建幂等**：`UNIQUE (job_type, aggregate_type, aggregate_id)` —— 一张 statement 只会有一个 AUTO_REPAYMENT。
- **轻量 lease**：`next_attempt_at` 表达时间语义——PENDING 时=最早可执行时间，PROCESSING 时=lease deadline；
  `lease_token` 表达本轮 owner identity，finalize 时比较 token。
- **指数退避**：`markFailed` → `min(2^(n-1), 60s)`。
- 派发表启动时由 `DelayJobHandler` 列表转 `EnumMap`；缺 handler 走失败路径而非静默 DONE。

> 更正一个常见误解：`delayjob` **也有** lease token 校验（`DelayJobWorker.lockCurrentLease`
> 比较随机 `leaseToken`）。三家族区别不在"有没有 lease 校验"，而在粒度/退避/lease 列模型。

### 2.2 家族 B：`messaging/outbox`（可靠消息发布）

与 delayjob **同构的 4 类**（`OutboxPoller / OutboxClaimer / OutboxWorker / OutboxRecoverer`），但：

- **一个 row = 一条要发布的消息**，**没有业务 handler**——worker 的"业务"就是 `KafkaOutboxMessagePublisher.publish` 同步等 broker ack。
- 业务方在状态变更**同事务**写入（transactional outbox），无 `scheduledAt`：`next_attempt_at=createdAt` 立即可发。
- 轻量 lease（同样是 `next_attempt_at` + `lease_token`），指数退避对称，终态是 **`PUBLISHED`**（不是 DONE）。
- 完整细节（dual-write、at-least-once、双层消费者幂等、DLT）见 **`events-outbox-inbox-kafka-cn.md`**。

### 2.3 家族 C：`statement` job（账单分片批处理，已扁平化为 claimable job）

**刻意合并成 1 个 dispatcher**（+ planner + daily cron + handler）：

| 类 | 职责 |
|---|---|
| `BillingCycleScheduler` | `@Scheduled(cron, zone=Asia/Tokyo)` 每天触发一次 reconciliation 心跳，本身不展开账期规则 |
| `StatementCycleService` | **planner**：扫描最近已过去的 close cycles，缺失周期才算 cycle（period/due date），按 `ceil(账户数 / targetAccountsPerJob)` 算 shardCount，`INSERT IGNORE` 建 N 个分片 |
| `StatementJobDispatcher` | **1 个类**完成 poll→短事务 claim→提交 `statementJobWorkerExecutor`→finalize→recover |
| `StatementJobHandler` | 处理一个 shard：`findAccountIdsForJob`（`CRC32(account_id) % shardCount`）取账户，**逐账户各开小事务**调 `StatementGenerationService`，统计 generated/skipped/failed |

- **一个 job = 一个分片，覆盖很多账户（fan-out）**。`StatementJob` 带 cycle 身份 `period_start/period_end/due_date` + `shard_no/shard_count` + 结果计数器。
- **daily cron 触发 reconciliation，执行靠 claimable job 并发跑**。这不是 edge-triggered "错过一次 tick 就丢账期"，而是 level-triggered catch-up：最近几个 close cycle 缺分片就补建。
- **创建幂等**：`UNIQUE (period_start, period_end, shard_no)`。
- **多列 lease（更丰富）**：`claimed_by / claimed_at / claim_until / claim_token`；
  `claim_until` 作 lease deadline，`claim_token` 作 finalize owner token。比 delay/outbox 多了"谁在处理、何时开始"的运维可见性。
- **无退避**：`markFailed` 直接回 PENDING（下个 ~1s poll 立即重领）或 DEAD。
- **故障隔离在账户级**：单账户失败不回滚整个 shard（每账户独立小事务）。详见 §4。

---

## 3. 总对比表（全文核心）

| 维度 | DelayJob | Outbox | StatementJob |
|---|---|---|---|
| **本质** | 延迟执行未来业务动作 | 可靠发布消息 | 周期账单分片批处理 |
| **一个 job 的粒度** | 1 聚合 1 动作 | 1 条消息 | 1 分片 N 账户（fan-out） |
| **类拆分** | 4 类（Poller/Claimer/Worker/Recoverer） | 4 类（同构） | **1 类 dispatcher** + planner + handler |
| **谁创建** | 业务方 `insertIfAbsent` | 业务方同事务 insert | daily cron → planner `INSERT IGNORE` |
| **触发时机** | `scheduledAt`（未来时刻） | 立即 | 关账日（日历） |
| **创建幂等键** | uk(job_type,agg_type,agg_id) | eventId（消费侧去重） | uk(period_start,period_end,shard_no) |
| **lease 列** | `next_attempt_at` + `lease_token` | `next_attempt_at` + `lease_token` | **多列** `claimed_by/at/until` + `claim_token` |
| **退避** | 指数 min(2^(n-1),60s) | 指数（对称） | **无退避**，立即回 PENDING |
| **终态** | DONE / DEAD | PUBLISHED / DEAD | DONE / DEAD |
| **业务 handler** | 有（按 jobType 派发） | 无（只 publish） | 有（账户级循环 fan-out） |
| **故障粒度** | 整个 job 成功/失败 | 整条消息 | 单账户隔离 + 分片级计数 |
| **scheduler 池** | `delayJobTaskScheduler`(2) | `outboxTaskScheduler`(**1**,保序) | `statementJobTaskScheduler`(2) + `billingCycleTaskScheduler`(1) |

> **一句话记忆**：三者是同一个 claimable-job 骨架的三种皮肤。
> **delayjob = 定时做一件事；outbox = 可靠发一条消息；statementjob = 分片跑一大批。**

---

## 4. StatementJob 深入：扁平化、数据模型、并发与隔离

### 4.1 为什么从"两层 batch"扁平化成"单层分片 job"

PR #1 之前是 **parent batch + 子任务两层**：`statement_batches`（父，status RUNNING/COMPLETED/PARTIALLY_FAILED）`1──<` `statement_jobs`（子分片），每个 job finalize 后还要 re-lock 父 batch、summarize 子任务状态、推进 batch 终态——约 12 个类（`StatementBatch/Service/Repository/Status/...`）。

**现在扁平化成单层**：`StatementCycleService` 直接创建 sharded job（无 batch 行），cycle 身份落在 job 上；`StatementJobDispatcher` 去掉所有 batch 完成度推进逻辑。**净删约 12 个类、800 行**。

| 维度 | 之前（batch + jobs） | 现在（flat jobs） |
| --- | --- | --- |
| 表数量 | 2（batches + jobs） | 1（jobs） |
| "整批是否完成" | 父 batch 行 + 完成度 re-lock + summary | 对 jobs 的 `GROUP BY status` 查询（按需） |
| cycle 身份 | 存父 batch，子 job 读父 | 直接存在 job 上 |
| 与 delayjob 形状 | 不同（两层、有父聚合） | 同形（单层 claimable job） |

**取舍**：失去"一行就能看到整批状态"的视图和 `PARTIALLY_FAILED` 批级语义；换来更少表/类/概念、claim 机制成为唯一主角。需要批级 SLA/看板时，再加一个**读模型查询**或轻量 batch 视图即可，不必长期维护父表 + 生命周期。

### 4.2 数据模型

`statement_jobs`（迁移 `0005-flatten-statement-jobs.sql`）核心列：

```text
id;  period_start / period_end / due_date           ← cycle 身份直接落在 job 上
shard_no / shard_count                              ← 分片
status  PENDING / PROCESSING / DONE / DEAD
claimed_by / claimed_at / claim_until / claim_token ← lease metadata + owner token
attempt_count
processed_/generated_/skipped_/failed_account_count ← 分片级计数（observability）
created_at / updated_at / last_error
```

约束：`uk(period_start, period_end, shard_no)`（创建幂等）；`idx(status, claim_until, created_at)`（claim/recover 扫描）；`chk` 保证 PROCESSING 必有完整 lease metadata + `claim_token`、`period_end >= period_start AND due_date > period_end`。

### 4.3 一次月度出账并发 walkthrough

```text
6/30 关账，7/1 01:00 JST  BillingCycleScheduler 触发 → StatementCycleService.createDueJobs
  → periodEnd=6/30 是关账日 → cycle: periodStart=6/1, periodEnd=6/30, dueDate=7/27
    （27 日落周末按 JapaneseBusinessDayCalendar 顺延工作日）
  → countBillableAccounts=2500 → shardCount=ceil(2500/1000)=3
  → INSERT IGNORE 建 shard 0/1/2（重复触发靠 uk 幂等跳过）
每 1s  StatementJobDispatcher.dispatch：短事务 FOR UPDATE SKIP LOCKED 领 PENDING
       → 改 PROCESSING（写 claimed_by/claimed_at/claim_until/claim_token）→ commit → 交 statementJobWorkerExecutor
worker  StatementJobHandler.handle：CRC32(account)%shardCount 取本片账户，逐个 generate（各开小事务）
        每 100 个账户无锁快查 claim_token；lease 已被 recover 接管则放弃剩余账户（止损，不出错）
finalize 新短事务：findByIdForUpdate 重锁 job + 校验 claim_token 仍是自己 → 标 DONE / PENDING / DEAD
每 10s  recoverStuckJobs：status=PROCESSING AND claim_until<=now 视为宕机，按一次失败放回
```

> *反向事实*：若一个分片用一个大事务处理上千账户，会长时间持有一组账户行锁，和 posting/repayment 抢锁，放大锁等待。所以**一个账户一个小事务**。

### 4.4 三道幂等防线 + 账户级故障隔离

- **创建分片幂等**：`uk(period_start, period_end, shard_no)` + `INSERT IGNORE`（多实例/当天重跑不重复建片）。
- **单账户出账幂等**：`statements` 自然键 `unique(credit_account_id, period_start, period_end)`，且先锁 account 再 `findByCycleForUpdate`（一账户一期只一张账单，重试不重复出账）。
- **交易归账幂等**：出账后标 `billing_status = BILLED`，下轮不再被选入。

`StatementJobHandler` 对单账户结果分类计数，不让一个坏账户拖垮整片：成功→`generated`；该账户本期无可出账交易（`rejected`）→`skipped`；可恢复错误（如 ledger 未就绪，`retryable`）/未预期异常→`failed`。只要 `failed>0` 整片回 `PENDING/DEAD` 重试，否则 `DONE`。计数落库作分片级 observability。

**中途 lease 检查（收窄新旧 worker 重叠窗口）**：handler 每处理 100 个账户做一次无锁主键点查
（`status='PROCESSING' AND claim_token=?`）；lease 已被 recoverer/新 worker 接管时立即放弃剩余账户。
没有这一步也**不会出错**（账户级幂等 + finalize token 校验兜底），但旧 worker 会把整片白跑一遍、
和新 worker 抢同一批账户的 row lock；有了它，重叠窗口从"整片跑完"缩到最多 100 个账户。

---

## 5. 平台执行资源：scheduler 池 ≠ worker 池

| 包 / 类 | 实际含义 | 不应误解成 |
| --- | --- | --- |
| `infrastructure.scheduler.PollingSchedulerConfiguration` | 创建 `outboxTaskScheduler`(1) / `delayJobTaskScheduler`(2) / `billingCycleTaskScheduler`(1) / `statementJobTaskScheduler`(2) 等 Spring scheduler 线程池，**只跑 poll/claim/recover** | 各机制的业务 scheduler 逻辑 |
| `infrastructure.async.WorkerExecutorConfiguration` | 创建 `outboxWorkerExecutor` / `delayJobWorkerExecutor` / `autoRepaymentDelayJobWorkerExecutor` / `statementJobWorkerExecutor` 等 **有界** worker 池（core=max），**跑真正业务** | 所有异步流程的入口 |
| `infrastructure.transaction.TransactionOperationsConfiguration` | 把 transaction manager 包成 `TransactionOperations`，给 worker 显式开短事务 | application service 的业务事务规则 |
| `messaging/outbox`、`delayjob` | 可靠性状态机（表、lease、worker、recoverer） | 普通 Kafka helper / 普通 `@Scheduled` |

- **为什么 scheduler 池和 worker 池分开**：`@Scheduled` 线程若直接跑长业务/等 broker，会拖住下一轮调度；三机制各用各的池，一种 backlog 不会饿死另一种；命名前缀让 thread dump / metrics 一眼区分。
- **worker 池有界 + 背压**：`queueCapacity` 满 → `TaskRejectedException` → poller 调 `markRejectedForRetry` 放回 retry（不卡在 PROCESSING）；`waitForTasksToCompleteOnShutdown=true` 优雅关闭。这是**对 DB 的背压**：宁可 job 稍后重试，也不让无限并发把 `credit_accounts` 行锁和连接池打满。
- **outbox scheduler 单线程**：发布保序更简单（虽然 SKIP LOCKED 也不会重复）；poll 只"领取+提交"，真正发 Kafka 在多线程 worker 池里，单线程调度足够喂饱。

> 命名澄清（同名不混淆）：`AuthorizationExpiryJobScheduler` 是**业务计划 port**（"计划一个过期动作"）；`AuthorizationExpiryDelayJobScheduler` 是 **DelayJob adapter**（把计划写成 `delay_jobs` row）；`DelayJobPoller` 才是真正的 `@Scheduled` 入口；`PollingSchedulerConfiguration` 是线程池配置。业务 service 只依赖 scheduler port 表达未来动作，不依赖 `delay_jobs` 表。

---

## 6. 保持一致 vs 故意不同

**Outbox 与 DelayJob 已收敛成一组对称结构**（同一套 claim-lease-recover 角色）：

| 统一角色 | Outbox | DelayJob | 共同点 |
| --- | --- | --- | --- |
| Poller | `OutboxPoller` | `DelayJobPoller` | `@Scheduled` 入口，只 poll/submit |
| Claimer | `OutboxClaimer` | `DelayJobClaimer` | 短事务 claim due rows，写 PROCESSING lease |
| Worker | `OutboxWorker` | `DelayJobWorker` | 执行长动作，再重锁 row finalize |
| Recoverer | `OutboxRecoverer` | `DelayJobRecoverer` | 恢复 lease 超时的 PROCESSING |
| State row | `OutboxEvent` | `DelayJob` | 都有 attempts、nextAttemptAt、lastError、retry/DEAD |

**而这些不同是被业务语义逼出来的，不是命名不统一**：

| 不同点 | 为什么不强行统一 |
| --- | --- |
| Outbox worker 无 handler dispatch，DelayJob 有 | Outbox 动作永远是 publish；DelayJob 执行多种业务动作，需按 `jobType` 派发 |
| 终态 `PUBLISHED` vs `DONE` | 一个完成消息发布，一个完成业务动作执行 |
| StatementJob 合 1 类 dispatcher，delay/outbox 拆 4 类 | **framework vs one-off**：delay/outbox 是跨 domain 复用的平台机制（拆类换可测/复用）；statement 是单一消费者的领域批（合 1 类端到端一眼读完，拆 4 类反而 over-engineering） |
| StatementJob 多列 lease + 无退避，delay/outbox 单列 + 退避 | fan-out 需要 `claimed_by/at` 的运维可见性；批处理失败少、立即重领够用（但见 §7 gap） |

> **关键**：正确性语义必须一致（三者共享 §1 那七条不变量），只是类数量/lease 列/退避不同。新 domain 加任务时只能从这两种参考形状里选，不许发明第三种 naive `@Scheduled`。

---

## 7. 优点与真实 gap（已对齐代码核对）

**优点**：一套心智模型覆盖三种场景；正确性细节齐备（SKIP LOCKED 竞争领取、claim 后才执行不持锁、lease-token 防迟到覆盖、recoverer 兜底、有界重试→DEAD、创建幂等）；平台资源隔离干净（分池/命名/优雅关闭/开关）；取舍有意识（framework 拆 4 类 vs one-off 合 1 类）。

**真实 gap（不是 bug，是上生产还差的工程化）**：

1. **DEAD 全家族缺可观测与重放（最该补）**。三者达 maxAttempts 转 DEAD 后只是躺在表里，**无指标/告警/"DEAD→重新入队"运维入口**。授权过期没释放额度、自动扣款没扣、某分片没出账——目前只能人去查表。注意：消费侧虽已有 Kafka DLT 写入，但同样没有 monitor group、告警和受控 replay 工具，尚未形成运维闭环。
2. **完成态行无保留策略**。`delay_jobs` 的 DONE、`statement_jobs` 的 DONE、`outbox_events` 的 PUBLISHED 都**永不清理**，表无界增长。`delay_jobs` 还有个微妙点：`uk(job_type,agg_type,agg_id)` 让一个聚合的 DONE 行**永久占用唯一键**（这本身是想要的 idempotency），所以清理时要保留 key 或迁冷表，不能裸删。
3. **StatementJob 单账户失败连坐整片（账户级重试隔离缺口）**。`failedAccountCount>0` 就把**整个 shard** 标失败回 PENDING 重领。账户级幂等让重跑安全且代价小（UNBILLED 过滤让重试自然收敛到失败账户），但重试**预算**记在 job 上：一个**永久失败**的账户每轮都烧掉一次 attempt，把整片 cycle 到 DEAD——同片里"晚一点就能成功"的账户（如等 ledger projection 补齐）被连坐，失去重试机会；且 DEAD 在 shard 粒度**看不出哪个账户坏了**（账户级明细只在日志里）。
   - **主要触发源是 ledger 投影滞后**（statement 生成前的缺失检查抛 retryable）。正常滞后是秒级，`max-attempts: 10` 足够覆盖；真正"永远补不齐"只会来自 ledger 消息进了 DLT——低概率且可观测，**ledger DLT 告警是这个缺口的第一道观测线**。
   - **方案已知但刻意不建**：失败账户单独落表（accountId/原因/attempt/next_retry_at）+ 独立退避重试循环，job 在所有账户终态后才 DONE/DEAD。不建的原因是成本收益：新表+迁移+新调度循环，对冲的却是"DLT 积压叠加账期收尾"的低概率场景——先靠 DLT 告警兜底，等真实发生率证明需要再上基建。
4. **StatementJob 无退避**。失败分片立即回 PENDING，持久失败会**高频 spin** 直到 maxAttempts，而 delay/outbox 都有指数退避。统一上 backoff 更稳（约十行：加一列 `next_attempt_at`，claim 查询改 `status='PENDING' AND next_attempt_at<=now`）。

> 顺带：polling 的秒级延迟、DB 当队列的吞吐上限，是**有意取舍**而非 gap（见 §9 Q1）。

---

## 8. 通用领域知识 / 设计模式

- **Database-backed work queue**：一张表 + 状态列当队列。优点：入队与业务状态**同库同事务**（无 dual-write）、运维简单、`SKIP LOCKED` 即得竞争消费；缺点：轮询延迟、表增长、不适合极高吞吐。
- **Competing Consumers + `SELECT … FOR UPDATE SKIP LOCKED`**：多消费者并发领取互不重叠，无需额外 MQ。MySQL 8.0+ / PostgreSQL 都支持。
- **Lease / Visibility Timeout**：领取者只拿一段时间的处理权，超时被回收。等价于 SQS visibility timeout、Kafka `max.poll.interval`。
- **Optimistic lease token**：finalize 前比对随机 owner token（轻量家族用 `lease_token`，StatementJob 用 `claim_token`），防迟到 worker 覆盖新状态。
- **Claim-then-execute**：claim 短事务提交后才干活，避免持 row lock 跑长业务/等外部。
- **At-least-once execution + Idempotent handler = Effectively-once**：lease 到期但老 worker 仍活会重复执行，所以 handler 必须幂等（`AUTO_REPAYMENT` 用确定性键 `auto-debit:<statementId>` + 银行侧去重；`AUTHORIZATION_EXPIRY` 用聚合状态检查"已释放则跳过"）。
- **Idempotent enqueue**：唯一键 + `insertIfAbsent`/`INSERT IGNORE` = 入队侧 exactly-once；执行侧再靠幂等 = effectively-once。两段分别治理。
- **Bounded worker pool + Backpressure**：core=max 固定、队列有界、满则拒绝并 requeue，避免压垮 DB。
- **Exponential backoff + Dead Letter**：可恢复故障退避重试（设上限避免恢复后睡太久），poison job 进 DEAD。
- **Sharding / Fan-out**：大批量用 `hash(key) % shardCount` 切片并行（`CRC32(account_id) % shardCount`），每片再拆账户级小事务隔离失败。
- **Time-triggered vs Event-triggered**：cron（billing daily）负责**何时开始**，claimable job 负责**并发执行 + 重试 + 恢复**——组合，不互相替代。
- **可注入 `Clock`**：时间是依赖，便于测固定 now（lease 超时/退避/关账日），并统一 billing 时区（Asia/Tokyo，JST 月末日切不能用 UTC）。

---

## 9. 硬核面试 Q&A

**Q1. 为什么用数据库表当队列，而不是直接上 Kafka/RabbitMQ？什么时候必须换？**
"任务计划"要和业务状态**同事务**落地避免 dual-write（账单生成与"到期扣款计划"、授权批准与"7 天过期计划"必须同生共死，写 MQ 是另一个系统无法和 MySQL 原子提交）；DB 表 + `SKIP LOCKED` 直接得到竞争消费/重试/恢复，运维简单。缺点：轮询秒级延迟、表增长（要归档）、DB 单点；**每秒数万任务的极高吞吐/低延迟**场景会压垮 DB，那时上专用 MQ。本项目万级/十万级 jobs/day、秒级延迟可接受，DB 队列是最优性价比。

**Q2. PROCESSING lease 是什么？为什么 delay/outbox/notification 用 `lease_token`，statement 用多列 claim？**
lease 是"限时处理权"。delay/outbox/notification 一个 row 是单一工作单元，所以保留轻量模型：
`next_attempt_at` 表示下次可执行/lease deadline，`lease_token` 表示本轮 owner。
statement 是分片 fan-out，需要 `claimed_by/claimed_at/claim_until` 的运维可见性，并用
`claim_token` 作为真正 owner token。
- *追问：为什么不用时间戳当 token？* Java `Instant` 与 MySQL `TIMESTAMP(6)` 有精度边界；
  timestamp 适合表达 deadline，随机 token 更适合回答"这次 finalize 是否仍属于我"。

**Q3. 为什么 claim 和 execute 分两段事务？execute 成功但 finalize 失败呢？**
claim 短事务提交即放锁，再交 worker；否则跑长业务（上千账户）或等 Kafka ack 期间一直持 job row lock，高峰行锁+连接池一起爆。finalize 失败 → job 仍 PROCESSING，lease 到期 recoverer 放回 → 重新执行（at-least-once，靠 handler 幂等兜底）。

**Q4. 多 pod，一个 job 会被执行两次吗？at-least-once 还是 exactly-once？**
claim 用 SKIP LOCKED 正常只一个 pod 领到；但 **lease 到期而老 worker 其实还活着**时 recoverer 会放回、另一 worker 重做 → 可能执行两次。所以是 **at-least-once 执行 + 幂等 handler = effectively-once**。lease 解决"卡死可见性"，**不是"绝不重复"**——别把 lease 当锁保证 exactly-once。

**Q5. worker pool 满了、或正在 shutdown，job 会怎样？**
`execute` 抛 `TaskRejectedException` → `markRejectedForRetry` → 从 PROCESSING 放回 retry，不卡死；shutdown 时 `waitForTasksToCompleteOnShutdown=true` 尽量跑完，剩下靠 lease 恢复。这是对 DB 的背压。

**Q6. 为什么 DelayJob/Outbox 拆 4 类，StatementJob 合 1 类？这不是不一致吗？**
不是，是 **framework-vs-one-off** 有意取舍。delay/outbox 是跨 domain 复用的平台机制、被多 handler 消费 → 拆 4 类换可测/复用；statement 是单一消费者的领域批 → 合 1 dispatcher 端到端一眼读完，拆 4 类是 over-engineering。
- *追问：怎么保证两种打包不退化成两套各有 bug 的实现？* 正确性语义必须一致（共享 §1 七条不变量），只类数不同；新 domain 只能从这两种形状里选。

**Q7. statement 为什么分片？shardCount 怎么定、建 job 后来了新账户怎么办？**
百万账户单事务/单 worker 不可行（锁太久、失败全回滚）；`CRC32(account_id) % shardCount` 扇出并行，每片再开账户级小事务隔离失败。shardCount=`ceil(账户数/targetPerJob)`，**建 job 时算一次并存到 job 上**。建后来的新账户用 job 上**已存的 shardCount** 做 hash，仍恰好落到某一个 shard，不漏不重——分片数固定在创建时，避免"中途 shardCount 变化导致同账户落到不同片"的重复出账。

**Q8. 怎么防止同一周期/聚合被重复建 job？**
唯一键 + 幂等插入。delay：`uk(job_type, agg_type, agg_id)` + `insertIfAbsent`；statement：`uk(period_start, period_end, shard_no)` + `INSERT IGNORE`。多实例/当天重跑都不重复——等价于 exactly-once enqueue。

**Q9. job 进了 DEAD 之后呢？**（当前 gap）三家族 DEAD 都只停在表里，缺指标/告警/重放。生产应有 `status=DEAD` 计数指标、告警，以及把 DEAD 改回 PENDING 的运维路径（类似消费侧 DLT 重放）。消费侧已有 Kafka DLT，但执行侧没有对等物。

**Q10. 这套和 Quartz / Spring `@Scheduled` / K8s CronJob 有什么区别？**
`@Scheduled` 只负责周期触发，不带分布式领取/重试/恢复；Quartz 也能 DB-backed 但更重、偏"调度"而非"工作队列"；K8s CronJob 是容器级定时。本项目是**轻量自建 claimable job**：`@Scheduled` 触发轮询，并发/租约/重试/恢复由 Claimer/Worker/Recoverer 承载。billing 用 cron（何时开始）+ statement job（如何并发执行）组合，正好示范"time-trigger 与 work-queue 各司其职"。

---

## 10. 一句话总结 + 读代码顺序

DelayJob / Outbox / StatementJob 是**同一个 claimable-job 骨架的三种皮肤**：短事务 SKIP LOCKED 领取、claim 后才执行、lease + 令牌防覆盖、recoverer 兜底、有界重试→DEAD、创建幂等、scheduler/worker 分池。差异都是**被场景逼出来的有意取舍**（单动作 vs 发消息 vs 分片扇出；4 类框架 vs 1 类一次性；单列 vs 多列 lease；有退避 vs 无退避）。真正待补的工程化：**DEAD 的可观测与重放、完成态行的保留清理、statement 分片的账户级失败隔离与退避**。

读代码顺序：`DelayJob*`（最纯）→ `Outbox*`（同构 + 发消息）→ `StatementJobDispatcher / StatementJobHandler / StatementCycleService`（合并 + 扇出）。
