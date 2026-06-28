--liquibase formatted sql

--changeset mini-card:0007-create-notification-deliveries dbms:mysql
--comment: Per-channel delivery records. Notification is the intent (one row); each enabled channel gets its own delivery row with an independent lifecycle (PENDING -> PROCESSING lease -> SENT / retry / DEAD), so app push succeeding while email retries is representable.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'notification_deliveries'
CREATE TABLE notification_deliveries (
    id CHAR(36) PRIMARY KEY,
    notification_id CHAR(36) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    -- 下面三列是 notification 的 immutable 快照(denormalized)：让一条 delivery 成为自洽的工作单元，
    -- worker 渲染模板/解析收件人时不必 JOIN notifications，也为将来把投递拆成独立微服务留好边界。
    notification_type VARCHAR(50) NOT NULL,
    subject_id VARCHAR(100) NOT NULL,
    recipient_key VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    -- PENDING 时是"下次可投递时间"，PROCESSING 时复用为 lease deadline(WHEN 到期，供 recoverer 扫描)。
    next_attempt_at TIMESTAMP(6) NOT NULL,
    -- lease identity(WHO 持有)：claim 时生成 UUID，finalize 时校验 status + lease_token。
    -- 不能用 next_attempt_at 兼任 token：Instant 纳秒精度经 TIMESTAMP(6) 微秒列 round-trip 后会被截断，
    -- 内存值与回读值不再相等，已成功的投递会被误判 lease changed 而最终 DEAD。CHAR(36) 精确比较不受此影响。
    lease_token CHAR(36) NULL,
    last_error VARCHAR(500) NULL,
    -- provider 返回的消息 id；同时也是"已成功"证据，便于对账与排查重复投递。
    provider_message_id VARCHAR(100) NULL,
    sent_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    -- (notification_id, channel) 唯一：即使创建路径并发/重放，也不可能给同一通知同一渠道建两条投递。
    CONSTRAINT uk_notification_deliveries_channel UNIQUE (notification_id, channel),
    -- poller 扫描 WHERE status=? AND next_attempt_at<=? ORDER BY created_at，复合索引覆盖该访问路径。
    INDEX idx_notification_deliveries_dispatchable (status, next_attempt_at, created_at),
    CONSTRAINT fk_notification_deliveries_notification FOREIGN KEY (notification_id)
        REFERENCES notifications (id),
    CONSTRAINT chk_notification_deliveries_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_notification_deliveries_channel CHECK (channel IN ('APP_PUSH', 'EMAIL')),
    CONSTRAINT chk_notification_deliveries_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'DEAD'))
);

--changeset mini-card:0007-drop-notification-status-check dbms:mysql
--comment: Delivery lifecycle moved off notifications onto notification_deliveries; drop the per-notification status check before dropping its column.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_notifications_status'
ALTER TABLE notifications DROP CHECK chk_notifications_status;

--changeset mini-card:0007-drop-notification-delivery-attempts-check dbms:mysql
--comment: Drop the per-notification delivery_attempts check before dropping its column.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_notifications_delivery_attempts'
ALTER TABLE notifications DROP CHECK chk_notifications_delivery_attempts;

--changeset mini-card:0007-drop-notification-lifecycle-columns dbms:mysql
--comment: Notification becomes pure intent; per-channel delivery lifecycle lives in notification_deliveries.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'status'
ALTER TABLE notifications
    DROP COLUMN status,
    DROP COLUMN delivery_attempts,
    DROP COLUMN last_error,
    DROP COLUMN sent_at;
