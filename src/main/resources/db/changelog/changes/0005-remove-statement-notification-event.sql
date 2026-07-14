--liquibase formatted sql

--changeset mini-card:0005-remove-statement-notification-event dbms:mysql splitStatements:true
--comment: Remove persisted statement notification records after deleting the redundant statement.closed Kafka path.

-- 先删渠道投递，再删通知意图，最后清理 Outbox；这个顺序避免违反 notification_deliveries 外键。
-- 反向事实：如果保留这些行却删掉 Java enum，MyBatis 还原历史投递时会因未知类型失败。
DELETE FROM notification_deliveries
WHERE notification_type IN ('STATEMENT_CLOSED', 'STATEMENT_READY');

DELETE FROM notifications
WHERE notification_type IN ('STATEMENT_CLOSED', 'STATEMENT_READY');

-- statement.closed 不再有 Kafka topic 路由；清理旧 Outbox 行，避免 PENDING/DEAD 历史数据被 worker 重试后
-- 因 unsupported integration event type 永久失败。Outbox 是投递工作表，不作为账单审计 source of truth。
DELETE FROM outbox_events
WHERE event_type = 'statement.closed';
