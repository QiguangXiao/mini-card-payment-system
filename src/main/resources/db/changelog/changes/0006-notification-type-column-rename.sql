--liquibase formatted sql

--changeset mini-card:0006-rename-notification-template-to-notification-type dbms:mysql
--comment: Rename notifications.template to notification_type. The column always stored a NotificationType enum (AUTHORIZATION_APPROVED, ...), never a render template; the misleading name is corrected and the word "template" is freed for a future per-channel rendering concept.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'template'
ALTER TABLE notifications
    CHANGE COLUMN template notification_type VARCHAR(50) NOT NULL;
