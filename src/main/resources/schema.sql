CREATE TABLE IF NOT EXISTS credit_accounts (
    id CHAR(36) PRIMARY KEY,
    credit_limit DECIMAL(19, 2) NOT NULL,
    reserved_amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    CONSTRAINT chk_credit_accounts_limit_positive CHECK (credit_limit > 0),
    CONSTRAINT chk_credit_accounts_reserved_non_negative CHECK (reserved_amount >= 0),
    CONSTRAINT chk_credit_accounts_reserved_within_limit CHECK (
        reserved_amount <= credit_limit
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
    expired_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_authorizations_idempotency_key UNIQUE (idempotency_key),
    INDEX idx_authorizations_card_created_at (card_id, created_at),
    INDEX idx_authorizations_expiry (status, expires_at, id),
    CONSTRAINT chk_authorizations_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_authorizations_decision_state CHECK (
        (status = 'PENDING' AND decline_reason IS NULL AND decided_at IS NULL
            AND expires_at IS NULL AND expired_at IS NULL)
        OR (status = 'APPROVED' AND decline_reason IS NULL AND decided_at IS NOT NULL
            AND expires_at IS NOT NULL AND expired_at IS NULL)
        OR (status = 'DECLINED' AND decline_reason IS NOT NULL AND decided_at IS NOT NULL
            AND expires_at IS NULL AND expired_at IS NULL)
        OR (status = 'EXPIRED' AND decline_reason IS NULL AND decided_at IS NOT NULL
            AND expires_at IS NOT NULL AND expired_at IS NOT NULL
            AND expired_at >= expires_at)
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
    authorization_id CHAR(36) NOT NULL,
    card_id VARCHAR(100) NOT NULL,
    template VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    delivery_attempts INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    sent_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_notifications_source_event UNIQUE (source_event_id),
    CONSTRAINT chk_notifications_delivery_attempts CHECK (delivery_attempts >= 0),
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
    id, credit_limit, reserved_amount, currency, status
) VALUES
    ('11111111-1111-1111-1111-111111111111', 100000.00, 0.00, 'JPY', 'ACTIVE'),
    ('22222222-2222-2222-2222-222222222222', 5000.00, 0.00, 'JPY', 'ACTIVE'),
    ('33333333-3333-3333-3333-333333333333', 100000.00, 0.00, 'JPY', 'BLOCKED'),
    ('44444444-4444-4444-4444-444444444444', 1000.00, 0.00, 'USD', 'ACTIVE');

INSERT IGNORE INTO cards (id, credit_account_id, status) VALUES
    ('card-123', '11111111-1111-1111-1111-111111111111', 'ACTIVE'),
    ('card-secondary', '11111111-1111-1111-1111-111111111111', 'ACTIVE'),
    ('card-low-limit', '22222222-2222-2222-2222-222222222222', 'ACTIVE'),
    ('card-blocked', '11111111-1111-1111-1111-111111111111', 'BLOCKED'),
    ('card-expired', '11111111-1111-1111-1111-111111111111', 'EXPIRED'),
    ('card-account-blocked', '33333333-3333-3333-3333-333333333333', 'ACTIVE'),
    ('card-usd', '44444444-4444-4444-4444-444444444444', 'ACTIVE');
