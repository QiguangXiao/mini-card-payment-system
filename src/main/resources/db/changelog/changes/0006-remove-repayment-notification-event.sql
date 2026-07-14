--liquibase formatted sql

--changeset mini-card:0006-remove-repayment-notification-event dbms:mysql splitStatements:true
--comment: Remove persisted repayment notifications and outbox work after deleting the repayment Kafka path.

-- 先清 consumer inbox，再按外键顺序删 delivery/notification。
-- 反向事实：如果只删 Java enum 而保留历史行，MyBatis 恢复 REPAYMENT_RECEIVED 时会因未知枚举失败；
-- 如果只删 notification，则 inbox 还会让同一个 source event 看起来已经消费成功，留下误导性的幂等记录。
DELETE ci
FROM consumer_inbox ci
JOIN notifications n ON n.source_event_id = ci.event_id
WHERE ci.consumer_name = 'notification-v1'
  AND n.notification_type = 'REPAYMENT_RECEIVED';

DELETE FROM notification_deliveries
WHERE notification_type = 'REPAYMENT_RECEIVED';

DELETE FROM notifications
WHERE notification_type = 'REPAYMENT_RECEIVED';

-- repayment.received 已没有 Kafka topic 路由；清理所有状态的旧工作行，避免 PENDING/DEAD 被 worker
-- 重新 claim 后因 unsupported integration event type 永久失败。Repayment 表仍是还款审计 source of truth。
DELETE FROM outbox_events
WHERE event_type = 'repayment.received';
