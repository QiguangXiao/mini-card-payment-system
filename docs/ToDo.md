# 剩余领域学习路线图

这份文档不是普通 TODO 清单，而是面向 PayPay Card / 金融后端面试准备的
remaining domain roadmap。

当前项目已经覆盖面试最核心的主链路：

```text
Authorization
-> Presentment Posting
-> Statement
-> Repayment
-> Notification / Risk / Ledger
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

### Done: 最小 Ledger

已经实现为最小 learning projection。

当前范围：

- 已新增 `ledger_entries` 表。
- `card_transaction.posted` 后记录 `CARD_TRANSACTION_POSTED/DEBIT` entry。
- `repayment.received` 后记录 `REPAYMENT_RECEIVED/CREDIT` entry。
- 每条 entry 关联 `source_event_id + entry_type`，并使用 `consumer_inbox` 做第一道幂等。
- 不做完整会计科目、不做复式借贷平衡、不做结算。

为什么值得做：

- 帮你区分交易流水、账单、还款、余额和会计账本。
- 面试里可以解释为什么 `CardTransaction` 不等于 ledger。
- 可以复用 Outbox/Inbox 思路，学习 downstream projection。

注意：

```text
最小 Ledger 是学习 projection，不是生产级总账。
```

### P1: 最小 Reconciliation

适合在最小 Ledger 后做。

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

- 如果没有 ledger 或至少稳定的 internal records，对账会变成普通列表 diff。
- 面试价值来自“为什么对不上、如何处理 exception”，不是 CSV 解析本身。

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
- 面试主线已经足够，reversal 是锦上添花。

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

面试可以讲概念，但不建议现在实现。

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

生产必需，但面试主线不急。

原因：

- 它会引入登录、权限、PII、安全设计。
- 对金融交易一致性面试帮助不如 ledger/reconciliation。
- 当前项目用 `cardId` / `creditAccountId` 作为学习用 routing key 已经足够。

## 3. 我的建议

当前最好的路线是：

```text
先消化现有主链路
-> 修文档过期点
-> 做最小 Ledger（已完成）
-> 再做最小 Reconciliation
-> 停下来复盘面试表达
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
Ledger 已经足够作为概念入口；Reconciliation 可以先只讲概念，不急着继续补代码。
```
