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
