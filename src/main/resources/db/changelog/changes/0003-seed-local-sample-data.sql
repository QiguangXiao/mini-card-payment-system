--liquibase formatted sql

--changeset mini-card:0003-seed-local-credit-accounts dbms:mysql splitStatements:true
--comment: Stable local sample accounts for manual API testing and interview walkthroughs.
INSERT IGNORE INTO credit_accounts (
    id, credit_limit, reserved_amount, posted_balance, currency, status
) VALUES
    ('11111111-1111-1111-1111-111111111111', 100000.00, 0.00, 0.00, 'JPY', 'ACTIVE'),
    ('22222222-2222-2222-2222-222222222222', 5000.00, 0.00, 0.00, 'JPY', 'ACTIVE'),
    ('33333333-3333-3333-3333-333333333333', 100000.00, 0.00, 0.00, 'JPY', 'BLOCKED'),
    ('44444444-4444-4444-4444-444444444444', 1000.00, 0.00, 0.00, 'USD', 'ACTIVE');

--changeset mini-card:0003-seed-local-cards dbms:mysql
--comment: Stable local sample cards for manual API testing and interview walkthroughs.
INSERT IGNORE INTO cards (id, credit_account_id, status) VALUES
    ('card-123', '11111111-1111-1111-1111-111111111111', 'ACTIVE'),
    ('card-secondary', '11111111-1111-1111-1111-111111111111', 'ACTIVE'),
    ('card-low-limit', '22222222-2222-2222-2222-222222222222', 'ACTIVE'),
    ('card-blocked', '11111111-1111-1111-1111-111111111111', 'BLOCKED'),
    ('card-expired', '11111111-1111-1111-1111-111111111111', 'EXPIRED'),
    ('card-account-blocked', '33333333-3333-3333-3333-333333333333', 'ACTIVE'),
    ('card-usd', '44444444-4444-4444-4444-444444444444', 'ACTIVE');
