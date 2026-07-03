--liquibase formatted sql

--changeset mini-card:0002-seed-local-credit-accounts dbms:mysql splitStatements:true
--comment: Stable local sample accounts for manual API testing and interview walkthroughs.
-- 样例账户只放最小可用数据：授权、入账、出账、还款流程都能从这些 card 开始。
INSERT IGNORE INTO credit_accounts (
    id, credit_limit, reserved_amount, posted_balance, currency, status
) VALUES
    -- 正常大额度账户：主 walkthrough 默认使用；保留一笔 authorization hold 和一张部分还款账单的余额画面。
    ('11111111-1111-1111-1111-111111111111', 100000.00, 3000.00, 4800.00, 'JPY', 'ACTIVE'),
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

--changeset mini-card:0002-seed-local-authorization-lifecycle dbms:mysql splitStatements:true
--comment: Sample authorization rows covering approved hold, posted presentment, decline, and expiry.
-- 这些行给 0001 的状态机约束配具体画面：同一张 card-123 上同时能看到 hold、posted、late presentment。
INSERT IGNORE INTO authorizations (
    id, idempotency_key, request_fingerprint, card_id, amount, currency, status,
    decline_reason, created_at, decided_at, expires_at, posted_at, expired_at
) VALUES
    ('66666666-6666-6666-6666-666666666660', 'auth-seed-approved-hold',
     'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
     'card-123', 3000.00, 'JPY', 'APPROVED',
     NULL, '2026-07-03 10:15:00.000000', '2026-07-03 10:15:00.120000',
     '2026-07-10 10:15:00.120000', NULL, NULL),
    ('66666666-6666-6666-6666-666666666661', 'auth-seed-posted-supermarket',
     'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
     'card-123', 3500.00, 'JPY', 'POSTED',
     NULL, '2026-06-15 09:00:00.000000', '2026-06-15 09:00:01.000000',
     '2026-06-22 09:00:01.000000', '2026-06-15 09:02:00.000000', NULL),
    ('66666666-6666-6666-6666-666666666662', 'auth-seed-posted-late-train',
     'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
     'card-123', 2300.00, 'JPY', 'POSTED',
     NULL, '2026-06-20 18:30:00.000000', '2026-06-20 18:30:01.000000',
     '2026-06-27 18:30:01.000000', '2026-06-28 08:00:00.000000', NULL),
    ('66666666-6666-6666-6666-666666666663', 'auth-seed-declined-low-limit',
     'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
     'card-low-limit', 10000.00, 'JPY', 'DECLINED',
     'INSUFFICIENT_CREDIT', '2026-07-03 10:20:00.000000', '2026-07-03 10:20:00.100000',
     NULL, NULL, NULL),
    ('66666666-6666-6666-6666-666666666664', 'auth-seed-expired-hold',
     'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
     'card-123', 1200.00, 'JPY', 'EXPIRED',
     NULL, '2026-06-01 08:00:00.000000', '2026-06-01 08:00:01.000000',
     '2026-06-08 08:00:01.000000', NULL, '2026-06-08 08:01:00.000000');

--changeset mini-card:0002-seed-local-statement-and-transactions dbms:mysql splitStatements:true
--comment: Sample posted transactions, a statement snapshot, repayment, ledger entries, and statement lines.
-- 这组数据把“authorization hold -> presentment posting -> ledger -> statement -> repayment”串成一条可查询链路。
INSERT IGNORE INTO statements (
    id, credit_account_id, period_start, period_end, due_date, total_amount,
    minimum_payment_amount, paid_amount, currency, transaction_count, status,
    version, generated_at, created_at, updated_at
) VALUES
    ('77777777-7777-7777-7777-777777777771',
     '11111111-1111-1111-1111-111111111111',
     '2026-06-01', '2026-06-30', '2026-07-25',
     5800.00, 1000.00, 1000.00, 'JPY', 2, 'PARTIALLY_PAID',
     2, '2026-07-01 00:05:00.000000', '2026-07-01 00:05:00.000000',
     '2026-07-03 12:00:00.000000');

INSERT IGNORE INTO card_transactions (
    id, network_transaction_id, authorization_id, card_id, credit_account_id,
    amount, currency, status, billing_status, presentment_received_at, posted_at,
    statement_id, statement_assigned_at, created_at, updated_at
) VALUES
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1', 'ntx-supermarket-20260615-0001',
     '66666666-6666-6666-6666-666666666661', 'card-123',
     '11111111-1111-1111-1111-111111111111',
     3500.00, 'JPY', 'POSTED', 'BILLED',
     '2026-06-15 09:01:00.000000', '2026-06-15 09:02:00.000000',
     '77777777-7777-7777-7777-777777777771', '2026-07-01 00:05:00.000000',
     '2026-06-15 09:01:00.000000', '2026-07-01 00:05:00.000000'),
    ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2', 'ntx-train-20260628-0001',
     '66666666-6666-6666-6666-666666666662', 'card-123',
     '11111111-1111-1111-1111-111111111111',
     2300.00, 'JPY', 'POSTED', 'BILLED',
     '2026-06-28 07:59:00.000000', '2026-06-28 08:00:00.000000',
     '77777777-7777-7777-7777-777777777771', '2026-07-01 00:05:00.000000',
     '2026-06-28 07:59:00.000000', '2026-07-01 00:05:00.000000');

INSERT IGNORE INTO repayments (
    id, idempotency_key, request_fingerprint, statement_id, credit_account_id,
    amount, currency, status, received_at, created_at, updated_at
) VALUES
    ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1', 'repay-card-123-20260703-0001',
     'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff',
     '77777777-7777-7777-7777-777777777771',
     '11111111-1111-1111-1111-111111111111',
     1000.00, 'JPY', 'RECEIVED',
     '2026-07-03 12:00:00.000000', '2026-07-03 11:59:58.000000',
     '2026-07-03 12:00:00.000000');

INSERT IGNORE INTO ledger_entries (
    id, source_event_id, entry_type, direction, source_type, source_id,
    credit_account_id, amount, currency, occurred_at, created_at
) VALUES
    ('99999999-9999-9999-9999-999999999991',
     '88888888-8888-8888-8888-888888888811',
     'CARD_TRANSACTION_POSTED', 'DEBIT', 'CARD_TRANSACTION',
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1',
     '11111111-1111-1111-1111-111111111111',
     3500.00, 'JPY', '2026-06-15 09:02:00.000000', '2026-06-15 09:02:00.010000'),
    ('99999999-9999-9999-9999-999999999992',
     '88888888-8888-8888-8888-888888888812',
     'CARD_TRANSACTION_POSTED', 'DEBIT', 'CARD_TRANSACTION',
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2',
     '11111111-1111-1111-1111-111111111111',
     2300.00, 'JPY', '2026-06-28 08:00:00.000000', '2026-06-28 08:00:00.010000'),
    ('99999999-9999-9999-9999-999999999993',
     '88888888-8888-8888-8888-888888888813',
     'REPAYMENT_RECEIVED', 'CREDIT', 'REPAYMENT',
     'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1',
     '11111111-1111-1111-1111-111111111111',
     1000.00, 'JPY', '2026-07-03 12:00:00.000000', '2026-07-03 12:00:00.010000');

INSERT IGNORE INTO statement_lines (
    id, statement_id, card_transaction_id, ledger_entry_id, network_transaction_id,
    authorization_id, card_id, amount, currency, posted_at, created_at
) VALUES
    ('cccccccc-cccc-cccc-cccc-ccccccccccc1',
     '77777777-7777-7777-7777-777777777771',
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1',
     '99999999-9999-9999-9999-999999999991',
     'ntx-supermarket-20260615-0001',
     '66666666-6666-6666-6666-666666666661',
     'card-123', 3500.00, 'JPY',
     '2026-06-15 09:02:00.000000', '2026-07-01 00:05:00.000000'),
    ('cccccccc-cccc-cccc-cccc-ccccccccccc2',
     '77777777-7777-7777-7777-777777777771',
     'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2',
     '99999999-9999-9999-9999-999999999992',
     'ntx-train-20260628-0001',
     '66666666-6666-6666-6666-666666666662',
     'card-123', 2300.00, 'JPY',
     '2026-06-28 08:00:00.000000', '2026-07-01 00:05:00.000000');

--changeset mini-card:0002-seed-local-async-reliability-tables dbms:mysql splitStatements:true
--comment: Static samples for statement jobs, outbox, delay jobs, notification deliveries, inbox, and risk projection.
-- 这些样例都避开可立即执行的 PENDING 状态，主要用于 SELECT 查看异步可靠性表结构。
INSERT IGNORE INTO statement_jobs (
    id, period_start, period_end, due_date, shard_no, shard_count, status,
    claimed_by, claimed_at, claim_until, claim_token, attempt_count,
    processed_account_count, generated_statement_count, skipped_account_count,
    failed_account_count, created_at, updated_at, last_error
) VALUES
    ('dddddddd-dddd-dddd-dddd-ddddddddddd1',
     '2026-06-01', '2026-06-30', '2026-07-25', 0, 2, 'DONE',
     NULL, NULL, NULL, NULL, 1, 20, 12, 8, 0,
     '2026-07-01 00:00:00.000000', '2026-07-01 00:03:30.000000', NULL),
    ('dddddddd-dddd-dddd-dddd-ddddddddddd2',
     '2026-05-01', '2026-05-31', '2026-06-25', 0, 1, 'DEAD',
     NULL, NULL, NULL, NULL, 5, 1, 0, 0, 1,
     '2026-06-01 00:00:00.000000', '2026-06-01 00:30:00.000000',
     'Sample dead shard after repeated account processing failures');

INSERT IGNORE INTO outbox_events (
    id, aggregate_type, aggregate_id, event_type, event_version, partition_key,
    payload, status, attempts, next_attempt_at, lease_token, created_at,
    published_at, last_error
) VALUES
    ('88888888-8888-8888-8888-888888888821',
     'AUTHORIZATION', '66666666-6666-6666-6666-666666666660',
     'authorization.approved', 1, 'card-123',
     JSON_OBJECT('authorizationId', '66666666-6666-6666-6666-666666666660',
                 'cardId', 'card-123', 'amount', '3000.00', 'currency', 'JPY'),
     'PUBLISHED', 1, '2026-07-03 10:15:01.000000', NULL,
     '2026-07-03 10:15:00.000000', '2026-07-03 10:15:02.000000', NULL),
    ('88888888-8888-8888-8888-888888888822',
     'CARD_TRANSACTION', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1',
     'card.transaction.posted', 1, 'card-123',
     JSON_OBJECT('cardTransactionId', 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1',
                 'cardId', 'card-123', 'amount', '3500.00', 'currency', 'JPY'),
     'PUBLISHED', 1, '2026-06-15 09:02:01.000000', NULL,
     '2026-06-15 09:02:00.000000', '2026-06-15 09:02:02.000000', NULL),
    ('88888888-8888-8888-8888-888888888823',
     'STATEMENT', '77777777-7777-7777-7777-777777777771',
     'statement.closed', 1, 'card-123',
     JSON_OBJECT('statementId', '77777777-7777-7777-7777-777777777771',
                 'recipientKey', 'card-123', 'totalAmount', '5800.00', 'currency', 'JPY'),
     'PUBLISHED', 1, '2026-07-01 00:05:01.000000', NULL,
     '2026-07-01 00:05:00.000000', '2026-07-01 00:05:02.000000', NULL),
    ('88888888-8888-8888-8888-888888888829',
     'NOTIFICATION', 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1',
     'notification.delivery.failed', 1, 'card-123',
     JSON_OBJECT('notificationId', 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1',
                 'channel', 'EMAIL'),
     'DEAD', 5, '2026-07-03 10:45:00.000000', NULL,
     '2026-07-03 10:15:03.000000', NULL,
     'Sample Kafka publish failure after max attempts');

INSERT IGNORE INTO delay_jobs (
    id, job_type, aggregate_type, aggregate_id, status, attempts, scheduled_at,
    next_attempt_at, lease_token, created_at, updated_at, last_error
) VALUES
    ('12121212-1212-1212-1212-121212121211',
     'AUTHORIZATION_EXPIRY', 'AUTHORIZATION', '66666666-6666-6666-6666-666666666664',
     'DONE', 1, '2026-06-08 08:00:01.000000', '2026-06-08 08:00:01.000000',
     NULL, '2026-06-01 08:00:01.000000', '2026-06-08 08:01:00.000000', NULL),
    ('12121212-1212-1212-1212-121212121212',
     'AUTO_REPAYMENT', 'STATEMENT', '77777777-7777-7777-7777-777777777771',
     'DEAD', 3, '2026-07-25 09:00:00.000000', '2026-07-25 09:30:00.000000',
     NULL, '2026-07-01 00:05:00.000000', '2026-07-25 09:30:00.000000',
     'Sample bank debit rejected after retries');

INSERT IGNORE INTO notifications (
    id, source_event_id, subject_type, subject_id, recipient_key,
    notification_type, created_at, updated_at
) VALUES
    ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1',
     '88888888-8888-8888-8888-888888888821',
     'AUTHORIZATION', '66666666-6666-6666-6666-666666666660',
     'card-123', 'AUTHORIZATION_APPROVED',
     '2026-07-03 10:15:03.000000', '2026-07-03 10:15:03.000000'),
    ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2',
     '88888888-8888-8888-8888-888888888823',
     'STATEMENT', '77777777-7777-7777-7777-777777777771',
     'card-123', 'STATEMENT_READY',
     '2026-07-01 00:05:03.000000', '2026-07-01 00:05:03.000000');

INSERT IGNORE INTO notification_deliveries (
    id, notification_id, channel, notification_type, subject_id, recipient_key,
    status, attempts, next_attempt_at, lease_token, last_error,
    provider_message_id, sent_at, created_at, updated_at
) VALUES
    ('ffffffff-ffff-ffff-ffff-fffffffffff1',
     'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1',
     'APP_PUSH', 'AUTHORIZATION_APPROVED',
     '66666666-6666-6666-6666-666666666660', 'card-123',
     'SENT', 1, '2026-07-03 10:15:03.000000', NULL, NULL,
     'push-msg-20260703-0001', '2026-07-03 10:15:05.000000',
     '2026-07-03 10:15:03.000000', '2026-07-03 10:15:05.000000'),
    ('ffffffff-ffff-ffff-ffff-fffffffffff2',
     'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1',
     'EMAIL', 'AUTHORIZATION_APPROVED',
     '66666666-6666-6666-6666-666666666660', 'card-123',
     'DEAD', 3, '2026-07-03 10:45:00.000000', NULL,
     'Sample provider 429 Too Many Requests', NULL, NULL,
     '2026-07-03 10:15:03.000000', '2026-07-03 10:45:00.000000'),
    ('ffffffff-ffff-ffff-ffff-fffffffffff3',
     'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee2',
     'APP_PUSH', 'STATEMENT_READY',
     '77777777-7777-7777-7777-777777777771', 'card-123',
     'SENT', 1, '2026-07-01 00:05:03.000000', NULL, NULL,
     'push-msg-20260701-0001', '2026-07-01 00:05:05.000000',
     '2026-07-01 00:05:03.000000', '2026-07-01 00:05:05.000000');

INSERT IGNORE INTO consumer_inbox (consumer_name, event_id, processed_at) VALUES
    ('authorization-notification', '88888888-8888-8888-8888-888888888821',
     '2026-07-03 10:15:03.000000'),
    ('ledger-entry-recorder', '88888888-8888-8888-8888-888888888811',
     '2026-06-15 09:02:00.010000'),
    ('risk-feature-projector', '88888888-8888-8888-8888-888888888821',
     '2026-07-03 10:15:02.000000');

INSERT IGNORE INTO card_risk_features (
    card_id, authorization_count, approved_count, declined_count, last_decision_at
) VALUES
    ('card-123', 4, 4, 0, '2026-07-03 10:15:00.120000'),
    ('card-low-limit', 1, 0, 1, '2026-07-03 10:20:00.100000');
