# 面试就绪评审与英文背诵材料（Interview Readiness Review & English Recitation Pack）

> 评审视角：PayPay Card 类发卡行后端团队的 Staff Backend Engineer / 面试官。
> 评审基准：**不按生产支付系统评判**，按"5 年 Java 后端经验、缺大规模支付生产经验的候选人，
> 用来证明 issuer 侧设计能力的严肃学习/面试项目"评判。
> JD 依据：PayPay Card Backend Engineer（Greenhouse 4003023008）。
> 评审日期：2026-07-02（代码基线：`e7b5e73`）。

---

## 目录

- [§1 值得主动展示的优秀设计（带代码证据）](#1-值得主动展示的优秀设计带代码证据)
- [§2 与 JD 要求的逐条对齐](#2-与-jd-要求的逐条对齐)
- [§3 对齐评分：78 / 100 及推理](#3-对齐评分78--100-及推理)
- [§4 面试展示价值排序与展示策略](#4-面试展示价值排序与展示策略)
- [§5 最可能触发的面试问题与答题要点](#5-最可能触发的面试问题与答题要点)
- [§6 最大技术风险 / 弱点（含缺陷时序推演）](#6-最大技术风险--弱点含缺陷时序推演)
- [§7 过度工程与可解释性风险](#7-过度工程与可解释性风险)
- [§8 面试前最高 ROI 改进计划](#8-面试前最高-roi-改进计划)
- [§9 英文背诵材料（English Recitation Pack）](#9-英文背诵材料english-recitation-pack)

---

## 1. 值得主动展示的优秀设计（带代码证据）

以下每一条都经过通读验证，经得起面试官顺着代码追问。

### 1.1 Claim-first 幂等（全项目最硬的资产）

授权、还款、presentment 三条写路径统一采用同一模式：

1. **先 INSERT 抢占**：以幂等键（`Idempotency-Key` header / `networkTransactionId` / `auto-debit:{statementId}`）
   为唯一索引做 INSERT-first claim，并发重试只有一个 winner
   （`AuthorizationService.authorize` → `authorizationRepository.claim(...)`）。
2. **loser 阻塞读 winner**：`findByIdempotencyKeyForUpdate` 上的 `FOR UPDATE`
   会等 winner 事务提交后读到**最终状态**，而不是误报处理中。
3. **fingerprint 冲突检测**：同一个幂等键携带不同请求体（不同金额/卡号）时抛
   `IdempotencyConflictException` → 409，防止"新交易被错误当成旧交易结果返回"。

为什么这比常见的"先查再插"强：read-then-insert 在两个并发请求都查不到时会**双双插入/双双预占额度**；
claim-first 把竞态裁决完全交给数据库唯一索引，没有窗口。

### 1.2 全项目统一锁顺序（推理出来的，不是碰巧对的）

| 用例 | 锁顺序 |
| --- | --- |
| 授权（`AuthorizationService`） | authorization claim → card(无锁读) → **account FOR UPDATE** |
| 入账（`PostingService`） | **authorization FOR UPDATE** → **account FOR UPDATE** → transaction claim |
| 过期（`AuthorizationExpiryService`） | **authorization FOR UPDATE** → **account FOR UPDATE** |
| 还款（`RepaymentService`） | repayment claim → **account FOR UPDATE** → **statement FOR UPDATE** |
| 出账（`StatementGenerationService`） | **account FOR UPDATE** → **candidate transactions FOR UPDATE** |

关键证据：`PostingService.post` 里注释明确写出"新 presentment 必须在 credit account row lock
**之后**再 INSERT claim，否则 posting（先锁交易再锁账户）和 statement（先锁账户再锁交易）
高并发时形成环路等待"。这说明锁顺序是设计约束，不是巧合——面试官用这个区分
"背过死锁定义"和"推理过死锁"。

### 1.3 可认领任务框架（DelayJob / Outbox / StatementJob 三族同构）

统一的 claim–work–finalize–recover 生命周期：

- **短事务 claim**：`FOR UPDATE SKIP LOCKED` 批量领取 due rows，改成 PROCESSING + 新 `lease_token`
  + lease deadline，提交。多实例并发安全，锁持有时间与外部调用解耦。
- **事务外干活**：Kafka publish / 银行扣款 / 逐账户出账都不占 job row lock。
- **finalize 前重锁校验 lease**：三条件（status 仍为 PROCESSING、快照带 token、token 与 DB 相等）
  才允许写终态。`next_attempt_at` 只是 deadline，不是 owner 身份——TIMESTAMP 精度和重领都会变，
  UUID token 才能挡住 stale worker 覆盖新 worker 的结果。
- **recoverer 独立路径**：单独扫描 lease 超时的 PROCESSING rows 放回 retry / DEAD，
  正常路径与宕机恢复路径边界清晰。
- **失败即落库**：attempts / nextAttemptAt / lastError 持久化，指数退避，超过 maxAttempts 进 DEAD，
  poison 任务不会无限打业务系统。

### 1.4 Outbox → Kafka → Inbox 的 at-least-once 闭环

- Outbox row 与业务状态**同事务提交**（domain event 由聚合在状态转换处产生，service 只负责 append）。
- producer 侧：`acks=all` + `enable.idempotence=true`；publish 等 broker ack 后才 markPublished。
- "ack 后、markPublished 前崩溃" → 事件重发 → **consumer 端双重去重**：
  Inbox claim（`consumer_inbox` 复合主键）+ 业务唯一键（如 `notifications.source_event_id`）。
- DLT 按失败的 consumer group 路由（当前仅 Notification 一个消费方）；未来新下游用自己的 group + DLT，互不影响。

### 1.5 Schema 与领域不变量互为防线

`0001-initial-schema.sql` 把状态机的合法字段组合写进 CHECK 约束（例如 authorizations 五状态各自的
decline_reason/decided_at/expires_at/posted_at/expired_at 组合、`reserved + posted <= credit_limit`、
statement 的 paid/status 一致性）。同时 domain `restore()` 也跑同一套 validation。
面试里"脏数据怎么防"有两层答案：应用层不变量 + 数据库最后防线。

### 1.6 Money 值对象与日本本地化细节

- `BigDecimal + Currency`，按 ISO 4217 法定小数位 `setScale(fractionDigits, RoundingMode.UNNECESSARY)`：
  尾零安全降级（DB 的 `100000.00` → JPY 100000）通过，真丢精度（`1234.50` JPY）fail-fast。
- 最低还款取整 `CEILING` 且 scale 由币种决定，避免对 JPY 算出"分以下日元"。
- 账期按 **JST**（`Asia/Tokyo`）切割而不是 UTC；due date 按日本营业日历顺延
  （`JapaneseBusinessDayCalendar`）。对日本发卡行是显性加分项。

### 1.7 韧性配置有推导过程

- 外部风控：Feign 读超时 800ms + **semaphore bulkhead=4 刻意小于 Hikari 池** + 断路器，
  供应商 brownout 时最多 4 条授权事务占连接，其他端点仍有余量。
- 通知渠道：每渠道独立断路器 + slow-call 阈值，硬超时由真实 HTTP client/SDK 配置，"又慢又错"的 provider 被快速隔离。
- 配置注释写明了每个数字的推导逻辑（如 lease timeout 必须 > 一次 provider 调用 + finalize 耗时）。

### 1.8 分层与测试基础

- 聚合封装状态转换（`approve/decline/expire/post` 私有化字段变更），controller 只做 HTTP adapter，
  repository 接口在 domain、实现在 infrastructure，MyBatis row record 做防腐。
- 已有三个高价值 IT：`AuthorizationConcurrencyIT`（并发授权不超额预占）、
  `DelayJobSkipLockedClaimIT`、`OutboxKafkaInboxRoundtripIT`；单测覆盖面广。

---

## 2. 与 JD 要求的逐条对齐

| JD 要求 | 项目覆盖 | 评价 |
| --- | --- | --- |
| Concurrency & distributed computing（required） | 幂等、行锁、锁顺序、lease、竞态分析 | **最强项**，深度足够硬核面试 |
| RDBMS + NoSQL + distributed cache（required） | MySQL 深；Redis 用于 velocity 滑窗 + L2 缓存 + 重建锁；Caffeine L1 | 强；"NoSQL"实际只有 Redis，不要自称有 document/KV store 经验 |
| APIs / Pub/Sub / database clients（required） | REST + Kafka + MyBatis（含一处 JdbcTemplate 对照） | 覆盖良好 |
| Microservices & event-driven（required） | 事件驱动很实；但**这是模块化单体** | 事件驱动强；微服务只有"可拆分的边界"，需口头补拆分与跨服务一致性 |
| High-traffic system design（required） | 分片批处理、两级缓存、bulkhead、SKIP LOCKED | 有设计、**无数字**（没有压测/容量证据、没有热点行落地方案） |
| Data structures / algorithms / OOP（required） | CRC32 分片、EnumMap dispatch、值对象、聚合 | 达标，但这项主要靠 coding 面 |
| DDD（preferred） | 聚合不变量、值对象、bounded context、防腐层 | 强 |
| Java / Spring Boot（preferred） | Java 21 / SB3（JD 是 11/17 混合 + 遗留 Java EE） | 强；备一句"为什么用 21、迁 17 有什么差异" |
| gRPC（preferred） | **无** | 缺口，口头补 |
| Kafka（preferred） | 幂等 producer、手动 offset、record ack、DLT | 强 |
| JUnit / Mockito | 单测面广，IT 仅 3 个 | 中；关键并发主张缺 IT 佐证 |
| AWS ECS / CloudFormation / CloudWatch | 代码中无，`docs/aws-ecs-deployment-cn.md` 有笔记 | 缺口，靠口头 + 文档 |

---

## 3. 对齐评分：78 / 100 及推理

不是拍脑袋，按权重拆解：

- **核心必选项（约占 60% 权重）得分 ~90%**：并发/幂等/事务边界/事件驱动/MySQL+Redis
  是这份 JD required 的主体，项目在这些点上的深度**超过 5 年经验候选人的常见水平**。
- **高流量设计（~15% 权重）约 50%**：有正确的结构（分片、缓存、隔舱、SKIP LOCKED），
  但没有负载数字、没有瓶颈定位、没有"单账户热点行"的落地方案。JD 写的是
  *experience designing high-traffic systems*，面试官会追流量数字，项目本身给不了。
- **微服务（~10% 权重）约 60%**：bounded context 划分真实，但进程内跨聚合事务
  （`PostingService` 同事务改 authorization + account + transaction）与跨服务的差距，
  必须靠口头把"如何拆、拆完一致性怎么办（saga/补偿）"讲圆。
- **Preferred 项（~15% 权重）**：DDD/Kafka/Spring 拿满；gRPC 与 AWS 实操为零。
- **扣分项**：仓库卫生（`bin/`、`build/`、`.idea/` 入库）；真实缺陷（自动扣款竞态，见 §6）
  如果被读代码的面试官先发现，会反噬"严谨"人设。（原第二缺陷 OVERDUE 不可达已通过删除该状态解决，见 §6.1。）

**78% 的含义**：项目足以支撑面试，但支撑不了"我做过大规模系统"的暗示。
最佳用法是证明"我对 issuer 域的正确性问题想得很深"，而不是伪装生产经验。
按 §8 完成前四项可提升到 ~85%，剩余缺口全部是可口头覆盖的类型。

---

## 4. 面试展示价值排序与展示策略

| 排名 | 模块 | 展示策略 |
| --- | --- | --- |
| 1 | **授权链路**（幂等 claim → 风控 → 行锁 → 额度预占 → outbox） | 旗舰。准备 5 分钟白板版本：一个事务讲完幂等、锁、不变量、事件、事务边界五个主题 |
| 2 | **可认领任务框架**（lease token、崩溃恢复、stale worker 防覆盖） | JD 并发题的天然素材；重点讲三条件 lease 校验和"为什么 deadline 不能当 owner 身份" |
| 3 | **Outbox→Kafka→Inbox 全链路** | 重点讲"ack 后崩溃"场景和 consumer 双重去重；主动说出 per-key 乱序的已知取舍 |
| 4 | **账单批处理**（CRC32 分片、JST 账期、周期级幂等、单账户小事务） | 讲"批处理也要有事务边界"：为什么不是一个大事务、失败怎么隔离 |
| 5 | **还款 + 自动扣款**（双聚合事务边界） | 修复 §6.2 后，这里是"我发现并修复了竞态"故事的素材 |
| 6 | **Schema 约束设计** | 被问 DB 时展开：状态机进 CHECK 的取舍（改状态机要发 migration） |
| 7 | **两级缓存** | **只在被问缓存时展开**；主叙事止步于 cache-aside + after-commit 失效，tombstone/CAS 留给追问 |
| 8 | **风控 velocity（Redis 滑窗 vs JDBC 对照）** | 一句话带过："JDBC 版是精确性对照基线" |

---

## 5. 最可能触发的面试问题与答题要点

按出现概率排序，前 6 个建议逐条彩排到能脱稿。

### Q1. "两个完全相同的授权请求同时到达，逐步走一遍会发生什么？"

要点：两边都先构造 PENDING 聚合 → 都执行 claim INSERT → 唯一索引裁决出 winner；
loser 的 INSERT 在 winner 提交前阻塞在 duplicate-key 锁上 → winner 提交后 loser claim 失败 →
loser `FOR UPDATE` 读到 winner 的**最终状态**（APPROVED/DECLINED）直接返回 →
全程只有一次额度预占。补充：fingerprint 校验挡住"同 key 不同请求体"。

### Q2. "为什么悲观锁不用乐观锁？单账户每秒 1000 笔授权怎么办？"

要点：额度预占是**必须串行**的读-改-写，冲突概率高时乐观锁重试风暴更糟；
FOR UPDATE 让冲突方排队而不是反复失败。热点行方案（项目里没有，必须口头准备）：
①额度分桶（把 limit 拆成 N 个子额度行，授权随机命中一桶，不足时再聚合）；
②预留池/本地配额（应用层批量预取额度，内存扣减 + 异步对账）；
③单写者模型（按 account 分区路由到单线程 + WAL）。要能比较三者的一致性代价。

### Q3. "Kafka ack 之后、markPublished 之前进程挂了会怎样？"

要点：outbox row 停在 PROCESSING → recoverer 在 lease 超时后放回 PENDING → 事件**重发** →
语义是 at-least-once → consumer 端 Inbox claim + 业务唯一键双重去重。
反问自己："为什么不用 Kafka 事务/XA？"——跨 MySQL 和 Kafka 没有廉价原子提交，
outbox 把问题收敛为"单库事务 + 可重放"。

### Q4. "同一聚合的两个事件会乱序吗？"

要点：**会**——claim 按 created_at 排序，但 4 线程 worker 池并发 publish，同 partition key
只保证分区内按发送序。当前 Notification consumer 必须以幂等和显式状态处理重复或乱序，不能依赖跨 topic 顺序。
若需要严格 per-key 有序：按 partition_key 哈希到单线程 worker，或单 publisher 串行发送，
代价是吞吐。**主动说出这一点**，别等面试官发现。

### Q5. "外部风控 HTTP 调用在 DB 事务里，供应商慢了会怎样？"

要点：承认这是取舍。缓解链：读超时 800ms 封顶 → semaphore bulkhead=4 < Hikari 池
（最多 4 条授权事务占连接）→ 断路器快速失败 → 失败映射为 decline 而不是 500。
"为什么不拆事务"：拆成 claim 事务 + 决策事务后，claim 成功但决策失败会留下已提交的
PENDING 行，需要额外的回收 job；mini 项目里单事务 + 并发上限是更诚实的选择。

### Q6. "这个单体怎么拆成微服务？拆完 PostingService 的跨聚合事务怎么办？"

要点：先按 bounded context 拆（authorization+credit account 是一个一致性核心，
statement/repayment 一组，notification 是事件消费者；risk 保留在授权同步决策链）。
拆开 authorization 与 account 后，posting 的"授权转 POSTED + 余额迁移"从本地事务变成
**saga**：预占确认 → 入账 → 失败补偿（重新释放 hold），幂等键贯穿每步。
诚实立场："现在是 modular monolith，事件边界已按拆分设计，但我没有伪装它是微服务。"

### Q7. "为什么轮询 outbox 而不用 Debezium/CDC？"

要点：CDC 优点（无轮询延迟、无应用侧发布代码）；轮询优点（无 binlog 基础设施、
retry/DEAD/观测都在应用内、教学可解释）。规模大了先加索引覆盖的 claim 查询 + 分区，
再考虑 CDC。

### Q8. "MySQL 撑不住了怎么分库分表？"

要点：按 `credit_account_id` 分片（授权/入账/还款/出账全部账户内闭环，锁和事务不跨片）；
幂等键唯一性从全局唯一索引降级为"分片内唯一 + 路由函数决定分片"；
账单批处理天然按账户分片，CRC32 shard 函数直接换成分片路由；
跨片的只有全局报表类查询 → 走读模型/数仓。

### Q9. "账单 job 全部重试失败后呢？谁会知道？"

要点（修复 §6.3 前的诚实答案）："进 DEAD 后没人知道，这是我已识别的 gap；
修复方式是 DEAD 计数 gauge + 告警 + runbook（DEAD 置回 PENDING 即可安全重放，
因为全链路幂等：statement 周期唯一键 + BILLED 标记 + line 唯一键）。"

### Q10. "JPY 没有小数，你怎么处理金额的？"

要点：`Money` 按币种法定小数位归一，`UNNECESSARY` 舍入模式让真丢精度 fail-fast；
DB 用 `DECIMAL(19,2)` 存，JPY 读出时尾零安全降级；最低还款用 CEILING 取整且 scale 由币种决定。

### Q11. "生产环境你会监控什么？"

要点（口头清单）：授权 P99/成功率/decline 原因分布；outbox lag（oldest PENDING age）
与 DEAD 计数；delay job 同项；MySQL 锁等待/慢查询；Hikari 池使用率；
Kafka consumer lag per group；断路器状态变化。项目里只有 actuator 基础项，承认并给清单。

### Q12.（若面试官读代码）"用户到期没还清会怎样？"

现在的诚实答案：当前模型刻意不建 OVERDUE——逾期状态需要 due-date 扫描 job 作为写入方才有意义，
没有写入方的状态只是死分支，所以删除了（见 §6.1）。如果要实现，方案是 `Statement.markOverdue`
+ `STATEMENT_OVERDUE_CHECK` delay job（dueDate + 1 营业日调度）+ `statement.overdue` outbox 事件。
"知道为什么不做"比留一个不可达状态更能体现判断力。

### Q13. "你的 daily cron 在触发时刻宕机了会怎样？Spring @Scheduled 有 misfire 补偿吗？"

要点：`@Scheduled` 没有 misfire 语义（对比 Quartz 的 JDBC JobStore + FIRE_ONCE_NOW）。
我的答案分两层：①系统里几乎所有定时工作都是 **level-triggered**——计划落库
（DelayJob/outbox row），poller 按 `next_attempt_at <= now` 扫表，宕机跨过触发点后
恢复即自愈；②唯一的 edge-triggered 定时器（账单周期规划的 daily cron）我在自查中
发现并改成了 **reconciliation**：每天对最近 N 个关账周期做 desired-state 对账，
缺哪期补哪期，幂等由 INSERT IGNORE + 唯一键兜底（完整经过见 §6.9）。
加分延伸：补偿机制自己也可能坏 → 独立的"已过关账日但无 jobs / 有 DEAD"告警兜底；
迟到补建的次生效应（dueDate 已过 → 立即扣款）要主动交底。

---

## 6. 最大技术风险 / 弱点（含缺陷时序推演）

### 6.1 `OVERDUE` 状态不可达 — 严重度 High（已解决：2026-07 删除该状态）

**解决方式**：删除而非实现。`StatementStatus.OVERDUE`、`Statement` 状态机的 OVERDUE 分支、
schema CHECK 中的 'OVERDUE'（changeset 0007）全部移除。理由：没有写入方的状态是状态机死分支，
与其留着被读代码的面试官发现"不可达"，不如删掉并把"为什么不做 + 要做怎么做"作为口头答案（见 Q12）。

**原始现状（存档）**：`StatementStatus.OVERDUE` 只被两处引用——`Statement.applyRepayment` 的
"OVERDUE 部分还款保持 OVERDUE"分支，以及 schema CHECK 约束。全代码库**没有任何路径**
把账单从 CLOSED/PARTIALLY_PAID 转为 OVERDUE：没有 `markOverdue()` 方法、没有到期检查 job、
自动扣款失败进 DEAD 后账单永远停在原状态。

**为什么致命**：这是信用卡域最核心的生命周期环节之一。面试官问"用户到期没还清会怎样"，
当前诚实答案是"什么都不发生"。状态机存在死状态还写进了 CHECK 约束，反而放大暴露面。

**最小修复**：`Statement.markOverdue(Instant)`（校验 dueDate 已过 + 状态合法）；
触发点二选一：自动扣款终局失败路径，或新增 `STATEMENT_OVERDUE_CHECK` delay job
（复用现有框架，dueDate + 1 营业日调度）；同时发 `statement.overdue` 事件走 outbox。

### 6.2 自动扣款"银行已出金、系统未入账"竞态 — 严重度 High

**完整时序推演**（`AutoRepaymentService.debitStatement`）：

```
T0  DelayJob 触发自动扣款，无锁读 statement：remaining = 10,000
T1  bankDebitGateway.debit(key="auto-debit:S1", amount=10,000) → 银行出金成功
T2  与此同时用户手动还款 8,000 提交成功 → remaining 变为 2,000
T3  repaymentService.receive(key="auto-debit:S1", amount=10,000)
      → claim 插入 PENDING → validateCanApply:
        10,000 > remaining(2,000) → RepaymentRejectedException
      → 整个事务回滚（PENDING claim 一并消失）
T4  DelayJob 按 retry policy 重试：
      重新读 statement：remaining = 2,000 → debitAmount = 2,000
      gateway.debit(同 key "auto-debit:S1", amount=2,000)
        → 真实银行网关按幂等键返回 T1 的结果（10,000 已扣）或直接拒绝金额不匹配
结局  银行侧客户被扣 10,000，系统侧要么入账 2,000、要么永远失败——
      资金与账务永久不平。模拟网关（simulated-success: true）掩盖了这一切。
```

**根因**：扣款金额在无锁快照上决定，而确定性幂等键假设"金额从不变化"。

**最小修复（两阶段占位）**：
1. 事务 A：`FOR UPDATE` 锁 statement → 固化本次扣款金额 → 插入 PENDING repayment
   （占用幂等键，金额已锁定）→ 提交；
2. 事务外：按 PENDING 记录的金额调银行扣款；
3. 事务 B：扣款成功 → finalize RECEIVED（复用现有锁顺序）；失败 → PENDING 标失败可重试。
   重试路径先查 PENDING 记录拿**已固化的金额**，杜绝漂移。

**修复后的面试价值**：这是"我在自查中发现了资金级竞态并修复"的最佳故事，
能部分对冲缺支付生产经验的背景短板。

### 6.3 账单批处理失败缺少运维闭环 — 严重度 Medium

StatementJob 重试耗尽后会进入 DEAD，但当前仍缺少 DEAD 计数告警和受控 replay 工具。
修复：DEAD 计数 gauge + 告警 + runbook（DEAD→PENDING 重放安全性论证见 §5-Q9）。

### 6.4 高流量主张缺证据 — 严重度 Medium（背景相关）

没有压测数字、没有热点行落地方案。这是候选人背景（enterprise 为主）与项目的共同短板，
只能靠系统设计口头能力补（§5-Q2/Q8 的准备就是补法）。

### 6.5 "微服务经验"名不副实的风险 — 严重度 Medium

若简历或开场把项目说成 microservices，跨服务一致性追问会立刻暴露。
正确话术：**"modular monolith with event-driven boundaries, designed for extraction."**

### 6.6 关键并发主张缺 IT 佐证 — 严重度 Medium

IT 只有 3 个。还款同 key 并发、posting-vs-expiry 竞态、presentment 重复请款这些
最想展示的性质没有测试。"我写了并发 IT 证明它"和"我推理过"是两个档次。

### 6.7 语言可达性 — 严重度 Medium（被低估）

全部注释和 docs 是中文（夹日文关键词）。PayPay Card 是英语/日语工作环境——
把仓库链接给面试官时，**他们读不了你最想展示的推理注释**。
修复：一份英文 `ARCHITECTURE.md` 一页纸（系统图 + 聚合清单 + 锁顺序表 + 三条写路径时序），
这是日方面试官唯一一定会读的东西。

### 6.8 仓库卫生 — 严重度 Low（但第一印象）

`bin/`、`build/`、`.idea/` 入库；`bin/main` 下有一套可能过期的 schema/mapper 副本，
评审者可能读错版本。`.gitignore` + `git rm -r --cached`，5 分钟修完。

### 6.9 【已修复】Daily cron 错过关账日 → 周期永不出账 — 原严重度 High

**发现经过（本身是面试素材）**：两轮 review 都没发现——它是**缺失机制型缺陷（absence bug）**，
逐行审查 `createDueJobs` 每行都对；直到讨论方法重载、对"错过触发时刻"做推演才暴露。
事后把全部定时入口按 **edge-triggered（错过即丢）vs level-triggered（扫表自愈）** 分类排查：
outbox/delayjob/notification/statement dispatcher 的轮询和"与业务同事务落库的 DelayJob 计划"
都自愈，`BillingCycleScheduler` 是全系统**唯一**的 edge-triggered 定时器。

**原缺陷时序**：

```
7/31 = 关账日
8/1 00:50  应用宕机；01:00 cron 该触发，没人在 → 错过
8/2 01:00  恢复后 cron 醒来：periodEnd = 8/1 不是 close date → 返回 0
结局       7 月周期的 statement_jobs 永远不会被创建——不是晚出账，是不出账，
           且比 DEAD job 更隐蔽：表里连一行可观测的尸体都没有
```

**修复（已实施）**：把 scheduler 从 edge-triggered 改成 **level-triggered reconciliation**：
每天醒来扫描最近 `reconciliation-lookback-cycles`（默认 2）个已过去的关账日，
用 `existsForCycle(periodStart, periodEnd)` 找缺失周期并补建。`existsForCycle` 命中
`uk_statement_jobs_cycle_shard` 最左前缀；它只是 fast path，多实例并发的最终幂等仍由
`INSERT IGNORE` + cycle/shard 唯一键兜底。`@Transactional` 保持在公共入口，
reconcile 为私有方法，无 self-invocation 问题；`createDueJobs(LocalDate)` 保留为
runbook 精确补跑入口。

**修复后残留取舍（要能主动说出）**：
- **迟到补建的 dueDate 可能已在过去**（极端：7/31 周期 8/28 才补建，dueDate=8/27 已过）
  → 自动扣款 delay job 立即执行，用户零通知期。完整修法是
  `dueDate = max(计算值, 补建日 + N 营业日)`；当前作为已接受行为。
- 全新环境首跑会回填 lookback 窗口内的历史周期（本项目无害；生产需"首个受管周期"配置）。
- 两 pod 并发补建且账户数恰好变化时，可能合并出混合 shard_count 的分片集——
  覆盖仍完备，重复扫描被单账户出账幂等吸收，但这依赖下游幂等。

### 6.10 其他中低风险（一句话备忘）

- `credit_accounts`/`cards` 缺 `created_at/updated_at`（全项目唯二没有审计字段的表，偏偏是钱最敏感的）；
  repository update 不校验 affected rows。
- `CreditAccount.validateState` 要求 `reserved + posted <= creditLimit`——降额后的合法超限账户
  `restore()` 直接抛异常，连还款（唯一能修复超限的操作）都执行不了。
  正确分层：超限只在 `reserve()` 拦**新增**占用，restore 只校验币种/非负。
- 领域 `IllegalStateException` 落到兜底 500；应补一个"合法请求撞上非法状态转换"→409 的显式映射。
- 无任何认证层（demo 合理，README 声明"假设在网关/零信任边界之后"即可）。

---

## 7. 过度工程与可解释性风险

原则：以下**功能上没错**，问题在于扩大被追问面、稀释核心叙事。45-60 分钟的面试里，
每分钟花在通知栈上就少一分钟讲授权。

| 部分 | 问题 | 建议 |
| --- | --- | --- |
| 通知投递栈（每渠道断路器 + slow-call 阈值 + lease 状态机 + 故障注入；硬超时交给 HTTP client） | 全项目最大子系统之一，但与 issuer 核心域无关 | 保留代码；面试降为一句话："通知走同一套可认领框架，附带渠道级熔断" |
| 缓存栈后 50%（tombstone 版本地板、rebuild 单飞锁、Pub/Sub 广播） | tombstone CAS 的正确性论证很难白板 3 分钟讲清，讲一半反而显得不稳 | 主叙事止步于 cache-aside + after-commit 失效 + 为什么不双写；其余等追问 |
| velocity 双实现（Redis + JDBC） | 学习价值高、面试价值低 | 一句"JDBC 版是精确性对照基线"带过 |
| 注释密度 | 学习工具没问题；但"每行都有注释"可能被读为不自信，且中文注释日方不可读 | 不删注释；用英文 ARCHITECTURE.md 承担对外叙事 |

---

## 8. 面试前最高 ROI 改进计划

| 优先级 | 改进 | 工作量 | 回报 |
| --- | --- | --- | --- |
| 1 | 仓库卫生（`.gitignore` 清 `bin/ build/ .idea/`）+ **英文 ARCHITECTURE.md 一页纸** | 半天 | 第一印象 + 日方可读性，ROI 最高 |
| 2 | ~~修 OVERDUE（§6.1）~~（已删除该状态解决）+ 自动扣款竞态（§6.2） | 半天~一天 | 消灭致命追问点，同时生产最佳面试故事 |
| 3 | 补 3 个并发 IT：还款同 key 并发 / posting-vs-expiry / presentment 重复请款 | 一天 | 把"我推理过"升级为"我有测试证明" |
| 4 | "100 倍流量"口头方案小抄（热点行、按账户分片、拆服务后的 saga） | 半天（纯准备） | 直接补 JD 的 high-traffic 与 microservices 两个缺口 |
| 5 | DEAD 可观测性（gauge + runbook 段落） | 2 小时 | "批处理静默失败"变成"有告警、可安全重放" |
| 6 | （可选）k6 压测一轮并记录数字 | 一天 | "我测过、瓶颈是 X"远胜于没有数字 |
| 7 | （不建议）为 checkbox 补 gRPC | — | 收益低于以上任何一项；口头准备"哪条内部调用适合 gRPC"即可 |

---

## 9. 英文背诵材料（English Recitation Pack）

> 用法：每条是**面试口语版**（第一人称、可直接说出口），控制在 45–90 秒。
> 前面的 One-liner 用于开场或被打断时的最短版本。建议顺序：先背 9.1 / 9.2 / 9.3 / 9.5 / 9.6
> （最高频），再背其余。

### 9.1 项目总述（Elevator Pitch）

**One-liner:** *It's a mini issuer-side credit card backend — authorization, posting, statements, and repayment — built to demonstrate correctness under concurrency: idempotency, row-level locking, transactional outbox, and recoverable background jobs.*

**Full version:**

> I built a mini credit-card issuer backend in Java 21 and Spring Boot 3, covering the full
> issuer-side lifecycle: card authorization with credit reservation, presentment posting,
> monthly statement generation as a sharded batch, and both manual and automatic repayment.
> The goal was never feature count — it was to get the hard correctness problems right:
> every write path is idempotent under concurrent retries, credit balances are protected by
> row-level locking with a consistent lock ordering across all use cases, state changes and
> their events are committed atomically through a transactional outbox, and every background
> job — outbox publishing, authorization expiry, statement batches — follows the same
> claim-lease-finalize-recover pattern so it survives crashes and never runs twice.
> It's deliberately a modular monolith with event-driven boundaries, so each bounded context
> could be extracted into a service later. Storage is MySQL as the source of truth, with Redis
> for velocity counting and a two-level read cache, and Kafka for cross-context event delivery.

### 9.2 幂等设计（Claim-first Idempotency）

**One-liner:** *Every write path uses insert-first claiming: the unique index picks exactly one winner, losers block and read the winner's final state, and a request fingerprint rejects key reuse with a different payload.*

**Full version:**

> For idempotency I deliberately avoided the read-then-insert pattern, because two concurrent
> requests can both see "no record" and both proceed — that's how you get a double credit hold.
> Instead, every write path claims first: it inserts a row keyed by the idempotency key, and the
> database's unique index picks exactly one winner. The losing request then does a
> SELECT ... FOR UPDATE on that key, which blocks until the winner's transaction commits,
> so the loser always returns the final decision — approved or declined — never a half-done state.
> There's one more guard: each request carries a SHA-256 fingerprint of its business fields.
> If the same idempotency key arrives with a different amount or card, we reject it with a 409
> instead of silently returning an unrelated result. So retries are safe, concurrent duplicates
> are safe, and client bugs that reuse keys are caught explicitly. The same pattern covers
> authorization, presentment posting — where the network transaction ID is the natural key —
> and repayment.

### 9.3 锁策略与死锁预防（Locking & Lock Ordering）

**One-liner:** *Hot credit-account rows are serialized with SELECT FOR UPDATE, and every use case acquires locks in the same global order, so lock waits can't form a cycle.*

**Full version:**

> Credit reservation is a read-modify-write on a hot row, so I chose pessimistic locking:
> the service takes SELECT ... FOR UPDATE on the credit account, then calls the aggregate's
> reserve method, which enforces the invariant that reserved plus posted never exceeds the limit.
> Optimistic locking would work at low contention, but on a hot account it degenerates into a
> retry storm; queueing on a row lock is more predictable. The part I'm most careful about is
> deadlock prevention: every use case acquires locks in the same global order.
> Authorization, expiry, and posting all lock the authorization first and the account second;
> repayment locks the account before the statement; statement generation locks the account
> before the candidate transactions — and posting deliberately claims the transaction row
> only *after* taking the account lock, because if posting locked transaction-then-account
> while the statement batch locked account-then-transactions, that's a textbook circular wait.
> Cheap checks like policy limits and the external risk call run *before* the account lock,
> to keep the critical section as short as possible.

### 9.4 信用账户聚合与不变量（Credit Account Aggregate）

**One-liner:** *The aggregate owns the money invariants — reserve, release, post, repay are the only ways to change balances, and available credit is always derived, never stored.*

**Full version:**

> The credit account aggregate maintains three numbers: the credit limit, the reserved amount
> for open authorization holds, and the posted balance for settled spend. Available credit is
> always derived — limit minus reserved minus posted — never stored, so those figures can't
> drift apart. All mutations go through intention-revealing methods: reserve() for a new hold,
> release() when a hold expires, postAuthorized() which moves money from reserved to posted at
> presentment, and applyRepayment() which reduces the posted balance. There are no setters,
> so nothing can bypass the invariants. The domain invariant — reserved plus posted never
> exceeds the limit — is enforced in the aggregate *and* mirrored as a CHECK constraint in MySQL,
> so even a buggy write path can't persist an impossible balance. Concurrency control is
> layered on top: the service takes the row lock first, then invokes the aggregate, so the
> invariant check always runs against fresh, exclusively-held state.

### 9.5 Transactional Outbox + Consumer Inbox

**One-liner:** *State change and event intent commit in one MySQL transaction; a background worker publishes to Kafka with at-least-once semantics, and consumers deduplicate twice — inbox table plus business unique keys.*

**Full version:**

> You can't atomically commit to MySQL and Kafka, so I use the transactional outbox pattern.
> When an aggregate changes state, it records a domain event in memory; the application service
> appends it to the outbox table in the *same* database transaction as the state change —
> so either both commit or neither does. A background pipeline then publishes: a claimer marks
> a batch of pending rows as PROCESSING in a short transaction, the worker sends to Kafka and
> waits for the broker acknowledgment *outside* any database transaction, and only then
> finalizes the row as PUBLISHED. The interesting failure is a crash after the Kafka ack but
> before the finalize: the row is still PROCESSING, a recoverer reclaims it after the lease
> expires, and the event gets published again. That's why the overall contract is at-least-once,
> and consumers deduplicate twice: an inbox table keyed by consumer name plus event ID as the
> first gate, and a business-level unique key — for example, one notification per source event —
> as the second, which also protects manual replays that bypass the inbox.

### 9.6 可认领任务框架（Claimable Jobs with Lease Tokens）

**One-liner:** *All background work follows claim-lease-finalize-recover: short-transaction claiming with SKIP LOCKED, work outside the lock, and a UUID lease token that stops a stale worker from overwriting a newer owner's result.*

**Full version:**

> Outbox publishing, delayed jobs like authorization expiry and auto-debit, and statement
> batch shards all share one lifecycle: claim, work, finalize, recover. A claimer runs a short
> transaction that selects due PENDING rows with FOR UPDATE SKIP LOCKED — so multiple instances
> can poll concurrently without fighting over the same rows — flips them to PROCESSING, writes
> a fresh UUID lease token and a lease deadline, and commits. The worker then does the actual
> work — a Kafka publish, a bank debit, generating statements — *without* holding any job row
> lock, because external latency should never stretch a database lock. To finalize, the worker
> re-locks the row and verifies three things: the status is still PROCESSING, it holds a token,
> and its token matches the database. If a recoverer already reclaimed the job after a lease
> timeout, the token won't match and the stale worker simply walks away instead of overwriting
> the new owner's result. I use a token rather than the deadline as the owner's identity because
> deadlines get rewritten on every reclaim and timestamp precision makes them unreliable.
> Failures increment an attempt counter with backoff, and after max attempts the job goes DEAD
> so a poison job can't hammer the system forever.

### 9.7 账单批处理（Statement Generation Batch)

**One-liner:** *A daily scheduler plans the cycle into CRC32-sharded jobs; each account is generated in its own small transaction, idempotent per account-cycle, with billing dates cut in JST.*

**Full version:**

> Statement generation is a batch problem, but I still treat transaction boundaries carefully.
> A daily scheduler checks whether yesterday was the closing date and, if so, idempotently
> plans the cycle into sharded jobs — accounts are partitioned by CRC32 of the account ID
> modulo the shard count, so shards are stable and disjoint. Workers claim shards through the
> same lease mechanism as other jobs. Crucially, there is no giant batch transaction: each
> account gets its own transaction that locks the credit account, snapshots the unbilled posted
> transactions into immutable statement lines, computes the total and the minimum payment,
> marks those transactions as billed, and schedules the due-date auto-debit — all committing
> together. One bad account fails alone; it never rolls back a thousand neighbors.
> Generation is idempotent at the account-plus-cycle level via a unique key, so a retried shard
> can't produce a duplicate statement. And all billing dates are cut in Japan Standard Time
> with a business-day calendar for the due date — using UTC here would misassign transactions
> near midnight JST to the wrong cycle.

### 9.8 还款与自动扣款（Repayment & Auto-debit）

**One-liner:** *A repayment updates the statement and the account in one locked transaction; auto-debit reuses the same entry point with a deterministic idempotency key so a retried debit never posts twice.*

**Full version:**

> A repayment touches two aggregates: the statement's paid amount and status, and the credit
> account's posted balance. Those must move together, so the service locks the account first,
> then the statement — following the global lock order — validates currency, remaining amount,
> and ownership, and commits both updates plus the outbox event in one transaction.
> The endpoint is idempotent through the same claim-first pattern as authorization.
> Automatic due-date debit is layered on top: a delay job fires on the due date, asks the bank
> gateway to debit, and only on success calls the same receive-repayment use case with a
> deterministic idempotency key — "auto-debit" plus the statement ID — so a retried job can
> never post the same debit twice. In reviewing this flow I actually found a race worth
> mentioning: the debit amount was decided from an unlocked snapshot, so a concurrent manual
> repayment could leave the bank debited but the posting rejected. The fix is a two-phase
> shape — lock the statement and persist a pending repayment with the fixed amount first,
> then debit the bank, then finalize — which is exactly how real issuers separate
> authorization from capture.

### 9.9 Money 值对象与 JPY（Money & Currency Handling)

**One-liner:** *Money is BigDecimal plus Currency, normalized to the currency's legal scale with fail-fast rounding — JPY has zero decimal places and the type makes it impossible to get that wrong.*

**Full version:**

> Monetary amounts are never raw doubles or bare BigDecimals — there's a Money value object
> combining a BigDecimal with a java.util.Currency. Its constructor normalizes the amount to
> the currency's legal fraction digits — zero for JPY, two for USD — using RoundingMode
> UNNECESSARY, which means trailing zeros from the database, like 100000.00 yen, are safely
> normalized, but a genuinely sub-yen amount fails fast instead of being silently rounded into
> a wrong figure. Addition and subtraction refuse mismatched currencies, so you physically
> can't subtract dollars from a yen limit. Rounding policy lives inside the type: the minimum
> payment calculation multiplies by a rate and rounds with CEILING at the currency's own scale,
> so callers can't accidentally apply two-decimal rounding to yen. In the database everything
> is DECIMAL, never float, and the schema backs the domain with CHECK constraints on
> non-negative balances.

### 9.10 两级缓存（Two-level Read Cache）

**One-liner:** *Cache-aside with Caffeine L1 and Redis L2 for the statement read model only; writes never update the cache — they invalidate it after the database commit.*

**Full version:**

> Statement reads are cached with two levels: Caffeine in-process as L1 with a short TTL and
> a hard size cap, and Redis as shared L2 with a longer, jittered TTL so a batch of keys never
> expires in the same second and stampedes the database. I only cache read models that can
> always be rebuilt from MySQL — never credit balances or idempotency state. The write path
> never updates the cache directly, because the response is assembled from multiple tables and
> a concurrent hand-built cache write can miss fields or write stale data; instead, after the
> database transaction commits, we invalidate. The subtle problem is the late-write race:
> a slow reader that loaded the old value from the database can write it back to Redis *after*
> the invalidation. I handle that with a monotonic version on the statement and a short-lived
> version-floor tombstone in L2, so a stale write-back is rejected by compare-and-set.
> Cross-pod L1 staleness is bounded by the short local TTL — an accepted trade-off, since
> statement reads tolerate seconds of staleness and strongly consistent paths just read MySQL.

### 9.11 韧性与外部依赖（Resilience: Bulkhead, Circuit Breaker, Timeouts）

**One-liner:** *The external risk call is fenced by a read timeout, a semaphore bulkhead sized below the connection pool, and a circuit breaker — so a vendor brownout degrades authorizations instead of taking down the service.*

**Full version:**

> The external risk check runs inside the authorization flow, so I treat it as the most
> dangerous dependency. Three fences, each with a sizing rationale. First, tight timeouts —
> 300 milliseconds to connect, 800 to read — so a hung vendor caps how long any single
> transaction is pinned. Second, a semaphore bulkhead deliberately sized *below* the Hikari
> connection pool: even if the vendor browns out completely, only four authorization
> transactions can be stuck holding connections, and every other endpoint keeps its headroom;
> the bulkhead has zero wait, so overflow fails fast instead of queueing on Tomcat threads.
> Third, a circuit breaker that opens on failure rate and converts vendor outages into
> immediate, well-defined declines rather than 500s. I also run the risk call *before* taking
> the account row lock, so a slow vendor never stretches the lock's critical section.
> Notifications get the same treatment per channel — independent circuit breakers with
> slow-call detection, while hard timeouts belong to the HTTP client — so a failing push provider can't drag email down.

### 9.12 Kafka 配置与消费语义（Kafka Semantics）

**One-liner:** *Idempotent producer with acks=all, manual per-record offset commits, one consumer group per bounded context, and a dead-letter topic per consumer.*

**Full version:**

> On the producer side I enable idempotence with acks=all, so broker-side retries don't
> duplicate writes within Kafka — though the end-to-end contract stays at-least-once because
> of the outbox, which is why consumer-side dedup still matters. On the consumer side,
> auto-commit is off and the ack mode is per-record: the offset commits only after the listener
> returns successfully, so a crash means redelivery, never silent loss. The Notification bounded
> context subscribes with its own durable consumer group; future contexts would use separate groups
> so they receive their own copy instead of competing for Notification's offsets. Poison messages go to a
> per-consumer dead-letter topic so one bad payload doesn't block a partition. The current
> project still needs DLT metrics, alerting, and controlled replay. Partition keys are the aggregate ID, which gives per-partition
> ordering — and I'll be upfront that with a parallel publisher pool, strict per-aggregate
> ordering isn't guaranteed; my consumers are order-insensitive by design, and if I needed
> strict ordering I'd serialize publishing per partition key and pay the throughput cost.

### 9.13 Schema 防线（Database as the Last Line of Defense）

**One-liner:** *Domain invariants are mirrored as CHECK constraints — legal status-field combinations, non-negative balances, reserved plus posted within the limit — so even a buggy write path can't persist impossible state.*

**Full version:**

> I treat the schema as the last line of defense, not just storage. Status machines are
> encoded as CHECK constraints: an APPROVED authorization must have a decision time and an
> expiry and no decline reason; a POSTED one must have been posted before expiry; a PAID
> statement must have paid-amount equal to total. Balances have non-negative checks and the
> account-level invariant — reserved plus posted within the credit limit — is enforced in both
> the aggregate and the database. Uniqueness carries the idempotency design: idempotency keys,
> the network transaction ID, one statement per account per cycle, one notification per source
> event, one delay job per aggregate-and-type. The trade-off I'd acknowledge: putting state
> machines into CHECK constraints means every status change ships as a migration, and MySQL
> only enforces CHECK from 8.0.16 — for this project the extra safety is worth the coupling,
> and Liquibase versions every change anyway.

### 9.14 诚实的边界与扩展路径（Limitations & Scaling Story — 主动交底用）

**One-liner:** *It's a modular monolith by intent; I can walk through how I'd shard MySQL by account, split services along the existing event boundaries, and replace the local posting transaction with a saga.*

**Full version:**

> I want to be upfront about what this project is and isn't. It's a modular monolith by
> intent — the bounded contexts communicate through events, but core posting still enjoys a
> local transaction across the authorization, the account, and the transaction record.
> If we split services, that becomes a saga: confirm the hold, post, and compensate by
> re-releasing the hold on failure, with idempotency keys carried through every step.
> For database scale, I'd shard by credit account ID, because authorization, posting,
> repayment, and statement generation are all account-local — locks and transactions never
> need to cross a shard, and the statement batch's CRC32 sharding maps directly onto physical
> shards. The hardest single problem is a hot account hammering one row lock; the realistic
> options are splitting the limit into sub-buckets, pre-reserving quota into memory with
> asynchronous reconciliation, or routing each account to a single writer — each trades
> consistency granularity for throughput, and I'd pick based on how strict the over-limit
> tolerance is. And I haven't load-tested this at scale — the design choices anticipate scale,
> but I'd rather say that plainly than imply production mileage I don't have.

### 9.15 自愈调度与一次真实的缺陷发现（Level-triggered Scheduling — 发现→修复故事）

**One-liner:** *Almost every timer in the system is level-triggered — durable plans in MySQL scanned by pollers — and when I found the one edge-triggered cron that could silently skip a billing cycle, I converted it into a reconciliation loop.*

**Full version:**

> One bug I'm genuinely glad I caught: statement cycle planning used to be a daily in-memory
> cron that asked "was yesterday the closing date?" Spring's @Scheduled has no misfire
> compensation, so if the app happened to be down at that one moment — say a deploy gone wrong
> on the first of the month — the answer would be "no" forever after, and that month's
> statements would silently never be planned. Not delayed — never created, and with no dead
> row in any table to alert on. What made it interesting is that every line of the code was
> correct; the defect was an *absence* — nothing owned the missed tick. I only found it by
> classifying every timed entry point as edge-triggered or level-triggered: the outbox,
> delay jobs, and notification pollers all scan durable state, so a crash across the trigger
> moment self-heals; this cron was the single edge-triggered exception. The fix follows the
> control-loop idea: instead of reacting to "today", the scheduler now reconciles desired
> state — it scans the last few closed billing cycles, finds any cycle with no planned jobs,
> and back-fills it. Idempotency needs no new machinery: an existence check is just a fast
> path, and the real guarantee stays with INSERT IGNORE on the cycle-and-shard unique key,
> so even two pods reconciling concurrently can't double-plan. And I'd volunteer the residual
> trade-off: a cycle recovered very late can carry a due date that's already passed, which
> would trigger an immediate auto-debit — in production I'd floor the due date at
> recovery-date-plus-N-business-days.

---

## 附：背诵优先级建议

| 优先级 | 条目 | 理由 |
| --- | --- | --- |
| 必背 | 9.1, 9.2, 9.3, 9.5, 9.6 | 开场 + JD 核心（并发/幂等/事件驱动）必问 |
| 高 | 9.8, 9.11, 9.14, 9.15 | 竞态故事、生产思维、主动交底、缺陷发现→修复故事——区分度最高的四条 |
| 中 | 9.4, 9.7, 9.12, 9.13 | 被追问时展开 |
| 低 | 9.9, 9.10 | 缓存/金额只在对应话题出现时使用 |
