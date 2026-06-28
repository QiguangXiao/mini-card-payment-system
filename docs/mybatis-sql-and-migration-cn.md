# MyBatis、SQL 与数据库 Migration（数据层统一笔记）

> 本文合并自两份旧文档：`mybatis-sql-learning-cn.md`（MyBatis + SQL 后端面试笔记）与
> `database-migration-liquibase-cn.md`（Liquibase 迁移说明）。合并时去掉两者关于"唯一约束/
> check 是业务 invariant 的数据库防线"等重叠表述，并**把迁移文件列表对齐到代码现状的 0001–0007**
> （旧文档只列到 0003）。一次性的 `db-schema-sync-2026-06-21-cn.md`（本地 schema drift 修复日志）
> 已单独归档到 `docs/archive/`，不并入本文。

> 关键词：MyBatis, 显式 SQL, resultMap, 动态 SQL, FOR UPDATE, SKIP LOCKED, 悲观/乐观锁,
> 隔离级别, 索引, 覆盖索引, EXPLAIN, keyset 分页, 批处理, BigDecimal/DECIMAL, Liquibase,
> schema drift, data backfill, 冪等(べきとう), マイグレーション。

一句话定位：

```text
MyBatis 不是替你设计数据库的 ORM，它让 SQL 保持显式，同时处理参数绑定、结果映射、Mapper 接口和 Spring 事务集成。
真正体现后端能力的是：表怎么设计、索引怎么选、事务怎么切、锁怎么拿、失败怎么恢复、重复请求怎么幂等。
```

本项目选 MyBatis 的原因：信用卡后台很多关键行为依赖明确 SQL（`SELECT ... FOR UPDATE`、`FOR UPDATE SKIP LOCKED`、unique constraint、状态条件更新）；相比纯 `JdbcTemplate` 减少重复 row mapping（项目保留 `JdbcRiskVelocityCounter` 作为 JdbcTemplate 对照示例），相比 JPA/Hibernate 更容易看见真实 SQL。

---

## 第一部分：MyBatis 在项目里的定位与绑定

### 1.1 分层

```text
Application service  控制 transaction boundary
  -> Repository adapter（如 MyBatisAuthorizationRepository）映射 row ↔ domain
     -> Mapper 接口（AuthorizationMapper）
        -> XML mapper（mappers/authorization/AuthorizationMapper.xml）
           -> authorizations 表
```

> controller / domain object **不**直接依赖 MyBatis。MyBatis 是 infrastructure adapter（数据库读写 + row mapping）；service 负责 use case 和事务边界；domain 负责状态和业务规则。

`mybatis.mapper-locations: classpath:mappers/**/*.xml`。当前 mapper：authorization / card / creditaccount / delayjob / inbox / ledger / notification(+delivery) / outbox / repayment / risk / statement(+job+billing) / transaction。

### 1.2 Mapper 接口 ↔ XML 绑定

| XML 元素 | 对应 Java |
| --- | --- |
| `namespace` | Mapper 接口全限定名 |
| `id` | Mapper 方法名 |
| `#{id}` | `@Param("id")` 参数 |
| `resultMap` | 查询结果如何映射为 Java object |

多参数方法必须用 `@Param` 给参数稳定名字，否则 XML 里只能用 `param1/param2` 这种不直观名字——金融项目 SQL 要可读、可 review。

### 1.3 `#{}` vs `${}`（高频题）

- `#{}` → prepared statement `?`：防 SQL injection、自动类型转换、可复用执行计划。**绝大多数业务参数都用它。**
- `${}` → 直接字符串拼接，有注入风险。**只在列名/表名/排序方向这种不能用 `?` 占位的位置**才考虑，且必须 Java 层白名单：

```java
String sortColumn = switch (request.sortBy()) {
    case "createdAt" -> "created_at";
    case "amount" -> "amount";
    default -> throw new IllegalArgumentException("unsupported sort");
};
```

> 用户输入**永远不能**直接进 `${}`。动态排序/表名先在 Java 层 whitelist mapping，再传入受控片段。

### 1.4 resultMap、constructor mapping、row object

项目常用 constructor mapping，把 DB row 映射成 immutable record，再由 repository 转成 domain aggregate：

```xml
<resultMap id="authorizationRowMap" type="...AuthorizationRow">
  <constructor>
    <arg column="id" javaType="java.lang.String"/>
    <arg column="amount" javaType="java.math.BigDecimal"/>
    <arg column="created_at" javaType="java.time.Instant"/>
  </constructor>
</resultMap>
```

`AuthorizationRow`（persistence detail）→ `Authorization` aggregate（business concept），保留 DDD 边界，domain 不需要知道列名/字符串 enum/MyBatis 注解。

| 方式 | 适合场景 |
| --- | --- |
| `resultType` | 列名和 Java 属性名简单一致 |
| `resultMap` | 列名不同、constructor、嵌套映射、复杂类型 |
| `TypeHandler` | JSON、枚举、特殊金额/时间需要统一转换 |

`<sql>` + `<include>` 复用列清单避免重复（但别把复杂业务 SQL 拆到看不懂，关键 SQL 要可 review）。

---

## 第二部分：CRUD 与动态 SQL

### 2.1 Insert：INSERT-first idempotency claim

授权创建是"先插入、靠唯一约束抢占"：

```xml
<insert id="insert">
  INSERT INTO authorizations (id, idempotency_key, request_fingerprint, card_id,
    amount, currency, status, created_at)
  VALUES (#{authorization.id}, #{idempotencyKey}, ...)
</insert>
```

- ID 应用层生成，便于在 request path 里解释业务对象生命周期。
- `idempotency_key` 靠数据库 unique constraint 防重复；并发同 key 插入只有一个 winner，其他收 `DuplicateKeyException`。

> 对状态改变接口，更倾向 **insert-first claim** 而不是 read-then-insert——后者并发下有 race，最终还是要靠 unique constraint 兜底。

### 2.2 Update：状态机用 conditional update

普通更新前要确认：是否已持 row lock、`WHERE` 是否限制了正确业务状态、金额是否 `DECIMAL`、是否检查 update count。更安全的状态迁移写成条件更新：

```sql
UPDATE authorizations SET status = 'EXPIRED'
WHERE id = ? AND status = 'APPROVED' AND expires_at <= ?
```

返回 0 rows 说明状态已被其他流程改变，**不能盲目认为成功**。

### 2.3 Select：不要习惯性 `SELECT *`

显式列：减少网络/内存、schema 加列不会意外影响 mapping、更易用覆盖索引、review 时清楚 use case 需要哪些字段。

### 2.4 动态 SQL 标签

- `<if>`：可选查询条件。
- `<where>`：自动去掉开头多余的 `AND/OR`。
- `<set>`：部分字段更新自动处理末尾逗号（但金融核心状态机更新别过度 patch，应明确字段和合法状态）。
- `<foreach>`：`IN (...)` 或批量 insert——**空集合要在 Java 层提前返回**避免非法 SQL；`IN` 列表太长影响 SQL 长度/执行计划，应分批。
- `<choose>`：类似 Java `switch`。

---

## 第三部分：事务、锁与并发（金融正确性核心）

### 3.1 Spring 事务与 MyBatis

```text
@Transactional service method 开启事务
  -> mapper select/update 用同一个 DB 连接
  -> 方法返回 -> Spring commit
```

- `@Transactional` 放在 application service 或明确的 worker/recoverer 方法上；mapper 本身不负责事务边界。
- 同事务内 `SELECT ... FOR UPDATE` 的锁持有到 commit/rollback。
- Runtime exception 默认 rollback；checked exception 默认不 rollback（除非配置）。
- **同类内 self-invocation 调用 `@Transactional` 方法不经过 Spring proxy**，是常见坑。

### 3.2 `SELECT ... FOR UPDATE`（悲观锁，额度冻结）

```sql
SELECT id, credit_limit, reserved_amount, posted_balance, currency, status
FROM credit_accounts WHERE id = ? FOR UPDATE
```

在事务内锁住 account row，同账户并发授权被**串行化**，防止两个请求同时看到相同 available credit 然后都批准。正确顺序：

```text
begin tx → claim idempotency key → lock account row → 校验并改 domain state → update → commit
```

### 3.3 `FOR UPDATE SKIP LOCKED`（队列表）

Outbox / DelayJob / StatementJob 的 claim 用：

```sql
SELECT id, status, next_attempt_at FROM outbox_events
WHERE status = 'PENDING' AND next_attempt_at <= ?
ORDER BY created_at, id LIMIT ? FOR UPDATE SKIP LOCKED
```

多 worker/pod 并发扫描，已被别人锁住的 row 被跳过，每条任务只被一个事务 claim。**适合**：queue-like table、后台 worker 分片。**不适合**：用户必须看到完整一致列表、需要严格全局顺序的处理。

### 3.4 锁和索引的关系

`FOR UPDATE` 是否高效取决于查询条件有没有合适索引。无索引时 InnoDB 可能扫描大量 row、锁范围更大、更易死锁。所以 `idempotency_key` 这类锁定查询列必须有唯一索引。

> Row lock 不是写了 `FOR UPDATE` 就自动安全高效——查询必须走合适索引。

### 3.5 乐观锁 vs 悲观锁

```sql
UPDATE credit_accounts SET reserved_amount = ?, version = version + 1
WHERE id = ? AND version = ?
```

update count = 0 → 数据已被别人改过，重试或返回冲突。乐观锁适合冲突少、可接受重试；不适合高冲突热点账户、必须严格串行化的复杂状态检查。**本项目额度冻结用悲观锁**（同账户短时间多笔并发，额度判断和状态修改必须在一个 tx 内串行化）。

### 3.6 事务隔离级别

| 现象 | 含义 | | 级别 | 特点 |
| --- | --- | --- | --- | --- |
| Dirty read | 读到未提交数据 | | READ UNCOMMITTED | 可能脏读，少用 |
| Non-repeatable read | 同事务两次读同行结果不同 | | READ COMMITTED | 每次读已提交版本，常用 |
| Phantom read | 同事务两次范围查询行数变化 | | REPEATABLE READ | **InnoDB 默认** |
| | | | SERIALIZABLE | 最强、并发最差 |

InnoDB 要点：普通 `SELECT` 是 consistent read（不加锁）；`FOR UPDATE` 是 locking read（加锁）；RR 下范围锁可能涉及 next-key lock；唯一索引等值查询锁范围更小。

> 不能只背隔离级别名字。额度冻结不是靠普通 SELECT 的隔离级别保证，而是靠同事务内对 credit account 做 `FOR UPDATE` 把余额检查和更新串起来。

### 3.7 死锁

```text
T1 锁 account A 再等 B；T2 锁 account B 再等 A  → 死锁
```

预防：多行锁**按固定顺序**获取（如按 account_id 排序）；事务尽量短；锁住必要 row、不做慢外部调用；建好索引避免锁范围扩大；捕获 deadlock/lock wait timeout 按幂等语义安全重试。

> 死锁关键不是完全消除，而是降低概率 + 让应用能安全重试——前提是请求有 idempotency key、状态迁移有条件、重复执行不会多扣钱。

---

## 第四部分：索引与性能

### 4.1 B+Tree 索引

适合：等值（`WHERE id = ?`）、范围（`created_at >= ?`）、排序、前缀（`LIKE 'abc%'`）。不适合（不 sargable）：`LIKE '%abc'`、对索引列做函数（`DATE(created_at) = ?`）、隐式类型转换。

### 4.2 联合索引与最左前缀

```sql
CREATE INDEX idx_outbox_pending ON outbox_events (status, next_attempt_at, created_at, id);
-- 适合 WHERE status='PENDING' AND next_attempt_at<=? ORDER BY created_at, id
```

等值列放前面；范围列之后的列过滤能力受限但仍可能帮排序/覆盖；排序能否用索引看 where 和 order by 是否匹配索引顺序。

### 4.3 覆盖索引

查询需要的列都在索引里则少回表（提升读性能）；但索引越多 insert/update 越慢，金融系统要平衡读写路径。

### 4.4 唯一索引 = 业务约束

```sql
UNIQUE (idempotency_key)                    -- 同 key 只能创建一个 authorization
UNIQUE (consumer_name, event_id)            -- 同 consumer 只处理同 event 一次
```

> 对幂等这种 correctness requirement，不会只靠应用层 check——应用可先检查提高体验，但最终必须由 database unique constraint 兜底。

### 4.5 EXPLAIN

| 字段 | 看什么 |
| --- | --- |
| `type` | `const`/`ref`/`range` 通常比 `ALL` 好 |
| `key` | 实际用了哪个索引 |
| `rows` | 估计扫描多少行 |
| `Extra` | 是否 `Using filesort` / `Using temporary` / `Using index` |

不要机械追求"没有 filesort"——小结果集 filesort 不一定严重，要看数据量、QPS、延迟、是否阻塞写入。

### 4.6 分页：offset vs keyset

- **Offset**（`LIMIT 50 OFFSET 10000`）：offset 越大跳过越多 row，数据变化时可能重复/漏。适合后台小数据量、页数不深。
- **Keyset**（`WHERE (created_at, id) < (?, ?) ORDER BY ... LIMIT 50`）：深分页性能稳定，适合交易流水、消息列表、审计日志。

### 4.7 N+1

查 100 条 authorization、每条再查一次 card = 101 次 SQL。解法：join 展示字段 / 批量 `IN` 查 cards 再内存 map / 读模型 projection / 谨慎缓存低变化维度（别过早上 Redis）。写路径不一定强行 join，避免把聚合边界和持久化优化混在一起。

### 4.8 连接池、超时、慢查询排查

数据库连接是有限资源（不是线程越多越好，太小排队、太大压垮 DB）；查询要有 timeout；长事务持有锁和 undo 版本影响吞吐。排查顺序：

```text
接口慢 → application metrics → DB 连接池等待时间 → slow query log
→ EXPLAIN 慢 SQL → 锁等待/死锁日志 → 决定加索引/改 SQL/改事务范围/改读模型
```

---

## 第五部分：批处理、金额、时间、安全

### 5.1 批处理

- **multi-values insert**（`INSERT ... VALUES (...),(...)`）：一条 SQL 多行，少网络往返；但 SQL 太长触发 packet size、单条失败整批失败、要控制 batch size。
- **`ExecutorType.BATCH`**：缓存 statement，大数据量定期 `flushStatements()`；失败要知道哪批失败再重试。
- **`rewriteBatchedStatements=true`**（MySQL Connector/J）：把多次 insert rewrite 成 multi-values，但不是银弹（仍要控 batch size、处理唯一键冲突、控事务时长）。
- 批量更新更难（每行值不同）：多条 update + BATCH、staging 表 join update、或 `CASE WHEN`（可读性差）。

> 批处理不能只说快。要说 batch size、事务范围、失败重试、幂等键、唯一约束、锁持有时间、监控。金融系统里批处理更重要的是**可恢复**，不是单次跑得最大。

### 5.2 金额与时间

```text
金额: Java BigDecimal + MySQL DECIMAL(19,2)，不用 double/float（二进制浮点无法精确表达十进制金额）
      更严格可用 minor unit（JPY 1000→1000, USD 10.25→1025 cents），但要在 domain 和 DB 设计里讲清
时间: Java Instant 表时间点；DB 存 UTC；API 层转用户时区；定时任务/过期/账单周期明确 clock 来源，测试注入 Clock
```

### 5.3 SQL 注入与安全

业务参数用 `#{}`；动态列名/排序方向白名单；不拼接用户输入；错误信息不暴露完整 SQL 和敏感参数；日志避免打印 card number / PII / token。

```text
金融后端里 SQL injection 不只是数据泄露，它可能变成 unauthorized money movement。
```

---

## 第六部分：Schema 演进与 Liquibase 迁移

### 6.1 为什么用 Liquibase（轻量方式）

本项目不演示企业级发布平台，采用轻量方式：Spring Boot 启动时自动执行 Liquibase、formatted SQL changelog（仍直接写 MySQL SQL）、一个 master changelog 管执行顺序、每次变化**追加新 changeset，不改已执行的**。

vs Flyway：Flyway 更极简（纯 SQL、线性版本号）；Liquibase 的 `precondition` 更适合本学习项目常解释的 schema drift 场景（"旧库有没有这个列""旧约束是否存在""本地 Docker volume 表是否过时"），更容易写成可重复执行、可解释的步骤。

### 6.2 它解决什么

早期靠 `schema.sql` + `CREATE TABLE IF NOT EXISTS` 建表，对空库方便，但有金融后端常见坑：

```text
代码和 schema 文件改了，但某环境的表已存在。
CREATE TABLE IF NOT EXISTS 不会帮你加列、删旧列、补索引、更新 CHECK constraint。
```

Liquibase 把 schema 变化变成有版本记录的变更历史：首次启动建 `DATABASECHANGELOG` 记录已执行的 changeset；`DATABASECHANGELOGLOCK` 防多实例同时迁移；启动时只执行未执行的；每个字段/索引/约束/数据回填都是明确步骤。

> Schema migration 不是简单建表。它要处理历史数据、兼容旧版本代码、约束补齐、失败恢复和审计记录。否则代码发布后，旧表结构可能让请求失败，或更糟——让金额、状态和幂等约束失效。

### 6.3 当前文件结构（对齐代码：0001–0007）

```text
src/main/resources/db/changelog/db.changelog-master.yaml      ← 入口，按顺序 include 下面 7 个
src/main/resources/db/changelog/changes/
  0001-initial-schema.sql                  当前空库 baseline，建完整表结构
  0002-sync-known-local-schema-drift.sql   修复已出现过的旧表结构（authorizations.posted_at、
                                           notifications 通用 subject model、credit_accounts 金额约束、
                                           outbox status CHECK、card_transactions 账单批索引）
  0003-seed-local-sample-data.sql          本地学习用样例卡 / 账户
  0004-statement-billing-jobs.sql          账单批处理 job 表
  0005-flatten-statement-jobs.sql          扁平化 statement job（去掉 batch 父表，见 claimable-jobs 文档）
  0006-notification-type-column-rename.sql  notifications.template → notification_type 正名
  0007-notification-deliveries.sql         新增 notification_deliveries 表（per-channel 投递）
```

- `build.gradle` 引入 `org.liquibase:liquibase-core`；`application.yml` 关闭 Spring SQL init（`spring.sql.init.mode: never`），指向 master changelog。
- **为什么 seed data（0003）排在 drift 修复（0002）之后**：旧库可能还没有 `credit_accounts.posted_balance`；若 baseline 先执行带 `posted_balance` 的 `INSERT IGNORE`，旧表会报 unknown column。先补结构，再插样例数据更安全。
- 这 7 个 changeset 串起来本身就是一部 **schema 演进史**：从初始结构 → 修本地 drift → 样例数据 → 账单 job → 扁平化 → 通知列正名 → 投递表，正好对应业务一路长出来的几个能力。

### 6.4 日常操作

```bash
# 首次/空库启动：Liquibase 自动按序执行 0001…0007
docker compose up -d && ./gradlew bootRun

# 看已执行哪些 changeset
docker compose exec mysql mysql -uroot -prootpassword mini_card \
  -e "SELECT ID, AUTHOR, FILENAME, DATEEXECUTED, ORDEREXECUTED FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED"
```

新增一次 schema 变化（如给 `repayments` 加 `failure_reason`）：

```sql
--liquibase formatted sql
--changeset mini-card:0008-add-repayment-failure-reason dbms:mysql
--comment: Store bank debit failure reason for retry and support investigation.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'repayments' AND column_name = 'failure_reason'
ALTER TABLE repayments ADD COLUMN failure_reason VARCHAR(200) NULL AFTER status;
```

然后在 `db.changelog-master.yaml` 末尾 `include` 新文件、启动应用让它执行。**不要再改已发布执行过的 changeset**（会导致 checksum 不一致）——要修正就追加新 changeset。

本地学习环境数据不重要时可 `docker compose down -v` 删库重建；但**真实环境不能用删库重建代替 migration**，要先确认：旧数据如何 backfill、新旧代码是否需短期兼容同表、新 constraint 是否被历史脏数据卡住、大表 DDL 是否锁表或影响线上、是否需要"先加 nullable → 回填 → 再改 NOT NULL"。

### 6.5 五个真实迁移例子（schema drift 模式）

1. **新增列但旧表已存在**：`authorizations` 缺 `posted_at`，MyBatis 写它后旧库报 Unknown column → `ALTER TABLE ... ADD COLUMN posted_at TIMESTAMP(6) NULL`。**先允许 NULL**，因为旧的 APPROVED/DECLINED/EXPIRED 本就没有 posting 时间，别硬塞假时间。
2. **字段语义升级（含 data backfill）**：`notifications` 从 `authorization_id/card_id` 升级到 `subject_type/subject_id/recipient_key`。步骤：加新列允许 NULL → 用旧字段 backfill（`UPDATE ... SET subject_id = authorization_id WHERE subject_id IS NULL ...`）→ 改 NOT NULL → 删旧列 → 加新索引/约束。**字段改名通常不是纯 DDL，还含历史数据语义转换。**
3. **约束规则过时**：旧 `reserved_amount <= credit_limit`，引入 posting 后额度占用还要看 `posted_balance` → 加 `posted_balance` 默认 0 → 删旧 check → 加 `posted_balance >= 0` 和 `reserved_amount + posted_balance <= credit_limit`。**constraint 是业务 invariant 的数据库防线，业务模型变了约束也要跟着变**，否则并发或 bug 可能绕过 Java 层保护。
4. **枚举值过时**：notification subject type 加 `REPAYMENT` 时要 `DROP CHECK` 再 `ADD CONSTRAINT ... CHECK (subject_type IN (...,'REPAYMENT'))`。**Java enum、DB check、消息 payload 的 event type 要一起演进**，否则应用支持了新类型、落库仍失败。
5. **查询方式变了要补索引**：账单批扫描 `status / statement_id IS NULL / posted_at / 按 credit_account_id 分组` → `CREATE INDEX idx_card_transactions_billing_batch ON card_transactions (status, statement_id, posted_at, credit_account_id)`。**migration 不只管 columns，也管 indexes**，查询路径变了索引没跟上，高流量下会变慢 SQL / 锁等待。

### 6.6 写 migration 的顺序原则

推荐：① 先加兼容字段尽量 nullable → ② backfill 历史数据 → ③ 代码切到新字段 → ④ 再加 `NOT NULL`/unique/check → ⑤ 最后删旧字段。

不推荐：同一次发布直接删旧字段又让旧代码运行；直接加 `NOT NULL` 但没 backfill；改已执行的 changeset；只改 Java mapper 不写 migration；只改 migration 不更新领域文档。

### 6.7 当前方案边界（学习项目级）

- 无自动 rollback——金融系统的 rollback 往往不是反向 DDL，而是设计 forward fix。
- MySQL DDL 很多时候隐式提交，别假设所有 DDL 都在一个普通业务事务里。
- 大表变更要评估锁表、复制延迟、在线 DDL、灰度发布，这里只保留面试解释点。
- 本地 seed data 为学习/手动验证，不代表生产数据初始化方式。

---

## 第七部分：MyBatis 常见坑

- **Mapper XML 没绑定上**：`namespace` ≠ Mapper 接口全限定名 / XML `id` ≠ 方法名 / `mapper-locations` 路径不含 XML / 参数名与 `#{}` 不一致。
- **null 参数需 `jdbcType`**：`#{declineReason,jdbcType=VARCHAR}`、`#{publishedAt,jdbcType=TIMESTAMP}`——参数为 null 时 JDBC driver 可能需要明确 SQL 类型。
- **enum 映射**：Java enum name ↔ DB VARCHAR（可读、便于排查）；但重命名 enum 影响历史数据，生产可用稳定 code 字段。
- **大字段/JSON**（Outbox payload）：写入前有 schema/version；不要在高频查询里扫 JSON 大字段；列表查询尽量只取 metadata。
- **懒加载**：学习项目不建议依赖神秘懒加载，更推荐写清楚 join SQL、分两次批量查询、或维护 projection。

---

## 第八部分：面试必备清单 + 怎么讲 + 练习

**必备清单**：SQL 基础（JOIN/GROUP BY/HAVING）；索引（B+Tree、联合、最左前缀、唯一、覆盖、失效）；事务（ACID、隔离级别、长事务风险）；锁（row lock、gap/next-key、FOR UPDATE、deadlock）；幂等（unique constraint、idempotency key、重复返回原结果）；批处理（size、事务范围、失败恢复、partial failure）；性能（EXPLAIN、slow query、连接池、分页、N+1）；金额（BigDecimal/DECIMAL/币种/rounding）；时间（UTC/Instant/时区/过期任务）；安全（注入、参数绑定、敏感日志）。

**怎么讲给面试官**：

- *为什么用 MyBatis*：信用卡后台希望 SQL 显式，授权/额度冻结/幂等/任务 claim 都依赖数据库约束和锁；MyBatis 让我直接写 `FOR UPDATE`/`SKIP LOCKED`/唯一约束 SQL，又不用手写所有 JDBC mapping。
- *并发授权不超额*：先用 `idempotency_key` insert-first claim 防重试重复；再在同一 Spring tx 里对 credit account `FOR UPDATE`，锁内完成额度检查和更新。靠数据库 row lock 串行化，不依赖单 JVM 的 `synchronized`。
- *后台任务为何 SKIP LOCKED*：Outbox/DelayJob 是 queue-like table，多 worker 同时扫 due rows，`SKIP LOCKED` 让每个 worker 跳过别人已 claim 的 row，水平扩展又不重复 claim。
- *如何优化慢 SQL*：先看 slow query log 和真实参数，再 EXPLAIN 看索引/扫描行数/filesort，然后调联合索引、减 SELECT 列、改 keyset 分页、拆读模型或缩小事务范围。不凭感觉加索引（索引增加写成本）。

**练习顺序**：① 读 `AuthorizationMapper.xml` 解释 resultMap/`<sql>`/`#{}`/`FOR UPDATE` → ② 读 `CreditAccountMapper.xml` 解释额度更新前为何锁 account row → ③ 读 `OutboxEventMapper.xml`/`DelayJobMapper.xml` 解释 `SKIP LOCKED` 如何支持多 worker → ④ 自己写带 `<where>`/`<if>`/分页/排序白名单的动态查询 → ⑤ 写 batch insert 并说明 size/事务/失败重试 → ⑥ 对一条查询写出预期索引再用 EXPLAIN 验证 → ⑦ 用一句话讲清"数据库唯一约束为什么比应用层 if check 更可靠"。
