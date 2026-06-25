--liquibase formatted sql

--changeset mini-card:0004-statement-batches dbms:mysql
--comment: Durable statement batch header for calendar-driven billing.
CREATE TABLE IF NOT EXISTS statement_batches (
    id CHAR(36) PRIMARY KEY,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    due_date DATE NOT NULL,
    status VARCHAR(30) NOT NULL,
    total_account_count BIGINT NOT NULL,
    target_accounts_per_job INT NOT NULL,
    job_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6) NULL,
    last_error VARCHAR(500) NULL,
    CONSTRAINT uk_statement_batches_cycle UNIQUE (period_start, period_end),
    INDEX idx_statement_batches_status (status, created_at),
    CONSTRAINT chk_statement_batches_period CHECK (period_end >= period_start AND due_date > period_end),
    CONSTRAINT chk_statement_batches_counts CHECK (
        total_account_count >= 0 AND target_accounts_per_job > 0 AND job_count > 0
    ),
    CONSTRAINT chk_statement_batches_status CHECK (
        status IN ('RUNNING', 'COMPLETED', 'PARTIALLY_FAILED')
    ),
    CONSTRAINT chk_statement_batches_completion CHECK (
        (status = 'RUNNING' AND completed_at IS NULL)
        OR (status IN ('COMPLETED', 'PARTIALLY_FAILED') AND completed_at IS NOT NULL)
    )
);

--changeset mini-card:0004-statement-jobs dbms:mysql
--comment: Durable sharded statement jobs with DB claim lease.
CREATE TABLE IF NOT EXISTS statement_jobs (
    id CHAR(36) PRIMARY KEY,
    batch_id CHAR(36) NOT NULL,
    shard_no INT NOT NULL,
    shard_count INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    claimed_by VARCHAR(100) NULL,
    claimed_at TIMESTAMP(6) NULL,
    claim_until TIMESTAMP(6) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    processed_account_count INT NOT NULL DEFAULT 0,
    generated_statement_count INT NOT NULL DEFAULT 0,
    skipped_account_count INT NOT NULL DEFAULT 0,
    failed_account_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    last_error VARCHAR(500) NULL,
    CONSTRAINT uk_statement_jobs_batch_shard UNIQUE (batch_id, shard_no),
    INDEX idx_statement_jobs_claimable (status, claim_until, created_at),
    CONSTRAINT fk_statement_jobs_batch FOREIGN KEY (batch_id)
        REFERENCES statement_batches (id),
    CONSTRAINT chk_statement_jobs_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'DONE', 'DEAD')
    ),
    CONSTRAINT chk_statement_jobs_shard CHECK (
        shard_count > 0 AND shard_no >= 0 AND shard_no < shard_count
    ),
    CONSTRAINT chk_statement_jobs_counters CHECK (
        attempt_count >= 0
        AND processed_account_count >= 0
        AND generated_statement_count >= 0
        AND skipped_account_count >= 0
        AND failed_account_count >= 0
    ),
    CONSTRAINT chk_statement_jobs_claim_state CHECK (
        (status = 'PROCESSING' AND claimed_by IS NOT NULL AND claimed_at IS NOT NULL AND claim_until IS NOT NULL)
        OR (status <> 'PROCESSING' AND claimed_by IS NULL AND claimed_at IS NULL AND claim_until IS NULL)
    )
);

--changeset mini-card:0004-card-transaction-billing-status dbms:mysql
--comment: Add explicit billed marker to card transactions.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'card_transactions' AND column_name = 'billing_status'
ALTER TABLE card_transactions
    ADD COLUMN billing_status VARCHAR(20) NOT NULL DEFAULT 'UNBILLED' AFTER status;

--changeset mini-card:0004-backfill-card-transaction-billing-status dbms:mysql
--comment: Existing transactions with statement_id were already billed.
UPDATE card_transactions
SET billing_status = CASE
    WHEN statement_id IS NULL THEN 'UNBILLED'
    ELSE 'BILLED'
END;

--changeset mini-card:0004-card-transaction-billing-status-check dbms:mysql
--comment: Enforce current card transaction billing status set.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_card_transactions_billing_status'
ALTER TABLE card_transactions
    ADD CONSTRAINT chk_card_transactions_billing_status CHECK (billing_status IN ('UNBILLED', 'BILLED'));

--changeset mini-card:0004-card-transaction-billing-assignment-check dbms:mysql
--comment: BILLED transactions must point at a statement assignment.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE() AND constraint_name = 'chk_card_transactions_billing_assignment'
ALTER TABLE card_transactions
    ADD CONSTRAINT chk_card_transactions_billing_assignment CHECK (
        (billing_status = 'UNBILLED' AND statement_id IS NULL AND statement_assigned_at IS NULL)
        OR (billing_status = 'BILLED' AND status = 'POSTED' AND statement_id IS NOT NULL AND statement_assigned_at IS NOT NULL)
    );

--changeset mini-card:0004-card-transaction-billing-marker-index dbms:mysql
--comment: Support statement job account shard scans.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'card_transactions' AND index_name = 'idx_card_transactions_billing_marker'
CREATE INDEX idx_card_transactions_billing_marker
    ON card_transactions (status, billing_status, posted_at, credit_account_id);

--changeset mini-card:0004-statement-lines dbms:mysql
--comment: Statement lines link user-visible card transactions with append-only ledger entries.
CREATE TABLE IF NOT EXISTS statement_lines (
    id CHAR(36) PRIMARY KEY,
    statement_id CHAR(36) NOT NULL,
    card_transaction_id CHAR(36) NOT NULL,
    ledger_entry_id CHAR(36) NULL,
    network_transaction_id VARCHAR(100) NOT NULL,
    authorization_id CHAR(36) NOT NULL,
    card_id VARCHAR(100) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    posted_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_statement_lines_card_transaction UNIQUE (card_transaction_id),
    CONSTRAINT uk_statement_lines_ledger_entry UNIQUE (ledger_entry_id),
    INDEX idx_statement_lines_statement (statement_id, posted_at, card_transaction_id),
    CONSTRAINT fk_statement_lines_statement FOREIGN KEY (statement_id)
        REFERENCES statements (id),
    CONSTRAINT fk_statement_lines_card_transaction FOREIGN KEY (card_transaction_id)
        REFERENCES card_transactions (id),
    CONSTRAINT fk_statement_lines_ledger_entry FOREIGN KEY (ledger_entry_id)
        REFERENCES ledger_entries (id),
    CONSTRAINT chk_statement_lines_amount_positive CHECK (amount > 0)
);

--changeset mini-card:0004-copy-old-statement-items dbms:mysql
--comment: Preserve old statement item rows as statement lines when the old table exists.
--preconditions onFail:MARK_RAN onError:HALT
--precondition-sql-check expectedResult:1 SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'statement_items'
INSERT IGNORE INTO statement_lines (
    id, statement_id, card_transaction_id, ledger_entry_id, network_transaction_id,
    authorization_id, card_id, amount, currency, posted_at, created_at
)
SELECT
    id, statement_id, card_transaction_id, NULL, network_transaction_id,
    authorization_id, card_id, amount, currency, posted_at, created_at
FROM statement_items;
