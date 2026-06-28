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
| [`events-outbox-inbox-kafka-cn.md`](events-outbox-inbox-kafka-cn.md) | ✅ | **已合并** `kafka-outbox-design` + `kafka-learning` + `event-outbox-messaging-design`；对齐代码（3 个 gap 仍存在、新增 StatementNotificationListener），保留 Kafka 配置参考 + 实现走读 + 11 道硬核 Q&A；3 份原文档已归档 |
| [`claimable-jobs-cn.md`](claimable-jobs-cn.md) | ✅ | **已合并** `async-workflows-comparison` + `claimable-job-families` + `statement-job-design`；**改正** async-workflows 把 statement 批处理当"非 claimable job"的过时描述（已扁平化），对齐当前 `StatementJobDispatcher`/`BillingCycleScheduler` 等类名；3 份原文档已归档 |
| [`notification-delivery-design-cn.md`](notification-delivery-design-cn.md) | ✅ | 已核对完全对齐代码（`max-attempts=8`、`processing-timeout=30s`、per-channel 断路器、迁移 0006/0007 一致）；**保留独立不归档**，仅修正了它对已归档消息/job 文档的引用 |

### 4. 数据（Data：MyBatis / SQL / Migration）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| [`mybatis-sql-and-migration-cn.md`](mybatis-sql-and-migration-cn.md) | ✅ | **已合并** `mybatis-sql-learning` + `database-migration-liquibase`；迁移文件列表对齐到 **0001–0007**（旧文档只列到 0003），保留全部 SQL 例子与面试话术 |
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
| `high-traffic-system-design-cn.md` + `production-runtime-sizing-cn.md` | ⏳ | 拟合并为一份"高流量 + 容量配置"文档 |
| `aws-ecs-deployment-cn.md` | ⏳ | 保留，对齐现状、裁掉与高流量/容量重复的部分 |

### 8. 面试（Interview）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| `paypay-card-jd-fit-cn.md` + `paypay-card-backend-interview-guide-cn.md` | ⏳ | 拟重度去重，合并为一份更精炼的"JD 对照 + 题库"（保留有价值的深挖 Q&A） |
| [`paypay-card-jd-alignment-review-cn.md`](paypay-card-jd-alignment-review-cn.md) | ✅ | 独立评审（已对齐最新代码），保留 |

### 9. 参考（Reference）

| 文档 | 状态 | 说明 |
| --- | --- | --- |
| `trilingual-glossary-cn.md` | ⏳ | 中英日术语表，保留 |

---

## 归档（Archive）

[`docs/archive/`](archive/) 保存被合并掉的原文档，内容保持原样，仅供回溯。当前已归档：

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
- `credit-card-lifecycle-cn.md` → 合并进 `credit-card-domain-cn.md`
- `ToDo.md` → 合并进 `credit-card-domain-cn.md`（剩余领域路线图）
- `authorization-design.md` → 合并进 `implementation-walkthrough-cn.md`（§14 设计决策）
