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
        credit_account_id, status, statement_id, posted_at, id
    ),
    CONSTRAINT fk_card_transactions_authorization FOREIGN KEY (authorization_id)
        REFERENCES authorizations (id),
    CONSTRAINT fk_card_transactions_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_accounts (id),
    CONSTRAINT fk_card_transactions_statement FOREIGN KEY (statement_id)
        REFERENCES statements (id),
    CONSTRAINT chk_card_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_card_transactions_status CHECK (status IN ('PENDING', 'POSTED')),
    CONSTRAINT chk_card_transactions_posting_state CHECK (
        (status = 'PENDING' AND posted_at IS NULL)
        OR (status = 'POSTED' AND posted_at IS NOT NULL)
    ),
    CONSTRAINT chk_card_transactions_statement_assignment CHECK (
        (statement_id IS NULL AND statement_assigned_at IS NULL)
        OR (status = 'POSTED' AND statement_id IS NOT NULL AND statement_assigned_at IS NOT NULL)
    )
);

CREATE TABLE IF NOT EXISTS statement_items (
    id CHAR(36) PRIMARY KEY,
    statement_id CHAR(36) NOT NULL,
    card_transaction_id CHAR(36) NOT NULL,
    network_transaction_id VARCHAR(100) NOT NULL,
    authorization_id CHAR(36) NOT NULL,
    card_id VARCHAR(100) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    posted_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_statement_items_card_transaction UNIQUE (card_transaction_id),
    INDEX idx_statement_items_statement (statement_id, posted_at, card_transaction_id),
    CONSTRAINT fk_statement_items_statement FOREIGN KEY (statement_id)
        REFERENCES statements (id),
    CONSTRAINT fk_statement_items_card_transaction FOREIGN KEY (card_transaction_id)
        REFERENCES card_transactions (id),
    CONSTRAINT chk_statement_items_amount_positive CHECK (amount > 0)
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

INSERT IGNORE INTO credit_accounts (
    id, credit_limit, reserved_amount, posted_balance, currency, status
) VALUES
    ('11111111-1111-1111-1111-111111111111', 100000.00, 0.00, 0.00, 'JPY', 'ACTIVE'),
    ('22222222-2222-2222-2222-222222222222', 5000.00, 0.00, 0.00, 'JPY', 'ACTIVE'),
    ('33333333-3333-3333-3333-333333333333', 100000.00, 0.00, 0.00, 'JPY', 'BLOCKED'),
    ('44444444-4444-4444-4444-444444444444', 1000.00, 0.00, 0.00, 'USD', 'ACTIVE');

INSERT IGNORE INTO cards (id, credit_account_id, status) VALUES
    ('card-123', '11111111-1111-1111-1111-111111111111', 'ACTIVE'),
    ('card-secondary', '11111111-1111-1111-1111-111111111111', 'ACTIVE'),
    ('card-low-limit', '22222222-2222-2222-2222-222222222222', 'ACTIVE'),
    ('card-blocked', '11111111-1111-1111-1111-111111111111', 'BLOCKED'),
    ('card-expired', '11111111-1111-1111-1111-111111111111', 'EXPIRED'),
    ('card-account-blocked', '33333333-3333-3333-3333-333333333333', 'ACTIVE'),
    ('card-usd', '44444444-4444-4444-4444-444444444444', 'ACTIVE');
