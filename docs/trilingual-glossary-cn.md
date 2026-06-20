# 中英日三语关键词表

这个文档把项目注释、学习文档和interview复习中反复出现的关键词统一整理成中文、English、日本語三语对照。它不再只是日语注音表，而是一个 credit card backend + Spring/Java + system design glossary。

表格列说明：

- 中文：interview时中文解释的主入口。
- English：代码、官方文档、interview追问里常见的英文锚点。
- 日本語：尽量使用日本信用卡、支付和技术文档中自然的说法，不做机械直译。
- 假名/读法：帮助快速读出日语词；外来语保留片假名或英文读法。
- 备注：说明本项目里的对应场景，避免背词和代码脱节。

## 业务词汇

### 信用卡授权与额度

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 授权 | authorization | オーソリ | オーソリ | 持卡消费时先确认卡和额度是否可用。 |
| 授权处理 | authorization processing | オーソリ処理 | オーソリしょり | `AuthorizationService` 主用例。 |
| 授权批准 | authorization approved | オーソリ承認 | オーソリしょうにん | 额度已被占用，后续等待 presentment。 |
| 授权拒绝 | authorization declined | オーソリ拒否 | オーソリきょひ | 风控、卡状态、额度不足都会导致拒绝。 |
| 授权过期 | authorization expired | オーソリ期限切れ | オーソリきげんぎれ | DelayJob 到期后释放占用额度。 |
| 授权释放 | authorization release | オーソリのリリース | オーソリのリリース | 比旧的直译说法更贴近支付文档语境。 |
| 金额占用 | amount hold | 金額の確保 | きんがくのかくほ | Stripe 日本语文档中使用的表达。 |
| 可用额度占用 | available amount hold | 利用可能額の確保 | りようかのうがくのかくほ | 授权批准时减少可用额度。 |
| 可用额度 | available credit | 利用可能枠 | りようかのうわく | PayPay Card 页面常见持卡人用语。 |
| 可用金额 | available amount | ご利用可能額 | ごりようかのうがく | 面向客户时更自然。 |
| 可用金额不足 | insufficient available amount | ご利用可能額不足 | ごりようかのうがくぶそく | 替代偏书面、偏授信系统内部的旧说法。 |
| 额度管理 | credit line management | 利用枠管理 | りようわくかんり | 对应 `CreditAccount` 的额度状态维护。 |

### 交易、入账、账单、扣款

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 交易 | transaction | 取引 | とりひき | `CardTransaction` 是发卡侧交易流水。 |
| 交易状态 | transaction status | 取引状態 | とりひきじょうたい | PENDING、POSTED、BILLED 等状态。 |
| 请款/入账数据 | presentment data | 売上データ | うりあげデータ | 网络或商户送来的正式请款数据。 |
| 入账处理 | posting / capture processing | 売上処理 | うりあげしょり | 比会计口吻更重的说法更贴近日常支付处理语境。 |
| 入账处理错误 | presentment error | 売上処理エラー | うりあげしょりエラー | presentment 不符合业务规则。 |
| 入账数据重复 | duplicate presentment data | 売上データ重複 | うりあげデータじゅうふく | 幂等冲突或网络重复送信。 |
| 已授权交易 | authorized transaction | オーソリ済み取引 | オーソリずみとりひき | 授权后等待或完成売上処理的交易。 |
| 交易快照 | transaction snapshot | 取引スナップショット | とりひきスナップショット | 账单生成时冻结历史数据。 |
| 账单/请款 | statement / billing | 請求 | せいきゅう | 持卡人应付金额和明细。 |
| 账单生成 | statement generation | 請求明細作成 | せいきゅうめいさいさくせい | 月批处理生成 statement 和 item。 |
| 账单确认/关账 | statement close | 請求確定 | せいきゅうかくてい | 月度账单金额固定下来。 |
| 账单明细 | statement item | 請求明細 | せいきゅうめいさい | PayPay Card 页面也使用这个词。 |
| 账单对象交易 | billable transaction | 請求対象取引 | せいきゅうたいしょうとりひき | posted 但尚未进入账单的交易。 |
| 关联到账单明细 | statement assignment | 請求明細への紐づけ | せいきゅうめいさいへのひもづけ | 替代机械直译的“分配”口吻。 |
| 关账日 | close day | 締め日 | しめび | 本项目默认每月固定日批处理。 |
| 付款日 | payment due date | 支払日 | しはらいび | 本项目按 27 日并顺延日本营业日。 |
| 付款账户 | payment account | お支払い口座 | おしはらいこうざ | PayPay Card 页面常见说法。 |
| 扣款账户 | debit account | 引き落とし口座 | ひきおとしこうざ | 银行账户自动扣款来源。 |
| 自动扣款 | automatic debit | 自動引き落とし | じどうひきおとし | 比贷款语境更重的说法更贴近信用卡账单付款。 |
| 账户振替 | account transfer / bank debit | 口座振替 | こうざふりかえ | 日本银行自动扣款常见术语。 |
| 扣款计划 | debit scheduling | 口座振替予定 | こうざふりかえよてい | due date DelayJob 的业务动作。 |
| 扣款请求 | debit request | 振替依頼 | ふりかえいらい | 调用 BankDebitGateway 前的请求。 |
| 扣款结果 | debit result | 振替結果 | ふりかえけっか | 当前模拟成功，保留失败路径。 |
| 扣款失败 | debit failure | 振替失敗 | ふりかえしっぱい | 后续可接 retry、通知、滞纳逻辑。 |
| 入金/到账 | payment received | 入金 | にゅうきん | 还款到账的后台处理语。 |
| 入金处理 | payment posting | 入金処理 | にゅうきんしょり | `RepaymentService` 的核心动作。 |
| 已支付 | paid | 支払い済み | しはらいずみ | statement remaining amount 为 0。 |
| 账单余额 | statement remaining balance | 請求残高 | せいきゅうざんだか | 尚未支付的账单金额。 |
| 营业日 | business day | 営業日 | えいぎょうび | 计算扣款日顺延。 |
| 下一个营业日 | next business day | 翌営業日 | よくえいぎょうび | 27 日遇周末或日本节假日时顺延。 |
| 日本法定节假日 | national holiday | 国民の祝日 | こくみんのしゅくじつ | 来自日本内阁府节假日规则。 |
| 调休假日 | substitute holiday | 振替休日 | ふりかえきゅうじつ | 周日节假日后的替代休假。 |

### 风控与通知

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 风控评估 | risk assessment | リスク評価 | リスクひょうか | 授权前的风险输入和规则判断。 |
| 风控决策 | risk decision | リスク判定 | リスクはんてい | APPROVE / DECLINE。 |
| 风控拒绝 | risk decline | リスク拒否 | リスクきょひ | 非技术异常，是业务拒绝。 |
| 外部风控审核 | external risk review | 外部審査 | がいぶしんさ | 模拟真实卡系统可扩展外部审核。 |
| 跨境交易 | cross-border transaction | 越境取引 | えっきょうとりひき | 风控特征之一。 |
| 通知 | notification | 通知 | つうち | 授权、账单、扣款结果都可能触发。 |
| 通知请求 | notification request | 通知依頼 | つうちいらい | notification bounded context 的输入。 |
| 通知状态 | notification status | 通知状態 | つうちじょうたい | PENDING、SENT、FAILED 等。 |
| 配信/发送 | delivery | 配信 | はいしん | 邮件、Push、站内信都可抽象为 delivery。 |
| 配信失败 | delivery failure | 配信失敗 | はいしんしっぱい | 后续可进入 retry 或 dead letter。 |
| 收件人 | recipient | 宛先 | あてさき | notification recipient key。 |

## 技术词汇

### Spring、Java、API

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 控制器 | controller | コントローラー | コントローラー | 只处理 HTTP、validation、response mapping。 |
| 应用服务 | application service | アプリケーションサービス | アプリケーションサービス | use case orchestration 和 transaction boundary。 |
| 领域对象 | domain object | ドメインオブジェクト | ドメインオブジェクト | 放业务状态和状态迁移。 |
| 聚合 | aggregate | 集約 | しゅうやく | DDD 中的一致性边界。 |
| 仓储 | repository | リポジトリ | リポジトリ | domain 需要的持久化端口。 |
| 端口 | port | ポート | ポート | application/domain 暴露的接口。 |
| 适配器 | adapter | アダプター | アダプター | infrastructure 对 port 的实现。 |
| 请求 DTO | request DTO | リクエストDTO | リクエストDTO | API boundary 入参。 |
| 响应 DTO | response DTO | レスポンスDTO | レスポンスDTO | API boundary 出参。 |
| 输入校验 | validation | 入力検証 | にゅうりょくけんしょう | Bean Validation + domain invariant。 |
| Bean | bean | Bean / ビーン | ビーン | Spring 容器管理的对象。 |
| 依赖注入 | dependency injection | 依存性注入 | いぞんせいちゅうにゅう | Spring IoC 的核心使用方式。 |
| 控制反转 | inversion of control | 制御の反転 | せいぎょのはんてん | 对象创建和依赖连接交给容器。 |
| 应用上下文 | application context | アプリケーションコンテキスト | アプリケーションコンテキスト | `ApplicationContext`。 |
| 组件扫描 | component scan | コンポーネントスキャン | コンポーネントスキャン | 发现 `@Component`、`@Service` 等 Bean。 |
| 配置属性 | configuration properties | 設定プロパティ | せっていプロパティ | `@ConfigurationProperties` 绑定配置。 |
| 注解 | annotation | アノテーション | アノテーション | `@Transactional`、`@Scheduled` 等。 |
| 声明式事务 | declarative transaction | 宣言的トランザクション | せんげんてきトランザクション | `@Transactional`。 |
| 编程式事务 | programmatic transaction | プログラムによるトランザクション管理 | プログラムによるトランザクションかんり | `TransactionTemplate`/`TransactionOperations`。 |
| 事务传播 | transaction propagation | トランザクション伝播 | トランザクションでんぱ | interview常问 `REQUIRED`、`REQUIRES_NEW`。 |
| 代理 | proxy | プロキシ | プロキシ | Spring AOP 和事务拦截依赖 proxy。 |
| 自调用 | self-invocation | 自己呼び出し | じこよびだし | 同类内部调用绕过 Spring proxy。 |
| 异常处理 | exception handling | 例外処理 | れいがいしょり | 区分业务拒绝、冲突、系统失败。 |
| 枚举 | enum | 列挙型 | れっきょがた | 状态和拒绝原因常用。 |
| record | record | レコード | レコード | Java 不可变数据载体。 |
| Builder | builder | ビルダー | ビルダー | 复杂对象构造，避免参数过长。 |
| Optional | Optional | Optional / オプショナル | オプショナル | 表达可能不存在，不替代业务判断。 |
| BigDecimal | BigDecimal | BigDecimal | ビッグデシマル | 金额字段，避免浮点误差。 |
| UUID | UUID | UUID | ユーアイディー | 事件、授权、交易等内部 ID。 |

### MyBatis、MySQL、事务与并发

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| MyBatis | MyBatis | MyBatis | マイバティス | 本项目主要 persistence framework。 |
| Mapper | mapper | マッパー | マッパー | Java interface + XML SQL。 |
| XML Mapper | XML mapper | Mapper XML ファイル | マッパーXMLファイル | MyBatis 官方日文文档使用的表达。 |
| 动态 SQL | dynamic SQL | 動的SQL | どうてきSQL | `<if>`、`<foreach>` 等。 |
| 行映射 | row mapping | 行マッピング | ぎょうマッピング | DB row 到 domain/DTO 的转换。 |
| 结果映射 | result map | 結果マップ | けっかマップ | MyBatis `resultMap`。 |
| 预编译语句 | prepared statement | プリペアドステートメント | プリペアドステートメント | 防 SQL injection，复用执行计划。 |
| 事务边界 | transaction boundary | トランザクション境界 | トランザクションきょうかい | 金融后台必须能讲清楚。 |
| 行锁 | row lock | 行ロック / レコードロック | ぎょうロック / レコードロック | MySQL 文档称 record lock。 |
| 锁定读 | locking read | ロック読み取り | ロックよみとり | `SELECT ... FOR UPDATE`。 |
| FOR UPDATE | FOR UPDATE | FOR UPDATE | フォーアップデート | 查询并锁住将被更新的行。 |
| 跳过已锁行 | SKIP LOCKED | SKIP LOCKED | スキップロックド | poller 多实例并发 claim。 |
| 唯一约束 | unique constraint | 一意制約 | いちいせいやく | idempotency key、job key。 |
| 索引 | index | インデックス | インデックス | 锁粒度和查询性能都依赖索引。 |
| 间隙锁 | gap lock | ギャップロック | ギャップロック | InnoDB 范围锁相关。 |
| next-key 锁 | next-key lock | ネクストキーロック | ネクストキーロック | record lock + gap lock。 |
| 死锁 | deadlock | デッドロック | デッドロック | 靠固定锁顺序、短事务、幂等重试降低风险。 |
| 锁竞争 | lock contention | ロック競合 | ロックきょうごう | 热点账户或批处理高并发常见问题。 |
| 隔离级别 | isolation level | 分離レベル | ぶんりレベル | 不能只背名字，要结合 SQL。 |
| 提交 | commit | コミット | コミット | 事务成功落库。 |
| 回滚 | rollback | ロールバック | ロールバック | 事务失败撤销。 |
| 幂等性 | idempotency | 冪等性 | べきとうせい | 重试不会重复扣款或占额。 |
| 幂等键 | idempotency key | 冪等キー | べきとうキー | 请求级去重 key。 |
| 请求指纹 | request fingerprint | リクエストフィンガープリント | リクエストフィンガープリント | 检测相同 key 携带不同请求体。 |

### Kafka、异步可靠性、Scheduler、系统设计

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 领域事件 | domain event | ドメインイベント | ドメインイベント | aggregate 状态变化产生的业务事实。 |
| 集成事件 | integration event | 連携イベント | れんけいイベント | 对外发布给其他 bounded context。 |
| Outbox | transactional outbox | アウトボックス | アウトボックス | 解决 DB + Kafka dual-write。 |
| Inbox | consumer inbox | インボックス | インボックス | consumer side idempotency。 |
| 最终一致性 | eventual consistency | 結果整合性 | けっかせいごうせい | 异步副作用不和主事务一起完成。 |
| 至少一次投递 | at-least-once delivery | 少なくとも1回の配信 | すくなくともいっかいのはいしん | 允许重复，consumer 必须幂等。 |
| 重复投递 | duplicate delivery | 重複配信 | じゅうふくはいしん | Kafka redelivery、Outbox retry 都可能发生。 |
| 重试 | retry | リトライ | リトライ | 短暂失败后再执行。 |
| 退避 | backoff | バックオフ | バックオフ | 避免失败后疯狂重试。 |
| 租约 | lease | リース | リース | PROCESSING 状态不是永久占用。 |
| 处理中租约 | processing lease | 処理中リース | しょりちゅうリース | stuck recovery 的判断依据。 |
| 定时任务 | scheduled task | 定期実行タスク | ていきじっこうタスク | `@Scheduled`。 |
| 调度器 | scheduler | スケジューラー | スケジューラー | 负责 poll/claim，不直接做重业务。 |
| 轮询器 | poller | ポーラー | ポーラー | 扫描 due rows 或 publishable rows。 |
| 工作者 | worker | ワーカー | ワーカー | 执行业务动作或 Kafka publish。 |
| 恢复器 | recoverer | リカバラー | リカバラー | 找回卡在 PROCESSING 的任务。 |
| 线程池 | thread pool | スレッドプール | スレッドプール | 控制并发和资源占用。 |
| 队列容量 | queue capacity | キュー容量 | キューようりょう | worker pool 背压入口。 |
| 主题 | topic | トピック | トピック | Kafka 消息分类。 |
| 分区 | partition | パーティション | パーティション | Kafka 顺序和并行度边界。 |
| 分区键 | partition key | パーティションキー | パーティションキー | 同一 aggregate 事件进入同一 partition。 |
| 消费者组 | consumer group | コンシューマーグループ | コンシューマーグループ | 多实例共享消费进度。 |
| offset | offset | オフセット | オフセット | consumer 读取位置。 |
| 确认应答 | acknowledgement | 確認応答 | かくにんおうとう | producer 等 broker ack，consumer 处理后提交。 |
| 死信主题 | dead-letter topic | デッドレタートピック | デッドレタートピック | 隔离无法自动恢复的坏消息。 |
| 有界上下文 | bounded context | 境界づけられたコンテキスト | きょうかいづけられたコンテキスト | DDD 边界，避免概念互相污染。 |
| 水平扩展 | horizontal scaling | 水平スケーリング | すいへいスケーリング | 多实例 poller/worker/Kafka consumer。 |
| 吞吐量 | throughput | スループット | スループット | 单位时间处理能力。 |
| 延迟 | latency | レイテンシ | レイテンシ | 请求或消息端到端耗时。 |
| 可用性 | availability | 可用性 | かようせい | 服务能否持续响应。 |
| 背压 | backpressure | バックプレッシャー | バックプレッシャー | 下游慢时控制上游速度。 |
| 健康检查 | health check | ヘルスチェック | ヘルスチェック | liveness/readiness 的入口。 |
| 可观测性 | observability | オブザーバビリティ | オブザーバビリティ | logs、metrics、traces。 |

## 参考来源

- [Stripe 日本语文档：支払い方法を保留する](https://docs.stripe.com/payments/place-a-hold-on-a-payment-method?locale=ja-JP)：オーソリ、キャプチャー、売上処理、リリース。
- [PayPay Card：お支払い方法](https://www.paypay-card.co.jp/service/payment/)：お支払い方法、利用明細。
- [PayPay Card：引き落とし口座に登録できる銀行](https://www.paypay-card.co.jp/service/bank-list/)：お支払い口座、引き落とし口座、預金口座振替依頼書。
- [PayPay Card：引き落とし後、ご利用可能額はいつ反映されますか](https://www.paypay-card.co.jp/service/000253.html)：ご利用可能額、ご利用可能枠、請求明細。
- [Spring Framework Reference：Dependency Injection](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html)：Bean、ApplicationContext、dependency injection。
- [Spring Framework Reference：Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)：declarative transaction、transaction propagation、`@Transactional`。
- [Java SE 21 日本語 API](https://docs.oracle.com/javase/jp/21/docs/api/index.html)：Java 标准库、record、Optional、BigDecimal、UUID 等 API 名称。
- [MyBatis 3 日本語ドキュメント](https://mybatis.org/mybatis-3/ja/index.html)：Mapper XML ファイル、動的 SQL、マッピング。
- [MySQL 8.0 日本語リファレンス：InnoDB ロック](https://dev.mysql.com/doc/refman/8.0/ja/innodb-locking.html)：レコードロック、ギャップロック、ネクストキーロック。
- [Apache Kafka Documentation](https://kafka.apache.org/documentation/)：topic、partition、consumer group、offset、delivery semantics。
