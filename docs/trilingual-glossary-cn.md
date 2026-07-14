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
| 账单明细 | statement line / statement item | 請求明細 | せいきゅうめいさい | 当前代码和表名使用 `StatementLine` / `statement_lines`；业务讨论里也常说 statement item。 |
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
| 通知 | notification | 通知 | つうち | 当前由授权决策或交易入账事件触发。 |
| 通知请求 | notification request | 通知依頼 | つうちいらい | notification bounded context 的输入。 |
| 通知意图 | notification intent | 通知意図 | つうちいと | 不可变的“通知谁、关于什么、为什么通知”。 |
| 投递状态 | delivery status | 配信状態 | はいしんじょうたい | PENDING、PROCESSING、SENT、DEAD。 |
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
| 存活探针 | liveness probe | liveness probe | ライブネスプローブ | 判断实例是否应该被重启。 |
| 就绪探针 | readiness probe | readiness probe | レディネスプローブ | 判断实例是否应该接收流量。 |
| 可观测性 | observability | オブザーバビリティ | オブザーバビリティ | logs、metrics、traces。 |
| JVM 堆内存 | JVM heap memory | JVM ヒープメモリ | ヒープメモリ | Java object 主要分配区域。 |
| 垃圾回收暂停 | GC pause | GC ポーズ | ジーシーポーズ | GC 导致应用线程短暂停顿。 |
| 元空间 | metaspace | メタスペース | メタスペース | 类元数据所在的 non-heap 内存区域。 |
| 运行时指标 | runtime metrics | ランタイムメトリクス | ランタイムメトリクス | JVM、process、thread、GC 等运行状态指标。 |

## 形象借喻词汇（从其他领域借来的比喻）

这一节单独收录后端/分布式/工程文化里那些**从其他领域借来、画面感很强的比喻词**——它们的字面来自墓地、厨房、战争、水利、神话……记住"源领域的那个画面"，技术含义基本就自己长出来了。按**源领域分组**，方便联想记忆。

两点说明：

- 日语里这类词**绝大多数直接用片假名音译**（外来语），所以"日本語"和"假名/读法"两列经常一样；少数有地道汉字说法的（如 `糖衣構文`、`技術的負債`、`銀の弾丸`、`踏み台`）已特别标出，面试时用汉字说法更显本地化。
- ⚠️ `缓存雪崩 / 击穿 / 穿透` 这三个是**中文技术圈的造词**，英文/日文圈没有一一对应，统称多用 `thundering herd / cache stampede`。在日本面试别默认对方听得懂"雪崩"，要会用英文锚点解释。

### 死亡・亡灵・墓地（death / undead / graveyard）

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 墓碑（删除标记） | tombstone | トゥームストーン | トゥームストーン | 墓地立碑标记"此处有死者"；删除不做物理删除，而写一个 tombstone 标记，让各副本收敛、防止旧值"复活"。本项目 L2 statement cache 正是用它配版本 CAS 防 late-write。 |
| 僵尸进程 | zombie process | ゾンビプロセス | ゾンビプロセス | 子进程已死、父进程没 `wait()` 回收，残留进程表里像活死人；引申"僵尸节点"=被判死却还在动作。 |
| 孤儿进程 | orphan process | 孤児プロセス | こじプロセス | 父进程先死，子进程被 init/PID 1 收养；也指 orphaned 资源（无主连接/文件）。 |
| 收割者 | reaper | リーパー | リーパー | 死神收割亡魂；后台 reaper 线程定期回收死亡/空闲资源（僵尸进程、过期连接）。 |
| 守护进程 | daemon | デーモン | デーモン | 希腊语"守护精灵"（**不是**基督教恶魔 demon），后台常驻默默干活；与 Maxwell's demon 同源。 |
| 幽灵记录 / 在线改表 | ghost record / gh-ost | ゴースト | ゴースト | SQL Server 标记删除尚未清理的行（tombstone 的表亲）；GitHub `gh-ost` 是在线无锁改表工具。 |
| 死信 | dead letter | デッドレター | デッドレター | 源自邮局"无法投递信件处"；无法消费/投递的消息进死信队列隔离。（异步章节已收） |

### 身体・生命・医学（body / life / medicine）

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 心跳 | heartbeat | ハートビート | ハートビート | 活体脉搏；周期性存活信号，丢失即判宕机触发 failover。日语概念也叫 `死活監視(しかつかんし)`。 |
| 握手 | handshake | ハンドシェイク | ハンドシェイク | 人见面握手互相确认；TCP 三次握手、TLS 握手建立双方确认。 |
| 无头 | headless | ヘッドレス | ヘッドレス | 没有"头"（界面）；headless 浏览器/CMS 只剩后端逻辑无 GUI。 |
| 骨干（网） | backbone | バックボーン | バックボーン | 脊椎承重；骨干网承载主干流量。 |
| 饥饿 | starvation | 飢餓状態 | きがじょうたい | 生物长期得不到食物；线程长期抢不到锁/CPU 无法推进。 |
| 占用 / 足迹 | footprint | フットプリント | フットプリント | 留下的脚印；memory/disk footprint = 资源占用量。 |
| 指纹 | fingerprint | フィンガープリント | フィンガープリント | 法医靠指纹辨人；request fingerprint 辨别同 key 不同请求体。（并发章节已收） |

### 自然・天气・地理（nature / weather / geography）

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 缓存雪崩 | cache avalanche | キャッシュ雪崩 | キャッシュなだれ | 积雪骤然崩塌；大量 key 同时过期，请求齐刷刷砸向 DB。⚠️中文造词，英文圈多统称 thundering herd / cache stampede。 |
| 缓存击穿 | hot-key miss | ホットキー失効 | ホットキーしっこう | 单个热点 key 过期瞬间洪水直达 DB。⚠️中文造词。缓解：互斥重建、逻辑过期。 |
| 缓存穿透 | cache penetration | キャッシュ貫通 | キャッシュかんつう | 查根本不存在的 key 每次绕过缓存打 DB。⚠️中文造词。缓解：空值缓存、布隆过滤器。 |
| 惊群 | thundering herd | サンダリングハード | サンダリングハード | 兽群受惊一齐狂奔；一个事件唤醒所有阻塞者 / 所有 miss 同时回源。 |
| 漂移 | drift | ドリフト | ドリフト | 船/大陆缓慢偏移；clock drift 时钟漂移、config drift 配置漂移。 |
| 热点 | hotspot | ホットスポット | ホットスポット | 局部高温/高活动；某分片/key/账户负载畸高。 |
| 冷启动 | cold start | コールドスタート | コールドスタート | 冬天冷车难发动；首次调用缓存空 / JVM 未热 / Serverless 容器未就绪。 |
| 预热 | warm-up | ウォームアップ | ウォームアップ | 运动前热身；缓存预热、JIT 预热把系统带到稳态。 |
| 级联（故障） | cascading failure | カスケード障害 | カスケードしょうがい | 瀑布逐级跌落；一处故障推倒下一个连环崩。 |
| 雪花 | snowflake | スノーフレーク | スノーフレーク | 没有两片相同；snowflake server=手工调到无法复制的反模式，Twitter Snowflake=分布式 ID。 |

### 厨房・食物（kitchen / food）

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 意大利面条代码 | spaghetti code | スパゲッティコード | スパゲッティコード | 面条缠成一团；控制流乱到无法追踪。 |
| 样板代码 | boilerplate | ボイラープレート | ボイラープレート | 旧报馆翻印的整块铅版 / 锅炉钢板；千篇一律的模板代码。 |
| 语法糖 | syntactic sugar | 糖衣構文 | とういこうぶん | 裹层糖更好入口；不增表达力、只为更易读的写法（Peter Landin 造词）。 |
| 盐 | salt | ソルト | ソルト | 做菜/腌制加盐；每个密码加随机 salt 防彩虹表。秘密盐叫 pepper（ペッパー）。 |
| 哈希 / 散列 | hash | ハッシュ | ハッシュ | "剁碎拌匀"成糊；把任意输入打散成定长摘要。 |
| Cookie | cookie | クッキー | クッキー | 源自 "magic cookie"（原样传回的小凭据）；HTTP cookie 存会话状态。 |
| 面包屑（导航） | breadcrumb | パンくずリスト | パンくずリスト | 《糖果屋》撒面包屑标路；展示当前所在层级路径。 |
| 陈旧 | stale | 陳腐化 | ちんぷか | 面包放陈；缓存/数据过了新鲜期。stale-while-revalidate=先给陈的再后台刷新。 |
| 吃自家狗粮 | dogfooding | ドッグフーディング | ドッグフーディング | "eat your own dog food"；团队自己先用自家产品。 |

### 水利・管道（water / plumbing）

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 流水线 / 管道 | pipeline | パイプライン | パイプライン | 输油/输水管道；CI/CD 或数据按阶段顺序流过。 |
| 冲刷 / 刷盘 | flush | フラッシュ | フラッシュ | 冲马桶/冲管；强制把缓冲数据写出到磁盘/网络。 |
| 排空 / 优雅下线 | drain | ドレイン | ドレイン | 放空水箱；connection draining=停接新流量、放在途请求做完再关。 |
| 汇 / 接收端 | sink | シンク | シンク | 水槽是排水终点；data sink / event sink=数据最终落地的消费端。 |
| 池 | pool | プール | プール | 蓄水池随取随用；连接池/线程池复用昂贵资源。 |
| 令牌桶 / 漏桶 | token bucket / leaky bucket | トークンバケット / リーキーバケット | トークンバケット | 桶里接令牌 / 漏桶匀速漏水；两种限流算法。 |
| 泄漏 | leak | リーク | リーク | 管道漏水；memory/resource leak=资源只借不还。 |
| 水位线 / 水印 | watermark | ウォーターマーク | ウォーターマーク | 河岸最高水位刻痕；日志 high/low watermark、Flink 事件时间 watermark。 |
| 背压 | backpressure | バックプレッシャー | バックプレッシャー | 流体反向顶推；下游慢则反压上游降速。（异步章节已收） |

### 战争・安防・间谍（war / security / espionage）

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 熔断器 | circuit breaker | サーキットブレーカー | サーキットブレーカー | 电路过载跳闸防火；依赖故障时跳闸停止调用、快速失败。 |
| 舱壁 | bulkhead | バルクヘッド | バルクヘッド | 船体水密隔舱，一舱进水不沉船；隔离资源池防一处故障拖垮全局。 |
| 金丝雀（发布） | canary release | カナリアリリース | カナリアリリース | 矿工带金丝雀先死报警；新版本先放给一小撮流量试探。 |
| 毒丸 | poison pill | ポイズンピル | ポイズンピル | 氰化物胶囊 / 反收购毒丸；特殊消息令消费者优雅停机，或"毒消息"毒死消费者。 |
| 特洛伊木马 | Trojan horse | トロイの木馬 | トロイのもくば | 希腊神话藏兵的礼物；伪装成正常程序的恶意软件。 |
| 蠕虫 | worm | ワーム | ワーム | 自钻自繁的虫；无需宿主即可自我复制传播的恶意程序。 |
| 蜜罐 | honeypot | ハニーポット | ハニーポット | 一罐蜜引熊；诱饵系统引攻击者上钩并观测。 |
| 沙箱 | sandbox | サンドボックス | サンドボックス | 儿童沙坑把脏乱关在里头；隔离执行环境限制副作用。 |
| 防火墙 | firewall | ファイアウォール | ファイアウォール | 楼间防火隔墙阻火蔓延；网络流量过滤屏障。 |
| 隔离区 / 堡垒机 | DMZ / bastion host | DMZ / 踏み台サーバー | ふみだいサーバー | 军事非武装缓冲区 / 城堡突出工事；DMZ 介于外网内网之间，bastion=加固跳板机（日语 `踏み台`）。 |
| 哨兵 | sentinel | センチネル | センチネル | 站岗哨兵；Redis Sentinel 监控主节点并触发故障转移。 |
| 看门狗 | watchdog | ウォッチドッグ | ウォッチドッグ | 看家犬；watchdog timer 检测系统卡死并复位。 |
| 银弹 | silver bullet | 銀の弾丸 | ぎんのだんがん | 唯一能杀狼人的银弹；指不存在的"一招制敌"（Brooks《没有银弹》）。 |

### 神话・动物・民俗・梗（myth / animals / folklore）

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 混沌猴 | chaos monkey | カオスモンキー | カオスモンキー | 一只猴在机房乱拔线；Netflix 随机杀实例验证韧性。 |
| 凤凰（服务器） | phoenix server | フェニックスサーバー | フェニックスサーバー | 浴火重生；坏了不修而是销毁重建（不可变基础设施）。 |
| 神谕 / 预言机 | oracle | オラクル | オラクル | 古神庙女祭司传神意；可信真相来源（Oracle DB；区块链 oracle 喂外部数据）。 |
| 流言 / 八卦协议 | gossip protocol | ゴシッププロトコル | ゴシッププロトコル | 流言一传十、十传百；节点像传八卦一样扩散状态（又称 epidemic）。 |
| 小黄鸭调试 | rubber duck debugging | ラバーダックデバッグ | ラバーダックデバッグ | 对着橡皮鸭逐行讲代码，讲着讲着自己发现 bug。 |
| 剃牦牛毛 | yak shaving | ヤクの毛刈り | ヤクのけがり | 为做 A 得先做 B 再做 C…陷入一串无关前置任务的递归。 |
| 自行车棚效应 | bikeshedding | 自転車置き場の議論 | じてんしゃおきばのぎろん | 帕金森琐碎定律：核电站没人懂直接放行、自行车棚人人能插嘴吵半天；纠结鸡毛蒜皮。 |
| 牲口而非宠物 | cattle, not pets | ペットではなく家畜 | かちく | 宠物有名要细养、牲口编号可替换；服务器应可随意销毁重建而非手工呵护。 |
| 动物园管理员 | ZooKeeper | ズーキーパー | ズーキーパー | 管一园子动物；Apache ZooKeeper 协调分布式系统里成群的服务。 |

### 戏剧・音乐・交通・机械（theater / music / transit / machinery）

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 演员（模型） | actor model | アクターモデル | アクターモデル | 舞台上各演员只靠台词（消息）交流、互不共享内存；一种并发模型。 |
| 暂存 / 预发布 | staging | ステージング | ステージング | 正式演出前的舞台；类生产的预发布环境。 |
| 编排 vs 编舞 | orchestration vs choreography | オーケストレーション / コレオグラフィ | オーケストレーション / コレオグラフィ | 指挥统一调度（中心化）vs 舞者各按约定步子（去中心、靠事件）；两种 Saga 风格。 |
| 边车 / 挎斗 | sidecar | サイドカー | サイドカー | 摩托车挎斗并行附挂；与主服务同 Pod 部署的辅助容器（服务网格代理）。 |
| 大使 | ambassador | アンバサダー | アンバサダー | 驻外大使代理对外交涉；代理容器替应用处理出站通信。 |
| 节流 | throttle | スロットリング | スロットリング | 油门控制进油量；限流控制请求速率。 |
| 车队效应 / 锁护送 | convoy effect / lock convoy | コンボイ効果 | コンボイこうか | 一辆慢车后面排长龙；持锁者一慢，线程全堵在它后面排队。 |

### 金融・办公・文书（finance / office / paperwork）

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 技术债务 | technical debt | 技術的負債 | ぎじゅつてきふさい | 借未来的钱应急；图快的烂实现要在日后维护里"付利息"（Ward Cunningham）。 |
| 账本 / 日志 | ledger / log | 台帳 / ログ | だいちょう / ログ | 会计账本只增不改；"log"更出自船上拖测速的木头（ship's log）。append-only 记录、WAL。 |
| 错误预算 | error budget | エラーバジェット | エラーバジェット | 一笔可花的预算；SLO 允许的不可用额度，花光就暂停上新功能（SRE）。 |
| 信封 | envelope | エンベロープ | エンベロープ | 信封裹信；message envelope 把 payload 连同元数据包起来。 |
| 清单 | manifest | マニフェスト | マニフェスト | 船运货物清单；部署/构建 manifest 列明内容。 |
| 快照 | snapshot | スナップショット | スナップショット | 一张定格照片；某一刻状态的时间点副本。（交易章节已收） |
| 检查点 | checkpoint | チェックポイント | チェックポイント | 关卡核验放行处；保存可恢复的一致状态以便重启续跑。 |

### 物理・电子・并发原语（physics / electronics / concurrency primitives）

| 中文 | English | 日本語 | 假名/读法 | 备注 |
| --- | --- | --- | --- | --- |
| 屏障 / 栅栏 | barrier | バリア | バリア | 物理路障；所有线程必到齐的同步点；memory barrier 约束读写重排序。 |
| 闩锁 | latch | ラッチ | ラッチ | 门闩一关到底；CountDownLatch 是一次性单向门。 |
| 信号量 | semaphore | セマフォ | セマフォ | 铁路臂板信号机举旗放行/停；限制并发访问数量的原语。 |
| 栅栏令牌 | fencing token | フェンシングトークン | フェンシングトークン | 拉栅栏圈地；单调递增令牌把"复活的旧锁持有者"挡在外面（与 tombstone 思路呼应）。 |
| 抖动 | jitter | ジッター | ジッター | 信号/手的颤动；给重试间隔加随机抖动，避免大家同步重试。 |
| 脑裂 | split-brain | スプリットブレイン | スプリットブレイン | 大脑两半各自为政；集群网络分区后两边都自认是 leader。 |
| 法定人数 | quorum | クォーラム | クォーラム | 议会表决的最低出席人数；写/读需达成一致的最少副本数。 |

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
- [Martin Kleppmann《Designing Data-Intensive Applications》](https://dataintensive.net/)：tombstone、quorum、split-brain、fencing token、gossip 等分布式比喻的权威出处。
- [Martin Fowler：CircuitBreaker](https://martinfowler.com/bliki/CircuitBreaker.html) 与 [TechnicalDebt](https://martinfowler.com/bliki/TechnicalDebt.html)：熔断器、技术债务两个比喻的经典阐述。
- [Fred Brooks：No Silver Bullet](https://en.wikipedia.org/wiki/No_Silver_Bullet)：银弹比喻的来源。
- [Netflix Chaos Monkey](https://netflix.github.io/chaosmonkey/)：混沌工程"放猴子进机房"的由来。
