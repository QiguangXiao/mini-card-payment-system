--liquibase formatted sql

--changeset mini-card:0002-credit-account-posted-balance dbms:mysql splitStatements:true
--comment: Add posted balance for databases created before presentment posting.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'credit_accounts' AND column_name = 'posted_balance'
ALTER TABLE credit_accounts
    ADD COLUMN posted_balance DECIMAL(19, 2) NOT NULL DEFAULT 0.00 AFTER reserved_amount;
ALTER TABLE credit_accounts
    MODIFY posted_balance DECIMAL(19, 2) NOT NULL;
ALTER TABLE credit_accounts
    ALTER COLUMN posted_balance DROP DEFAULT;

--changeset mini-card:0002-drop-old-credit-account-limit-check dbms:mysql
--comment: Drop the old reserved-only limit check before adding the current used-limit check.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_credit_accounts_reserved_within_limit'
ALTER TABLE credit_accounts DROP CHECK chk_credit_accounts_reserved_within_limit;

--changeset mini-card:0002-credit-account-posted-non-negative-check dbms:mysql
--comment: Ensure posted balance cannot become negative.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_credit_accounts_posted_non_negative'
ALTER TABLE credit_accounts
    ADD CONSTRAINT chk_credit_accounts_posted_non_negative CHECK (posted_balance >= 0);

--changeset mini-card:0002-credit-account-used-within-limit-check dbms:mysql
--comment: Enforce reserved plus posted exposure within the credit limit.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_credit_accounts_used_within_limit'
ALTER TABLE credit_accounts
    ADD CONSTRAINT chk_credit_accounts_used_within_limit CHECK (
        reserved_amount + posted_balance <= credit_limit
    );

--changeset mini-card:0002-authorization-posted-at dbms:mysql
--comment: Add posting timestamp for authorization records created before presentment posting.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'authorizations' AND column_name = 'posted_at'
ALTER TABLE authorizations
    ADD COLUMN posted_at TIMESTAMP(6) NULL AFTER expires_at;

--changeset mini-card:0002-drop-authorization-state-check dbms:mysql
--comment: Rebuild authorization lifecycle check so POSTED state includes posted_at.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_authorizations_decision_state'
ALTER TABLE authorizations DROP CHECK chk_authorizations_decision_state;

--changeset mini-card:0002-add-authorization-state-check dbms:mysql
--comment: Current authorization lifecycle state machine, including POSTED.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_authorizations_decision_state'
ALTER TABLE authorizations
    ADD CONSTRAINT chk_authorizations_decision_state CHECK (
        (status = 'PENDING' AND decline_reason IS NULL AND decided_at IS NULL
            AND expires_at IS NULL AND posted_at IS NULL AND expired_at IS NULL)
        OR (status = 'APPROVED' AND decline_reason IS NULL AND decided_at IS NOT NULL
            AND expires_at IS NOT NULL AND posted_at IS NULL AND expired_at IS NULL)
        OR (status = 'POSTED' AND decline_reason IS NULL AND decided_at IS NOT NULL
            AND expires_at IS NOT NULL AND posted_at IS NOT NULL AND expired_at IS NULL
            AND posted_at <= expires_at)
        OR (status = 'DECLINED' AND decline_reason IS NOT NULL AND decided_at IS NOT NULL
            AND expires_at IS NULL AND posted_at IS NULL AND expired_at IS NULL)
        OR (status = 'EXPIRED' AND decline_reason IS NULL AND decided_at IS NOT NULL
            AND expires_at IS NOT NULL AND posted_at IS NULL AND expired_at IS NOT NULL
            AND expired_at >= expires_at)
    );

--changeset mini-card:0002-notification-subject-type dbms:mysql
--comment: Add the generic notification subject model to old authorization-only notification tables.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'subject_type'
ALTER TABLE notifications
    ADD COLUMN subject_type VARCHAR(50) NULL AFTER source_event_id;

--changeset mini-card:0002-notification-subject-id dbms:mysql
--comment: Add generic subject id to old notification tables.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'subject_id'
ALTER TABLE notifications
    ADD COLUMN subject_id VARCHAR(100) NULL AFTER subject_type;

--changeset mini-card:0002-notification-recipient-key dbms:mysql
--comment: Add current routing key to old notification tables.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'recipient_key'
ALTER TABLE notifications
    ADD COLUMN recipient_key VARCHAR(100) NULL AFTER subject_id;

--changeset mini-card:0002-backfill-notification-subject-type dbms:mysql
--comment: Old notification rows were authorization notifications.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'authorization_id'
UPDATE notifications
SET subject_type = 'AUTHORIZATION'
WHERE subject_type IS NULL;

--changeset mini-card:0002-backfill-notification-subject-id dbms:mysql
--comment: Backfill subject id from the old authorization_id column when it exists.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'authorization_id'
UPDATE notifications
SET subject_id = authorization_id
WHERE subject_id IS NULL
  AND authorization_id IS NOT NULL;

--changeset mini-card:0002-backfill-notification-recipient-key dbms:mysql
--comment: Backfill recipient key from the old card_id column when it exists.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'card_id'
UPDATE notifications
SET recipient_key = card_id
WHERE recipient_key IS NULL
  AND card_id IS NOT NULL;

--changeset mini-card:0002-notification-subject-type-not-null dbms:mysql
--comment: Enforce current notification subject model after backfill.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'subject_type' AND is_nullable = 'YES'
ALTER TABLE notifications
    MODIFY subject_type VARCHAR(50) NOT NULL;

--changeset mini-card:0002-notification-subject-id-not-null dbms:mysql
--comment: Enforce current notification subject id after backfill.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'subject_id' AND is_nullable = 'YES'
ALTER TABLE notifications
    MODIFY subject_id VARCHAR(100) NOT NULL;

--changeset mini-card:0002-notification-recipient-key-not-null dbms:mysql
--comment: Enforce current notification recipient key after backfill.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'recipient_key' AND is_nullable = 'YES'
ALTER TABLE notifications
    MODIFY recipient_key VARCHAR(100) NOT NULL;

--changeset mini-card:0002-drop-notification-authorization-id dbms:mysql
--comment: Remove old notification column after subject backfill.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'authorization_id'
ALTER TABLE notifications
    DROP COLUMN authorization_id;

--changeset mini-card:0002-drop-notification-card-id dbms:mysql
--comment: Remove old notification routing column after recipient backfill.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'notifications' AND column_name = 'card_id'
ALTER TABLE notifications
    DROP COLUMN card_id;

--changeset mini-card:0002-notification-recipient-index dbms:mysql
--comment: Support notification lookup by current recipient routing key.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'notifications' AND index_name = 'idx_notifications_recipient'
CREATE INDEX idx_notifications_recipient
    ON notifications (recipient_key, created_at);

--changeset mini-card:0002-drop-notification-subject-type-check dbms:mysql
--comment: Rebuild subject type check so it includes repayment notifications.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_notifications_subject_type'
ALTER TABLE notifications DROP CHECK chk_notifications_subject_type;

--changeset mini-card:0002-add-notification-subject-type-check dbms:mysql
--comment: Current notification subject type set.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_notifications_subject_type'
ALTER TABLE notifications
    ADD CONSTRAINT chk_notifications_subject_type CHECK (
        subject_type IN ('AUTHORIZATION', 'CARD_TRANSACTION', 'STATEMENT', 'REPAYMENT')
    );

--changeset mini-card:0002-outbox-status-check dbms:mysql
--comment: Add explicit Outbox status check for old local databases.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_outbox_status'
ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'DEAD'));

--changeset mini-card:0002-card-transaction-billing-index dbms:mysql
--comment: Support statement batch scans of unassigned posted transactions.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'card_transactions' AND index_name = 'idx_card_transactions_billing_batch'
CREATE INDEX idx_card_transactions_billing_batch
    ON card_transactions (status, statement_id, posted_at, credit_account_id);
