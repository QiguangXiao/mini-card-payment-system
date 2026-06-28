# Statement Job 设计与对比说明

> 关键词：claimable job, DB claim, FOR UPDATE SKIP LOCKED, PROCESSING lease,
> lease token, recover, sharding, fan-out, idempotency, fault isolation,
> 請求ジョブ(せいきゅうジョブ)。

本文说明 `statement` 模块的账单批处理（statement job）当前是怎么设计的，
为什么从“parent batch + 子任务”两层结构**扁平化（flatten）**成单层 claimable job，
以及它和项目里其他 database-backed job（`delayjob`、`outbox`）的区别、优缺点。

相关文档：
- 跨机制对比（scheduler / outbox / delayjob / kafka）见
  [async-workflows-comparison-cn.md](async-workflows-comparison-cn.md)。
- Outbox 可靠投递细节见 [kafka-outbox-design.md](kafka-outbox-design.md)。

---

## 1. 什么是 claimable job

本项目所有“数据库工作队列”都遵循同一个核心模式，本文统一称为 **claimable job**：

```
状态机(state machine)：
  PENDING ──claim──▶ PROCESSING(lease) ──成功──▶ DONE
                          │
                          ├──失败且未超限──▶ PENDING（重试）
                          └──失败且超限──▶ DEAD（人工排查）

调度回路(dispatch loop)：
  poll（定时扫描）
   → claim（短事务 + FOR UPDATE SKIP LOCKED，把 PENDING 改成 PROCESSING lease）
   → 提交 worker pool 执行业务
   → finalize（重新校验 lease token 后标 DONE / 重试 / DEAD）
   → recover（把 lease 超时的 PROCESSING 放回 PENDING / DEAD）
```

三个反复出现的关键设计点：

- **claim 是短事务**：只做 `PENDING → PROCESSING`，提交后才执行业务。
  *如果* 把 claim 和业务放进一个大事务，长业务会长时间持有 job row lock，
  其他实例什么也领不到。
- **PROCESSING 是 lease（租约），不是永久所有权**：worker/pod 宕机后，
  recover 会把超时任务放回可执行状态。*如果* 没有 recover，任务会永久卡死。
- **finalize 时校验 lease token**：迟到的旧 worker 不能覆盖已经被 recover/reclaim
  的新状态。*如果* 不校验 token，一个 lease 过期后才返回的 worker 可能把别人
  正在处理的任务错误地标成 DONE。

`statement` 的 job 现在是这个模式的 **reference 实现**，目标是让 `delayjob`、`outbox`
之后可以对齐成同样的形状。

---

## 2. 当前 statement job 处理流程

### 2.1 角色分工

| 组件 | 职责 |
| --- | --- |
| `BillingCycleScheduler` | daily cron（JST），每天醒一次，只判断“今天要不要建本期 job” |
| `StatementCycleService` | 计算 billing cycle（period / due date）、按账户数算分片数、原子创建 sharded jobs |
| `StatementJobDispatcher` | 一个类完成 poll / claim / 派发 / finalize / recover |
| `StatementJobHandler` | 处理“一个分片”：取该 shard 的账户，逐个调用 generation |
| `StatementGenerationService` | 处理“一个账户”：锁账户 → 快照交易 → 出账（独立小事务） |

> 设计要点：**一个 job = 一个分片（shard）**，**一个账户 = 一个小事务**。
> job handler 不持有覆盖所有账户的大事务，而是把每个账户交给
> `StatementGenerationService` 自己开 transaction boundary 并锁 credit account。
> *如果* 一个分片用一个大事务处理上千账户，会长时间持有一组账户行锁，
> 和 posting / repayment 抢锁，放大锁等待。

### 2.2 一次月度出账的请求路径（concrete walkthrough）

假设关账日（close day）= 月末，支付基准日（payment base day）= 27 日：

1. **6/30 关账，7/1 01:00 JST** `BillingCycleScheduler` 触发
   → `StatementCycleService.createDueJobs(runDate=7/1)`。
2. 判断 `periodEnd = 6/30` 是否为关账日 → 是。计算 cycle：
   `periodStart=6/1, periodEnd=6/30, dueDate=7/27`（27 日落周末则按
   `JapaneseBusinessDayCalendar` 顺延到工作日）。
3. `countBillableAccounts(...)` = 2500 → `shardCount = ceil(2500 / 1000) = 3`。
4. 原子创建 3 个 `StatementJob`（shard 0/1/2），`INSERT IGNORE`：
   重复触发同一关账日，靠 `uk_statement_jobs_cycle_shard` 幂等跳过。
5. **每 1 秒** `StatementJobDispatcher.dispatch()`：短事务 `FOR UPDATE SKIP LOCKED`
   领取 PENDING job，改 PROCESSING（写 `claimed_by / claimed_at / claim_until / claim_token`），提交，
   把 job 交给 `statementJobWorkerExecutor` 线程池。
6. worker 里 `StatementJobHandler.handle(job)`：用 `job.shardNo / shardCount`
   把账户哈希分片，`findAccountIdsForJob(...)` 拿本片账户，逐个
   `generate(...)`。单账户失败被隔离（见 2.4）。
7. handler 返回计数后 `dispatch` 在新短事务里 finalize：先 `findByIdForUpdate`
   重新锁 job 并**校验 lease token**（`claim_token` 是否仍是自己 claim 时的值），
   再标 `DONE` 或 `PENDING/DEAD`。
8. **每 10 秒** `recoverStuckJobs()`：`status=PROCESSING AND claim_until <= now`
   的 job 视为 worker 宕机，按一次失败处理放回 PENDING / DEAD。

### 2.3 idempotency（幂等）的三道防线

- **创建分片幂等**：`uk_statement_jobs_cycle_shard (period_start, period_end, shard_no)`
  + `INSERT IGNORE`。scheduler 多实例或当天重跑不会重复建分片。
- **单账户出账幂等**：`statements` 的自然键 `unique(credit_account_id, period_start, period_end)`，
  且 `StatementGenerationService` 先锁 account 再 `findByCycleForUpdate`。
  一个账户一期只会有一张账单，job 重试不会重复出账。
- **交易归账幂等**：交易出账后标 `billing_status = BILLED`，下一轮不会再被选入。

### 2.4 fault isolation（故障隔离）

`StatementJobHandler` 对单账户的三类结果分别计数，不让一个坏账户拖垮整片：

| 单账户结果 | 计入 | 含义 |
| --- | --- | --- |
| 成功 | `generated` | 正常出账 |
| `StatementGenerationException.rejected` | `skipped` | 该账户本期无可出账交易 |
| `StatementGenerationException.retryable` | `failed` | 可恢复错误（如 ledger 未就绪） |
| 其他 `RuntimeException` | `failed` | 未预期错误 |

只要 `failed > 0`，整片 finalize 成 `PENDING/DEAD`（会重试整片）；否则 `DONE`。
计数（processed / generated / skipped / failed）落库，作为分片级 observability。

---

## 3. 数据模型（flatten 之后）

`statement_jobs`（见迁移 `0005-flatten-statement-jobs.sql`）核心列：

```
id            cycle 身份：period_start / period_end / due_date     ← 直接落在 job 上
shard_no / shard_count                                            ← 分片
status        PENDING / PROCESSING / DONE / DEAD
claimed_by / claimed_at / claim_until / claim_token               ← lease metadata + owner token
attempt_count
processed_/generated_/skipped_/failed_account_count              ← 分片级计数
created_at / updated_at / last_error
```

关键约束：
- `uk_statement_jobs_cycle_shard (period_start, period_end, shard_no)` —— 创建幂等。
- `idx_statement_jobs_claimable (status, claim_until, created_at)` —— claim / recover 扫描。
- `chk_statement_jobs_claim_state` —— PROCESSING 必须有完整 lease metadata + token，
  非 PROCESSING 不能残留 lease。
- `chk_statement_jobs_period` —— `period_end >= period_start AND due_date > period_end`。

> 没有 `statement_batches` 表。“本期是否全部出账完成 / 有没有 DEAD 分片”
> 改成对 `statement_jobs` 按 cycle 的查询（`GROUP BY status`）回答，
> 不再维护一张独立的 batch 生命周期表。

---

## 4. 与“之前实现”的对比（flatten 前后）

### 4.1 之前：parent batch + 子任务（两层）

```
statement_batches（父）  1 ──< statement_jobs（子分片）
  status: RUNNING / COMPLETED / PARTIALLY_FAILED
  completed_at, total_account_count, job_count, ...
```

- `StatementBatchService` 创建 batch 行 + 子 job 行；每个 job finalize 后调用
  `completeBatchIfAllJobsFinished(batchId)`：重新锁 batch、`summarizeByBatchId`
  统计子任务状态、把 batch 推进终态。
- 子 job 通过 `batch_id` 外键 + `uk(batch_id, shard_no)` 关联父批。
- 涉及类：`StatementBatch` / `StatementBatchStatus` / `StatementBatchService` /
  `StatementBatchRepository`(+impl/mapper/row/xml) / `StatementJobStatusSummary`(+row) /
  `StatementBatchCreationResult`，约 12 个文件。

### 4.2 现在：单层 sharded claimable job

- `StatementCycleService` 直接创建 sharded job（无 batch 行），cycle 身份落在 job 上。
- `StatementJobDispatcher` 去掉所有 batch 完成度推进逻辑。
- 删除上面约 12 个 batch 相关文件，净减约 800 行。

### 4.3 对比表

| 维度 | 之前（batch + jobs） | 现在（flat jobs） |
| --- | --- | --- |
| 表数量 | 2（batches + jobs） | 1（jobs） |
| 创建幂等 | batch `unique(period)` | job `unique(period, shard)` + `INSERT IGNORE` |
| “整批是否完成” | 父 batch 行 + 完成度重锁 + summary | 对 jobs 的查询（按需） |
| cycle 身份 | 存父 batch，子 job 读父 | 直接存在 job 上 |
| 类 / 行数 | 多约 12 个类 | 砍掉约 12 个类、800 行 |
| 与 delayjob 形状 | 不同（两层、有父聚合） | 同形（单层 claimable job） |

### 4.4 优缺点

**flatten 的优点**
- 更少表、更少类、更少概念；claim 机制成为唯一主角，面试更好讲。
- `statement_jobs` 与 `delay_jobs` 结构同形，便于“其他 domain 对齐成这个样子”。
- 去掉了 batch 完成度的 re-lock + summary 这类“看起来企业级但价值有限”的机制。

**flatten 的代价（trade-off）**
- 失去一行就能看到的“整批状态”视图；要看进度得 `GROUP BY status` 查询。
- 失去 `PARTIALLY_FAILED` 这种批级语义（现在是“按 cycle 数 DEAD 分片”）。

> 取舍结论：对“学习 + 面试 + 不过度包装”的目标，单层更合适；
> 真正需要批级 SLA / 看板时，再加一个**读模型查询**或轻量 batch 视图即可，
> 不必为此长期维护一张父表和它的生命周期。

---

## 5. 与其他领域 job 的对比（delayjob / outbox）

项目里其实有两“家族”的 claimable job：

### 家族 A：`delayjob` / `outbox`（4 类拆分 + 单列 lease）

- 拆成 4 个类：`Poller`（定时 + 派发）、`Claimer`（短事务 claim）、
  `Worker`（执行 + finalize + lease token 校验）、`Recoverer`（恢复超时）。
- **单列 lease 模型**：用 `next_attempt_at` 一列同时表达
  “最早可执行时间（backoff）”和 “PROCESSING lease 到期时间”。
- **有指数退避（exponential backoff，带上限）**：失败回 PENDING 时
  `next_attempt_at = now + min(2^n, cap)`。
- `delayjob` 是**泛型**的：`jobType + aggregateType + aggregateId`，
  `Worker` 用 `EnumMap<DelayJobType, Handler>` 分发；用于单聚合的未来动作
  （authorization expiry、auto-repayment）。
- `outbox` 同形，但业务是“可靠发消息到 Kafka”，状态机
  `PENDING/PROCESSING/PUBLISHED/DEAD`。

> 注意（更正一个常见误解）：`delayjob` / `outbox` **也有** lease token 校验
> （`Worker.lockCurrentLease` 比较随机 `lease_token`）。
> 区别不在“有没有 lease 校验”，而在下面这些点。

### 家族 B：`statement` job（1 类 dispatcher + 显式 owner token + fan-out）

- 一个 `StatementJobDispatcher` 合并了 poll / claim / 派发 / finalize / recover，
  比 4 类拆分更紧凑、更易读。
- **显式 claim metadata + owner token**：`claimed_by / claimed_at / claim_until`
  表达“谁持有、何时持有、租约何时到期”，`claim_token` 表达“这次 claim 的真正 owner”。
  finalize 比较 token，而不是比较 timestamp。
- **没有 backoff**：失败回 PENDING 立即可再 claim，靠 `max_attempts` 兜底
  （见第 6 节的取舍说明）。
- **fan-out / sharding + 单项故障隔离 + 分片计数**：这是 batch 出账特有的，
  delayjob/outbox 没有。

### 5.1 对比表

| 维度 | delayjob / outbox（家族 A） | statement job（家族 B，新） |
| --- | --- | --- |
| 调度类数量 | 4（Poller/Claimer/Worker/Recoverer） | 1（Dispatcher） |
| lease 列 | `next_attempt_at` + `lease_token` | `claimed_by/at/until` + `claim_token` |
| lease token 校验 | 有（比较随机 `lease_token`） | 有（比较随机 `claim_token`） |
| 重试 backoff | 有（指数退避 + 上限） | 无（立即可重试，max_attempts 兜底） |
| 一个 job 干什么 | 一个聚合的未来动作 / 一条消息 | 一个分片（上千账户）的 fan-out |
| 故障粒度 | 整个 job 成功/失败 | 单账户隔离，分片级计数 |
| 适用 | 单聚合延迟动作、可靠投递 | 大规模批量出账 |

### 5.2 各自优缺点

**家族 A（4 类 + 单列）**
- 优点：单列 lease 极简；backoff 现成；泛型 + handler map 扩展新 jobType 方便。
- 缺点：4 个类对“一个队列”来说偏碎；`next_attempt_at` 一列身兼两职，
  含义需要解释（既是“下次重试时间”又是“lease 截止时间”）。

**家族 B（1 类 + 双列 + fan-out）**
- 优点：一个 dispatcher 读完即懂；`claimed_by/at/until` 排障时一眼看清归属；
  原生支持 sharding 和单项故障隔离。
- 缺点：双列 lease 比单列多一列；当前没有 backoff，坏分片会在
  ~10 次内快速打到 DEAD（对偶发故障稍欠温柔）。

---

## 6. 关键取舍（trade-offs）

1. **不引入 backoff（当前）**：statement job 失败立即可重试。
   原因：本次目标是“简化”，且分片级失败较少（单账户错误已在 handler 内隔离计数）。
   *如果* 要把它纳入 reference 形状，加一列 `next_attempt_at` 并把 claim 查询
   改成 `status='PENDING' AND next_attempt_at <= now` 即可，约十行改动。
2. **不保留 batch 状态表**：用查询回答“整批是否完成”。
   原因：避免为低频的批级看板长期维护一张父表 + 生命周期。
3. **显式 owner token 而非 timestamp token**：旧设计用 `claim_until` 兼任 lease deadline
   和 finalize token。新设计保留 `claimed_by/at/until` 的 observability，但新增
   `claim_token` 作为随机 owner token。这样 `claim_until` 只负责 recover 判断，
   不再承担“证明这次 PROCESSING 属于我”的职责。

### 6.1 改前 / 改后：为什么加 `claim_token`

| 维度 | 改前 | 改后 |
| --- | --- | --- |
| lease deadline | `claim_until` | `claim_until` |
| finalize owner check | 比较 `claim_until` | 比较随机 `claim_token` |
| 可观测性 | `claimed_by / claimed_at / claim_until` | 保持不变，并多一个 token 排查字段 |
| 主要风险 | Java `Instant` 与 MySQL `TIMESTAMP(6)` 精度不一致时，可能误判 lease changed；同一 worker 过期后重领也不能只靠 worker id 区分 | token 每次 claim 重新生成，不依赖 timestamp 精度，也不依赖 worker id |

反事实：如果没有 `claim_token`，一个慢 worker 可能在 lease 过期后才回来 finalize。
旧代码只能靠 `claim_until` 判断它是不是当前 owner；这会把“租约到期时间”和“owner 身份”
混在一起。现在 `claim_until` 只回答“什么时候可 recover”，`claim_token` 才回答
“当前 finalize 是否仍属于这次 claim”。

---

## 7. 如何把其他 domain 对齐成这个形状

以后想把 `delayjob` 对齐成 statement 的 reference 形状，建议：

1. **合并 4 类为 1 个 dispatcher**：把 `Poller/Claimer/Worker/Recoverer` 合成一个
   `DelayJobDispatcher`（poll / claim / 派发 / finalize / recover），保留
   `EnumMap` handler 分发。
2. **决定 lease 列模型**：要么保留单列 `next_attempt_at`（简，但身兼两职），
   要么升级成 `claimed_by/at/until + claim_token`（observability 强，owner check 更稳）。
   两者择一并统一。
3. **保留各自不可合并的差异**：delayjob 的 backoff、泛型 handler；
   statement 的 sharding、单项隔离。**不要**为了“统一”强行抽象成一个泛型框架——
   它们用例不同（单聚合动作 vs 批量 fan-out），共享的是模式，不是同一段代码。

> 面试一句话：「三套队列共享 claim-lease-recover 模式，但生命周期和失败语义不同，
> 我保持它们各自独立、形状对齐，而不是过早抽象成一个通用 job 框架。」

---

## 8. 面试讲解要点

- **claim/lease/recover**：为什么 `FOR UPDATE SKIP LOCKED` + 短事务 claim 能让多实例
  安全并行；为什么 PROCESSING 必须是 lease；lease token 校验防的是哪种 race。
- **flatten 的判断**：什么时候两层（batch+jobs）是必要的，什么时候是过度包装；
  我基于“可被其他 domain 对齐 + 不过度包装”选了单层。
- **sharding**：百万账户怎么拆分片、为什么单账户单事务、为什么单项故障要隔离。
- **idempotency**：分片创建、单账户出账、交易归账三层各自的幂等键。
- **诚实的取舍**：当前没有 backoff、没有批级状态表，分别用什么代价换了什么简洁。
