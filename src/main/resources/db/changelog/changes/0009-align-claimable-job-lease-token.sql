--liquibase formatted sql

--changeset mini-card:0009-outbox-events-add-lease-token dbms:mysql
--comment: Add explicit owner token for Outbox PROCESSING lease. next_attempt_at remains the retry/lease deadline; lease_token is the finalize ownership check.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'outbox_events' AND column_name = 'lease_token'
ALTER TABLE outbox_events ADD COLUMN lease_token CHAR(36) NULL AFTER next_attempt_at;

--changeset mini-card:0009-delay-jobs-add-lease-token dbms:mysql
--comment: Add explicit owner token for DelayJob PROCESSING lease. next_attempt_at remains the retry/lease deadline; lease_token is the finalize ownership check.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'delay_jobs' AND column_name = 'lease_token'
ALTER TABLE delay_jobs ADD COLUMN lease_token CHAR(36) NULL AFTER next_attempt_at;

--changeset mini-card:0009-notification-deliveries-add-lease-token-if-missing dbms:mysql
--comment: Older local schemas created notification_deliveries before lease_token existed; add it when missing so the shared lease-token invariant can be applied.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notification_deliveries' AND column_name = 'lease_token'
ALTER TABLE notification_deliveries ADD COLUMN lease_token CHAR(36) NULL AFTER next_attempt_at;

--changeset mini-card:0009-backfill-outbox-event-lease-token dbms:mysql
--comment: Existing PROCESSING outbox rows must receive a token before the stricter lease-token check is added.
UPDATE outbox_events
SET lease_token = UUID()
WHERE status = 'PROCESSING'
  AND lease_token IS NULL;

--changeset mini-card:0009-clear-outbox-event-stale-lease-token dbms:mysql
--comment: Non-PROCESSING outbox rows must not retain stale lease ownership.
UPDATE outbox_events
SET lease_token = NULL
WHERE status <> 'PROCESSING'
  AND lease_token IS NOT NULL;

--changeset mini-card:0009-backfill-delay-job-lease-token dbms:mysql
--comment: Existing PROCESSING delay jobs must receive a token before the stricter lease-token check is added.
UPDATE delay_jobs
SET lease_token = UUID()
WHERE status = 'PROCESSING'
  AND lease_token IS NULL;

--changeset mini-card:0009-clear-delay-job-stale-lease-token dbms:mysql
--comment: Non-PROCESSING delay jobs must not retain stale lease ownership.
UPDATE delay_jobs
SET lease_token = NULL
WHERE status <> 'PROCESSING'
  AND lease_token IS NOT NULL;

--changeset mini-card:0009-backfill-notification-delivery-lease-token dbms:mysql
--comment: Notification delivery already has lease_token; backfill any existing PROCESSING rows before adding the shared check.
UPDATE notification_deliveries
SET lease_token = UUID()
WHERE status = 'PROCESSING'
  AND lease_token IS NULL;

--changeset mini-card:0009-clear-notification-delivery-stale-lease-token dbms:mysql
--comment: Non-PROCESSING notification deliveries must not retain stale lease ownership.
UPDATE notification_deliveries
SET lease_token = NULL
WHERE status <> 'PROCESSING'
  AND lease_token IS NOT NULL;

--changeset mini-card:0009-outbox-events-lease-token-check dbms:mysql
--comment: PROCESSING outbox rows must carry lease_token; non-PROCESSING rows must not keep stale ownership.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_outbox_events_lease_token'
ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_lease_token CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL)
    );

--changeset mini-card:0009-delay-jobs-lease-token-check dbms:mysql
--comment: PROCESSING delay jobs must carry lease_token; non-PROCESSING rows must not keep stale ownership.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_delay_jobs_lease_token'
ALTER TABLE delay_jobs
    ADD CONSTRAINT chk_delay_jobs_lease_token CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL)
    );

--changeset mini-card:0009-notification-deliveries-lease-token-check dbms:mysql
--comment: PROCESSING notification deliveries must carry lease_token; non-PROCESSING rows must not keep stale ownership.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_notification_deliveries_lease_token'
ALTER TABLE notification_deliveries
    ADD CONSTRAINT chk_notification_deliveries_lease_token CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL)
    );
