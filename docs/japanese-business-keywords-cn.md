# 日语业务关键词注音表

这个文档集中整理代码注释里出现的日语关键词。代码类头里也会保留假名注音；这里作为复习索引，方便面试前快速过一遍信用卡业务和后端可靠性词汇。

选词原则：信用卡业务词优先使用日本支付/信用卡文档里真实出现的说法。例如 Stripe 日本语文档使用 `オーソリ`、`キャプチャー`、`売上処理`、`リリース`，PayPay Card 官方页面使用 `お支払い口座`、`引き落とし`、`ご利用可能額`、`ご利用可能枠`、`請求明細`。因此避免使用把英文或中文逐字直译成日语的旧称，尤其是授权释放、额度占用、自动扣款这类高频业务词。

## 授权与额度

| 日语 | 假名 | 中文 | English |
| --- | --- | --- | --- |
| オーソリ | オーソリ | 授权 | authorization |
| オーソリ処理 | オーソリしょり | 授权处理 | authorization processing |
| オーソリ承認 | オーソリしょうにん | 授权批准 | authorization approved |
| オーソリ拒否 | オーソリきょひ | 授权拒绝 | authorization declined |
| オーソリ期限切れ | オーソリきげんぎれ | 授权过期 | authorization expired |
| オーソリのリリース | オーソリのリリース | 授权释放 | authorization release |
| 金額の確保 | きんがくのかくほ | 金额占用/保证 | amount hold |
| 利用可能額の確保 | りようかのうがくのかくほ | 可用额度占用 | available amount hold |
| ご利用可能額不足 | ごりようかのうがくぶそく | 可用额度不足 | insufficient available amount |
| 利用可能枠 | りようかのうわく | 可用额度 | available credit |
| ご利用可能額 | ごりようかのうがく | 可用金额 | available amount |
| 利用枠管理 | りようわくかんり | 额度管理 | credit line management |

## 交易与入账

| 日语 | 假名 | 中文 | English |
| --- | --- | --- | --- |
| 取引 | とりひき | 交易 | transaction |
| 取引状態 | とりひきじょうたい | 交易状态 | transaction status |
| 売上 | うりあげ | 销售/请款 | presentment/sales |
| 売上データ | うりあげデータ | 入账数据/请款数据 | presentment data |
| 売上処理 | うりあげしょり | 入账处理 | posting/capture processing |
| 売上処理エラー | うりあげしょりエラー | 入账处理错误 | presentment error |
| 売上データ重複 | うりあげデータじゅうふく | 入账数据重复 | duplicate presentment data |
| オーソリ済み取引 | オーソリずみとりひき | 已授权交易 | authorized transaction |
| 取引スナップショット | とりひきスナップショット | 交易快照 | transaction snapshot |

## 账单与还款

| 日语 | 假名 | 中文 | English |
| --- | --- | --- | --- |
| 請求 | せいきゅう | 账单/请款 | statement/billing |
| 請求明細作成 | せいきゅうめいさいさくせい | 生成账单明细 | statement generation |
| 請求確定 | せいきゅうかくてい | 账单确认/关账 | statement close |
| 請求明細 | せいきゅうめいさい | 账单明细 | statement item |
| 請求対象取引 | せいきゅうたいしょうとりひき | 账单对象交易 | billable transaction |
| 請求明細への紐づけ | せいきゅうめいさいへのひもづけ | 关联到账单明细 | statement assignment |
| 締め日 | しめび | 关账日 | close day |
| 支払日 | しはらいび | 付款日 | payment due date |
| 支払基準日 | しはらいきじゅんび | 付款基准日 | payment base day |
| 支払い済み | しはらいずみ | 已支付 | paid |
| 支払い | しはらい | 支付/还款 | payment |
| 入金 | にゅうきん | 入金/到账 | payment received |
| 入金処理 | にゅうきんしょり | 入金/入账处理 | payment posting |
| 入金イベント | にゅうきんイベント | 入金事件 | payment event |
| 請求残高 | せいきゅうざんだか | 账单余额 | statement remaining balance |

## 银行扣款与营业日

| 日语 | 假名 | 中文 | English |
| --- | --- | --- | --- |
| 口座振替 | こうざふりかえ | 银行账户自动扣款 | bank debit |
| 引き落とし | ひきおとし | 银行扣款 | account debit |
| 自動引き落とし | じどうひきおとし | 自动扣款 | automatic debit |
| お支払い口座 | おしはらいこうざ | 付款账户 | payment account |
| 引き落とし口座 | ひきおとしこうざ | 扣款账户 | debit account |
| 口座振替予定 | こうざふりかえよてい | 自动扣款计划 | debit scheduling |
| 振替依頼 | ふりかえいらい | 扣款请求 | debit request |
| 振替結果 | ふりかえけっか | 扣款结果 | debit result |
| 振替失敗 | ふりかえしっぱい | 扣款失败 | debit failure |
| 営業日 | えいぎょうび | 营业日 | business day |
| 翌営業日 | よくえいぎょうび | 下一个营业日 | next business day |
| 祝日 | しゅくじつ | 节假日 | public holiday |
| 国民の祝日 | こくみんのしゅくじつ | 日本国民节假日 | national holiday |
| 振替休日 | ふりかえきゅうじつ | 调休假日 | substitute holiday |
| 国民の休日 | こくみんのきゅうじつ | 国民休息日 | citizens' holiday |

## 异步可靠性与并发

| 日语 | 假名 | 中文 | English |
| --- | --- | --- | --- |
| 遅延ジョブ | ちえんジョブ | 延迟任务 | delay job |
| ジョブ種別 | ジョブしゅべつ | 任务类型 | job type |
| ジョブ状態 | ジョブじょうたい | 任务状态 | job status |
| 定期実行 | ていきじっこう | 定时执行 | scheduled execution |
| 行ロック | ぎょうロック | 行锁 | row lock |
| 処理中リース | しょりちゅうリース | 处理中租约 | processing lease |
| リース検証 | リースけんしょう | 租约校验 | lease validation |
| 明示的トランザクション | めいじてきトランザクション | 显式事务 | explicit transaction |
| 境界 | きょうかい | 边界 | boundary |
| 冪等性 | べきとうせい | 幂等性 | idempotency |
| 冪等衝突 | べきとうしょうとつ | 幂等冲突 | idempotency conflict |
| 重複依頼 | じゅうふくいらい | 重复请求 | duplicate request |
| 重複配信 | じゅうふくはいしん | 重复投递 | duplicate delivery |
| アウトボックス | アウトボックス | Outbox | outbox |
| 確実発行 | かくじつはっこう | 可靠发布 | reliable publication |
| 確認応答 | かくにんおうとう | 确认应答 | acknowledgement |
| デッドレター | デッドレター | 死信 | dead letter |

## 风控与通知

| 日语 | 假名 | 中文 | English |
| --- | --- | --- | --- |
| リスク評価 | リスクひょうか | 风控评估 | risk assessment |
| リスク判定 | リスクはんてい | 风控决策 | risk decision |
| リスク拒否 | リスクきょひ | 风控拒绝 | risk decline |
| 外部審査 | がいぶしんさ | 外部风控审核 | external risk review |
| 越境取引 | えっきょうとりひき | 跨境交易 | cross-border transaction |
| 通知 | つうち | 通知 | notification |
| 通知依頼 | つうちいらい | 通知请求 | notification request |
| 通知状態 | つうちじょうたい | 通知状态 | notification status |
| 配信 | はいしん | 投递/发送 | delivery |
| 配信失敗 | はいしんしっぱい | 投递失败 | delivery failure |
| 宛先 | あてさき | 收件人/目的地 | recipient |

## 参考来源

- [Stripe 日本语文档：支払い方法を保留する](https://docs.stripe.com/payments/place-a-hold-on-a-payment-method?locale=ja-JP)：オーソリ、キャプチャー、売上処理、リリース。
- [PayPay Card：お支払い方法](https://www.paypay-card.co.jp/service/payment/)：お支払い方法、利用明細。
- [PayPay Card：引き落とし口座に登録できる銀行](https://www.paypay-card.co.jp/service/bank-list/)：お支払い口座、引き落とし口座、預金口座振替依頼書。
- [PayPay Card：引き落とし後、ご利用可能額はいつ反映されますか](https://www.paypay-card.co.jp/service/000253.html)：ご利用可能額、ご利用可能枠、請求明細。
