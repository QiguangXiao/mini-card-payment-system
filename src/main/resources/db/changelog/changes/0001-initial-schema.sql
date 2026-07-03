--liquibase formatted sql

--changeset mini-card:0001-current-schema dbms:mysql splitStatements:true
--comment: Current baseline schema for the mini-card issuer backend.

-- credit_accounts 是 issuer 侧的额度账户：一张或多张 card 共享同一个授信额度。
-- 关键学习点：reserved_amount 是 authorization hold，posted_balance 是已入账未还金额；
-- 真正可用额度不是一列，而是 credit_limit - reserved_amount - posted_balance。
CREATE TABLE IF NOT EXISTS credit_accounts (
    -- 内部 aggregate id。当前项目用 CHAR(36) UUID，便于 domain 层提前生成和 API 排查。
    -- 防重复样例：id='11111111-1111-1111-1111-111111111111' 只能代表一个授信账户。
    id CHAR(36) PRIMARY KEY,
    -- 授信额度上限；金融金额用 DECIMAL，避免 double/float 二进制精度问题。
    -- 100000.00 表示 100,000 JPY 授信额度。
    credit_limit DECIMAL(19, 2) NOT NULL,
    -- 已批准但尚未入账的授权占用额度。
    -- 3000.00 表示一笔已批准、尚未 presentment 的 authorization hold。
    reserved_amount DECIMAL(19, 2) NOT NULL,
    -- 已入账但尚未还清的账单/交易余额。
    -- 4800.00 表示已入账消费 5800 JPY、后来还了 1000 JPY，仍欠 4800 JPY。
    posted_balance DECIMAL(19, 2) NOT NULL,
    -- ISO 4217 currency code，例如 JPY/USD。
    currency CHAR(3) NOT NULL,
    -- ACTIVE/BLOCKED；当前没有 DB CHECK，下一轮可考虑补齐。
    -- ACTIVE 允许授权；BLOCKED 表示账户冻结，业务层应拒绝新授权。
    status VARCHAR(20) NOT NULL,
    -- credit_limit 必须为正，否则额度账户没有业务意义。
    CONSTRAINT chk_credit_accounts_limit_positive CHECK (credit_limit > 0),
    -- 两个余额都不能为负；释放额度或还款逻辑出错时会被 DB 拦住。
    CONSTRAINT chk_credit_accounts_reserved_non_negative CHECK (reserved_amount >= 0),
    CONSTRAINT chk_credit_accounts_posted_non_negative CHECK (posted_balance >= 0),
    -- row lock 下的额度不变量：authorization hold + posted debt 不能超过授信额度。
    -- 没有这条 CHECK 时，Java 层 bug 或手工 SQL 可能把账户写成“已冻结+已入账 > 授信额度”的超额状态；
    -- 后续 available credit 会变成负数，授权/还款/账单解释都会失真。
    CONSTRAINT chk_credit_accounts_used_within_limit CHECK (
        reserved_amount + posted_balance <= credit_limit
    )
);

-- cards 是用户刷卡入口。card_id 在本项目用稳定字符串，方便本地用 card-123 调接口。
CREATE TABLE IF NOT EXISTS cards (
    -- 对外可读的 card key；真实生产会避免直接暴露 PAN。
    -- 防重复样例：id='card-123' 只能映射到一个 credit_account_id。
    id VARCHAR(100) PRIMARY KEY,
    -- 多张卡可指向同一个 credit account。
    -- card-123 和 card-secondary 都指向 11111111-1111-1111-1111-111111111111。
    credit_account_id CHAR(36) NOT NULL,
    -- ACTIVE/BLOCKED/EXPIRED；当前没有 DB CHECK，下一轮可考虑补齐。
    -- ACTIVE 可刷卡；BLOCKED 是卡冻结；EXPIRED 是卡片过期。
    status VARCHAR(20) NOT NULL,
    -- card 必须挂在一个已存在的额度账户上。
    CONSTRAINT fk_cards_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_accounts (id)
);

-- authorizations 表示一次刷卡/网购授权决策。
-- 关键学习点：idempotency_key 保护重复请求，row lock 保护额度扣减，状态机 CHECK 保护生命周期。
CREATE TABLE IF NOT EXISTS authorizations (
    -- authorization 的内部主键。
    -- 防重复样例：id='66666666-6666-6666-6666-666666666660' 只能定位一次刷卡/网购授权决策。
    -- 真正防“同一请求重复授权”的是下面的 idempotency_key 唯一键，PRIMARY KEY 只保证行身份不重复。
    id CHAR(36) PRIMARY KEY,
    -- API caller 传入的幂等键；同一个 key 只能产生一条 authorization。
    -- auth-seed-approved-hold。
    idempotency_key VARCHAR(100) NOT NULL,
    -- 请求指纹用于识别“同 key 不同请求体”的幂等冲突。
    -- aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa。
    request_fingerprint CHAR(64) NOT NULL,
    -- card-123，表示这次授权发生在哪张卡上。
    card_id VARCHAR(100) NOT NULL,
    -- 3500.00，表示便利店或网购消费 3,500 JPY。
    amount DECIMAL(19, 2) NOT NULL,
    -- JPY；若 card-usd 用 JPY 请求，业务层会触发 currency mismatch。
    currency CHAR(3) NOT NULL,
    -- PENDING/APPROVED/POSTED/DECLINED/EXPIRED。
    status VARCHAR(20) NOT NULL,
    -- DECLINED 时记录原因；其他状态必须为空。
    -- INSUFFICIENT_CREDIT、CARD_BLOCKED、ACCOUNT_BLOCKED、CURRENCY_MISMATCH。
    decline_reason VARCHAR(50) NULL,
    -- 2026-07-03 10:15:00.000000，表示 API 创建授权请求的时间。
    created_at TIMESTAMP(6) NOT NULL,
    -- 2026-07-03 10:15:00.120000，表示风控/额度决策完成时间。
    decided_at TIMESTAMP(6) NULL,
    -- 2026-07-10 10:15:00.120000，表示 authorization hold 的过期时间。
    expires_at TIMESTAMP(6) NULL,
    -- presentment 成功入账后记录消费 authorization 的时间。
    -- 2026-07-04 09:00:00.000000，表示商户请款后这笔授权被正式消费。
    posted_at TIMESTAMP(6) NULL,
    -- 授权到期释放 reservation 后记录时间。
    -- 2026-07-10 10:16:00.000000，表示到期任务释放 hold 的时间。
    expired_at TIMESTAMP(6) NULL,
    -- 幂等唯一键：并发相同请求让 DB 决定谁 claim 成功。
    -- 防重复样例：客户端超时后用同一个 idempotency_key='auth-seed-approved-hold' 重试，
    -- 第二次 INSERT 会撞唯一键，service 回读第一条 authorization，而不是再次冻结额度。
    CONSTRAINT uk_authorizations_idempotency_key UNIQUE (idempotency_key),
    -- 索引样例：查询 card-123 最近 10 次授权历史：
    -- WHERE card_id='card-123' AND created_at >= '2026-06-01 00:00:00.000000'
    -- ORDER BY created_at DESC LIMIT 10。
    -- 技巧：card_id 是等值条件，created_at 是范围/排序列；联合索引能把“某张卡的时间线”聚在一起。
    -- 注意：如果只查 created_at、不带 card_id，这个索引用不上最左前缀，应该另建时间维度索引。
    -- 没有它时，按卡查历史或计算 velocity 特征会更容易退化成全表扫描。
    INDEX idx_authorizations_card_created_at (card_id, created_at),
    -- 索引样例：授权过期任务扫描可释放 hold：
    -- WHERE status='APPROVED' AND expires_at <= '2026-07-10 10:15:00.120000'
    -- ORDER BY expires_at, id LIMIT 100。
    -- status 放第一列是为了先缩小“仍可过期”的候选集合，expires_at 再按时间窗口推进。
    -- 技巧：status 单独看是低基数字段，但在队列扫描里和 expires_at 组合后很有价值；
    -- id 放最后不是为了过滤，而是给同一 expires_at 的记录一个稳定分页/锁定顺序。
    INDEX idx_authorizations_expiry (status, expires_at, id),
    CONSTRAINT chk_authorizations_amount_positive CHECK (amount > 0),
    -- 状态机约束：每个 status 对应一组必须存在/必须为空的时间戳和原因。
    -- 这类 CHECK 是 domain state machine 的数据库防线：即使 mapper 写错字段组合，
    -- DB 也不能接受“APPROVED 但没有 expires_at”或“DECLINED 却占着 expires_at”的脏行。
    CONSTRAINT chk_authorizations_decision_state CHECK (
        -- PENDING：刚 claim 幂等键，还没有做风控/额度决策；不能有决定时间、过期时间或终态时间。
        (status = 'PENDING' AND decline_reason IS NULL AND decided_at IS NULL
            AND expires_at IS NULL AND posted_at IS NULL AND expired_at IS NULL)
        -- APPROVED：已经批准并占用 reserved_amount；必须有 decided_at/expires_at，尚未入账或过期。
        OR (status = 'APPROVED' AND decline_reason IS NULL AND decided_at IS NOT NULL
            AND expires_at IS NOT NULL AND posted_at IS NULL AND expired_at IS NULL)
        -- POSTED：presentment 已消费这笔 authorization；posted_at 必须存在，expired_at 不能存在。
        -- 这里不强制 posted_at <= expires_at：真实 presentment/clearing 可能晚于 authorization expiry；
        -- 是否接受 late presentment 是业务策略，应由 application/domain 流程判断，而不是写死成 row-level CHECK。
        OR (status = 'POSTED' AND decline_reason IS NULL AND decided_at IS NOT NULL
            AND expires_at IS NOT NULL AND posted_at IS NOT NULL AND expired_at IS NULL
        )
        -- DECLINED：必须有 decline_reason；拒绝不会产生 reservation，因此 expires_at/posted_at/expired_at 都为空。
        OR (status = 'DECLINED' AND decline_reason IS NOT NULL AND decided_at IS NOT NULL
            AND expires_at IS NULL AND posted_at IS NULL AND expired_at IS NULL)
        -- EXPIRED：曾经 APPROVED，过了 expires_at 后释放 reservation；不能已经 POSTED。
        OR (status = 'EXPIRED' AND decline_reason IS NULL AND decided_at IS NOT NULL
            AND expires_at IS NOT NULL AND posted_at IS NULL AND expired_at IS NOT NULL
            AND expired_at >= expires_at)
    )
);

-- statements 是一个账期关闭后的账单快照。
-- 关键学习点：账单金额和 statement_lines 是审计快照；还款只推进 paid_amount/status/version。
CREATE TABLE IF NOT EXISTS statements (
    -- statement 的内部主键。
    -- 防重复样例：id='77777777-7777-7777-7777-777777777771' 只能定位一张账单快照。
    -- 同账户同账期防重复靠 uk_statements_cycle，因为不同 id 也可能描述同一个业务账期。
    id CHAR(36) PRIMARY KEY,
    -- 11111111-1111-1111-1111-111111111111，对应 card-123 的额度账户。
    credit_account_id CHAR(36) NOT NULL,
    -- 2026-06-01，本期账单开始日。
    period_start DATE NOT NULL,
    -- 2026-06-30，本期账单结束日。
    period_end DATE NOT NULL,
    -- 2026-07-25，还款 due date。
    due_date DATE NOT NULL,
    -- 5800.00，本期账单总消费金额。
    total_amount DECIMAL(19, 2) NOT NULL,
    -- 1000.00，最低还款额。
    minimum_payment_amount DECIMAL(19, 2) NOT NULL,
    -- 1000.00，表示已经收到部分还款。
    paid_amount DECIMAL(19, 2) NOT NULL,
    -- JPY。
    currency CHAR(3) NOT NULL,
    -- 2，表示本期 statement_lines 有两笔消费。
    transaction_count INT NOT NULL,
    -- CLOSED/PARTIALLY_PAID/PAID/OVERDUE。
    status VARCHAR(20) NOT NULL,
    -- read-model/cache version；还款等状态变化递增，Redis CAS/tombstone 用它挡住 stale cache。
    version BIGINT NOT NULL DEFAULT 0,
    -- 2026-07-01 00:05:00.000000，表示批处理生成账单快照的时间。
    generated_at TIMESTAMP(6) NOT NULL,
    -- 2026-07-01 00:05:00.000000，DB row 创建时间。
    created_at TIMESTAMP(6) NOT NULL,
    -- 2026-07-03 12:00:00.000000，收到还款后更新时间。
    updated_at TIMESTAMP(6) NOT NULL,
    -- 一个账户同一账期只能生成一张 statement，保证出账幂等。
    -- 防重复样例：credit_account_id='11111111-1111-1111-1111-111111111111',
    -- period_start='2026-06-01', period_end='2026-06-30' 的账单已存在时，scheduler 重跑或两个 worker 竞争，
    -- 第二次 INSERT 会撞唯一键，避免用户看到两张同周期账单。
    CONSTRAINT uk_statements_cycle UNIQUE (credit_account_id, period_start, period_end),
    -- 索引样例：还款页查询 account A 的待还账单：
    -- WHERE credit_account_id='11111111-1111-1111-1111-111111111111'
    --   AND due_date <= '2026-07-25'
    --   AND status IN ('CLOSED', 'PARTIALLY_PAID', 'OVERDUE')
    -- ORDER BY due_date ASC LIMIT 20。
    -- credit_account_id 放第一列，因为大多数查询先按账户收窄，再看 due date 和 status。
    -- 技巧：due_date 是范围和排序列；status 放在后面主要帮助过滤，不一定总能完全避免额外排序。
    -- 如果未来出现“全站查所有 OVERDUE 账单”的运营任务，需要另评估 (status, due_date) 索引。
    INDEX idx_statements_account_due (credit_account_id, due_date, status),
    CONSTRAINT fk_statements_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_accounts (id),
    -- 账期可以是同一天，但还款 due date 必须晚于账期结束日；否则刚出账就“已到期”。
    CONSTRAINT chk_statements_period CHECK (period_end >= period_start AND due_date > period_end),
    -- 当前实现不生成 0 元账单；空分片会直接 DONE，不落 statement。
    -- total/minimum/paid 三者共同保护账单金额不变量：
    -- 1) total_amount/minimum_payment_amount 必须为正；
    -- 2) minimum 不能大于 total；
    -- 3) paid_amount 不能小于 0，也不能超过 total，避免 overpay 把账单状态推歪。
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
    -- paid_amount 与状态必须一致，避免出现 PAID 但未还清这类脏状态。
    -- 注意这里把“账单生命周期状态”和“金额进度”绑在一起：
    -- CLOSED = 尚未还款；PARTIALLY_PAID = 已还一部分；PAID = 全额结清；OVERDUE = 到期仍未全额还。
    CONSTRAINT chk_statements_payment_state CHECK (
        -- CLOSED：账单刚生成，paid_amount 必须为 0。
        (status = 'CLOSED' AND paid_amount = 0)
        -- PARTIALLY_PAID：还款已入账但未结清，金额必须严格介于 0 和 total 之间。
        OR (status = 'PARTIALLY_PAID' AND paid_amount > 0 AND paid_amount < total_amount)
        -- PAID：全额结清，paid_amount 必须等于 total_amount。
        OR (status = 'PAID' AND paid_amount = total_amount)
        -- OVERDUE：逾期只表示未还清；可能完全没还，也可能部分还款后仍逾期。
        OR (status = 'OVERDUE' AND paid_amount < total_amount)
    ),
    -- version 是 cache/read-model 的单调版本，不能倒退成负数。
    CONSTRAINT chk_statements_version CHECK (version >= 0)
);

-- card_transactions 表示 presentment 入账后的用户可见交易流水。
-- 关键学习点：authorization 是 hold，card_transaction 是正式入账；billing_status 表示是否已被账单收录。
CREATE TABLE IF NOT EXISTS card_transactions (
    -- transaction 的内部主键。
    -- 防重复样例：id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1' 只能定位一条正式入账流水。
    -- 同一网络交易防重复靠 network_transaction_id 唯一键，而不是依赖随机 id。
    id CHAR(36) PRIMARY KEY,
    -- 卡组织/收单侧交易号；用于 presentment 幂等。
    -- ntx-supermarket-20260615-0001。
    network_transaction_id VARCHAR(100) NOT NULL,
    -- 66666666-6666-6666-6666-666666666661。
    authorization_id CHAR(36) NOT NULL,
    -- card-123。
    card_id VARCHAR(100) NOT NULL,
    -- 11111111-1111-1111-1111-111111111111。
    credit_account_id CHAR(36) NOT NULL,
    -- 3500.00。
    amount DECIMAL(19, 2) NOT NULL,
    -- JPY。
    currency CHAR(3) NOT NULL,
    -- PENDING 表示 presentment 已收到但未完成入账；POSTED 表示已正式入账。
    status VARCHAR(20) NOT NULL,
    -- UNBILLED/BILLED：POSTED 只代表已入账，BILLED 才代表进入某期 statement。
    billing_status VARCHAR(20) NOT NULL,
    -- 2026-06-15 09:01:00.000000，表示收到商户/网络请款消息的时间。
    presentment_received_at TIMESTAMP(6) NOT NULL,
    -- 2026-06-15 09:02:00.000000，表示账户余额和 ledger 已更新的入账时间。
    posted_at TIMESTAMP(6) NULL,
    -- 77777777-7777-7777-7777-777777777771；未出账时为空。
    statement_id CHAR(36) NULL,
    -- 2026-07-01 00:05:00.000000，表示账单批处理把交易归入 statement 的时间。
    statement_assigned_at TIMESTAMP(6) NULL,
    -- 2026-06-15 09:01:00.000000。
    created_at TIMESTAMP(6) NOT NULL,
    -- 2026-07-01 00:05:00.000000。
    updated_at TIMESTAMP(6) NOT NULL,
    -- presentment 幂等唯一键，防止同一网络交易重复入账。
    -- 防重复样例：卡组织重复发送 network_transaction_id='ntx-supermarket-20260615-0001' 的 presentment，
    -- 第二次 INSERT 会撞唯一键，避免 posted_balance 和账本都多记一次消费。
    CONSTRAINT uk_card_transactions_network_transaction UNIQUE (network_transaction_id),
    -- 索引样例：排查某笔授权是否已经产生正式入账流水：
    -- WHERE authorization_id='66666666-6666-6666-6666-666666666661'。
    -- 它让 “authorization hold -> posted transaction” 的链路查询不需要扫 card_transactions 全表。
    -- 技巧：这是窄索引，目标是快速定位候选 row；读取 amount/status/posted_at 等字段时仍会回表。
    INDEX idx_card_transactions_authorization (authorization_id),
    -- 索引样例：用户交易明细页查询 card-123 最近入账交易：
    -- WHERE card_id='card-123' AND posted_at >= '2026-06-01 00:00:00.000000'
    -- ORDER BY posted_at DESC LIMIT 20。
    -- posted_at 可空，但状态约束保证 POSTED 必有 posted_at，实际列表查询通常只看已入账记录。
    -- 技巧：card_id 等值 + posted_at 时间线，是用户交易列表最典型的 B+Tree 访问方式；
    -- 如果要强制只看 POSTED，SQL 可额外加 status='POSTED'，但这个索引没有把 status 放进去。
    INDEX idx_card_transactions_card_posted_at (card_id, posted_at),
    -- 索引样例：statement generation 按账户扫描本期未出账交易：
    -- WHERE credit_account_id='11111111-1111-1111-1111-111111111111'
    --   AND status='POSTED'
    --   AND billing_status='UNBILLED'
    --   AND statement_id IS NULL
    --   AND posted_at BETWEEN '2026-06-01 00:00:00.000000' AND '2026-06-30 23:59:59.999999'
    -- ORDER BY posted_at, id LIMIT 500。
    -- 技巧：前四列都是强选择性/等值或 IS NULL 条件，先把“某账户、本状态、未出账”的候选集压小；
    -- posted_at 是账期范围列，id 是稳定分页列。posted_at 进入 range 后，后续列更多用于顺序稳定，
    -- 不要误以为所有后续列都还能继续像等值条件一样高效过滤。
    INDEX idx_card_transactions_statement_candidates (
        credit_account_id, status, billing_status, statement_id, posted_at, id
    ),
    -- 索引样例：账单 scheduler 统计 2026-06 账期内有哪些账户有未出账交易：
    -- WHERE status='POSTED'
    --   AND billing_status='UNBILLED'
    --   AND posted_at BETWEEN '2026-06-01 00:00:00.000000' AND '2026-06-30 23:59:59.999999'
    -- GROUP BY credit_account_id。
    -- credit_account_id 放在后面，是因为这个路径先按状态和时间窗口扫候选，再按账户聚合。
    -- 技巧：它和上面的 statement_candidates 不是重复索引：
    -- 1) billing_batch 是 scheduler 先找“哪些账户需要出账”；
    -- 2) statement_candidates 是拿到某个账户后再拉明细。
    -- posted_at 是 range 后，credit_account_id 主要服务聚合/减少回表，不保证 GROUP BY 完全免排序。
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
    -- status 与 posted_at 互相约束：
    -- PENDING 不能有 posted_at；POSTED 必须有 posted_at。否则会出现“看似已入账但没有入账时间”的审计空洞。
    CONSTRAINT chk_card_transactions_posting_state CHECK (
        (status = 'PENDING' AND posted_at IS NULL)
        OR (status = 'POSTED' AND posted_at IS NOT NULL)
    ),
    -- 有 statement_id 就必须是已入账交易。
    -- statement_id/statement_assigned_at 是一对字段：只写其中一个会让账单行追踪不完整。
    CONSTRAINT chk_card_transactions_statement_assignment CHECK (
        (statement_id IS NULL AND statement_assigned_at IS NULL)
        OR (status = 'POSTED' AND statement_id IS NOT NULL AND statement_assigned_at IS NOT NULL)
    ),
    -- billing_status 与 statement assignment 必须一致。
    -- 这条约束挡住两类常见账单脏数据：
    -- 1) BILLED 但没有 statement_id：交易被标记已出账却找不到账单；
    -- 2) UNBILLED 但有 statement_id：同一交易未来可能被 batch 再次扫入另一张账单。
    CONSTRAINT chk_card_transactions_billing_assignment CHECK (
        (billing_status = 'UNBILLED' AND statement_id IS NULL AND statement_assigned_at IS NULL)
        OR (billing_status = 'BILLED' AND status = 'POSTED' AND statement_id IS NOT NULL AND statement_assigned_at IS NOT NULL)
    )
);

-- repayments 表示一次还款请求及其应用结果。
-- 关键学习点：idempotency_key 保护重复还款请求，RECEIVED 后同事务更新 account 和 statement。
CREATE TABLE IF NOT EXISTS repayments (
    -- repayment 的内部主键。
    -- 防重复样例：id='bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb1' 只能定位一条还款处理记录。
    -- 同一还款请求防重复靠 idempotency_key 唯一键，避免客户端重试产生多笔还款。
    id CHAR(36) PRIMARY KEY,
    -- repay-card-123-20260703-0001。
    idempotency_key VARCHAR(100) NOT NULL,
    -- ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff。
    request_fingerprint CHAR(64) NOT NULL,
    -- 77777777-7777-7777-7777-777777777771，表示还哪张账单。
    statement_id CHAR(36) NOT NULL,
    -- PENDING 时可以为空；成功应用后记录归属账户，便于账户维度查询。
    -- RECEIVED 后写入 11111111-1111-1111-1111-111111111111。
    credit_account_id CHAR(36) NULL,
    -- 1000.00，表示收到 1,000 JPY 还款。
    amount DECIMAL(19, 2) NOT NULL,
    -- JPY。
    currency CHAR(3) NOT NULL,
    -- PENDING/RECEIVED。
    status VARCHAR(20) NOT NULL,
    -- 2026-07-03 12:00:00.000000，表示银行扣款或入金确认时间。
    received_at TIMESTAMP(6) NULL,
    -- 2026-07-03 11:59:58.000000。
    created_at TIMESTAMP(6) NOT NULL,
    -- 2026-07-03 12:00:00.000000。
    updated_at TIMESTAMP(6) NOT NULL,
    -- 还款幂等唯一键：支付回调、客户端重试或 API 超时重放时，同一请求只能入账一次。
    -- 防重复样例：idempotency_key='repay-card-123-20260703-0001' 的还款重复提交，第二次会回读原 repayment，
    -- 不会再次增加 paid_amount，也不会再次减少 posted_balance。
    CONSTRAINT uk_repayments_idempotency_key UNIQUE (idempotency_key),
    -- 索引样例：打开账单详情页时查询该 statement 下的还款记录：
    -- WHERE statement_id='77777777-7777-7777-7777-777777777771'
    -- ORDER BY created_at ASC LIMIT 20。
    -- statement_id 是主过滤条件，created_at 让列表天然按时间线读取。
    -- 技巧：这是典型 parent-child 列表索引；先等值定位 parent，再按 child created_at 顺序读取。
    INDEX idx_repayments_statement (statement_id, created_at),
    -- 索引样例：账户对账查询 account A 在 2026-07-03 收到的所有还款：
    -- WHERE credit_account_id='11111111-1111-1111-1111-111111111111'
    --   AND received_at BETWEEN '2026-07-03 00:00:00.000000' AND '2026-07-03 23:59:59.999999'。
    -- credit_account_id 可空只发生在 PENDING；RECEIVED 状态约束保证成功还款一定能走账户维度查询。
    -- 技巧：credit_account_id 等值 + received_at 范围，适合账户日终对账；如果只查 received_at 全局时间窗，
    -- 这个索引无法走最左前缀，需要另一个以 received_at 开头的运营/报表索引。
    INDEX idx_repayments_account_received (credit_account_id, received_at),
    CONSTRAINT fk_repayments_statement FOREIGN KEY (statement_id)
        REFERENCES statements (id),
    CONSTRAINT fk_repayments_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_accounts (id),
    CONSTRAINT chk_repayments_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_repayments_status CHECK (status IN ('PENDING', 'RECEIVED')),
    -- PENDING/RECEIVED 的字段组合约束：
    -- PENDING 只是幂等 claim，还没有真正应用到账户/账单；RECEIVED 才必须记录 account 和 received_at。
    -- 没有这条 CHECK 时，可能出现“RECEIVED 但没有 credit_account_id”的还款，后续按账户对账会断链。
    CONSTRAINT chk_repayments_received_state CHECK (
        (status = 'PENDING' AND credit_account_id IS NULL AND received_at IS NULL)
        OR (status = 'RECEIVED' AND credit_account_id IS NOT NULL AND received_at IS NOT NULL)
    )
);

-- ledger_entries 是最小化账本事实表。
-- 关键学习点：它不是完整复式会计总账，只保留消费入账和还款入账两类审计事实。
CREATE TABLE IF NOT EXISTS ledger_entries (
    -- ledger entry 的内部主键。
    -- 防重复样例：id='99999999-9999-9999-9999-999999999991' 只能定位一条账本事实。
    -- 同一事件重复落账靠 source_event_id + entry_type 唯一键拦截。
    id CHAR(36) PRIMARY KEY,
    -- 来源事件 id；同一事件重复消费时用于幂等。
    -- 88888888-8888-8888-8888-888888888811，来自 card.transaction.posted event。
    source_event_id CHAR(36) NOT NULL,
    -- CARD_TRANSACTION_POSTED 或 REPAYMENT_RECEIVED。
    entry_type VARCHAR(50) NOT NULL,
    -- DEBIT 增加 issuer 应收；CREDIT 减少 issuer 应收。
    direction VARCHAR(20) NOT NULL,
    -- CARD_TRANSACTION 或 REPAYMENT。
    source_type VARCHAR(50) NOT NULL,
    -- source_type=CARD_TRANSACTION 时填 card_transactions.id；source_type=REPAYMENT 时填 repayments.id。
    source_id CHAR(36) NOT NULL,
    -- 11111111-1111-1111-1111-111111111111。
    credit_account_id CHAR(36) NOT NULL,
    -- 3500.00。
    amount DECIMAL(19, 2) NOT NULL,
    -- JPY。
    currency CHAR(3) NOT NULL,
    -- 2026-06-15 09:02:00.000000，表示账本事实发生时间。
    occurred_at TIMESTAMP(6) NOT NULL,
    -- 2026-06-15 09:02:00.010000，表示 ledger row 写入时间。
    created_at TIMESTAMP(6) NOT NULL,
    -- 同一个 event 对同一种 entry type 只能落一次账。
    -- 防重复样例：source_event_id='88888888-8888-8888-8888-888888888811'
    -- 和 entry_type='CARD_TRANSACTION_POSTED' 因 Kafka redelivery 被插入两次，
    -- 第二次会撞唯一键，避免账本重复 DEBIT。
    CONSTRAINT uk_ledger_entries_source_event_type UNIQUE (source_event_id, entry_type),
    -- 索引样例：生成 statement 时读取 account A 的 2026-06 账本事实：
    -- WHERE credit_account_id='11111111-1111-1111-1111-111111111111'
    --   AND occurred_at BETWEEN '2026-06-01 00:00:00.000000' AND '2026-06-30 23:59:59.999999'
    -- ORDER BY occurred_at, id LIMIT 500。
    -- id 作为最后一列提供稳定排序/分页，避免同一时间戳的记录批处理顺序不稳定。
    -- 技巧：ledger 是审计事实表，查询通常以账户为入口；这个索引优化“某账户的一段时间线”，
    -- 不负责按 source_event_id 幂等查重，幂等由 uk_ledger_entries_source_event_type 负责。
    INDEX idx_ledger_entries_account_occurred (credit_account_id, occurred_at, id),
    CONSTRAINT fk_ledger_entries_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_accounts (id),
    CONSTRAINT chk_ledger_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_ledger_entries_type CHECK (
        entry_type IN ('CARD_TRANSACTION_POSTED', 'REPAYMENT_RECEIVED')
    ),
    CONSTRAINT chk_ledger_entries_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_ledger_entries_source_type CHECK (source_type IN ('CARD_TRANSACTION', 'REPAYMENT')),
    -- entry_type、direction、source_type 三者必须匹配，避免账本语义被自由组合破坏。
    -- 当前最小 Ledger 只表达两种事实：
    -- 1) card transaction posted = DEBIT，增加 issuer 对持卡人的应收；
    -- 2) repayment received = CREDIT，减少 issuer 对持卡人的应收。
    -- 如果没有这条 CHECK，可能写出 REPAYMENT_RECEIVED + DEBIT 这种会计方向相反的分录。
    CONSTRAINT chk_ledger_entries_type_direction CHECK (
        (entry_type = 'CARD_TRANSACTION_POSTED'
            AND direction = 'DEBIT'
            AND source_type = 'CARD_TRANSACTION')
        OR (entry_type = 'REPAYMENT_RECEIVED'
            AND direction = 'CREDIT'
            AND source_type = 'REPAYMENT')
    )
);

-- statement_lines 是账单行快照。
-- 关键学习点：它复制 network_transaction_id/card_id/amount 等展示字段，避免历史账单随源交易变化而漂移。
CREATE TABLE IF NOT EXISTS statement_lines (
    -- statement line 的内部主键。
    -- 防重复样例：id='cccccccc-cccc-cccc-cccc-ccccccccccc1' 只能定位账单上的一行展示快照。
    -- 同一交易/账本事实重复进账单，靠下面两个 UNIQUE 约束拦截。
    id CHAR(36) PRIMARY KEY,
    -- 77777777-7777-7777-7777-777777777771。
    statement_id CHAR(36) NOT NULL,
    -- aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1。
    card_transaction_id CHAR(36) NOT NULL,
    -- 99999999-9999-9999-9999-999999999991；旧兼容数据可为空。
    ledger_entry_id CHAR(36) NULL,
    -- ntx-supermarket-20260615-0001，作为账单展示快照。
    network_transaction_id VARCHAR(100) NOT NULL,
    -- 66666666-6666-6666-6666-666666666661。
    authorization_id CHAR(36) NOT NULL,
    -- card-123。
    card_id VARCHAR(100) NOT NULL,
    -- 3500.00。
    amount DECIMAL(19, 2) NOT NULL,
    -- JPY。
    currency CHAR(3) NOT NULL,
    -- 2026-06-15 09:02:00.000000。
    posted_at TIMESTAMP(6) NOT NULL,
    -- 2026-07-01 00:05:00.000000。
    created_at TIMESTAMP(6) NOT NULL,
    -- 一笔 card transaction 只能进入一条 statement line，防止重复出账。
    -- 防重复样例：statement worker 处理到一半超时重试，
    -- card_transaction_id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1' 再次生成账单行会撞唯一键，
    -- 避免一笔 1000 JPY 消费在同一张或不同账单里出现两次。
    CONSTRAINT uk_statement_lines_card_transaction UNIQUE (card_transaction_id),
    -- 一条 ledger entry 最多对应一条 statement line；MySQL 允许多个 NULL。
    -- 这里 ledger_entry_id 可空是为了保留迁移/兼容空间；但非空时必须唯一，防止同一账本事实被重复出账。
    -- 防重复样例：ledger_entry_id='99999999-9999-9999-9999-999999999991' 已经形成 statement line，
    -- 重跑账单生成时再次引用它会撞唯一键；
    -- 但旧数据若 ledger_entry_id=NULL，多条 NULL 不会互相冲突，这是 MySQL UNIQUE 的特性。
    CONSTRAINT uk_statement_lines_ledger_entry UNIQUE (ledger_entry_id),
    -- 索引样例：账单详情页读取某 statement 的所有账单行：
    -- WHERE statement_id='77777777-7777-7777-7777-777777777771'
    -- ORDER BY posted_at ASC, card_transaction_id ASC LIMIT 100。
    -- card_transaction_id 放最后，是为了同一 posted_at 下仍有稳定的二级顺序和回表定位线索。
    -- 技巧：statement_id 等值后，posted_at/card_transaction_id 的顺序和页面展示顺序一致；
    -- 这个索引不覆盖 amount/currency 等展示列，读取账单行详情时仍可能回表。
    INDEX idx_statement_lines_statement (statement_id, posted_at, card_transaction_id),
    CONSTRAINT fk_statement_lines_statement FOREIGN KEY (statement_id)
        REFERENCES statements (id),
    CONSTRAINT fk_statement_lines_card_transaction FOREIGN KEY (card_transaction_id)
        REFERENCES card_transactions (id),
    CONSTRAINT fk_statement_lines_ledger_entry FOREIGN KEY (ledger_entry_id)
        REFERENCES ledger_entries (id),
    CONSTRAINT chk_statement_lines_amount_positive CHECK (amount > 0)
);

-- statement_jobs 是账单批处理的 durable sharded jobs。
-- 关键学习点：没有 parent batch 表；cycle 身份直接落在 job 上，完成度由 statement_jobs 查询回答。
CREATE TABLE IF NOT EXISTS statement_jobs (
    -- job 的内部主键。
    -- 防重复样例：id='dddddddd-dddd-dddd-dddd-ddddddddddd1' 只能定位一个 durable batch shard。
    -- 同一账期同一 shard 防重复靠 uk_statement_jobs_cycle_shard。
    id CHAR(36) PRIMARY KEY,
    -- 2026-06-01，表示要为哪个账期生成 statement。
    period_start DATE NOT NULL,
    -- 2026-06-30。
    period_end DATE NOT NULL,
    -- 2026-07-25，本期账单统一 due date。
    due_date DATE NOT NULL,
    -- 0，表示第 0 个 shard。
    shard_no INT NOT NULL,
    -- 2，表示这个账期被切成 2 个 shard。
    shard_count INT NOT NULL,
    -- PENDING/PROCESSING/DONE/DEAD。
    status VARCHAR(30) NOT NULL,
    -- PROCESSING 时记录领取者，非 PROCESSING 必须为空。
    -- statement-worker-1。
    claimed_by VARCHAR(100) NULL,
    -- 2026-07-01 00:00:01.000000。
    claimed_at TIMESTAMP(6) NULL,
    -- lease deadline；只回答 WHEN 到期，不回答 WHO 拥有。
    -- 2026-07-01 00:05:01.000000，过了这个时间 recoverer 可以重新放回 PENDING。
    claim_until TIMESTAMP(6) NULL,
    -- lease owner token；worker finalize 前用它防止 stale worker 覆盖新 owner。
    -- aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee。
    claim_token CHAR(36) NULL,
    -- 1，表示这个 job 被领取/执行过一次。
    attempt_count INT NOT NULL DEFAULT 0,
    -- 20，表示这个 shard 扫过 20 个账户。
    processed_account_count INT NOT NULL DEFAULT 0,
    -- 12，表示生成了 12 张非空账单。
    generated_statement_count INT NOT NULL DEFAULT 0,
    -- 8，表示 8 个账户本期无消费，跳过。
    skipped_account_count INT NOT NULL DEFAULT 0,
    -- 0；若某账户处理失败，worker finalize 时会增加。
    failed_account_count INT NOT NULL DEFAULT 0,
    -- 2026-07-01 00:00:00.000000。
    created_at TIMESTAMP(6) NOT NULL,
    -- 2026-07-01 00:03:30.000000。
    updated_at TIMESTAMP(6) NOT NULL,
    -- dead job 可写 Account shard failed after max attempts。
    last_error VARCHAR(500) NULL,
    -- scheduler 重复触发同一 cycle 时，唯一键保证每个 shard 只创建一次。
    -- 这就是 statement job creation 的 idempotency key：应用停机后补跑、多个 pod 同时跑，都不会建重复分片。
    -- 防重复样例：period_start='2026-06-01', period_end='2026-06-30', shard_no=0 已存在，
    -- 另一个 scheduler 实例再次创建同一 period_start/period_end/shard_no，
    -- INSERT 会撞唯一键；系统应把它当成“已创建”，而不是制造第二个 shard 0。
    CONSTRAINT uk_statement_jobs_cycle_shard UNIQUE (period_start, period_end, shard_no),
    -- 索引样例：dispatcher 领取可执行账单 job：
    -- WHERE status='PENDING' AND claim_until IS NULL
    -- ORDER BY created_at ASC LIMIT 10。
    -- 索引样例：recoverer 扫描过期 lease：
    -- WHERE status='PROCESSING' AND claim_until <= '2026-07-01 00:05:01.000000'
    -- ORDER BY claim_until ASC, created_at ASC LIMIT 10。
    -- status + claim_until + created_at 让“可领取/可恢复”的队列扫描保持小范围、有顺序。
    -- 技巧：status 是低基数，但队列表通常每次只扫 PENDING 或 PROCESSING，所以它适合放第一列；
    -- claim_until 是 range 条件，之后的 created_at 只能帮助同一时间窗口内稳定顺序，不代表严格 FIFO。
    -- worker claim 后还要 UPDATE/锁定 row，索引只是帮它少扫无关 job。
    INDEX idx_statement_jobs_claimable (status, claim_until, created_at),
    CONSTRAINT chk_statement_jobs_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'DONE', 'DEAD')
    ),
    CONSTRAINT chk_statement_jobs_period CHECK (period_end >= period_start AND due_date > period_end),
    -- shard_no 从 0 开始，必须小于 shard_count；否则 worker 按取模/分片扫描时会出现永远处理不到的非法 shard。
    CONSTRAINT chk_statement_jobs_shard CHECK (
        shard_count > 0 AND shard_no >= 0 AND shard_no < shard_count
    ),
    -- 这些 counter 是 worker finalize 后的统计结果；负数没有业务含义，通常意味着 update 或 mapping 写错。
    CONSTRAINT chk_statement_jobs_counters CHECK (
        attempt_count >= 0
        AND processed_account_count >= 0
        AND generated_statement_count >= 0
        AND skipped_account_count >= 0
        AND failed_account_count >= 0
    ),
    -- PROCESSING 是 lease 状态；非 PROCESSING 不能残留旧 owner 信息。
    -- 四个 claim 字段必须“同生共死”：
    -- claimed_by/claimed_at/claim_until/claim_token 任意缺一个，worker 都不能证明自己仍持有本轮 lease；
    -- 非 PROCESSING 若残留 claim_token，迟到 worker 或人工排查会误以为还有 owner。
    CONSTRAINT chk_statement_jobs_claim_state CHECK (
        -- PROCESSING：必须有 worker identity、claim 时间、lease deadline 和 owner token。
        (status = 'PROCESSING'
            AND claimed_by IS NOT NULL
            AND claimed_at IS NOT NULL
            AND claim_until IS NOT NULL
            AND claim_token IS NOT NULL)
        -- PENDING/DONE/DEAD：不再被任何 worker 持有，claim metadata 必须全部清空。
        OR (status <> 'PROCESSING'
            AND claimed_by IS NULL
            AND claimed_at IS NULL
            AND claim_until IS NULL
            AND claim_token IS NULL)
    )
);

-- outbox_events 是可靠消息发布表。
-- 关键学习点：业务事务只写 Outbox row；后台 worker 等 Kafka ack 后才标 PUBLISHED。
CREATE TABLE IF NOT EXISTS outbox_events (
    -- outbox event 的内部主键。
    -- 防重复样例：id='88888888-8888-8888-8888-888888888821' 只能定位一条待发布消息。
    -- 注意这里没有业务唯一键：同一 aggregate 可以产生多个不同 event，这是合法的。
    id CHAR(36) PRIMARY KEY,
    -- AUTHORIZATION、CARD_TRANSACTION、STATEMENT。
    aggregate_type VARCHAR(50) NOT NULL,
    -- 66666666-6666-6666-6666-666666666661 或 statement id。
    aggregate_id VARCHAR(100) NOT NULL,
    -- authorization.approved、card.transaction.posted、statement.closed。
    event_type VARCHAR(100) NOT NULL,
    -- 1；事件 schema 改动时递增，用于 consumer 兼容判断。
    event_version INT NOT NULL,
    -- card-123；Kafka producer 用它决定 partition，保持同一 key 内相对顺序。
    partition_key VARCHAR(100) NOT NULL,
    -- {"authorizationId":"...","cardId":"card-123","amount":"3000.00"}。
    payload JSON NOT NULL,
    -- PENDING/PROCESSING/PUBLISHED/DEAD。
    status VARCHAR(20) NOT NULL,
    -- 0 表示未重试；3 表示已失败重试三次。
    attempts INT NOT NULL DEFAULT 0,
    -- PENDING/FAILED 时是下次可发布时间；PROCESSING 时复用为 lease deadline。
    -- 2026-07-03 10:15:01.000000。
    next_attempt_at TIMESTAMP(6) NOT NULL,
    -- lease owner token；next_attempt_at 是 deadline，不是 owner identity。
    -- bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb；只有 PROCESSING 时存在。
    lease_token CHAR(36) NULL,
    -- 2026-07-03 10:15:00.000000。
    created_at TIMESTAMP(6) NOT NULL,
    -- Kafka broker ack 后写入；不代表 consumer 已处理完成。
    -- 2026-07-03 10:15:02.000000；PENDING/DEAD 可为空。
    published_at TIMESTAMP(6) NULL,
    -- KafkaTimeoutException: topic not available。
    last_error VARCHAR(500) NULL,
    -- 索引样例：Outbox poller 扫描可发布消息：
    -- WHERE status='PENDING' AND next_attempt_at <= '2026-07-03 10:15:01.000000'
    -- ORDER BY created_at ASC LIMIT 100。
    -- status 先过滤可发布消息，next_attempt_at 控制 retry/backoff 时间，created_at 保持近似 FIFO。
    -- 技巧：因为 next_attempt_at <= ... 是 range，ORDER BY created_at 不一定完全被索引满足；
    -- 更严格的队列顺序通常写成 ORDER BY next_attempt_at ASC, created_at ASC。
    -- 这里的目标是快速找“已经到时间”的候选消息，claim/finalize 时仍要回表比较 lease_token。
    INDEX idx_outbox_publishable (status, next_attempt_at, created_at),
    CONSTRAINT chk_outbox_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'DEAD')),
    -- Outbox 的 PROCESSING 也是 lease，不是终态。
    -- PROCESSING 必须带 lease_token；PUBLISHED/DEAD/PENDING 必须清空 token，避免旧 owner 信息误导 finalize。
    CONSTRAINT chk_outbox_events_lease_token CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL)
    )
);

-- delay_jobs 是未来业务动作调度表。
-- 关键学习点：DelayJob 和 Outbox 都像 DB-backed queue，但语义不同；DelayJob 执行业务动作，Outbox 发布消息。
CREATE TABLE IF NOT EXISTS delay_jobs (
    -- delay job 的内部主键。
    -- 防重复样例：id='12121212-1212-1212-1212-121212121211' 只能定位一个未来业务动作。
    -- 同一 aggregate 同一 job_type 防重复靠 uk_delay_jobs_aggregate。
    id CHAR(36) PRIMARY KEY,
    -- AUTHORIZATION_EXPIRY、AUTO_REPAYMENT。
    job_type VARCHAR(100) NOT NULL,
    -- AUTHORIZATION、STATEMENT。
    aggregate_type VARCHAR(50) NOT NULL,
    -- 66666666-6666-6666-6666-666666666660。
    aggregate_id VARCHAR(100) NOT NULL,
    -- PENDING/PROCESSING/DONE/DEAD。
    status VARCHAR(20) NOT NULL,
    -- 2，表示已经失败重试两次。
    attempts INT NOT NULL DEFAULT 0,
    -- 业务计划时间，例如 authorization expiresAt 或 statement dueDate。
    -- 2026-07-10 10:15:00.120000。
    scheduled_at TIMESTAMP(6) NOT NULL,
    -- PENDING 时是下次可执行时间；PROCESSING 时复用为 lease deadline。
    -- 2026-07-10 10:15:00.120000，失败 backoff 后会被推迟。
    next_attempt_at TIMESTAMP(6) NOT NULL,
    -- lease owner token；worker finalize 前比较 token，挡住迟到 worker。
    -- cccccccc-cccc-cccc-cccc-cccccccccccc；只有 PROCESSING 时存在。
    lease_token CHAR(36) NULL,
    -- 2026-07-03 10:15:00.000000。
    created_at TIMESTAMP(6) NOT NULL,
    -- 2026-07-10 10:15:02.000000。
    updated_at TIMESTAMP(6) NOT NULL,
    -- Authorization already posted, skip expiry。
    last_error VARCHAR(500) NULL,
    -- 同一 aggregate 的同一 future action 只计划一次，保证 scheduler/重试幂等。
    -- 例如同一 authorization 只能有一个 AUTHORIZATION_EXPIRY job；重复 approve/replay 不会创建两条释放任务。
    -- 防重复样例：job_type='AUTHORIZATION_EXPIRY', aggregate_type='AUTHORIZATION',
    -- aggregate_id='66666666-6666-6666-6666-666666666664' 已存在时，API 重试或事件重放再次 schedule，
    -- 会撞 (job_type, aggregate_type, aggregate_id) 唯一键，避免到期时重复释放 reserved_amount。
    CONSTRAINT uk_delay_jobs_aggregate UNIQUE (job_type, aggregate_type, aggregate_id),
    -- 索引样例：DelayJob worker 扫描可执行任务：
    -- WHERE status='PENDING' AND next_attempt_at <= '2026-07-10 10:15:00.120000'
    -- ORDER BY created_at ASC LIMIT 100。
    -- 它和 Outbox 的 publishable 索引形状相似，但语义是“执行业务动作”，不是“发布消息”。
    -- 技巧：status + next_attempt_at 是 DB-backed queue 的经典组合；status 过滤状态，next_attempt_at 做调度时间。
    -- next_attempt_at 是 range 后，created_at 主要提供近似 FIFO 和 tie-breaker，不要把它理解成全局严格顺序。
    INDEX idx_delay_jobs_runnable (status, next_attempt_at, created_at),
    CONSTRAINT chk_delay_jobs_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT chk_delay_jobs_status CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'DEAD')),
    -- DelayJob 与 Outbox 同一套 lease invariant：
    -- PROCESSING = 有 worker 持有；其他状态 = 没有 worker 持有。
    -- 没有这条 CHECK 时，DONE job 可能残留 lease_token，让迟到 worker/finalize 判断变得含糊。
    CONSTRAINT chk_delay_jobs_lease_token CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL)
    )
);

-- notifications 是通知意图(intent)，不是具体投递结果。
-- 关键学习点：一条 notification 可以 fan-out 成多条 notification_deliveries，各渠道独立 retry。
CREATE TABLE IF NOT EXISTS notifications (
    -- notification intent 的内部主键。
    -- 防重复样例：id='eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1' 只能定位一次通知意图。
    -- 同一 integration event 防重复靠 source_event_id 唯一键。
    id CHAR(36) PRIMARY KEY,
    -- 来源 integration event id；consumer replay 时用它保证同一事件只创建一个通知意图。
    -- 88888888-8888-8888-8888-888888888821。
    source_event_id CHAR(36) NOT NULL,
    -- AUTHORIZATION、STATEMENT、REPAYMENT。
    subject_type VARCHAR(50) NOT NULL,
    -- authorization id 或 statement id。
    subject_id VARCHAR(100) NOT NULL,
    -- card-123、user-001、account-111；本项目用可读 key 方便学习。
    recipient_key VARCHAR(100) NOT NULL,
    -- 模板/通知业务类型，例如 AUTHORIZATION_APPROVED、STATEMENT_CLOSED。
    notification_type VARCHAR(50) NOT NULL,
    -- 2026-07-03 10:15:03.000000。
    created_at TIMESTAMP(6) NOT NULL,
    -- 2026-07-03 10:15:03.000000。
    updated_at TIMESTAMP(6) NOT NULL,
    -- 同一个 integration event 只能创建一个 notification intent；Kafka redelivery 靠它幂等。
    -- 防重复样例：authorization.approved event 重投递两次，
    -- source_event_id='88888888-8888-8888-8888-888888888821' 相同，
    -- 第二次 INSERT notification 会撞唯一键，避免同一业务事件 fan-out 出两组 delivery。
    CONSTRAINT uk_notifications_source_event UNIQUE (source_event_id),
    -- 索引样例：用户消息中心按接收人查询通知列表：
    -- WHERE recipient_key='card-123'
    -- ORDER BY created_at DESC LIMIT 20。
    -- 即使当前项目没有完整消息中心 API，这个索引表达了 notification intent 的主要读模型方向。
    -- 技巧：recipient_key 等值 + created_at 时间线，适合用户消息列表；如果未来按 notification_type 做筛选，
    -- 可能需要评估 (recipient_key, notification_type, created_at) 或单独 projection。
    -- subject_type 不在 DB 层做白名单 CHECK：notification 是扩展型外围能力，
    -- 新业务主题应先由 Java enum/domain/listener 控制，避免每加一种通知对象都必须做 schema migration。
    INDEX idx_notifications_recipient (recipient_key, created_at)
);

-- notification_deliveries 是每个渠道的投递工作单元。
-- 关键学习点：APP_PUSH 成功、EMAIL 失败重试这种局部结果，必须拆到 delivery 层才能表达。
CREATE TABLE IF NOT EXISTS notification_deliveries (
    -- delivery 的内部主键。
    -- 防重复样例：id='ffffffff-ffff-ffff-ffff-fffffffffff1' 只能定位一个渠道投递任务。
    -- 同一 notification/channel 防重复靠 uk_notification_deliveries_channel。
    id CHAR(36) PRIMARY KEY,
    -- eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1。
    notification_id CHAR(36) NOT NULL,
    -- APP_PUSH 或 EMAIL。
    channel VARCHAR(20) NOT NULL,
    -- 下面三列是 notification 的 immutable 快照，让 delivery 自洽，不必 JOIN notification 才能发送。
    -- AUTHORIZATION_APPROVED。
    notification_type VARCHAR(50) NOT NULL,
    -- 66666666-6666-6666-6666-666666666660。
    subject_id VARCHAR(100) NOT NULL,
    -- card-123。
    recipient_key VARCHAR(100) NOT NULL,
    -- PENDING/PROCESSING/SENT/DEAD。
    status VARCHAR(20) NOT NULL,
    -- 1，表示投递 provider 调用过一次。
    attempts INT NOT NULL DEFAULT 0,
    -- PENDING 时是下次可投递时间；PROCESSING 时复用为 lease deadline。
    -- 2026-07-03 10:16:00.000000。
    next_attempt_at TIMESTAMP(6) NOT NULL,
    -- lease owner token；不能用 next_attempt_at 时间戳兼任 owner。
    -- dddddddd-dddd-dddd-dddd-dddddddddddd；只有 PROCESSING 时存在。
    lease_token CHAR(36) NULL,
    -- Provider 429 Too Many Requests。
    last_error VARCHAR(500) NULL,
    -- provider 返回的消息 id，作为送达证据和排障线索。
    -- push-msg-20260703-0001。
    provider_message_id VARCHAR(100) NULL,
    -- 2026-07-03 10:15:05.000000；未发送成功时为空。
    sent_at TIMESTAMP(6) NULL,
    -- 2026-07-03 10:15:03.000000。
    created_at TIMESTAMP(6) NOT NULL,
    -- 2026-07-03 10:15:05.000000。
    updated_at TIMESTAMP(6) NOT NULL,
    -- 同一 notification 的同一 channel 只能有一条 delivery。
    -- 如果 APP_PUSH delivery 被重复创建，用户可能收到两次推送；如果 EMAIL 重复创建，也会破坏 retry 统计。
    -- 防重复样例：notification_id='eeeeeeee-eeee-eeee-eeee-eeeeeeeeeee1', channel='APP_PUSH'
    -- 已存在时，重复构建 APP_PUSH 会撞唯一键，
    -- 但 APP_PUSH 与 EMAIL 可以同时存在，因为 channel 不同。
    CONSTRAINT uk_notification_deliveries_channel UNIQUE (notification_id, channel),
    -- 索引样例：delivery worker 扫描可投递任务：
    -- WHERE status='PENDING' AND next_attempt_at <= '2026-07-03 10:16:00.000000'
    -- ORDER BY created_at ASC LIMIT 100。
    -- 失败 backoff 后仍按 next_attempt_at 重新进入队列，created_at 维持同一时间窗口内的处理顺序。
    -- 技巧：这是 per-channel delivery worker 的 runnable queue 索引，和 Outbox/DelayJob 形状一致；
    -- range 条件同样意味着 created_at 是近似顺序。发送前仍会 claim row，避免多个 worker 同发一个 channel。
    INDEX idx_notification_deliveries_dispatchable (status, next_attempt_at, created_at),
    CONSTRAINT fk_notification_deliveries_notification FOREIGN KEY (notification_id)
        REFERENCES notifications (id),
    CONSTRAINT chk_notification_deliveries_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_notification_deliveries_channel CHECK (channel IN ('APP_PUSH', 'EMAIL')),
    -- delivery 状态只允许四种：等待、租约处理中、发送成功、死信。
    CONSTRAINT chk_notification_deliveries_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'DEAD')),
    -- per-channel delivery 的 lease invariant 与 Outbox/DelayJob 对齐。
    -- PROCESSING 必须有 token；SENT/DEAD/PENDING 不能残留旧 token，避免旧 worker 覆盖新状态。
    CONSTRAINT chk_notification_deliveries_lease_token CHECK (
        (status = 'PROCESSING' AND lease_token IS NOT NULL)
        OR (status <> 'PROCESSING' AND lease_token IS NULL)
    )
);

-- consumer_inbox 是 Kafka consumer 幂等表。
-- 关键学习点：Kafka at-least-once 下，先 claim inbox，再执行业务；重复 event 会被主键挡住。
CREATE TABLE IF NOT EXISTS consumer_inbox (
    -- authorization-notification、ledger-entry-recorder、risk-feature-projector。
    consumer_name VARCHAR(100) NOT NULL,
    -- 88888888-8888-8888-8888-888888888821，来自 integration event id。
    event_id CHAR(36) NOT NULL,
    -- 2026-07-03 10:15:04.000000。
    processed_at TIMESTAMP(6) NOT NULL,
    -- 同一个 consumer 对同一个 event 只能处理一次；不同 consumer 可以各自处理。
    -- 防重复样例：AuthorizationNotificationListener 已处理 event 后 Kafka rebalance 又投递一次，
    -- 再插入 consumer_name='authorization-notification',
    -- event_id='88888888-8888-8888-8888-888888888821' 会撞主键，业务逻辑直接跳过。
    -- 另一个 consumer_name 可以处理同一 event，因为它代表不同订阅者的独立进度。
    PRIMARY KEY (consumer_name, event_id)
);

-- card_risk_features 是风险读模型/projection。
-- 关键学习点：由 Kafka 事件异步投影，支持授权时快速读取 velocity/历史决策特征。
CREATE TABLE IF NOT EXISTS card_risk_features (
    -- card_id 既是业务 key 也是主键。
    -- 防重复样例：card_id='card-123' 只有一行 velocity/projection 计数。
    -- Kafka 重复投影时应更新同一行，而不是插入第二行风险特征。
    card_id VARCHAR(100) PRIMARY KEY,
    -- 4，表示该卡累计 4 次授权决策事件。
    authorization_count BIGINT NOT NULL DEFAULT 0,
    -- 4，表示累计批准 4 次。
    approved_count BIGINT NOT NULL DEFAULT 0,
    -- 0，表示当前样例卡没有拒绝记录；card-low-limit 会是 1。
    declined_count BIGINT NOT NULL DEFAULT 0,
    -- 2026-07-03 10:15:00.120000，表示最近一次授权决策时间。
    last_decision_at TIMESTAMP(6) NOT NULL,
    -- approved + declined 必须等于总授权决策数，防止投影计数漂移。
    -- 这张表是异步 projection，若 consumer bug 只加总数不加分类，CHECK 会立刻暴露不一致。
    CONSTRAINT chk_card_risk_feature_counts CHECK (
        authorization_count >= 0
        AND approved_count >= 0
        AND declined_count >= 0
        AND approved_count + declined_count = authorization_count
    )
);
