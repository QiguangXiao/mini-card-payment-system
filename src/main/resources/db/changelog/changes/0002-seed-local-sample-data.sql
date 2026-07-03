--liquibase formatted sql

--changeset mini-card:0002-seed-local-credit-accounts dbms:mysql splitStatements:true
--comment: Stable local sample accounts for manual API testing and interview walkthroughs.
-- 样例账户只放最小可用数据：授权、入账、出账、还款流程都能从这些 card 开始。
INSERT IGNORE INTO credit_accounts (
    id, credit_limit, reserved_amount, posted_balance, currency, status
) VALUES
    -- 正常大额度账户：主 walkthrough 默认使用。
    ('11111111-1111-1111-1111-111111111111', 100000.00, 0.00, 0.00, 'JPY', 'ACTIVE'),
    -- 低额度账户：用于测试 insufficient credit / decline。
    ('22222222-2222-2222-2222-222222222222', 5000.00, 0.00, 0.00, 'JPY', 'ACTIVE'),
    -- 冻结账户：用于测试 account blocked。
    ('33333333-3333-3333-3333-333333333333', 100000.00, 0.00, 0.00, 'JPY', 'BLOCKED'),
    -- 外币账户：用于测试 currency mismatch。
    ('44444444-4444-4444-4444-444444444444', 1000.00, 0.00, 0.00, 'USD', 'ACTIVE');

--changeset mini-card:0002-seed-local-cards dbms:mysql
--comment: Stable local sample cards for manual API testing and interview walkthroughs.
-- card id 使用可读字符串，方便 curl/Postman 手工演示；生产不会这样暴露真实卡号。
INSERT IGNORE INTO cards (id, credit_account_id, status) VALUES
    ('card-123', '11111111-1111-1111-1111-111111111111', 'ACTIVE'),
    ('card-secondary', '11111111-1111-1111-1111-111111111111', 'ACTIVE'),
    ('card-low-limit', '22222222-2222-2222-2222-222222222222', 'ACTIVE'),
    ('card-blocked', '11111111-1111-1111-1111-111111111111', 'BLOCKED'),
    ('card-expired', '11111111-1111-1111-1111-111111111111', 'EXPIRED'),
    ('card-account-blocked', '33333333-3333-3333-3333-333333333333', 'ACTIVE'),
    ('card-usd', '44444444-4444-4444-4444-444444444444', 'ACTIVE');
