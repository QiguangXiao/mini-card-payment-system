--liquibase formatted sql

--changeset mini-card:0001-initial-schema dbms:mysql splitStatements:true
--comment: Baseline schema for the mini-card issuer backend plus local sample cards.
CREATE TABLE IF NOT EXISTS credit_accounts (
    id CHAR(36) PRIMARY KEY,
    credit_limit DECIMAL(19, 2) NOT NULL,
    reserved_amount DECIMAL(19, 2) NOT NULL,
    posted_balance DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    CONSTRAINT chk_credit_accounts_limit_positive CHECK (credit_limit > 0),
    CONSTRAINT chk_credit_accounts_reserved_non_negative CHECK (reserved_amount >= 0),
    CONSTRAINT chk_credit_accounts_posted_non_negative CHECK (posted_balance >= 0),
    CONSTRAINT chk_credit_accounts_used_within_limit CHECK (
        reserved_amount + posted_balance <= credit_limit
    )
);

CREATE TABLE IF NOT EXISTS cards (
    id VARCHAR(100) PRIMARY KEY,
    credit_account_id CHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,
    CONSTRAINT fk_cards_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_accounts (id)
);

CREATE TABLE IF NOT EXISTS authorizations (
    id CHAR(36) PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    card_id VARCHAR(100) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    decline_reason VARCHAR(50) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    decided_at TIMESTAMP(6) NULL,
    expires_at TIMESTAMP(6) NULL,
    posted_at TIMESTAMP(6) NULL,
    expired_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_authorizations_idempotency_key UNIQUE (idempotency_key),
    INDEX idx_authorizations_card_created_at (card_id, created_at),
    INDEX idx_authorizations_expiry (status, expires_at, id),
    CONSTRAINT chk_authorizations_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_authorizations_decision_state CHECK (
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
    )
);

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

CREATE TABLE IF NOT EXISTS statements (
    id CHAR(36) PRIMARY KEY,
    credit_account_id CHAR(36) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    due_date DATE NOT NULL,
    total_amount DECIMAL(19, 2) NOT NULL,
    minimum_payment_amount DECIMAL(19, 2) NOT NULL,
    paid_amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    transaction_count INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    generated_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_statements_cycle UNIQUE (credit_account_id, period_start, period_end),
    INDEX idx_statements_account_due (credit_account_id, due_date, status),
    CONSTRAINT fk_statements_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_accounts (id),
    CONSTRAINT chk_statements_period CHECK (period_end >= period_start AND due_date > period_end),
    CONSTRAINT chk_statements_amounts CHECK (
        total_amount > 0
        AND minimum_payment_amount > 0
        AND minimum_payment_amount <= total_amount
        AND paid_amount >= 0
        AND paid_amount <= total_amount
    ),
    CONSTRAINT chk_statements_transaction_count CHECK (transaction_count > 0),
    CONSTRAINT chk_statements_status CHECK (
        status IN ('CLOSED', 'PARTIALLY_PAID', 'PAID', 'OVERDUE')
    ),
    CONSTRAINT chk_statements_payment_state CHECK (
        (status = 'CLOSED' AND paid_amount = 0)
        OR (status = 'PARTIALLY_PAID' AND paid_amount > 0 AND paid_amount < total_amount)
        OR (status = 'PAID' AND paid_amount = total_amount)
        OR (status = 'OVERDUE' AND paid_amount < total_amount)
    )
);

CREATE TABLE IF NOT EXISTS card_transactions (
    id CHAR(36) PRIMARY KEY,
    network_transaction_id VARCHAR(100) NOT NULL,
    authorization_id CHAR(36) NOT NULL,
    card_id VARCHAR(100) NOT NULL,
    credit_account_id CHAR(36) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    billing_status VARCHAR(20) NOT NULL,
    presentment_received_at TIMESTAMP(6) NOT NULL,
    posted_at TIMESTAMP(6) NULL,
    statement_id CHAR(36) NULL,
    statement_assigned_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_card_transactions_network_transaction UNIQUE (network_transaction_id),
    INDEX idx_card_transactions_authorization (authorization_id),
    INDEX idx_card_transactions_card_posted_at (card_id, posted_at),
    INDEX idx_card_transactions_statement_candidates (
        credit_account_id, status, billing_status, statement_id, posted_at, id
    ),
    INDEX idx_card_transactions_billing_batch (
        status, billing_status, posted_at, credit_account_id
    ),
    CONSTRAINT fk_card_transactions_authorization FOREIGN KEY (authorization_id)
        REFERENCES authorizations (id),
    CONSTRAINT fk_card_transactions_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_accounts (id),
    CONSTRAINT fk_card_transactions_statement FOREIGN KEY (statement_id)
        REFERENCES statements (id),
    CONSTRAINT chk_card_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_card_transactions_status CHECK (status IN ('PENDING', 'POSTED')),
    CONSTRAINT chk_card_transactions_billing_status CHECK (billing_status IN ('UNBILLED', 'BILLED')),
    CONSTRAINT chk_card_transactions_posting_state CHECK (
        (status = 'PENDING' AND posted_at IS NULL)
        OR (status = 'POSTED' AND posted_at IS NOT NULL)
    ),
    CONSTRAINT chk_card_transactions_statement_assignment CHECK (
        (statement_id IS NULL AND statement_assigned_at IS NULL)
        OR (status = 'POSTED' AND statement_id IS NOT NULL AND statement_assigned_at IS NOT NULL)
    ),
    CONSTRAINT chk_card_transactions_billing_assignment CHECK (
        (billing_status = 'UNBILLED' AND statement_id IS NULL AND statement_assigned_at IS NULL)
        OR (billing_status = 'BILLED' AND status = 'POSTED' AND statement_id IS NOT NULL AND statement_assigned_at IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS repayments (
    id CHAR(36) PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    statement_id CHAR(36) NOT NULL,
    credit_account_id CHAR(36) NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    received_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_repayments_idempotency_key UNIQUE (idempotency_key),
    INDEX idx_repayments_statement (statement_id, created_at),
    INDEX idx_repayments_account_received (credit_account_id, received_at),
    CONSTRAINT fk_repayments_statement FOREIGN KEY (statement_id)
        REFERENCES statements (id),
    CONSTRAINT fk_repayments_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_accounts (id),
    CONSTRAINT chk_repayments_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_repayments_status CHECK (status IN ('PENDING', 'RECEIVED')),
    CONSTRAINT chk_repayments_received_state CHECK (
        (status = 'PENDING' AND credit_account_id IS NULL AND received_at IS NULL)
        OR (status = 'RECEIVED' AND credit_account_id IS NOT NULL AND received_at IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id CHAR(36) PRIMARY KEY,
    source_event_id CHAR(36) NOT NULL,
    entry_type VARCHAR(50) NOT NULL,
    direction VARCHAR(20) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_id CHAR(36) NOT NULL,
    credit_account_id CHAR(36) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    occurred_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_ledger_entries_source_event_type UNIQUE (source_event_id, entry_type),
    INDEX idx_ledger_entries_account_occurred (credit_account_id, occurred_at, id),
    CONSTRAINT fk_ledger_entries_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_accounts (id),
    CONSTRAINT chk_ledger_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_ledger_entries_type CHECK (
        entry_type IN ('CARD_TRANSACTION_POSTED', 'REPAYMENT_RECEIVED')
    ),
    CONSTRAINT chk_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entries_source_type CHECK (source_type IN ('CARD_TRANSACTION', 'REPAYMENT')),
    CONSTRAINT chk_ledger_entries_type_direction CHECK (
        (entry_type = 'CARD_TRANSACTION_POSTED'
            AND direction = 'DEBIT'
            AND source_type = 'CARD_TRANSACTION')
        OR (entry_type = 'REPAYMENT_RECEIVED'
            AND direction = 'CREDIT'
            AND source_type = 'REPAYMENT')
    )
);

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

CREATE TABLE IF NOT EXISTS outbox_events (
    id CHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INT NOT NULL,
    partition_key VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    published_at TIMESTAMP(6) NULL,
    last_error VARCHAR(500) NULL,
    INDEX idx_outbox_publishable (status, next_attempt_at, created_at),
    CONSTRAINT chk_outbox_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'DEAD'))
);

CREATE TABLE IF NOT EXISTS delay_jobs (
    id CHAR(36) PRIMARY KEY,
    job_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    scheduled_at TIMESTAMP(6) NOT NULL,
    next_attempt_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    last_error VARCHAR(500) NULL,
    CONSTRAINT uk_delay_jobs_aggregate UNIQUE (job_type, aggregate_type, aggregate_id),
    INDEX idx_delay_jobs_runnable (status, next_attempt_at, created_at),
    CONSTRAINT chk_delay_jobs_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT chk_delay_jobs_status CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'DEAD'))
);

CREATE TABLE IF NOT EXISTS notifications (
    id CHAR(36) PRIMARY KEY,
    source_event_id CHAR(36) NOT NULL,
    subject_type VARCHAR(50) NOT NULL,
    subject_id VARCHAR(100) NOT NULL,
    recipient_key VARCHAR(100) NOT NULL,
    template VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    delivery_attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    sent_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_notifications_source_event UNIQUE (source_event_id),
    INDEX idx_notifications_recipient (recipient_key, created_at),
    CONSTRAINT chk_notifications_delivery_attempts CHECK (delivery_attempts >= 0),
    CONSTRAINT chk_notifications_subject_type CHECK (
        subject_type IN ('AUTHORIZATION', 'CARD_TRANSACTION', 'STATEMENT', 'REPAYMENT')
    ),
    CONSTRAINT chk_notifications_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE TABLE IF NOT EXISTS consumer_inbox (
    consumer_name VARCHAR(100) NOT NULL,
    event_id CHAR(36) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (consumer_name, event_id)
);

CREATE TABLE IF NOT EXISTS card_risk_features (
    card_id VARCHAR(100) PRIMARY KEY,
    authorization_count BIGINT NOT NULL DEFAULT 0,
    approved_count BIGINT NOT NULL DEFAULT 0,
    declined_count BIGINT NOT NULL DEFAULT 0,
    last_decision_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT chk_card_risk_feature_counts CHECK (
        authorization_count >= 0
        AND approved_count >= 0
        AND declined_count >= 0
        AND approved_count + declined_count = authorization_count
    )
);
