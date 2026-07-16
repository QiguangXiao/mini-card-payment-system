# 文档索引（Docs Index）

这份索引是文档重新整理后的**分类总入口**。目标：把原来 28 份、相互重复的文档，
合并成按主题分类的少数几份，**删除重复 / 低价值 / 面试一般不涉及的内容**，
但**保留概念讲解、举例和反向事实（counterfactual）解释**，并对齐最新代码实现。

> 重整进行中（分步做）。已合并的标 ✅；尚未处理的原文档暂时仍以扁平方式留在 `docs/` 根下，
> 标 ⏳。每完成一类，就把对应原文档移入 [`docs/archive/`](archive/) 归档（不删除），
> 并在这里更新状态。

## 约定

- **新合并文档**：放在 `docs/` 根下，命名直白（如 `caching-and-rate-limiting-cn.md`）。
- **归档原文档**：移入 `docs/archive/`，内容保持原样不改，方便回溯。
- 选择"扁平文件 + 本索引分类"而不是物理子文件夹，是为了减少跨文档链接的路径维护成本；
  分类由本索引承担。如需改成子文件夹，是一次低成本的后续调整。

---

## 分类总览

### 1. 业务领域（Business / Domain）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| [`credit-card-domain-cn.md`](credit-card-domain-cn.md) | ✅ | **已合并** `credit-card-lifecycle` + `ToDo` 路线图；对齐 statement claimable job 类名（`BillingCycleScheduler`/`StatementCycleService`/`StatementJobHandler`/`StatementGenerationService`） |
| [`domain-state-flow-cn.md`](domain-state-flow-cn.md) | ✅ | **保留不归档**；已把 statement 批处理类名对齐到 claimable job（`BillingCycleScheduler`/`StatementCycleService`/`StatementJobDispatcher`/`StatementGenerationService`），账户级出账锁逻辑未变 |

### 2. 项目实现（Implementation）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| [`implementation-walkthrough-cn.md`](implementation-walkthrough-cn.md) | ✅ | **保留**主走读；对齐 statement 到 claimable job、§7/§8 加机制文档指针、裁 §10/§12 重复、文末融入 `authorization-design` 设计决策（§14）；并修正 `StatementService`/`BankDebitStatus` 等过时类名 |

### 3. 消息与异步（Messaging & Async）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| [`events-outbox-inbox-kafka-cn.md`](events-outbox-inbox-kafka-cn.md) | ✅ | **已合并** `kafka-outbox-design` + `kafka-learning` + `event-outbox-messaging-design`；对齐当前 Authorization/CardTransaction 两条事件路径与 Notification consumer，保留 Kafka 配置参考 + 实现走读 + 11 道硬核 Q&A；3 份原文档已归档 |
| [`claimable-jobs-cn.md`](claimable-jobs-cn.md) | ✅ | **已合并** `async-workflows-comparison` + `claimable-job-families` + `statement-job-design`；**改正** async-workflows 把 statement 批处理当"非 claimable job"的过时描述（已扁平化），对齐当前 `StatementJobDispatcher`/`BillingCycleScheduler` 等类名；3 份原文档已归档 |
| [`notification-delivery-design-cn.md`](notification-delivery-design-cn.md) | ✅ | 已核对完全对齐代码（`max-attempts=8`、`processing-timeout=30s`、per-channel 断路器、`lease_token` 模型）；**保留独立不归档**，仅修正了它对已归档消息/job 文档的引用 |

### 4. 数据（Data：MyBatis / SQL / Migration）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| [`mybatis-sql-and-migration-cn.md`](mybatis-sql-and-migration-cn.md) | ✅ | **已合并** `mybatis-sql-learning` + `database-migration-liquibase`；对齐当前 active changelog `0001–0007`，保留 SQL 例子与 interview 话术 |
| `db-schema-sync-2026-06-21-cn.md` | ✅ 归档 | 一次性本地 schema drift 修复日志，**直接归档**不并入 |

### 5. 缓存与限流（Caching & Rate Limiting）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| [`caching-and-rate-limiting-cn.md`](caching-and-rate-limiting-cn.md) | ✅ | **已合并** `cache-snapshot-design` + `cache-invalidation-broadcast` + `distributed-cache`；改正"跨 pod 重建锁未做"过时说法、`v1`→`v2`；3 份原文档已归档 |

### 6. 框架与语言（Spring / Java）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| [`spring-java-technical-learning-cn.md`](spring-java-technical-learning-cn.md) | ✅ | **保留不归档**；§7/§8/§9 裁成指向新 数据/消息/缓存 文档的精简指针节（2702→2259 行），保留 Spring/库集成的独特小点；修正 §15 过时引用（归档 cache 文档、已删除的 `StatementBatchService`/`StatementService`）|

### 7. 运行时与运维（Runtime & Ops）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| [`jvm-threads-runtime-cn.md`](jvm-threads-runtime-cn.md) | ✅ | **已合并** `jvm-monitoring-learning` + `thread-runtime-learning`；去掉两份重复的本地快照/线程状态/Runbook/Q&A（2307→~360 行），保留全部知识点+反向事实；对齐 `statement-batch`→`billing-cycle`+`statement-job` 线程池 |
| [`traffic-rate-limiting-and-capacity-cn.md`](traffic-rate-limiting-and-capacity-cn.md) | ✅ | **已合并并重写** `high-traffic-system-design` + `production-runtime-sizing`；按当前代码列出 API/Kafka/worker/provider 的生产与消费速率、RateLimiter/Semaphore/CircuitBreaker、主动限制与被动瓶颈、容量公式和压测路径；两份原文档已归档 |
| `aws-ecs-deployment-cn.md` | ⏳ | 保留，对齐现状、裁掉与高流量/容量重复的部分 |

### 8. 面试（Interview）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| [`interview-qa-bank-cn.md`](interview-qa-bank-cn.md) | ✅ | **问答总库（2026-07-16 重组）**：收拢原 jd-fit 的回答模板（Q1–Q7）、深挖题库（Q8–Q80）、系统设计题、排障题、red flag 回答、追问速查，以及原 guide 的速记二十问（速Q1–速Q20，逐题标注对应深挖题，重复问答以"速记 + 深挖"两档并存） |
| [`paypay-card-jd-fit-cn.md`](paypay-card-jd-fit-cn.md) | ✅ | 瘦身为纯"JD 对照与证据"（JD 映射 + 项目锚点 + gap + 面试官视角，5564→781 行）；问答迁至问答总库，distributed lock 专题独立，复习流程迁至 guide |
| [`paypay-card-backend-interview-guide-cn.md`](paypay-card-backend-interview-guide-cn.md) | ✅ | 冲刺手册：主链路速记 + 九个高频专题 + 自测清单 + 复习路线/最后一周计划/最后一轮顺序/压缩素材/反向提问（后四者自 jd-fit 迁入）；顺带修正了迁入段落里指向已归档文档的旧引用 |
| [`paypay-card-jd-alignment-review-cn.md`](paypay-card-jd-alignment-review-cn.md) | ✅ | 独立评审（已对齐最新代码），保留；§8 三次复盘（2026-07-12）：复习边界（收窄/删除/略读/学透四档）+ 面试数字清单（12 个锚点数字与"只记公式"规则） |
| [`interview-readiness-review-cn.md`](interview-readiness-review-cn.md) | ✅ | 面试就绪评审（2026-07-02）：优秀设计证据、JD 对齐评分 78%、展示排序、题库答题要点（含 Q13 missed-cron）、三个缺陷时序推演（missed-cron 已按 reconciliation 修复，见 §6.9）、过度工程清单、ROI 改进计划，**文末附 15 条英文背诵材料** |

### 9. 参考（Reference）

| 文档 | 状态 | 说明 |
| --- | --- | --- |
| `trilingual-glossary-cn.md` | ⏳ | 中英日术语表，保留 |

### 10. 独立专题（Standalone Topics）

| 文档 | 状态 | 说明 |
| --- | --- | --- |
| [`distributed-lock-cn.md`](distributed-lock-cn.md) | ✅ | 原 jd-fit §33 独立成篇：distributed lock 是什么、生产四种做法、10 个必答问题、典型事故；money path 为什么用 DB row lock / DB lease，以及项目里唯一一把 best-effort Redis 锁（statement cache 重建 single-flight）为什么可以例外 |

---

## 归档（Archive）

[`docs/archive/`](archive/) 保存被合并掉的原文档，仅供回溯。
2026-07 收缩重构删除了部分文中描述的组件（ledger / historical risk projection、
`statement.closed` 与 `repayment.received` Kafka 路径等）；受影响的归档文档已清理
对应段落，并在 H1 下方加"归档对齐说明"指向现行文档，其余内容保持原样。当前已归档：

- `cache-snapshot-design-cn.md` → 合并进 `caching-and-rate-limiting-cn.md`
- `cache-invalidation-broadcast-cn.md` → 合并进 `caching-and-rate-limiting-cn.md`
- `distributed-cache-cn.md` → 合并进 `caching-and-rate-limiting-cn.md`
- `kafka-outbox-design.md` → 合并进 `events-outbox-inbox-kafka-cn.md`
- `kafka-learning-cn.md` → 合并进 `events-outbox-inbox-kafka-cn.md`
- `event-outbox-messaging-design-claude-cn.md` → 合并进 `events-outbox-inbox-kafka-cn.md`
- `async-workflows-comparison-cn.md` → 合并进 `claimable-jobs-cn.md`
- `claimable-job-families-comparison-claude-cn.md` → 合并进 `claimable-jobs-cn.md`
- `statement-job-design-cn.md` → 合并进 `claimable-jobs-cn.md`
- `mybatis-sql-learning-cn.md` → 合并进 `mybatis-sql-and-migration-cn.md`
- `database-migration-liquibase-cn.md` → 合并进 `mybatis-sql-and-migration-cn.md`
- `db-schema-sync-2026-06-21-cn.md` → 一次性日志，直接归档（未并入）
- `jvm-monitoring-learning-cn.md` → 合并进 `jvm-threads-runtime-cn.md`
- `thread-runtime-learning-cn.md` → 合并进 `jvm-threads-runtime-cn.md`
- `high-traffic-system-design-cn.md` → 合并进 `traffic-rate-limiting-and-capacity-cn.md`
- `production-runtime-sizing-cn.md` → 合并进 `traffic-rate-limiting-and-capacity-cn.md`
- `credit-card-lifecycle-cn.md` → 合并进 `credit-card-domain-cn.md`
- `ToDo.md` → 合并进 `credit-card-domain-cn.md`（剩余领域路线图）
- `authorization-design.md` → 合并进 `implementation-walkthrough-cn.md`（§14 设计决策）
