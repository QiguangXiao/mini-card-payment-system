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
    card_id VARCHAR(100) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    decline_reason VARCHAR(50) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    decided_at TIMESTAMP(6) NULL,
    CONSTRAINT uk_authorizations_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_authorizations_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_authorizations_decision_state CHECK (
        (status = 'PENDING' AND decline_reason IS NULL AND decided_at IS NULL)
        OR (status = 'APPROVED' AND decline_reason IS NULL AND decided_at IS NOT NULL)
        OR (status = 'DECLINED' AND decline_reason IS NOT NULL AND decided_at IS NOT NULL)
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
