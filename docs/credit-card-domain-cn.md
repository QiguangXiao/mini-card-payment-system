# 信用卡发卡方业务领域：全流程、分支与剩余领域路线图

> 本文合并自两份旧文档：`credit-card-lifecycle-cn.md`（刷卡到还款的业务全流程 + 分支）与
> `ToDo.md`（剩余领域学习路线图）。合并时把两者重复的 "Ledger vs Reconciliation 区别" 与
> "后续领域优先级" 统一讲一次，并**对齐代码**：旧文档把账单批处理描述成 `StatementBatchPoller`/
> `StatementBatchService`/`StatementService.generate`，但 PR #1 已改成 claimable job
> （`BillingCycleScheduler` → `StatementCycleService` → `StatementJobDispatcher` →
> `StatementJobHandler` → `StatementGenerationService`）。原两份已归档在 `docs/archive/`。
> 从 HTTP 到锁/状态的逐请求细节见 `domain-state-flow-cn.md`；实现走读见 `implementation-walkthrough`。

> 关键词：发卡方, 授权, 入账, 账单, 还款, 退款, 冲正, 争议, 账本, 对账, issuer, authorization,
> presentment/clearing, posting, statement, repayment, refund, reversal, dispute, ledger,
> reconciliation, 発行体(はっこうたい), 与信(よしん)。

---

## 1. 参与方

| 参与方 | 英文 | 角色 |
| --- | --- | --- |
| 持卡人 | Cardholder | 用卡消费的人 |
| 商户 | Merchant | 提供商品/服务并发起交易 |
| 收单机构 | Acquirer | 为商户处理刷卡的金融机构/支付服务方 |
| 卡组织/网络 | Card Network | Visa/Mastercard/**JCB** 等交易网络 |
| 发卡方 | Issuer | 发卡并承担授信/账务管理的一方，例如 **PayPay Card** |
| 发卡后台 | Issuer Backend | 处理授权、额度、入账、账单、还款、风控的系统 |

视角差异（本项目站在 **issuer** 视角）：

```text
商户/收单侧关心：我能不能收钱？
卡组织关心：交易如何路由、清分、结算？
发卡方关心：这张卡能不能用？额度够不够？这笔交易如何进入持卡人账务？
```

> PayPay Card 作为 issuer，最关注的是"外部请求进来后，如何保护持卡人账户、额度、账务和风控一致性"，而不是商户怎么 capture，也不是先关注 settlement cash movement。

---

## 2. 正向全流程

例子：持卡人在便利店刷 1,000 JPY。

```text
Authorization（授权占额度）
-> Presentment / Clearing（商户正式提交交易）
-> Posting（发卡方入账）
-> Statement（生成账单）
-> Payment（持卡人还款）
```

### 2.1 Authorization 授权

- **方向**：Merchant → Acquirer → Network → Issuer（外部主动，issuer 响应）。请求本质：*"这张卡现在能不能先批准这笔 1,000 JPY？"*
- Issuer 判断：卡是否存在/active、账户是否 active、额度是否足够、风控是否通过、请求是否重复（idempotency）。
- 批准：`Authorization PENDING → APPROVED`，`CreditAccount.reservedAmount += 1000`（**只是 hold 额度，还没入账**，可用额度减少）。拒绝：`PENDING → DECLINED`，账户不变（卡冻结/额度不足/风控/币种不支持）。

> **interview 重点**：如何防重试重复占额度？如何防并发刷爆额度？为什么不用 `synchronized`？为什么授权成功只是 reserve 而非入账？
> *答*：Authorization 是实时决策路径，必须低延迟、高一致性。先用 idempotency claim 处理重试，再用 credit account row lock 串行化同账户额度变化，最后由 aggregate 保证 `reservedAmount` 不超 `creditLimit`。

### 2.2 Presentment / Clearing 入账

- **Presentment ≠ Clearing**：Presentment 是"商户/收单把一笔已授权交易正式提交给发卡方请求入账"；Clearing 更宽，是"卡组织/收单/发卡之间交换交易、费用、退款、冲正记录的清分流程/文件流"。即 **clearing file 里包含 presentment 记录，presentment 是一种 clearing 记录**。
- **方向**：Acquirer → Network → Issuer。请求带 network transaction id、可关联授权的 reference、card id、amount、currency、merchant、transaction date。
- Issuer 检查：能否找到原授权、原授权是否 `APPROVED`、是否未过期、金额是否匹配、这条 presentment 是否已处理过。通过后：

```text
Authorization APPROVED → POSTED
CreditAccount.reservedAmount -= 1000；postedBalance += 1000
CardTransaction PENDING → POSTED   （授权 hold 变成正式交易，可进入账单/退款/争议/ledger/对账）
```

> **为什么不叫 Capture**：`Capture` 偏商户/收单语言（"我把 authorization capture 掉准备收钱"）；issuer 视角更自然是 "Presentment received → Posting → CardTransaction POSTED"。

### 2.3 Statement 账单（周期批处理，已扁平化为 claimable job）

- 不是外部实时请求，而是周期性批处理。当前产品级固定日期：**每月月末关账，次日由 `BillingCycleScheduler`（每天 JST cron）触发 reconciliation 心跳，补建缺失的出账分片；次月 27 日为扣款基准日；27 日非日本营业日则按 `JapaneseBusinessDayCalendar` 顺延到下一营业日**。
- 当前实现（claimable job，详见 `claimable-jobs-cn.md`）：
  - `BillingCycleScheduler` 每天 cron 触发 → `StatementCycleService` 扫描最近已过去的 close cycles，缺失周期才计算 billing cycle（period/dueDate）、按账户数算 shardCount、`INSERT IGNORE` 建分片 `statement_jobs`。
  - `StatementJobDispatcher` claim 分片 → `StatementJobHandler` 取本片账户 → 逐账户调 `StatementGenerationService.generate(...)`（**每账户独立小事务**），按 billing cycle 汇总未出账的 `POSTED` 交易。
  - `statements` 存账单汇总，`statement_lines` 存交易快照；`card_transactions.statement_id` 标记交易已进入哪期账单（防重复出账）。
  - 同事务写 `AUTO_REPAYMENT` DelayJob 计划 dueDate 自动扣款；Statement 不再重复发布 Kafka 通知事件，Outbox/Inbox 学习由 Authorization 与 CardTransaction 两条主线承担。

```text
6 月账单：便利店 1,000 + 餐厅 3,000 → statementBalance = 4,000，dueDate = 7/27
```

> **interview 重点**：哪些交易能进账单？账单生成后历史交易能改吗？refund 在账单前后处理有何不同？batch 怎么防重复出账？batch 与 posting 并发怎么办？失败账户会不会拖垮整批？（账户级隔离见 claimable-jobs）

### 2.4 Payment 还款

- 主动者：自动扣款系统 / 银行入金通知 / 持卡人主动还款。本质：减少持卡人欠款 → 更新 statement paid amount → 恢复 available credit。
- 当前实现（简化版 Repayment）：
  - `AUTO_REPAYMENT` DelayJob 到期 → `AutoRepaymentDelayJobHandler` → `AutoRepaymentService`；当前不建 `bank_accounts` 表，假设已有默认扣款授权；银行侧走 Feign/HTTP 到模拟银行 `SimulatedBankController`（`BankDebitGatewayAdapter` 挂 bankDebit 熔断），默认 `SUCCESS`；业务拒绝（`FAILED`）不入账交 DelayJob retry/DEAD，银行 4xx 走 permanent 快速 DEAD。
  - 成功后用确定性幂等键 `auto-debit:{statementId}` 调 `RepaymentService.receive(...)`；`POST /api/repayments` 用 `Idempotency-Key` 防重复还款。
  - `RepaymentService.receive(...)` 在**同一 transaction boundary** 内更新 `repayments`、`credit_accounts.posted_balance`、`statements.paid_amount/status/version`；**锁顺序 `credit account → statement`**，避免和账单生成相反锁顺序。
  - 还款完成后提交 repayment/account/statement 状态，并在 commit 后失效 Statement 缓存；还款路径不发布 Kafka 通知事件。
  - **当前不支持** overpayment、多账单自动分摊、真实银行资金清算、生产级 double-entry ledger。

> **interview 重点**：银行入金通知重复怎么办？还款与新消费并发怎么办？多还了怎么办？部分还款如何反映到账单？还款失败/撤销怎么办？（这些不该塞进 `transaction` domain，应由 `repayment`/`statement`/`creditaccount` 协作）

---

## 3. 分支流程

| 分支 | 场景 | issuer 做什么 | 当前项目 |
| --- | --- | --- | --- |
| **Authorization Reversal 授权撤销** | 授权成功后交易取消（入账前） | `APPROVED → REVERSED/CANCELED`，`reservedAmount -= amount`，**不产生 posted 交易** | 未实现 |
| **Authorization Expiry 授权过期** | 授权批准后商户一直没 presentment | `APPROVED → EXPIRED`，`reservedAmount -= amount` | **已实现**（DelayJob `AUTHORIZATION_EXPIRY`） |
| **Refund 退款** | 已入账后退货 | 推荐 **append-only**：原购买交易保持 POSTED，创建单独的 REFUND 交易关联原交易，`postedBalance -= refundAmount`（比直接改 `POSTED→REFUNDED` 更易审计） | 未实现 |
| **Clearing Reversal / Adjustment 清分冲正** | 清分文件发现上条 presentment 错了 | 找到原交易、创建 reversal/adjustment 交易、修正 `postedBalance`、记 reason code、保留关联；**不物理删除原交易**；幂等键来自 network clearing record id | 未实现 |
| **Dispute / Chargeback 争议** | 持卡人称非本人交易 | 记 dispute case、可能发 provisional credit、经网络发起 chargeback、等商户举证、最终定责。**独立 `dispute` domain**，不塞进 authorization/transaction 基本流程 | 未实现 |

---

## 4. Ledger、流水、对账分别是什么（高频混淆点）

| 概念 | 回答的问题 | 用途 / 特征 |
| --- | --- | --- |
| **CardTransaction 流水** | 用户看见了哪些消费/退款/调整？ | APP 明细、客服查询、账单生成 |
| **Ledger 账本** | 内部会计科目如何借贷变化？ | 偏财务一致性，要求 double-entry + append-only。如 `Debit 持卡人应收 1000 / Credit 网络结算应付 1000` |
| **Reconciliation 对账** | 内部记录和外部网络/银行/清算文件是否一致？ | 找 missing / duplicate / amount mismatch / status mismatch / settlement mismatch，输出 exception report |

> 一句话区分：**Ledger 是内部账怎么记；Reconciliation 是内部账和外部资料是否对得上。** `CardTransaction` 不是 ledger——它是持卡人可见的流水（"这笔已 posted"），ledger 更靠近会计事实和账户余额解释。

当前项目**不实现 Ledger**：主链路以 `CardTransaction`、`credit_accounts.posted_balance`、
`Statement` 和 `Repayment` 表达交易与欠款状态。这样避免用只有单边 DEBIT/CREDIT 标签的投影冒充
production accounting system。生产级 Ledger 需要 accounting account、balanced journal、
fee/interest/refund adjustment、不可变更正和更严格审计，应作为独立系统边界设计。

---

## 5. Issuer 后台最重要的工程关注点

| 关注点 | 要点 |
| --- | --- |
| **Idempotency** | 所有外部状态变更请求都可能重试（authorization retry、presentment/refund/payment callback duplicate）→ 外部 id / idempotency key + **unique constraint** 兜底 |
| **Row lock + transaction boundary** | 核心账户余额不能靠内存锁（多实例）→ `SELECT ... FOR UPDATE` 保护同一 credit account 的额度变化 |
| **Explicit state transition** | 状态显式（`PENDING→APPROVED→POSTED`、`APPROVED→EXPIRED`、`CardTransaction PENDING→POSTED`），不要用一个 boolean 表达复杂生命周期 |
| **Audit trail** | 不能只存最终余额，要能回答：为什么这笔钱被占用？哪个外部请求导致入账？是否重复？是否有退款/冲正？当时风控和额度判断是什么？ |
| **Eventual consistency** | 通知、风控投影、运营报表可异步；授权/入账/还款等资金/额度核心路径必须在明确 transaction boundary 内完成 |
| **Failure recovery** | Kafka 挂、consumer 重复消费、scheduler 宕机、presentment 文件重投、payment callback 重复/乱序 → 这就是 Outbox、Inbox、DelayJob、幂等键和状态机存在的原因 |

---

## 6. 映射到当前项目

**已覆盖**：

```text
Authorization → Credit hold → Presentment Posting → CardTransaction → Statement → Repayment
→ Authorization Expiry → Outbox/Kafka notification
```

**未覆盖**：Refund / reversal、Reconciliation、Settlement cash movement、Dispute / chargeback。

---

## 7. 剩余领域学习路线图

> 这不是普通 TODO 清单，而是服务金融后端 interview 的 **remaining domain roadmap**。当前已覆盖 interview 最核心的主链路（authorization → posting → statement → repayment → notification/risk → outbox/inbox/delayjob），所以**不建议继续无差别堆功能**——只补最能帮助理解金融后端概念的小切片，其余先停在文档解释层。

| 优先级 | 领域 | 推荐范围 | 为什么值得做 / 为什么不是更前 |
| --- | --- | --- | --- |
| **Concept only** | Ledger | 保留 transaction / statement / accounting / reconciliation 区别，不实现伪 double-entry 投影 | 概念重要，但正确实现会扩张到科目、平衡 journal、adjustment 和审计；当前代码边界主动收口 |
| **P1** | 最小 Reconciliation | 本地 CSV/内存模拟 network/bank statement，对比 `card_transactions` 或 `repayments`，输出 mismatch report；**不自动改账、不接真实文件** | 对 PayPay Card 这类金融后台很贴近真实工作；interview 价值在"为什么对不上、如何处理 exception"，不是 CSV 解析 |
| **P2** | Authorization Reversal | 外部在 presentment 前撤销授权：`APPROVED → REVERSED`，释放 `reservedAmount`，写 Outbox event | 补齐 authorization hold 的典型负向分支，强化 idempotency/row lock/state transition。**不更前**：现有 `Authorization Expiry` 已展示释放 hold 的核心机制 |
| **P3** | Refund | 涉及已出账前/后退款、是否影响 statement、是否产生 credit balance、是否需 ledger adjustment、是否和 dispute 混淆 | 真实业务重要，但实现易膨胀 → **先在本文理解，暂不急写代码** |
| **P4** | Dispute / Chargeback | 概念可讲，不建议现在实现 | 生命周期长、状态多、强依赖卡组织规则/证据/时限/运营，容易把学习项目拖成业务流程系统 |
| **P5** | Settlement | 不建议现在实现 | 更偏资金清算和外部报表；当前没有真正 acquirer/network/bank 文件，容易做成假的"状态字段" |
| **P6** | Cardholder/User/Auth | 生产必需，interview 主线不急 | 会引入登录、权限、PII、安全设计；对金融交易一致性 interview 帮助不如 ledger/reconciliation。当前用 `cardId`/`creditAccountId` 作学习用 routing key 已足够 |

**建议路线**：先消化现有主链路 → 理解 Ledger 概念边界但不实现 → 最小 Reconciliation 或 Authorization Reversal 二选一 → 停下来复盘 interview 表达。

---

## 8. interview 一句话总结

> 信用卡发卡后台的核心不是"商户 capture"，而是 issuer 如何处理 authorization、presentment posting、账单和还款。Authorization 负责实时批准和额度 hold；Presentment/Clearing 到达后把 hold 转成 CardTransaction 并入账；之后 refund、statement、payment、ledger、reconciliation 都围绕已入账交易和账户余额继续演进。每一步都要考虑 idempotency、row lock、transaction boundary、audit trail 和 failure recovery。
