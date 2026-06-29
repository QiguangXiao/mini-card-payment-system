# 数据库 Migration 与 Liquibase 操作说明

这份文档说明本项目为什么引入轻量 Liquibase、它解决什么问题、平时怎么操作，以及遇到旧表结构时应该怎样写 migration。

## 1. Liquibase 适合吗？

适合，但要用轻量方式。

本项目不是要演示企业级发布平台，所以没有引入复杂的 Liquibase CLI 流程、回滚包、审批平台或 XML 大配置。当前采用：

- Spring Boot 启动时自动执行 Liquibase。
- `formatted SQL changelog`，仍然直接写 MySQL SQL。
- 一个 master changelog 管理执行顺序。
- 每次表结构变化都追加新的 changeset，不再修改已经执行过的 changeset。

和 Flyway 相比，Flyway 更极简，适合纯 SQL、线性版本号的团队。Liquibase 稍微重一点，但它的 `precondition` 更适合这个学习项目：我们经常要解释“旧库有没有这个列”“旧约束是否存在”“本地 Docker volume 里表结构是不是过时”。这些 schema drift 场景用 Liquibase 更容易写成可重复执行、可解释的步骤。

## 2. 它解决什么问题？

早期项目靠 `schema.sql` 和 `CREATE TABLE IF NOT EXISTS` 建表。当前代码已经迁到
Liquibase changelog；这里保留 `schema.sql` 的说法，是为了说明为什么需要 migration。
那个早期方式对空库很方便，但有一个金融后端里很常见的坑：

```text
代码和 schema 文件已经改了，但本地或某个环境里的表已经存在。
CREATE TABLE IF NOT EXISTS 不会帮你加列、删旧列、补索引、更新 CHECK constraint。
```

Liquibase 的作用是把 schema 变化变成有版本记录的变更历史：

- 第一次启动时创建 `DATABASECHANGELOG`，记录哪些 changeset 已执行。
- 用 `DATABASECHANGELOGLOCK` 防止多个应用实例同时迁移同一个数据库。
- 应用启动时只执行还没执行过的 changeset。
- 每个字段、索引、约束、数据回填都可以作为明确步骤记录下来。

对 PayPay Card 这类金融系统 interview，可以这样表达：

> Schema migration 不是简单建表。它要处理历史数据、兼容旧版本代码、约束补齐、失败恢复和审计记录。否则代码发布后，旧表结构可能导致请求失败，或者更糟糕的是让金额、状态和幂等约束失效。

## 3. 当前文件结构

```text
build.gradle
src/main/resources/application.yml
src/main/resources/db/changelog/db.changelog-master.yaml
src/main/resources/db/changelog/changes/0001-initial-schema.sql
src/main/resources/db/changelog/changes/0002-sync-known-local-schema-drift.sql
src/main/resources/db/changelog/changes/0003-seed-local-sample-data.sql
```

关键点：

- `build.gradle` 引入 `org.liquibase:liquibase-core`。
- `application.yml` 关闭 Spring SQL init：`spring.sql.init.mode: never`。
- `application.yml` 指向 `classpath:db/changelog/db.changelog-master.yaml`。
- `0001-initial-schema.sql` 是当前空库 baseline，只负责建当前完整表结构。
- `0002-sync-known-local-schema-drift.sql` 处理已经出现过的旧表结构问题。
- `0003-seed-local-sample-data.sql` 插入本地学习用样例卡和账户。

为什么 seed data 单独放在 `0003`，并且排在 `0002` 后面？

因为旧库可能还没有 `credit_accounts.posted_balance`。如果 baseline 先执行带 `posted_balance` 的 `INSERT IGNORE`，旧表会直接报 unknown column。先补结构，再插样例数据，顺序更安全。

## 4. 日常怎么操作？

### 4.1 第一次启动或空库启动

```bash
docker compose up -d
./gradlew bootRun
```

应用启动时 Liquibase 会自动执行：

```text
0001-initial-schema.sql
0002-sync-known-local-schema-drift.sql
0003-seed-local-sample-data.sql
```

### 4.2 查看已经执行过哪些 changeset

```bash
docker compose exec mysql mysql -uroot -prootpassword mini_card \
  -e "SELECT ID, AUTHOR, FILENAME, DATEEXECUTED, ORDEREXECUTED FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED"
```

重点看：

- `ID`：changeset id，例如 `0002-authorization-posted-at`。
- `FILENAME`：来自哪个 changelog 文件。
- `DATEEXECUTED` / `ORDEREXECUTED`：执行时间和顺序。

### 4.3 新增一次 schema 变化

假设要给 `repayments` 增加 `failure_reason`：

1. 新建文件，例如：

```text
src/main/resources/db/changelog/changes/0004-add-repayment-failure-reason.sql
```

2. 写 formatted SQL：

```sql
--liquibase formatted sql

--changeset mini-card:0004-add-repayment-failure-reason dbms:mysql
--comment: Store bank debit failure reason for retry and customer support investigation.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'repayments' AND column_name = 'failure_reason'
ALTER TABLE repayments
    ADD COLUMN failure_reason VARCHAR(200) NULL AFTER status;
```

3. 在 `db.changelog-master.yaml` 末尾 include：

```yaml
  - include:
      file: db/changelog/changes/0004-add-repayment-failure-reason.sql
```

4. 启动应用或跑测试，让 Liquibase 执行。

5. 不要再改已经发布并执行过的 changeset。要修正就追加新的 changeset。

### 4.4 本地可以重建，真实数据不能随便重建

本地学习环境如果数据不重要，可以：

```bash
docker compose down -v
docker compose up -d
./gradlew bootRun
```

但真实环境不能用删库重建代替 migration。真实环境应该先确认：

- 旧数据如何 backfill。
- 新旧代码是否需要短期兼容同一张表。
- 新 constraint 是否会被历史脏数据卡住。
- 大表 DDL 是否会锁表或影响线上流量。
- 是否需要先加 nullable column，再回填，再改 NOT NULL。

## 5. 旧表结构过时的几种例子

### 例子一：新增列，但旧表已经存在

问题：

```text
authorizations 表已存在，但没有 posted_at。
MyBatis insert/update 开始写 posted_at 后，旧库报 Unknown column。
```

迁移方式：

```sql
ALTER TABLE authorizations
    ADD COLUMN posted_at TIMESTAMP(6) NULL AFTER expires_at;
```

为什么先允许 `NULL`？

旧数据里的授权可能是 `APPROVED`、`DECLINED` 或 `EXPIRED`，它们本来就没有 posting 时间。先加 nullable column，避免把历史数据硬塞成假的时间。

### 例子二：字段语义升级，不只是加列

旧模型：

```text
notifications.authorization_id
notifications.card_id
```

旧模型只能表达 authorization 通知。后来 notification 要支持 authorization、posting、statement、repayment，就改成：

```text
subject_type
subject_id
recipient_key
```

迁移方式不是直接删旧列，而是：

1. 加新列，先允许 `NULL`。
2. 用旧字段 backfill：

```sql
UPDATE notifications
SET subject_type = 'AUTHORIZATION'
WHERE subject_type IS NULL;

UPDATE notifications
SET subject_id = authorization_id
WHERE subject_id IS NULL
  AND authorization_id IS NOT NULL;

UPDATE notifications
SET recipient_key = card_id
WHERE recipient_key IS NULL
  AND card_id IS NOT NULL;
```

3. 改成 `NOT NULL`。
4. 删除旧列。
5. 加新索引和新 constraint。

interview 重点：这是 schema migration 里的 data backfill。字段改名通常不是纯 DDL，它还包含历史数据语义转换。

### 例子三：约束规则过时

旧额度约束：

```sql
reserved_amount <= credit_limit
```

后来增加 presentment posting 和 repayment 后，额度占用不只看授权冻结金额，还要看已经入账但未还款的 `posted_balance`。

新约束：

```sql
reserved_amount + posted_balance <= credit_limit
```

迁移方式：

1. 添加 `posted_balance` 并给旧数据默认 `0.00`。
2. 删除旧的 reserved-only check。
3. 添加 `posted_balance >= 0`。
4. 添加 `reserved_amount + posted_balance <= credit_limit`。

interview 重点：constraint 是业务 invariant 的数据库防线。业务模型变了，数据库约束也要跟着变，否则并发或 bug 可能绕过 Java 层保护。

### 例子四：枚举值过时

旧 notification subject type 可能只允许：

```text
AUTHORIZATION, CARD_TRANSACTION, STATEMENT
```

后来加了 repayment notification 后，必须把 `REPAYMENT` 加进 check constraint：

```sql
ALTER TABLE notifications DROP CHECK chk_notifications_subject_type;

ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_subject_type CHECK (
        subject_type IN ('AUTHORIZATION', 'CARD_TRANSACTION', 'STATEMENT', 'REPAYMENT')
    );
```

interview 重点：Java enum、数据库 check constraint、消息 payload 里的 event type 要一起演进，否则应用代码已经支持新类型，落库时仍可能失败。

### 例子五：查询方式变了，需要补索引

statement batch 会扫描：

```text
status = POSTED
statement_id IS NULL
posted_at <= period_end
按 credit_account_id 分组
```

因此需要：

```sql
CREATE INDEX idx_card_transactions_billing_batch
    ON card_transactions (status, statement_id, posted_at, credit_account_id);
```

interview 重点：migration 不只负责 columns，也负责 indexes。业务查询路径变了，但索引没跟上，高流量下会变成慢 SQL 或锁等待。

## 6. 写 migration 的顺序原则

推荐顺序：

1. 先加兼容字段，尽量 nullable。
2. backfill 历史数据。
3. 代码切到新字段。
4. 再加 `NOT NULL`、unique、check constraint。
5. 最后删除旧字段。

不推荐：

- 在同一次发布里直接删除旧字段，又让旧版本代码继续运行。
- 直接加 `NOT NULL`，但没有 backfill。
- 修改已执行过的 changeset，导致 checksum 不一致。
- 只改 Java mapper，不写 migration。
- 只改 migration，不更新领域文档和 walkthrough。

## 7. 当前方案的边界

这个项目目前的 migration 是学习项目级别，不是生产发布平台：

- 没有做自动 rollback。金融系统里的 rollback 往往不是简单反向 DDL，而是要设计 forward fix。
- MySQL DDL 很多时候会隐式提交，不要假设所有 DDL 都在一个普通业务事务里。
- 大表变更需要评估锁表、复制延迟、在线 DDL 和灰度发布，这里只保留 interview 解释点，不引入额外工具。
- 本地 seed data 是为了学习和手动 API 验证，不代表生产数据初始化方式。

## 8. 参考

- [Spring Boot Database Initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Liquibase SQL changelog example](https://docs.liquibase.com/secure/user-guide-5-1/sql-changelog-example)
