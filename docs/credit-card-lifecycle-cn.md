# 信用卡刷卡到还款全流程学习笔记

这份文档用最基础、最典型的信用卡消费例子，解释从刷卡、授权、入账、账单、还款到退款/冲正分支的完整链路。

它分成两部分：

- 第一部分：通用信用卡业务流程，说明各个参与方、主动者、响应者和正向/分支流程。
- 第二部分：聚焦 PayPay Card 作为发卡方 issuer backend，说明最应该关心哪些请求、状态和处理逻辑。

## 1. 先记住参与方

一个典型刷卡消费里，常见参与方是：

| 参与方 | 英文 | 角色 |
| --- | --- | --- |
| 持卡人 | Cardholder | 使用信用卡消费的人 |
| 商户 | Merchant | 提供商品/服务并发起交易 |
| 收单机构 | Acquirer | 为商户处理刷卡交易的金融机构/支付服务方 |
| 卡组织/网络 | Card Network | Visa/Mastercard/JCB 等交易网络 |
| 发卡方 | Issuer | 给持卡人发卡并承担授信/账务管理的一方，例如 PayPay Card |
| 发卡后台 | Issuer Backend | 处理授权、额度、入账、账单、还款、风控等系统 |

最重要的视角差异：

```text
商户/收单侧关心：我能不能收钱？
卡组织关心：交易如何路由、清分、结算？
发卡方关心：这张卡能不能用？额度够不够？这笔交易如何进入持卡人账务？
```

## 2. 最典型正向例子

例子：

```text
持卡人在便利店刷 PayPay Card 消费 1,000 JPY。
```

这笔交易通常会经过几个阶段：

```text
Authorization
-> Presentment / Clearing
-> Posting
-> Statement
-> Payment
```

中文可以理解成：

```text
授权占额度
-> 商户正式提交交易
-> 发卡方入账
-> 生成账单
-> 持卡人还款
```

## 3. 阶段一：Authorization 授权

### 3.1 谁主动，谁响应

主动者：

```text
Merchant / Acquirer / Card Network
```

响应者：

```text
Issuer / PayPay Card backend
```

请求方向：

```text
Merchant
-> Acquirer
-> Card Network
-> Issuer
```

### 3.2 请求想问什么

授权请求本质上是在问 issuer：

```text
这张卡现在能不能先批准这笔 1,000 JPY 的消费？
```

Issuer 需要判断：

- 卡是否存在。
- 卡是否 active。
- 账户是否 active。
- 额度是否足够。
- 风控是否通过。
- 请求是否重复，是否满足 idempotency。

### 3.3 Issuer 做什么

如果批准：

```text
Authorization: PENDING -> APPROVED
CreditAccount.reservedAmount += 1,000
```

含义：

- 这笔交易还没真正入账。
- 只是先把额度 hold 住。
- 用户可用额度会减少。

如果拒绝：

```text
Authorization: PENDING -> DECLINED
CreditAccount 不变
```

拒绝原因可能是：

- 卡被冻结。
- 额度不足。
- 风控拒绝。
- 币种不支持。

### 3.4 面试重点

发卡后台授权阶段最容易被问：

- 如何防止同一个请求重试导致重复占额度？
- 如何防止并发交易把额度刷爆？
- 为什么不用 Java `synchronized`？
- 为什么要用数据库 row lock？
- 为什么授权成功后只是 reserve，而不是直接入账？

推荐回答：

> Authorization 是实时决策路径，必须低延迟、高一致性。发卡方先通过 idempotency claim 处理重试，再用 credit account row lock 串行化同账户额度变化，最后由 aggregate 保证 reservedAmount 不超过 creditLimit。

## 4. 阶段二：Presentment / Clearing

### 4.1 Presentment 和 Clearing 是不是一个东西

不是完全一样。

`Presentment` 更具体：

```text
商户/收单方把一笔已授权交易正式提交给发卡方，请求入账。
```

`Clearing` 更宽：

```text
卡组织/收单/发卡之间交换交易数据、费用、退款、冲正等记录的清分流程或文件流。
```

可以这样理解：

```text
Clearing file/process contains presentment records.
Presentment is one type of clearing record.
```

### 4.2 谁主动，谁响应

主动者：

```text
Acquirer / Card Network
```

响应者：

```text
Issuer / PayPay Card backend
```

请求方向：

```text
Acquirer
-> Card Network
-> Issuer
```

### 4.3 请求想表达什么

Presentment 不是问“能不能消费”，而是在说：

```text
这笔之前批准过的交易，商户现在正式提交，请发卡方入账。
```

请求里通常会带：

- network transaction id。
- authorization id 或可关联授权的 reference。
- card id / token。
- amount。
- currency。
- merchant 信息。
- transaction date。

### 4.4 Issuer 做什么

Issuer 会检查：

- 是否能找到原授权。
- 原授权是否是 `APPROVED`。
- 授权是否还没过期。
- presentment 金额是否匹配。
- 这条 presentment 是否已经处理过。

如果通过：

```text
Authorization: APPROVED -> POSTED
CreditAccount.reservedAmount -= 1,000
CreditAccount.postedBalance += 1,000
CardTransaction: PENDING -> POSTED
```

含义：

- 授权 hold 变成正式交易。
- 用户账务上出现一笔 card transaction。
- 这笔交易以后可以进入账单、退款、争议、ledger、对账。

### 4.5 为什么不叫 Capture

`Capture` 更偏商户/收单侧语言。

商户视角：

```text
我把之前 authorization capture 掉，准备收钱。
```

发卡方视角：

```text
我收到 presentment/clearing record，然后把交易 posted to cardholder account。
```

所以 PayPay Card 作为 issuer backend，更自然的命名是：

```text
Presentment received -> Posting -> CardTransaction POSTED
```

## 5. 阶段三：Statement 账单

### 5.1 谁主动，谁响应

主动者：

```text
Issuer backend scheduled job / billing system
```

响应者：

```text
Issuer account / statement domain
```

这通常不是外部实时请求，而是周期性批处理。
当前项目默认采用产品级固定日期：

```text
每月 15 日关账
次日由 StatementBatchPoller 跑 billing batch
下一个固定 10 日作为 dueDate / auto debit date
```

这里先不做客户自定义扣款日，因为当前更值得练习和解释的是：

- batch 怎么避免重复出账？
- batch 和 posting 并发怎么办？
- due-date 后续动作怎么可靠执行？
- 失败账户会不会拖垮整批？

### 5.2 做什么

账单周期结束时，系统会把该周期内已经 posted 的 card transactions 汇总：

```text
CardTransactions in billing cycle
-> Statement
-> statementBalance
-> minimumPayment
-> dueDate
```

例子：

```text
6 月账单：
- 便利店消费 1,000 JPY
- 餐厅消费 3,000 JPY
statementBalance = 4,000 JPY
dueDate = 7 月 10 日
```

### 5.3 面试重点

账单阶段会被问：

- 哪些交易能进入账单？
- 账单生成后历史交易还能不能改？
- refund 发生在账单前后，处理有什么不同？
- minimum payment 怎么计算？
- due date 怎么处理？

当前项目已经实现基础 statement generation：

- `StatementBatchPoller` 每分钟轻量检查一次，只有关账日次日才真正跑 batch。
- `StatementBatchService` 计算 billing cycle 和 dueDate，并逐个账户调用 `StatementService.generate(...)`。
- `StatementService.generate(...)` 按 billing cycle 汇总未出账的 `POSTED` transactions。
- `statements` 保存账单汇总，`statement_items` 保存交易快照。
- `card_transactions.statement_id` 记录交易已经进入哪期账单，防止重复出账。
- `StatementService` 在同一事务里写 `AUTO_REPAYMENT` DelayJob，计划 dueDate 自动扣款。
- `statement.closed` 通过 Outbox 发布；当前 Notification 已消费它创建 `STATEMENT_READY` 通知，未来 PDF 生成、还款提醒也可以消费。

账单生成只固定金额，不恢复信用额度；当前项目已经通过简化的 `Repayment` 领域处理还款入账。

## 6. 阶段四：Payment 还款

### 6.1 谁主动，谁响应

主动者可能是：

```text
自动扣款系统
银行入金通知
持卡人主动还款
```

响应者：

```text
Issuer payment/account backend
```

### 6.2 做什么

还款本质是减少持卡人欠款：

```text
Payment received
-> reduce outstanding balance
-> update statement paid amount
-> maybe restore available credit
```

例子：

```text
statementBalance = 4,000 JPY
dueDate 自动扣款 4,000 JPY
bank debit result = SUCCESS
postedBalance / outstanding balance 减少
availableCredit 恢复
statement 标记为 paid
```

### 6.3 面试重点

还款阶段会被问：

- 银行入金通知重复怎么办？
- 还款和新消费并发怎么办？
- 多还了怎么办？
- 部分还款怎么反映到账单？
- 还款失败或撤销怎么办？

这些通常不应该塞进 `transaction` domain，而应该有独立的 `payment` / `statement` / `creditaccount` 协作。

当前项目已经实现简化版 Repayment：

- `AUTO_REPAYMENT` DelayJob 到期后由 `AutoRepaymentDelayJobHandler` 调用 `AutoRepaymentService`。
- 当前不建 `bank_accounts` 表，先假设客户已有默认银行扣款授权。
- `SimulatedBankDebitGateway` 默认返回 `SUCCESS`；配置成 `FAILED` 时不会入账，失败交给 DelayJob retry/DEAD 路径记录。
- 自动扣款成功后用确定性幂等键 `auto-debit:{statementId}` 调用 `RepaymentService.receive(...)`。
- `POST /api/repayments` 通过 `Idempotency-Key` 防止重复还款。
- `RepaymentService.receive(...)` 在同一 transaction boundary 内更新 `repayments`、`credit_accounts.posted_balance` 和 `statements.paid_amount/status`。
- 锁顺序保持 `credit account row lock -> statement row lock`，避免和账单生成流程产生相反锁顺序。
- `repayment.received` 通过 Outbox 发布，Notification 已消费它创建 `REPAYMENT_RECEIVED` 通知。
- 当前不支持 overpayment、多账单自动分摊、真实银行资金清算和 ledger 分录。

## 7. 正向流程总图

```text
1. Cardholder buys goods
   Cardholder -> Merchant

2. Authorization request
   Merchant -> Acquirer -> Network -> Issuer
   Issuer checks card/account/risk/limit
   Authorization APPROVED
   reservedAmount increases

3. Presentment / Clearing
   Acquirer -> Network -> Issuer
   Issuer validates original authorization
   Authorization POSTED
   CardTransaction POSTED
   reservedAmount decreases
   postedBalance increases

4. Statement generation
   Issuer billing batch
   posted transactions -> statement
   statement dueDate -> AUTO_REPAYMENT DelayJob

5. Payment
   Auto debit / Cardholder payment -> Issuer
   outstanding balance decreases
   available credit restored
```

## 8. 分支例子一：Authorization Reversal 授权撤销

场景：

```text
便利店收银机授权成功后，交易取消。
```

谁主动：

```text
Merchant / Acquirer
```

谁响应：

```text
Issuer
```

Issuer 做什么：

```text
Authorization APPROVED -> REVERSED 或 CANCELED
reservedAmount -= amount
```

注意：

- 这是入账前撤销 hold。
- 不应该产生 posted card transaction。
- 当前项目还没实现这个分支。

## 9. 分支例子二：Authorization Expiry 授权过期

场景：

```text
授权批准后，商户一直没有提交 presentment。
```

谁主动：

```text
Issuer backend scheduled job
```

谁响应：

```text
Issuer authorization/account domain
```

Issuer 做什么：

```text
Authorization APPROVED -> EXPIRED
reservedAmount -= amount
```

这是当前项目已经实现的 `DelayJob` 场景。

## 10. 分支例子三：Refund 退款

场景：

```text
持卡人买了 1,000 JPY 商品，交易已经入账，后来退货。
```

退款发生在 posting 之后。

谁主动：

```text
Merchant / Acquirer / Network
```

谁响应：

```text
Issuer
```

Issuer 做什么：

```text
Original CardTransaction: POSTED
Refund record received
Create refund transaction or adjustment
postedBalance -= refundAmount
```

可能状态：

```text
POSTED -> PARTIALLY_REFUNDED
POSTED -> REFUNDED
```

或者更审计友好的做法：

```text
Original purchase transaction remains POSTED
Create a separate REFUND transaction linked to original transaction
```

面试里更推荐第二种，因为 append-only 更容易审计。

## 11. 分支例子四：Clearing Reversal / Adjustment 清分冲正

场景：

```text
清分文件里发现上一条 presentment 错了，需要冲正。
```

它和 refund 不完全一样。

Refund 是客户/商户业务退款。

Reversal / adjustment 更像清分或操作层面的纠错。

Issuer 可能做：

```text
找到原 card transaction
创建 reversal/adjustment transaction
修正 postedBalance
记录 reason code
保留原交易和冲正交易的关联
```

面试重点：

- 不要物理删除原交易。
- 用新 transaction 或 adjustment 保留 audit trail。
- 幂等键通常来自 network clearing record id。

## 12. 分支例子五：Dispute / Chargeback 争议

场景：

```text
持卡人说这笔消费不是本人交易，发起争议。
```

参与方更多：

```text
Cardholder
Issuer
Network
Acquirer
Merchant
```

Issuer 可能做：

- 记录 dispute case。
- 临时给持卡人 provisional credit。
- 通过网络发起 chargeback。
- 等待商户举证。
- 最终决定持卡人是否需要还这笔钱。

这通常是独立的 `dispute` domain，不建议塞进 authorization 或 transaction 的基本 posting 流程。

## 13. Ledger、流水、对账分别是什么

### CardTransaction 流水

回答：

```text
用户看见了哪些消费/退款/调整？
```

用于：

- APP 明细。
- 客服查询。
- 账单生成。

### Ledger 账本

回答：

```text
内部会计科目如何借贷变化？
```

例子：

```text
Debit  Cardholder Receivable        1,000
Credit Network Settlement Payable   1,000
```

Ledger 更偏财务一致性，通常要求 double-entry 和 append-only。

### Reconciliation 对账

回答：

```text
内部记录和外部网络/银行/清算文件是否一致？
```

会发现：

- missing record。
- duplicate record。
- amount mismatch。
- status mismatch。
- settlement amount mismatch。

所以它们不是一个东西。

推荐实现顺序：

```text
CardTransaction
-> Statement
-> Payment
-> Minimal Ledger
-> Reconciliation
```

## 14. PayPay Card 作为发卡方最该关注什么

PayPay Card 是 issuer 视角时，最关注的是：

```text
外部请求进来后，如何保护持卡人账户、额度、账务和风控一致性。
```

不是最先关注商户怎么 capture，也不是先关注 settlement cash movement。

## 15. Issuer 需要接收哪些请求

### 15.1 Authorization request

来自：

```text
Network / Acquirer / Merchant
```

PayPay Card 后台处理：

- 校验 card。
- 校验 account。
- 校验额度。
- 调用 risk。
- 幂等处理。
- row lock 防并发超额。
- 生成 authorization audit record。
- 成功时 reserve credit。

输出：

```text
APPROVED / DECLINED
decline reason
```

### 15.2 Presentment / Clearing record

来自：

```text
Network / clearing file / acquirer
```

PayPay Card 后台处理：

- 用 network transaction id 做幂等。
- 找到原 authorization。
- 校验授权状态和金额。
- 把 hold 转成 posted transaction。
- 更新账户 `reservedAmount` 和 `postedBalance`。
- 产生 `CardTransaction`。

输出：

```text
CardTransaction POSTED
authorization POSTED
```

### 15.3 Refund / Reversal record

来自：

```text
Network / acquirer / merchant
```

PayPay Card 后台处理：

- 找到原 card transaction。
- 校验退款金额不超过可退款金额。
- 创建 refund/reversal transaction 或 adjustment。
- 更新 posted balance。
- 保留 audit trail。

输出：

```text
REFUND / REVERSAL recorded
account balance adjusted
```

### 15.4 Payment received

来自：

```text
Bank debit result / user payment / internal payment processor
```

PayPay Card 后台处理：

- 幂等识别 payment event。
- 更新 outstanding balance。
- 更新 statement paid amount。
- 恢复 available credit。
- 处理失败、重复、部分还款、多还款。

输出：

```text
Payment recorded
statement/account updated
```

### 15.5 Dispute / chargeback event

来自：

```text
Cardholder / network / acquirer
```

PayPay Card 后台处理：

- 建立 dispute case。
- 冻结或标记相关 transaction。
- 可能发放 provisional credit。
- 处理网络规则、证据、最终责任归属。

这属于更后面的阶段。

## 16. Issuer 后台最重要的工程关注点

### 16.1 Idempotency

所有外部状态变更请求都可能重试：

- Authorization retry。
- Presentment duplicate。
- Refund duplicate。
- Payment callback duplicate。

设计原则：

```text
外部 id / idempotency key + unique constraint
```

### 16.2 Row lock 和 transaction boundary

核心账户余额不能靠内存锁。

多实例部署时必须依赖数据库或分布式一致性机制。

本项目当前用：

```text
SELECT ... FOR UPDATE
```

保护同一 credit account 的额度变化。

### 16.3 Explicit state transition

状态变化必须显式：

```text
Authorization PENDING -> APPROVED -> POSTED
Authorization APPROVED -> EXPIRED
CardTransaction PENDING -> POSTED
```

不要用一个 boolean 表达复杂生命周期。

### 16.4 Audit trail

金融系统不能只保存最终余额。

需要能回答：

- 为什么这笔钱被占用？
- 哪个外部请求导致入账？
- 是否重复请求？
- 是否有退款或冲正？
- 当时风控和额度判断是什么？

### 16.5 Eventual consistency

通知、风控投影、运营报表可以异步。

授权、入账、还款等资金/额度核心路径要在明确 transaction boundary 内完成。

### 16.6 Failure recovery

需要考虑：

- Kafka 挂了。
- consumer 重复消费。
- scheduler 宕机。
- presentment 文件重复投递。
- payment callback 重复或乱序。

这就是 Outbox、Inbox、DelayJob、幂等键和状态机存在的原因。

## 17. 映射到当前项目

当前项目已经覆盖：

```text
Authorization
-> Credit hold
-> Presentment Posting
-> CardTransaction
-> Statement
-> Repayment
-> Authorization Expiry
-> Outbox/Kafka notification and risk projection
```

当前项目还没覆盖：

```text
Refund / reversal
Ledger
Reconciliation
Settlement cash movement
Dispute / chargeback
```

最自然的后续路线：

```text
1. Refund / reversal：围绕 CardTransaction 和 Statement 生命周期扩展
2. Statement due/overdue：处理到期、逾期和最低还款判断
3. Minimal Ledger：记录 posting、repayment 等内部借贷分录
4. Reconciliation：对比外部清算/资金文件
5. Settlement cash movement / dispute：补齐资金移动和争议处理分支
```

## 18. 面试一句话总结

可以这样讲：

> 信用卡发卡后台的核心不是“商户 capture”，而是 issuer 如何处理 authorization、presentment posting、账单和还款。Authorization 负责实时批准和额度 hold；Presentment/Clearing 到达后，Issuer 把 hold 转成 CardTransaction 并入账；之后 refund、statement、payment、ledger、reconciliation 都围绕已入账交易和账户余额继续演进。每一步都要考虑 idempotency、row lock、transaction boundary、audit trail 和 failure recovery。
