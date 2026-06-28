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
| `credit-card-lifecycle-cn.md` | ⏳ | 发卡方刷卡到还款全流程概念（拟并入 `ToDo.md` 的 remaining-domain 路线图） |
| `domain-state-flow-cn.md` | ⏳ | 授权→还款的状态流转与锁规则（深度，逐请求示例） |
| `ToDo.md` | ⏳ | 剩余领域学习路线图（ledger/reconciliation/reversal…），拟并入业务领域文档 |

### 2. 项目实现（Implementation）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| `implementation-walkthrough-cn.md` | ⏳ | request-to-table 核心流程走读（拟并入 `authorization-design.md`） |
| `authorization-design.md` | ⏳ | 授权聚合/事务/幂等设计（英文），拟并入 walkthrough |

### 3. 消息与异步（Messaging & Async）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| `kafka-outbox-design.md` + `kafka-learning-cn.md` + `event-outbox-messaging-design-claude-cn.md` | ⏳ | 拟合并为一份"事件 / Outbox / Inbox / Kafka"文档 |
| `async-workflows-comparison-cn.md` + `claimable-job-families-comparison-claude-cn.md` + `statement-job-design-cn.md` | ⏳ | 拟合并为一份"claimable job（DelayJob/Outbox/StatementJob）"文档 |
| `notification-delivery-design-cn.md` | ⏳ | 通知真实投递（保留独立，对齐代码） |

### 4. 数据（Data：MyBatis / SQL / Migration）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| `mybatis-sql-learning-cn.md` + `database-migration-liquibase-cn.md` | ⏳ | 拟合并为一份数据/SQL 文档 |
| `db-schema-sync-2026-06-21-cn.md` | ⏳ | 一次性本地 schema 同步记录，拟直接归档 |

### 5. 缓存与限流（Caching & Rate Limiting）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| [`caching-and-rate-limiting-cn.md`](caching-and-rate-limiting-cn.md) | ✅ | **已合并** `cache-snapshot-design` + `cache-invalidation-broadcast` + `distributed-cache`；改正"跨 pod 重建锁未做"过时说法、`v1`→`v2`；3 份原文档已归档 |

### 6. 框架与语言（Spring / Java）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| `spring-java-technical-learning-cn.md` | ⏳ | 保留，但裁掉已被 cache/kafka/mybatis 文档覆盖的重复部分 |

### 7. 运行时与运维（Runtime & Ops）

| 文档 | 状态 | 说明 / 合并来源 |
| --- | --- | --- |
| `jvm-monitoring-learning-cn.md` + `thread-runtime-learning-cn.md` | ⏳ | 拟合并为一份"JVM + 线程模型 + 排查"文档 |
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
