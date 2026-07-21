# 剩余领域学习路线图

> **归档对齐说明（2026-07）**：本文正文已按当前代码重新校准。Ledger 和
> `repayment.received` Kafka 路径是已经删除的历史实现，不在路线中标成“已完成能力”；
> Ledger 只保留为 production concept。现行领域入口见
> [credit-card-domain-cn.md](../credit-card-domain-cn.md)。

这份文档不是普通 TODO 清单，而是服务 PayPay Card / 金融后端 interview 准备的
remaining domain roadmap。

当前项目已经覆盖 interview 最核心的主链路：

```text
Authorization
-> Presentment Posting
-> BillingCycleScheduler reconciliation + sharded StatementJob
-> Statement
-> Repayment
-> Notification / Redis velocity / External Risk
-> Outbox / Inbox / DelayJob
```

所以后续不建议继续无差别堆功能。更好的策略是：只补最能帮助理解金融后端概念的
小切片，其余先停在文档解释层。

## 1. Ledger 和 Reconciliation 是一回事吗

不是一回事。

### Ledger

`Ledger` 是账本。它回答：

```text
系统内部认为钱和债务是怎么变化的？
```

在信用卡 issuer backend 里，ledger 通常记录会计含义更强的 entries，例如：

- 消费入账：持卡人应还款增加。
- 还款入账：持卡人应还款减少。
- 退款：应还款减少或形成 credit balance。
- 手续费、利息、调整项：按产品规则入账。

它强调：

- double-entry 或至少 debit/credit direction。
- 每个 entry 有 source business event。
- 所有金额变化可审计、可追溯、不可随便修改。

当前项目里的 `CardTransaction` 不是 ledger。它是持卡人可见的交易流水，表达
“这笔交易已经 posted”。Ledger 会更靠近会计事实和账户余额解释。

### Reconciliation

`Reconciliation` 是对账。它回答：

```text
我们内部记录的交易、金额、状态，和外部网络/银行/清算文件是否一致？
```

典型对账对象：

- Card network clearing file vs internal `card_transactions`。
- Bank debit result file vs internal `repayments`。
- Settlement report vs internal ledger entries。

它强调：

- compare internal records and external records。
- 找 missing、duplicate、amount mismatch、status mismatch。
- 生成 exception report，给运营或后续自动修复流程处理。

一句话区分：

```text
Ledger 是内部账怎么记。
Reconciliation 是内部账和外部资料是否对得上。
```

## 2. 最值得补的领域排名

### Concept only：Ledger（曾有 learning projection，2026-07 已移除）

曾实现为最小 learning projection（`ledger_entries` 表 + Kafka 消费两类事件记 DEBIT/CREDIT 分录），后为聚焦主线整体移除；概念仍值得口头掌握。

为什么值得懂：

- 帮你区分交易流水、账单、还款、余额和会计账本。
- interview 里可以解释为什么 `CardTransaction` 不等于 ledger。

注意：

```text
当前仓库没有 Ledger。
被删除的最小 projection 不是生产级总账，也不应在 interview 中说成“实现了 double-entry ledger”。
```

推荐记住：真正的 production ledger 至少要回答 account/chart、balanced journal、
append-only correction、fee/interest/refund adjustment、币种与会计日期、审计和 reconciliation；
只把两条 Kafka 事件翻译成 DEBIT/CREDIT 行，不足以证明这些能力。

### P1: 最小 Reconciliation

不要求先重新实现 Ledger。当前稳定 internal records（`card_transactions`、`repayments`、
`statements`）已经足够支撑一个最小“外部资料 vs 内部事实”对账练习。

推荐范围：

- 做一个本地 CSV 或内存输入的 simulated network/bank statement。
- 对比 `card_transactions` 或 `repayments`。
- 输出 mismatch report。
- 不自动改账，不做真实文件接入。

为什么值得做：

- 对 PayPay Card 这种金融后端很贴近真实工作。
- 能解释为什么金融系统需要 operational exception handling。
- 能把 ledger、clearing、bank debit、statement 的边界讲清楚。

不建议先做的原因：

- 如果没有稳定的 internal records、业务匹配键和 exception ownership，对账会退化成普通列表 diff。
- interview 价值来自“为什么对不上、如何处理 exception”，不是 CSV 解析本身。

### P2: Authorization Reversal

可以作为一个很小的负向分支。

推荐范围：

- 外部网络在 presentment 前撤销授权。
- `Authorization: APPROVED -> REVERSED`。
- 释放 `reserved_amount`。
- 写 Outbox event。

为什么有价值：

- 补齐 authorization hold 的一个典型分支。
- 继续强化 idempotency、row lock、state transition。

为什么不是 P1：

- 现有 `Authorization Expiry` 已经展示了释放 hold 的核心机制。
- interview 主线已经足够，reversal 是锦上添花。

### P3: Refund

真实业务很重要，但学习实现容易膨胀。

它会牵涉：

- 已出账前退款和已出账后退款。
- 是否影响 statement。
- 是否产生 credit balance。
- 是否需要 ledger adjustment。
- 是否和 dispute/chargeback 混淆。

建议：

```text
先在 credit-card-lifecycle 文档里理解，暂时不急着写代码。
```

### P4: Dispute / Chargeback

interview可以讲概念，但不建议现在实现。

原因：

- 生命周期长。
- 状态多。
- 强依赖卡组织规则、证据材料、时限、运营处理。
- 容易把学习项目拖成业务流程系统。

### P5: Settlement

不建议现在实现。

原因：

- Settlement 更偏资金清算和外部报表。
- 当前项目还没有真正 acquirer/network/bank 文件。
- 很容易做成假的“状态字段”，但学不到核心。

### P6: Cardholder/User/Auth

生产必需，但 interview 主线不急。

原因：

- 它会引入登录、权限、PII、安全设计。
- 对金融交易一致性 interview 帮助不如 ledger/reconciliation。
- 当前项目用 `cardId` / `creditAccountId` 作为学习用 routing key 已经足够。

## 3. 我的建议

当前最好的路线是：

```text
先消化现有主链路
-> 理解 Ledger 概念边界，但不恢复伪总账 projection
-> 最小 Reconciliation 或 Authorization Reversal 二选一
-> 停下来复盘 interview 表达
```

如果只再补一个领域：

```text
选最小 Reconciliation。
```

如果再补两个领域：

```text
最小 Reconciliation + Authorization Reversal。
```

如果时间有限：

```text
现有 Authorization / Posting / StatementJob / Repayment / Outbox / DelayJob 已足够作为代码主线；
Ledger 与 Reconciliation 先做到概念边界清楚，不急着继续加代码。
```
