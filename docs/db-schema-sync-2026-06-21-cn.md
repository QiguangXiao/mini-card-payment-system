# 2026-06-21 本地 DB Schema 同步记录

这份记录说明本次把本地 Docker MySQL 里的旧表结构同步到当时 schema 快照的过程。
它不是通用 migration 工具，而是一次 local schema drift 修复记录，方便之后排查
“代码已经改了字段，但本地 DB 还是旧表”的问题。

后续项目已引入 Liquibase；通用操作请看
`docs/database-migration-liquibase-cn.md`，当前 changelog 入口是
`src/main/resources/db/changelog/db.changelog-master.yaml`。

## 1. 背景

本次启动应用后，真实请求 `POST /api/authorizations` 先后暴露出两个旧库问题：

- `authorizations` 缺少 `posted_at`，导致 MyBatis insert 报 `Unknown column 'posted_at'`。
- `notifications` 仍是旧模型，包含 `authorization_id` / `card_id`，缺少新的
  `subject_type` / `subject_id` / `recipient_key`，导致 Kafka notification consumer 写入失败。

当时仓库里的 schema 快照已经是新模型；问题来自本地 MySQL Docker volume 保留了旧表结构。

## 2. 本次同步的表结构

### 2.1 authorizations

已补齐：

- `posted_at TIMESTAMP(6) NULL`
- `chk_authorizations_decision_state` 约束，包含 `POSTED` 状态和 `posted_at` 校验

业务含义：

- authorization 在 presentment posting 后会从 `APPROVED` 变为 `POSTED`。
- `posted_at` 记录 issuer 视角下授权变成已入账交易的时间。

### 2.2 notifications

已从旧字段迁移到通用 subject model：

- 新字段：`subject_type VARCHAR(50) NOT NULL`
- 新字段：`subject_id VARCHAR(100) NOT NULL`
- 新字段：`recipient_key VARCHAR(100) NOT NULL`
- 新索引：`idx_notifications_recipient (recipient_key, created_at)`
- 新约束：`chk_notifications_subject_type`
- 删除旧字段：`authorization_id`、`card_id`

历史数据回填规则：

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

业务含义：

- `subject_type + subject_id` 表达通知关联的业务对象。
- `recipient_key` 表达当前项目里通知的路由线索；因为还没有 Cardholder/User 模型，所以 authorization 通知暂时使用 `cardId`。

### 2.3 credit_accounts

已更新金额约束：

- 保留：`chk_credit_accounts_limit_positive`
- 保留：`chk_credit_accounts_reserved_non_negative`
- 新增：`chk_credit_accounts_posted_non_negative`
- 新增：`chk_credit_accounts_used_within_limit`
- 删除旧约束：`chk_credit_accounts_reserved_within_limit`

业务含义：

- 可用额度不只看 `reserved_amount`，还要看已经入账但未还款的 `posted_balance`。
- 正确约束是 `reserved_amount + posted_balance <= credit_limit`。

### 2.4 outbox_events

已补齐：

- `chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'DEAD'))`

业务含义：

- Outbox 是 reliable publish 机制。
- `PROCESSING` 是 lease 状态，不是最终状态；worker 或应用崩溃后由 recoverer 重试。

### 2.5 card_transactions

已补齐：

- `idx_card_transactions_billing_batch (status, statement_id, posted_at, credit_account_id)`

业务含义：

- statement batch 会扫描 `POSTED AND statement_id IS NULL` 的交易。
- 该索引用于支持账单批处理按状态、账单归属、入账时间和账户聚合查询。

## 3. 本次验证

迁移前数据检查：

- `credit_accounts` 没有 `posted_balance < 0`。
- `credit_accounts` 没有 `reserved_amount + posted_balance > credit_limit`。
- `outbox_events` 没有非法状态或负数 attempts。
- `notifications` 有 1 条旧记录缺少新 subject 字段，已用旧 `authorization_id` / `card_id` 回填。

迁移后结构检查：

- `notifications.subject_type`、`subject_id`、`recipient_key` 已是 `NOT NULL`。
- `notifications` 旧字段 `authorization_id`、`card_id` 已移除。
- `idx_notifications_recipient` 已存在。
- `idx_card_transactions_billing_batch` 已存在。
- `chk_notifications_subject_type`、`chk_outbox_status`、新的 credit account 金额约束已存在。

真实请求验证：

```http
POST /api/authorizations
Idempotency-Key: codex-db-migration-002
Content-Type: application/json
```

```json
{
  "cardId": "card-123",
  "amount": "123.45",
  "currency": "JPY",
  "merchantId": "merchant-codex",
  "merchantCountry": "JP",
  "cardholderCountry": "JP"
}
```

验证结果：

- API 返回 `APPROVED`。
- Outbox event `authorization.approved` 变为 `PUBLISHED`。
- Risk feature projection 成功。
- Notification consumer 成功写入新模型通知：
  `subject_type = AUTHORIZATION`、`subject_id = <authorizationId>`、`recipient_key = card-123`。

## 4. 之后字段变更的规则

以后如果代码、mapper、domain 或 Liquibase changelog 增加/删除/改名 DB 字段，需要同步处理：

- 追加 Liquibase changeset，并让实际本地 DB 执行迁移，而不是只改建表快照。
- 对旧数据做 backfill 或明确说明为什么可以清空/重建。
- 补齐相关 index、foreign key、check constraint。
- 汇报时明确列出改了哪些 columns、indexes、constraints、data backfill。
- 如果改动影响主流程，跑一条真实请求验证 API、Outbox/Kafka consumer 和最终落库。

interview表达：

> Schema drift 是线上常见问题。金融系统里不能只依赖应用启动时自动建表，更要把字段变更、历史数据回填、约束补齐和回滚策略作为 migration 的一部分。本项目现在用 Liquibase 记录版本化 migration；生产系统还需要补充在线 DDL、发布窗口和 forward-fix 策略。
