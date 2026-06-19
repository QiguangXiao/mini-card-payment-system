# MyBatis 与 SQL 后端面试学习笔记

这份文档整理后端工程师面试里需要掌握的 MyBatis 核心使用、SQL 基础与进阶、事务、锁、索引、批处理和金融后端常见追问点。

它不只局限于本项目已经写过的代码，但会尽量用本项目的信用卡授权、额度冻结、Outbox、DelayJob 等例子来解释。

先记住一句话：

```text
MyBatis 不是替你设计数据库的 ORM。
它的价值是让 SQL 保持显式，同时帮你处理参数绑定、结果映射、Mapper 接口和 Spring 事务集成。
```

本项目选择 MyBatis 的原因：

- 信用卡后台很多关键行为依赖明确 SQL，例如 `SELECT ... FOR UPDATE`、`FOR UPDATE SKIP LOCKED`、unique constraint 和状态条件更新。
- 面试时可以清楚解释数据库如何保证 `idempotency`、`row lock`、`transaction boundary` 和并发安全。
- 相比纯 `JdbcTemplate`，MyBatis 减少重复 row mapping；相比 JPA/Hibernate，它更容易看见真实 SQL。

## 1. MyBatis 在项目里的位置

当前项目主要配置：

```yaml
mybatis:
  mapper-locations: classpath:mappers/**/*.xml
```

典型结构：

```text
Java Mapper interface
-> XML mapper
-> MyBatis creates implementation
-> Repository adapter maps row object and domain object
-> Application service controls transaction boundary
```

例子：

```text
AuthorizationService
-> AuthorizationRepository
-> MyBatisAuthorizationRepository
-> AuthorizationMapper
-> src/main/resources/mappers/authorization/AuthorizationMapper.xml
-> authorizations table
```

面试回答：

> 我不会让 controller 或 domain object 直接依赖 MyBatis。MyBatis 是 infrastructure adapter，负责数据库读写和 row mapping；application service 负责 use case 和 transaction boundary；domain object 负责状态和业务规则。

## 2. Mapper 接口和 XML 如何绑定

Java 接口：

```java
@Mapper
public interface AuthorizationMapper {

    AuthorizationRow findById(@Param("id") String id);

    int insert(
            @Param("idempotencyKey") String idempotencyKey,
            @Param("authorization") AuthorizationRow authorization
    );
}
```

XML：

```xml
<mapper namespace="com.minicard.authorization.infrastructure.mybatis.AuthorizationMapper">

    <select id="findById" resultMap="authorizationRowMap">
        SELECT id, request_fingerprint, card_id, amount, currency, status
        FROM authorizations
        WHERE id = #{id}
    </select>

</mapper>
```

绑定规则：

| XML 元素 | 对应 Java |
| --- | --- |
| `namespace` | Mapper 接口全限定名 |
| `id` | Mapper 方法名 |
| `#{id}` | `@Param("id")` 参数 |
| `resultMap` | 查询结果如何映射为 Java object |

面试容易问：

为什么要写 `@Param`？

可以回答：

> 多参数方法必须给参数起稳定名字，否则 XML 里只能依赖 `param1`、`param2` 这类不直观名字。金融项目里 SQL 需要可读、可 review，显式 `@Param` 更安全。

## 3. `#{}` 和 `${}` 的区别

这是 MyBatis 高频面试题。

### 3.1 `#{}`：参数绑定

```xml
WHERE id = #{id}
```

MyBatis 会使用 prepared statement：

```sql
WHERE id = ?
```

优点：

- 防 SQL injection。
- 自动处理类型转换。
- 可复用执行计划。

绝大多数业务参数都应该使用 `#{}`。

### 3.2 `${}`：字符串拼接

```xml
ORDER BY ${sortColumn}
```

`${}` 会直接拼到 SQL 字符串里，有 SQL injection 风险。

只有在 SQL 关键字、列名、表名这种不能用 `?` 占位的位置，才可能使用 `${}`，而且必须白名单校验：

```java
String sortColumn = switch (request.sortBy()) {
    case "createdAt" -> "created_at";
    case "amount" -> "amount";
    default -> throw new IllegalArgumentException("unsupported sort");
};
```

面试回答：

> 用户输入永远不能直接进 `${}`。如果必须动态排序或动态表名，先在 Java 层做 whitelist mapping，再传入受控 SQL 片段。

## 4. `resultMap`、constructor mapping 和 row object

本项目常用 constructor mapping：

```xml
<resultMap id="authorizationRowMap"
           type="com.minicard.authorization.infrastructure.mybatis.AuthorizationRow">
    <constructor>
        <arg column="id" javaType="java.lang.String"/>
        <arg column="amount" javaType="java.math.BigDecimal"/>
        <arg column="created_at" javaType="java.time.Instant"/>
    </constructor>
</resultMap>
```

好处：

- row object 可以做成 immutable record/class。
- 数据库 row 和 domain object 分离。
- domain 不需要知道列名、字符串 enum、MyBatis 注解。

常见选择：

| 方式 | 适合场景 |
| --- | --- |
| `resultType` | 列名和 Java 属性名简单一致 |
| `resultMap` | 列名不同、constructor、嵌套映射、复杂类型 |
| `TypeHandler` | JSON、枚举、特殊金额/时间类型需要统一转换 |

本项目里 `AuthorizationRow` 再由 `MyBatisAuthorizationRepository` 转成 `Authorization` aggregate。这样可以保留 DDD 边界：

```text
Database row is persistence detail.
Domain aggregate is business concept.
```

## 5. 可复用 SQL 片段

XML 里可以用 `<sql>` 和 `<include>` 避免列清单重复：

```xml
<sql id="authorizationColumns">
    id, request_fingerprint, card_id, amount, currency, status,
    decline_reason, created_at, decided_at, expires_at, posted_at, expired_at
</sql>

<select id="findById" resultMap="authorizationRowMap">
    SELECT <include refid="authorizationColumns"/>
    FROM authorizations
    WHERE id = #{id}
</select>
```

注意：

- 适合复用列清单。
- 不要把复杂业务 SQL 拆到看不懂。
- 关键 SQL 应该保持可 review。

## 6. CRUD 写法重点

### 6.1 Insert

本项目授权创建是 `INSERT-first idempotency claim`：

```xml
<insert id="insert">
    INSERT INTO authorizations (
        id, idempotency_key, request_fingerprint, card_id, amount, currency,
        status, created_at
    ) VALUES (
        #{authorization.id},
        #{idempotencyKey},
        #{authorization.requestFingerprint},
        #{authorization.cardId},
        #{authorization.amount},
        #{authorization.currency},
        #{authorization.status},
        #{authorization.createdAt}
    )
</insert>
```

关键点：

- ID 在应用层生成，便于 request path 里解释业务对象生命周期。
- `idempotency_key` 靠数据库 unique constraint 防重复。
- 并发请求同时插入时，只有一个 winner，其他请求收到 `DuplicateKeyException`。

面试回答：

> 对状态改变接口，我更倾向 insert-first claim，而不是 read-then-insert。read-then-insert 在并发下有 race condition，最终还是要靠 unique constraint 兜底。

### 6.2 Update

普通更新：

```xml
<update id="update">
    UPDATE credit_accounts
    SET reserved_amount = #{reservedAmount},
        posted_balance = #{postedBalance},
        status = #{status}
    WHERE id = #{id}
</update>
```

金融系统里要注意：

- 更新前是否已经持有 row lock。
- `WHERE` 是否限制了正确业务状态。
- 金额字段是否使用 `DECIMAL` 和 `BigDecimal`。
- update count 是否需要检查。

更安全的状态迁移可以写成 conditional update：

```sql
UPDATE authorizations
SET status = 'EXPIRED'
WHERE id = ?
  AND status = 'APPROVED'
  AND expires_at <= ?
```

如果返回 0 rows，说明状态已经被其他流程改变，不能盲目认为成功。

### 6.3 Select

不要习惯性 `SELECT *`：

```sql
SELECT id, credit_limit, reserved_amount, posted_balance, currency, status
FROM credit_accounts
WHERE id = ?
```

原因：

- 减少网络和内存开销。
- schema 加列不会意外影响 mapping。
- 更容易利用 covering index。
- review 时更清楚这个 use case 需要哪些字段。

## 7. 动态 SQL 核心语法

MyBatis 动态 SQL 使用 OGNL 表达式，常见标签如下。

### 7.1 `<if>`

```xml
<select id="searchAuthorizations" resultMap="authorizationRowMap">
    SELECT <include refid="authorizationColumns"/>
    FROM authorizations
    WHERE card_id = #{cardId}
    <if test="status != null">
        AND status = #{status}
    </if>
    <if test="createdAfter != null">
        AND created_at >= #{createdAfter}
    </if>
</select>
```

适合可选查询条件。

### 7.2 `<where>`

`<where>` 会自动处理开头多余的 `AND` / `OR`：

```xml
<select id="search" resultMap="authorizationRowMap">
    SELECT <include refid="authorizationColumns"/>
    FROM authorizations
    <where>
        <if test="cardId != null">
            card_id = #{cardId}
        </if>
        <if test="status != null">
            AND status = #{status}
        </if>
    </where>
</select>
```

### 7.3 `<set>`

`<set>` 适合部分字段更新，并自动处理末尾逗号：

```xml
<update id="patchNotification">
    UPDATE notifications
    <set>
        <if test="status != null">status = #{status},</if>
        <if test="lastError != null">last_error = #{lastError},</if>
        updated_at = #{updatedAt}
    </set>
    WHERE id = #{id}
</update>
```

注意：

- 金融核心状态更新不要过度使用 patch。
- 状态机类更新应明确字段和合法状态。

### 7.4 `<foreach>`

常用于 `IN (...)` 或批量 insert：

```xml
<select id="findByIds" resultMap="authorizationRowMap">
    SELECT <include refid="authorizationColumns"/>
    FROM authorizations
    WHERE id IN
    <foreach collection="ids" item="id" open="(" separator="," close=")">
        #{id}
    </foreach>
</select>
```

注意：

- 空集合要在 Java 层提前返回，避免生成非法 SQL。
- `IN` 列表太长会影响 SQL 长度、执行计划和网络传输，应分批。

### 7.5 `<choose>`

类似 Java `switch`：

```xml
<choose>
    <when test="status != null">
        status = #{status}
    </when>
    <otherwise>
        status IN ('APPROVED', 'DECLINED')
    </otherwise>
</choose>
```

## 8. 批处理 Batch

批处理是后端面试常问点，因为它同时涉及吞吐、事务、内存和失败恢复。

### 8.1 多 values insert

```xml
<insert id="insertBatch">
    INSERT INTO card_risk_features (
        card_id, window_start, authorization_count, updated_at
    ) VALUES
    <foreach collection="rows" item="row" separator=",">
        (
            #{row.cardId},
            #{row.windowStart},
            #{row.authorizationCount},
            #{row.updatedAt}
        )
    </foreach>
</insert>
```

优点：

- 一条 SQL 插入多行，减少网络 round trip。
- 容易理解和 debug。

限制：

- SQL 太长会触发 packet size 或 parser 压力。
- 单条失败会导致整批失败。
- 需要自己控制 batch size，例如 100、500、1000。

### 8.2 `ExecutorType.BATCH`

MyBatis 也支持 batch executor：

```java
try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
    BatchMapper mapper = session.getMapper(BatchMapper.class);
    for (Row row : rows) {
        mapper.insert(row);
    }
    session.flushStatements();
    session.commit();
}
```

在 Spring Boot 项目里，通常会配置专门的 `SqlSessionTemplate` 或局部使用，避免和普通 mapper 混在一起。

注意：

- batch executor 会缓存 statement 和参数，数据量大时要定期 `flushStatements()`。
- 失败时要知道哪一批失败，再做重试或记录。
- 对金融状态变化，批处理必须考虑 idempotency 和 partial failure。

### 8.3 MySQL JDBC batch 优化

MySQL Connector/J 常见优化参数：

```text
rewriteBatchedStatements=true
```

作用：

- 驱动可以把多次 insert rewrite 成更高效的 multi-values insert。

但它不是银弹：

- 仍要控制 batch size。
- 仍要处理 unique key 冲突。
- 仍要考虑事务时间过长导致 lock 持有太久。

### 8.4 批量更新

批量更新比批量插入更难，因为每行可能更新不同值。

常见方案：

1. 多条 update + `ExecutorType.BATCH`。
2. 用临时表/staging table，再 join update。
3. 用 `CASE WHEN`，但 SQL 可读性和长度会变差。

面试回答：

> 批处理不能只说快。我要说明 batch size、事务范围、失败重试、幂等键、唯一约束、锁持有时间和监控。金融系统里批处理更重要的是可恢复，而不是单次跑得最大。

## 9. Spring 事务与 MyBatis

在 Spring Boot 中，MyBatis mapper 会参与当前 Spring transaction。

典型路径：

```text
@Transactional service method starts transaction
-> mapper select/update uses same database connection
-> method returns
-> Spring commits
```

关键点：

- `@Transactional` 应放在 application service 或明确的 worker/recoverer 方法上。
- MyBatis mapper 本身通常不负责事务边界。
- 同一个事务内的 `SELECT ... FOR UPDATE` 锁会一直持有到 commit/rollback。
- Runtime exception 默认触发 rollback；checked exception 默认不 rollback，除非配置。
- 同一个类内部 self-invocation 调用 `@Transactional` 方法不会经过 Spring proxy，这是常见坑。

面试回答：

> 我会把 transaction boundary 放在 use case 层。Repository/Mapper 只表达数据库操作，不能偷偷开启业务事务。这样才能解释哪些状态变化是原子的，哪些是 eventual consistency。

## 10. 悲观锁、`FOR UPDATE` 与 `SKIP LOCKED`

### 10.1 `SELECT ... FOR UPDATE`

本项目额度冻结使用：

```sql
SELECT id, credit_limit, reserved_amount, posted_balance, currency, status
FROM credit_accounts
WHERE id = ?
FOR UPDATE
```

含义：

- 在事务内锁住这条 account row。
- 同一账户并发授权会被串行化。
- 防止两个请求同时看到相同 available credit，然后都批准。

正确顺序：

```text
begin transaction
-> claim idempotency key
-> lock card/account row
-> validate and change domain state
-> update rows
-> commit
```

### 10.2 `FOR UPDATE SKIP LOCKED`

Outbox 和 DelayJob poller 使用：

```sql
SELECT id, status, next_attempt_at
FROM outbox_events
WHERE status = 'PENDING'
  AND next_attempt_at <= ?
ORDER BY created_at, id
LIMIT ?
FOR UPDATE SKIP LOCKED
```

含义：

- 多个 worker/pod 同时扫描待处理任务。
- 已经被别人锁住的 rows 会被跳过。
- 每条任务只会被一个事务 claim。

适合：

- queue-like table。
- Outbox polling。
- 延迟任务。
- 后台 worker 分片处理。

不适合：

- 用户必须看到完整一致列表的查询。
- 需要严格全局顺序的处理。

### 10.3 锁和索引的关系

`FOR UPDATE` 是否高效，取决于查询条件有没有合适索引。

如果没有索引：

```sql
SELECT * FROM authorizations WHERE idempotency_key = ? FOR UPDATE;
```

数据库可能扫描大量 rows，锁范围更大，性能更差。

应有：

```sql
CREATE UNIQUE INDEX uk_authorizations_idempotency_key
ON authorizations (idempotency_key);
```

面试回答：

> Row lock 不是 Java 代码里写了 `FOR UPDATE` 就自动安全高效。查询必须走合适索引，否则 InnoDB 可能锁更多记录或范围，吞吐会下降，也更容易死锁。

## 11. 乐观锁 Optimistic Lock

常见写法：

```sql
UPDATE credit_accounts
SET reserved_amount = ?, version = version + 1
WHERE id = ?
  AND version = ?
```

如果 update count = 0，说明数据已被别人改过，需要重试或返回冲突。

适合：

- 冲突较少。
- 可以接受重试。
- 不想长时间持有锁。

不适合：

- 高冲突热点账户。
- 必须严格串行化复杂状态检查。

本项目授权额度冻结目前更适合 pessimistic lock：

```text
同一账户可能短时间多笔交易并发，额度判断和状态修改必须在一个 transaction boundary 内串行化。
```

## 12. 事务隔离级别

常见现象：

| 现象 | 含义 |
| --- | --- |
| Dirty read | 读到别人未提交数据 |
| Non-repeatable read | 同一事务内两次读同一行结果不同 |
| Phantom read | 同一事务内两次范围查询，出现新增/消失的 rows |

常见隔离级别：

| Isolation level | 特点 |
| --- | --- |
| READ UNCOMMITTED | 可能脏读，业务系统很少用 |
| READ COMMITTED | 每次查询读已提交版本，很多系统常用 |
| REPEATABLE READ | MySQL InnoDB 默认，事务内一致性读更稳定 |
| SERIALIZABLE | 最强但并发性最差 |

MySQL InnoDB 重点：

- 普通 `SELECT` 是 consistent read，不加锁。
- `SELECT ... FOR UPDATE` 是 locking read，会加锁。
- RR 下范围锁可能涉及 next-key lock。
- 唯一索引等值查询通常锁范围更小。

面试回答：

> 不能只背隔离级别名字，要结合具体 SQL。比如额度冻结不是靠普通 SELECT 的隔离级别保证，而是靠同一事务内对 credit account 做 `SELECT ... FOR UPDATE`，把余额检查和更新串起来。

## 13. 索引核心知识

### 13.1 B+Tree 索引

MySQL InnoDB 常用 B+Tree 索引。

适合：

- 等值查询：`WHERE id = ?`
- 范围查询：`created_at >= ?`
- 排序：`ORDER BY created_at`
- 前缀匹配：`LIKE 'abc%'`

不适合：

- `LIKE '%abc'`
- 对索引列做函数：`DATE(created_at) = ?`
- 隐式类型转换：字符串列拿数字比。

这些会让查询不够 sargable，也就是数据库难以利用索引定位范围。

### 13.2 联合索引和最左前缀

索引：

```sql
CREATE INDEX idx_outbox_pending
ON outbox_events (status, next_attempt_at, created_at, id);
```

适合：

```sql
WHERE status = 'PENDING'
  AND next_attempt_at <= ?
ORDER BY created_at, id
```

理解顺序：

- 等值列通常放前面。
- 范围列之后的列对过滤能力会受限制，但仍可能帮助排序或覆盖。
- 排序字段是否能利用索引，要看 where 条件和 order by 是否匹配索引顺序。

### 13.3 覆盖索引 Covering Index

如果查询需要的列都在索引里，数据库可以少回表。

例子：

```sql
SELECT id, status, next_attempt_at
FROM delay_jobs
WHERE status = 'PENDING'
ORDER BY next_attempt_at
LIMIT 100;
```

索引包含这些列时，可能成为 covering index。

注意：

- 覆盖索引可以提升读性能。
- 但索引越多，insert/update 成本越高。
- 金融系统要平衡读路径和写路径。

### 13.4 唯一索引 Unique Index

唯一索引不只是性能优化，也是业务约束。

例子：

```sql
UNIQUE (idempotency_key)
UNIQUE (event_id, consumer_group)
```

它们分别表达：

- 同一个 idempotency key 只能创建一个 authorization。
- 同一个 consumer group 只能处理同一个 event 一次。

面试回答：

> 对幂等这种 correctness requirement，我不会只靠应用层 check。应用可以先检查提高体验，但最终必须由 database unique constraint 兜底。

## 14. `EXPLAIN` 要会看什么

常用：

```sql
EXPLAIN
SELECT id, status, next_attempt_at
FROM outbox_events
WHERE status = 'PENDING'
  AND next_attempt_at <= NOW()
ORDER BY created_at, id
LIMIT 50;
```

重点字段：

| 字段 | 看什么 |
| --- | --- |
| `type` | `const`/`ref`/`range` 通常比 `ALL` 好 |
| `key` | 实际使用了哪个索引 |
| `rows` | 估计扫描多少行 |
| `Extra` | 是否 `Using filesort`、`Using temporary`、`Using index` |

不要机械追求“没有 filesort”。小结果集 filesort 不一定严重；真正要看数据量、QPS、延迟和是否阻塞写入。

面试回答：

> 我会先用 EXPLAIN 看执行计划，再结合慢查询日志和真实数据分布判断。索引设计不是只看 SQL 语法，还要看 cardinality、选择性、写入成本和业务访问模式。

## 15. 分页 Pagination

### 15.1 Offset pagination

```sql
SELECT id, created_at
FROM authorizations
ORDER BY created_at DESC, id DESC
LIMIT 50 OFFSET 10000;
```

问题：

- offset 越大，数据库跳过的 rows 越多。
- 数据变化时，用户可能看到重复或漏数据。

适合：

- 后台小数据量列表。
- 页数不深。

### 15.2 Keyset pagination

```sql
SELECT id, created_at
FROM authorizations
WHERE (created_at, id) < (?, ?)
ORDER BY created_at DESC, id DESC
LIMIT 50;
```

优点：

- 深分页性能稳定。
- 适合交易流水、消息列表、审计日志。

面试回答：

> 金融交易流水通常更适合 keyset pagination，用上一页最后一条的排序 key 继续查。Offset 深分页会越来越慢，也容易受新增数据影响。

## 16. N+1 查询问题

坏例子：

```text
查 100 条 authorization
-> 每条 authorization 再查一次 card
-> 总共 101 次 SQL
```

解决方式：

- join 查询需要展示的字段。
- 批量 `IN` 查询 cards，再在 Java 内存中 map。
- 读模型/projection 表。
- 缓存低变化维度数据，但不要过早引入 Redis。

面试回答：

> 我会先识别访问模式。如果是列表展示，通常用 join 或 projection；如果是领域聚合写路径，不一定强行 join，避免把聚合边界和持久化优化混在一起。

## 17. Join、聚合和读模型

### 17.1 Join

适合：

- 查询多个表组合出的只读视图。
- 报表和后台管理列表。
- 一次请求需要稳定快照。

注意：

- join 条件必须有索引。
- 不要 join 太多大表。
- 写路径不要为了省一次查询而破坏业务边界。

### 17.2 聚合函数

```sql
SELECT card_id, COUNT(*) AS cnt, SUM(amount) AS total_amount
FROM authorizations
WHERE created_at >= ?
GROUP BY card_id;
```

注意：

- 大表聚合容易慢。
- 高频统计应考虑 projection table、异步汇总或分区。
- 金额汇总必须注意币种，不能把 JPY 和 USD 直接相加。

### 17.3 读模型 Projection

本项目 Risk feature projection 就是一个例子：

```text
Kafka event
-> Risk consumer
-> update projection table
-> risk check can read local feature quickly
```

读模型的价值：

- 为查询优化单独建表。
- 不污染核心交易表。
- 可以通过事件重放重建。

## 18. 金额和时间字段

### 18.1 金额

推荐：

```text
Java: BigDecimal
MySQL: DECIMAL(19,2)
```

不要用：

```text
double / float
```

原因：

- 二进制浮点无法精确表达十进制金额。
- 金融系统需要明确 scale 和 rounding。

更严格的生产系统也可能用 minor unit：

```text
JPY 1000 -> 1000
USD 10.25 -> 1025 cents
```

但无论选 `DECIMAL` 还是 minor unit，都要在 domain model 和数据库设计里讲清楚。

### 18.2 时间

常见建议：

- Java 使用 `Instant` 表达时间点。
- 数据库统一存 UTC。
- API 层再转换成用户时区显示。
- 定时任务、过期时间、账单周期要明确 clock 来源，测试中可注入 `Clock`。

## 19. SQL 注入与安全

基本规则：

- 业务参数用 `#{}`。
- 动态列名、排序方向必须白名单。
- 不拼接用户输入。
- 错误信息不要暴露完整 SQL 和敏感参数。
- 日志里避免打印 card number、PII、token 等敏感数据。

金融后端尤其要注意：

```text
SQL injection is not only data leak.
It can become unauthorized money movement.
```

## 20. 连接池、超时和慢查询

后端工程师需要知道：

- 数据库连接是有限资源，不是线程越多越好。
- 连接池太小会排队，太大会压垮数据库。
- 查询必须有 timeout，避免线程长期卡死。
- 慢查询日志和 metrics 比猜测更可靠。
- 长事务会持有锁和 undo 版本，影响系统整体吞吐。

常见排查顺序：

```text
接口慢
-> 看 application metrics
-> 看数据库连接池等待时间
-> 看 slow query log
-> EXPLAIN 慢 SQL
-> 看锁等待和死锁日志
-> 再决定加索引、改 SQL、改事务范围或改读模型
```

## 21. 死锁 Deadlock

死锁例子：

```text
T1 locks account A, then waits for account B
T2 locks account B, then waits for account A
```

预防：

- 多行锁按固定顺序获取，例如按 account_id 排序。
- 事务尽量短。
- 锁住必要 rows，不做慢外部调用。
- 建好索引，避免锁范围扩大。
- 捕获 deadlock/lock wait timeout，按幂等语义安全重试。

面试回答：

> 死锁不是完全不能出现，关键是降低概率，并让应用能安全重试。前提是请求有 idempotency key，状态迁移有条件，重复执行不会多扣钱。

## 22. MyBatis 常见坑

### 22.1 Mapper XML 没绑定上

常见原因：

- `namespace` 不等于 Mapper 接口全限定名。
- XML `id` 不等于方法名。
- `mapper-locations` 路径不包含 XML。
- 方法参数名和 XML 里的 `#{}` 不一致。

### 22.2 null 参数需要 `jdbcType`

本项目常见写法：

```xml
#{declineReason,jdbcType=VARCHAR}
#{publishedAt,jdbcType=TIMESTAMP}
```

原因：

- 参数为 null 时，JDBC driver 可能需要明确 SQL 类型。

### 22.3 enum 映射

简单做法：

```text
Java enum name <-> DB VARCHAR
```

优点：

- 可读。
- 便于排查。

注意：

- 重命名 enum 会影响历史数据。
- 生产系统可以用稳定 code 字段。

### 22.4 大字段和 JSON

Outbox payload 这类字段可能是 JSON string：

- 写入前要有 schema/version。
- 不要在高频查询里扫描 JSON 大字段。
- 列表查询尽量只取 metadata。

### 22.5 懒加载

MyBatis 支持一些关联映射能力，但在面试项目里不建议依赖神秘懒加载。

更推荐：

- 写清楚 join SQL。
- 或分两次批量查询。
- 或维护 projection。

## 23. 后端面试 SQL 必备清单

你至少要能讲清楚这些：

| 主题 | 必须能回答 |
| --- | --- |
| SQL 基础 | `SELECT`、`INSERT`、`UPDATE`、`DELETE`、`JOIN`、`GROUP BY`、`HAVING` |
| 索引 | B+Tree、联合索引、最左前缀、唯一索引、覆盖索引、索引失效 |
| 事务 | ACID、隔离级别、commit/rollback、长事务风险 |
| 锁 | row lock、gap lock/next-key lock、`FOR UPDATE`、deadlock |
| 幂等 | unique constraint、idempotency key、重复请求返回原结果 |
| 批处理 | batch size、事务范围、失败恢复、partial failure |
| 性能 | EXPLAIN、slow query、连接池、分页、N+1 |
| 金额 | `BigDecimal`、`DECIMAL`、币种、rounding |
| 时间 | UTC、`Instant`、时区显示、过期任务 |
| 安全 | SQL injection、参数绑定、敏感日志 |

## 24. 本项目可以如何讲给面试官

### 24.1 为什么用 MyBatis

推荐回答：

> 这个项目是信用卡后台练习项目，我希望 SQL 保持显式。授权、额度冻结、幂等和后台任务 claim 都依赖数据库约束和锁。MyBatis 让我可以直接写 `FOR UPDATE`、`SKIP LOCKED` 和唯一约束相关 SQL，同时又不用手写所有 JDBC mapping。

### 24.2 如何保证并发授权不超额

推荐回答：

> 请求先用 `idempotency_key` 做 insert-first claim，防止重试重复执行。然后在同一个 Spring transaction 里对 credit account 执行 `SELECT ... FOR UPDATE`。锁持有期间完成额度检查和 reserved amount 更新。这样同一账户的额度变化被数据库 row lock 串行化，不依赖单 JVM 的 synchronized。

### 24.3 为什么后台任务用 `SKIP LOCKED`

推荐回答：

> Outbox 和 DelayJob 是 queue-like table。多个 worker 可以同时扫描 due rows，`FOR UPDATE SKIP LOCKED` 让每个 worker 跳过别人已经 claim 的 rows，再把自己拿到的 rows 改成 PROCESSING。这样可以水平扩展 worker，同时避免重复 claim。

### 24.4 如何做批量消费或批量入库

推荐回答：

> 我会先按业务幂等键设计唯一约束，再选择 multi-values insert 或 MyBatis batch executor。批大小不会无限大，会按数据库压力、网络包大小、事务时间和失败恢复能力来调。批处理失败后要能定位失败批次，并安全重试。

### 24.5 如何优化慢 SQL

推荐回答：

> 先看慢查询日志和真实参数，再用 EXPLAIN 看是否走了预期索引、扫描行数和 filesort/temporary。然后结合业务访问模式调整联合索引、减少 SELECT 列、改 keyset pagination、拆读模型，或者缩小事务范围。不会只凭感觉加索引，因为索引会增加写入成本。

## 25. 练习建议

可以按这个顺序练：

1. 读 `AuthorizationMapper.xml`，解释 `resultMap`、`<sql>`、`#{}`、`FOR UPDATE`。
2. 读 `CreditAccountMapper.xml`，解释为什么额度更新前要锁 account row。
3. 读 `OutboxEventMapper.xml` 和 `DelayJobMapper.xml`，解释 `SKIP LOCKED` 如何支持多 worker。
4. 自己写一个动态查询 mapper，包含 `<where>`、`<if>`、分页和排序白名单。
5. 自己写一个 batch insert mapper，并说明 batch size、事务、失败重试策略。
6. 对一条查询写出预期索引，再用 `EXPLAIN` 验证。
7. 用一句话讲清楚：数据库唯一约束为什么比应用层 if check 更可靠。

最后再记住这句面试总纲：

```text
MyBatis 只是 SQL 执行和映射工具。
真正体现后端能力的是：表怎么设计、索引怎么选、事务怎么切、锁怎么拿、失败怎么恢复、重复请求怎么幂等。
```
