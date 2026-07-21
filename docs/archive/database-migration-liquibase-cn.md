# 数据库 Migration 与 Liquibase 学习笔记

> **归档对齐说明（2026-07）**：当前 active changelog 是 `0001` 到 `0007`，不是只有 baseline/seed。
> `0003–0007` 是 forward-only cleanup migrations，依次删除 Ledger、历史 Risk projection、statement/repayment
> notification 记录，并收紧 Statement status。本文同时保留 baseline reset 与经典 migration 的学习价值。

这份文档记录本项目为什么使用 Liquibase、baseline reset 后如何继续 forward migration，以及现行
cleanup changeset 与历史模型演进里值得学习的案例。

关键词：Liquibase, baseline reset, schema migration, data backfill, CHECK constraint, unique constraint, online DDL, forward fix, マイグレーション。

## 1. 当前状态：baseline reset 后已追加 `0003–0007`

当前 `db.changelog-master.yaml` 按下面顺序 include：

```text
src/main/resources/db/changelog/db.changelog-master.yaml
src/main/resources/db/changelog/changes/0001-initial-schema.sql
src/main/resources/db/changelog/changes/0002-seed-local-sample-data.sql
src/main/resources/db/changelog/changes/0003-remove-ledger-projection.sql
src/main/resources/db/changelog/changes/0004-remove-historical-risk-projection.sql
src/main/resources/db/changelog/changes/0005-remove-statement-notification-event.sql
src/main/resources/db/changelog/changes/0006-remove-repayment-notification-event.sql
src/main/resources/db/changelog/changes/0007-remove-statement-overdue-status.sql
```

- `0001-initial-schema.sql`：reset 时形成的宽 baseline，创建核心表，也仍创建随后会被 cleanup 删除的
  `ledger_entries`、`card_risk_features` 和旧 notification/status 形态。
- `0002-seed-local-sample-data.sql`：本地 walkthrough 样例；其中历史 Ledger、Risk、statement/repayment
  notification 样例会在后续 cleanup 中被删除。
- `0003`：解除 `statement_lines.ledger_entry_id` 的 FK/unique 后删列，再删除 `ledger_entries`。
- `0004`：先清旧 Risk consumer 的 Inbox 进度，再删除 `card_risk_features`。
- `0005`：按 delivery → notification → outbox 顺序清理 `statement.closed` 数据。
- `0006`：清理 repayment notification 对应 Inbox、delivery、intent 和 `repayment.received` Outbox。
- `0007`：删除从未有写入方的 `OVERDUE`，收紧 `chk_statements_status/payment_state`。

因此“当前最终 schema”是**顺序执行 `0001–0007` 后的结果**，不能只读 `0001` 就下结论。`0001/0002`
仍含历史对象是一次明确的迁移链痕迹，不代表这些表、事件或 enum 仍被现行 Java 代码使用。

这次 reset 的前提是：本项目是学习/interview 项目，没有真实生产数据库需要保留旧 Liquibase checksum。如果是生产系统，不能随便改已执行过的 changeset；应该继续 append 新 migration。

## 2. 为什么要做 baseline reset

旧 changelog 同时承担两件事：

1. 给空库创建当前 schema。
2. 记录项目从旧模型演进到新模型的历史步骤。

对学习者来说，这会造成一个认知噪音：想看“当前最终表结构”时，要从 `0001` 跑到 `0010`，中间还会看到已经被删除的 `statement_batches`、已改名的 `template`、已迁走的 notification lifecycle 字段。

baseline reset 后，职责更清楚：

- `0001–0007` 的合成结果负责回答：**当前系统有哪些表、字段、索引、约束？**
- 本文负责回答：**历史上为什么要这样迁移，真实生产迁移要注意什么？**

interview 里可以这样说：

> For a learning repository, I reset the baseline so new databases start from the current source of truth. I still kept the migration lessons as documentation. In production, I would not rewrite executed changelogs; I would append forward migrations and handle old data with backfill and compatibility windows.

## 3. Liquibase 在项目里的角色

Liquibase 不是为了“自动建表”这么简单。它解决的是 schema 和代码一起演进的问题：

- 记录已执行 changeset 到 `DATABASECHANGELOG`。
- 通过 `DATABASECHANGELOGLOCK` 防止多个应用实例同时迁移同一个 DB。
- 空库按顺序初始化。
- 旧库只执行还没执行过的 changeset。
- 每次字段、索引、约束、数据回填都能作为可 review 的步骤落地。

金融后端要特别关心：

- 历史数据是否能通过新约束。
- 新旧代码是否短时间兼容同一张表。
- 大表 DDL 是否锁表、影响写入或复制。
- 失败后是 rollback 还是 forward fix。
- 幂等、金额、状态机约束是否在 DB 层兜底。

## 4. 当前日常操作

空库启动：

```bash
docker compose up -d
./gradlew bootRun
```

查看 Liquibase 执行记录：

```bash
docker compose exec mysql mysql -uroot -prootpassword mini_card \
  -e "SELECT ID, AUTHOR, FILENAME, DATEEXECUTED, ORDEREXECUTED FROM DATABASECHANGELOG ORDER BY ORDEREXECUTED"
```

新增真实 schema 变化时，不再改已执行的 `0001–0007`，而是从 `0008-xxx.sql` 继续追加：

```sql
--liquibase formatted sql

--changeset mini-card:0008-add-repayment-failure-reason dbms:mysql
--comment: Store bank debit failure reason for retry and support investigation.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'repayments' AND column_name = 'failure_reason'
ALTER TABLE repayments
    ADD COLUMN failure_reason VARCHAR(200) NULL AFTER status;
```

然后在 `db.changelog-master.yaml` 末尾 include `0008-add-repayment-failure-reason.sql`。

## 5. 写 migration 的安全顺序

推荐顺序：

1. 先加兼容字段，尽量 nullable。
2. 部署能同时读旧字段/新字段的代码，或先保持旧代码不受影响。
3. backfill 历史数据。
4. 加 `NOT NULL`、unique、CHECK、FK 等强约束。
5. 代码完全切到新字段。
6. 最后删除旧字段。

不推荐：

- 直接修改已执行过的 changeset，导致 checksum 不一致。
- 一次发布里直接删旧字段，但旧版本应用还在运行。
- 对有历史数据的大表直接加 `NOT NULL`，没有 default/backfill。
- 只改 mapper，不写 migration。
- 只写 migration，不更新 domain docs、walkthrough、测试和本地 seed。

## 6. 经典迁移案例

前五个案例中，Notification 拆表、Statement job 扁平化、lease token、statement version 和 credit exposure
已经体现在 `0001` baseline 的现行结构里；Ledger/Risk/冗余通知/OVERDUE 的删除则是 `0003–0007` 的 active
forward migrations。它们不需要逐条背 SQL，但要能讲清依赖顺序、数据清理、约束变化和 rollback/forward-fix 取舍。

### 6.1 Notification：从单表生命周期拆成 intent + delivery

旧模型把通知意图和投递状态混在 `notifications`：

```text
notifications.status
notifications.delivery_attempts
notifications.last_error
notifications.sent_at
```

问题是：一条通知可以通过多个渠道发送。APP_PUSH 成功、EMAIL 失败重试时，单个 `status` 无法表达局部成功/局部失败。

最终模型：

```text
notifications              -- 通知意图，一条业务事件只创建一次
notification_deliveries    -- 每个 channel 一条 delivery，独立 PENDING/PROCESSING/SENT/DEAD
```

迁移步骤的生产形态应该是：

1. 创建 `notification_deliveries`。
2. 对旧通知按渠道回填 delivery rows。
3. worker/consumer 切到 delivery 表。
4. 确认旧字段不再被代码读取。
5. 删除 `notifications.status/delivery_attempts/last_error/sent_at`。

interview 重点：

- 这是 domain modeling 迁移，不只是“拆表”。
- 拆表后才能表达 per-channel retry 和 eventual consistency。
- `UNIQUE(notification_id, channel)` 是幂等防线，防止同一渠道重复创建 delivery。
- delivery 保存 `notification_type/subject_id/recipient_key` 快照，让 worker 不必 JOIN parent 才能发送。

### 6.2 Statement jobs：从 parent batch 扁平化到 claimable jobs

旧模型：

```text
statement_batches(id, period_start, period_end, due_date, status, ...)
statement_jobs(batch_id, shard_no, shard_count, ...)
```

最终模型：

```text
statement_jobs(period_start, period_end, due_date, shard_no, shard_count, ...)
```

为什么删掉 `statement_batches`？

- 当前项目不需要单独维护 batch 生命周期。
- “本期是否全部完成”可以通过 `statement_jobs` 按 cycle 查询得到。
- 少一张 parent 表，scheduler 的幂等创建由 `UNIQUE(period_start, period_end, shard_no)` 负责。

迁移里的关键点：

- `statement_jobs` 是可重建的 ephemeral 工作项，历史迁移里可以先清空再加 NOT NULL cycle 列。
- 生产里如果 job 表已有重要审计含义，就不能直接 `DELETE`，需要 backfill cycle 字段再切换。
- 先删 FK/旧唯一键，再删 `batch_id`，否则 DDL 会失败。

interview 重点：

> I removed a parent batch table because it did not own a meaningful business lifecycle. The durable unit is the shard job itself, and completion can be derived by querying jobs for the cycle. This reduces schema ceremony while keeping idempotency through a cycle/shard unique key.

### 6.3 Lease token：不要把 deadline 当 owner identity

旧 claimable-job 模型只靠时间字段：

```text
next_attempt_at 或 claim_until = lease deadline
```

问题是：deadline 只回答 **WHEN 到期**，不能回答 **WHO 持有**。worker 在事务外执行较慢时，可能发生：

```text
worker A claim row -> PROCESSING
A 执行业务很慢，lease timeout
recoverer 把 row 放回 PENDING
worker B 重新 claim
A 回来 finalize，如果不校验 owner token，就可能覆盖 B 的结果
```

最终模型：

```text
deadline field: next_attempt_at / claim_until
owner field:    lease_token / claim_token
```

CHECK 约束：

```sql
(status = 'PROCESSING' AND lease_token IS NOT NULL)
OR
(status <> 'PROCESSING' AND lease_token IS NULL)
```

interview 重点：

- `nextAttemptAt` 是 retry/deadline，`lease_token` 是 ownership。
- worker finalize 前重新 `SELECT ... FOR UPDATE`，比较当前 DB token 与 claimed snapshot token。
- token 用 UUID 精确比较，避免 timestamp 精度 round-trip 问题。
- 这是 Outbox、DelayJob、NotificationDelivery、StatementJob 的共同 mental model。

### 6.4 Statement version：read model cache 需要正式版本

旧思路可能会用业务字段推导缓存版本，例如 `paidAmount`。问题是：不是所有展示变化都会改变金额；
即使当前写路径主要由 repayment 推进，未来 due date 调整、争议标记或展示字段修复都可能金额不变。

最终模型：

```text
statements.version BIGINT NOT NULL DEFAULT 0
```

它服务 Redis L2 cache 的 CAS/tombstone：

- DB 更新 statement 时同事务递增 version。
- cache 写入携带 `<version>|<payload>`。
- 迟到旧版本写入会被 CAS 拒绝。
- eviction 写 tombstone，也带 version，挡住旧读回填。

interview 重点：

> Cache consistency needs a monotonic source-of-truth version, not a derived business value. The version belongs in the DB row and is updated in the same transaction as the business state.

### 6.5 Credit exposure：约束随业务模型升级

早期只需：

```text
reserved_amount <= credit_limit
```

引入 presentment posting 后，账户风险暴露变成：

```text
reserved_amount + posted_balance <= credit_limit
```

迁移顺序：

1. 加 `posted_balance`，旧数据默认 0。
2. 删除旧的 reserved-only CHECK。
3. 加 `posted_balance >= 0`。
4. 加 `reserved_amount + posted_balance <= credit_limit`。

interview 重点：

- CHECK 是业务 invariant 的数据库防线。
- 业务模型变了，数据库约束必须跟着变。
- 不然 Java 层看似正确，手工 SQL、旧代码、并发 bug 仍可能写出非法金额状态。

### 6.6 现行 `0003–0007`：删除路径也要有依赖顺序

这五个 cleanup changeset 展示了另一类常被忽略的 migration：代码已经删掉旧机制后，如何安全清理 schema
和 durable work data。

| changeset | 顺序为什么重要 | 如果漏掉会怎样 |
| --- | --- | --- |
| `0003-remove-ledger-projection` | 先 drop `statement_lines` 的 FK/unique/column，再 drop parent table | 直接删 `ledger_entries` 会被 FK 拒绝 |
| `0004-remove-historical-risk-projection` | 先删旧 consumer Inbox 进度，再删 projection table | 运维会误以为仍有 Risk Kafka 下游；残留进度也不能代表现行消费 |
| `0005-remove-statement-notification-event` | delivery → intent → Outbox | 先删 parent intent 违反 FK；残留 PENDING/DEAD Outbox 会被 worker 重试成 unsupported event |
| `0006-remove-repayment-notification-event` | Inbox → delivery → intent → Outbox | 只删 Java enum 会使历史 row 在 MyBatis enum restore 时失败；只删 intent 会留下误导的幂等进度 |
| `0007-remove-statement-overdue-status` | 先确认 DB 无历史 `OVERDUE`，再 drop/recreate CHECK | Java enum 与 DB 允许值不一致时，脏写会拖到读取时才以 `valueOf` 500 暴露 |

这里的 interview 重点不是“会写 DROP”，而是：**删除代码、历史数据、异步工作项、Inbox 进度和 DB constraint
必须作为一个兼容性窗口来设计。** Durable queue 表不是业务审计 source of truth；旧事件已无 publisher 路由时，
保留其待处理 row 反而会制造永久失败。

## 7. Migration interview 问答

**Q: 为什么不能直接改已执行的 changelog？**

因为 Liquibase 会记录 checksum。生产库已经执行过的 changeset 被修改后，下次启动可能 checksum mismatch。生产修正应追加新的 forward migration。

**Q: 什么时候可以 baseline reset？**

学习项目、无生产数据、团队明确接受重建 DB 时可以。生产系统通常只在新服务首次上线前整理 baseline；已上线系统要保留历史 migration。

**Q: 添加 NOT NULL 字段怎么做？**

先加 nullable 或带安全 default，backfill 历史数据，再改 NOT NULL。大表还要评估锁表和 online DDL。

**Q: 删除字段怎么做？**

先让新旧代码兼容，确认没有读写旧字段，再删除。灰度发布期间尤其不能先删字段。

**Q: 为什么 migration 也要考虑索引？**

因为业务查询路径变化后，旧索引可能不服务新 SQL。没有合适索引的 `FOR UPDATE` 或 batch scan 会扩大扫描范围，导致慢查询、锁等待和死锁概率上升。

**Q: rollback 怎么办？**

金融系统经常更偏向 forward fix。很多 DDL 难以安全反向执行，尤其是删除字段和数据转换。上线前要设计可恢复路径、备份、灰度和监控。

## 8. 当前项目的 DB 约束取舍

这次 baseline reset 后，又做了两处刻意放宽：

- `authorizations` 不再用 DB CHECK 强制 `posted_at <= expires_at`。真实 presentment/clearing 可能晚于 authorization expiry，是否接受 late presentment 是业务策略，应放在 application/domain 流程里判断，而不是写死成 row-level CHECK。
- `notifications.subject_type` 不再做 DB 白名单 CHECK。Notification 是扩展型外围能力，新业务主题应先由 Java enum、listener 和 domain 语义控制；否则每增加一个通知对象都要做 schema migration，维护成本大于收益。

剩下仍可作为下一轮 schema review 讨论：

- `cards.status` 是否补 CHECK：`ACTIVE/BLOCKED/EXPIRED`。
- `credit_accounts.status` 是否补 CHECK：`ACTIVE/BLOCKED`。
- `notifications.notification_type` 是否补 CHECK，对齐 Java enum。
- `delay_jobs.job_type` 是否补 CHECK：`AUTHORIZATION_EXPIRY/AUTO_REPAYMENT`。
- `delay_jobs UNIQUE(job_type, aggregate_type, aggregate_id)` 是否足够；未来如果同一 aggregate 需要多次同类 job，可能要加入 business key 或 scheduled_at。

## 9. 参考

- [Spring Boot Database Initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Liquibase SQL changelog example](https://docs.liquibase.com/secure/user-guide-5-1/sql-changelog-example)
