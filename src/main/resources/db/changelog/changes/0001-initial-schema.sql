--liquibase formatted sql

--changeset mini-card:0001-current-schema dbms:mysql splitStatements:true
--comment: Current baseline schema for the mini-card issuer backend.

-- credit_accounts 是 issuer 侧的额度账户：一张或多张 card 共享同一个授信额度。
-- 关键学习点：reserved_amount 是 authorization hold，posted_balance 是已入账未还金额；
-- 真正可用额度不是一列，而是 credit_limit - reserved_amount - posted_balance。
CREATE TABLE IF NOT EXISTS credit_accounts (
    -- 内部 aggregate id。当前项目用 CHAR(36) UUID，便于 domain 层提前生成和 API 排查。
    -- PRIMARY KEY 防止两个额度账户共用同一个 id；样例：account-001 只能代表一个授信账户。
    id CHAR(36) PRIMARY KEY,
    -- 授信额度上限；金融金额用 DECIMAL，避免 double/float 二进制精度问题。
    credit_limit DECIMAL(19, 2) NOT NULL,
    -- 已批准但尚未入账的授权占用额度。
    reserved_amount DECIMAL(19, 2) NOT NULL,
    -- 已入账但尚未还清的账单/交易余额。
    posted_balance DECIMAL(19, 2) NOT NULL,
    -- ISO 4217 currency code，例如 JPY/USD。
    currency CHAR(3) NOT NULL,
    -- ACTIVE/BLOCKED；当前没有 DB CHECK，下一轮可考虑补齐。
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
    -- PRIMARY KEY 防止两张卡共用同一个 card id；样例：card-123 只能映射到一个 credit_account_id。
    id VARCHAR(100) PRIMARY KEY,
    -- 多张卡可指向同一个 credit account。
    credit_account_id CHAR(36) NOT NULL,
    -- ACTIVE/BLOCKED/EXPIRED；当前没有 DB CHECK，下一轮可考虑补齐。
    status VARCHAR(20) NOT NULL,
    -- card 必须挂在一个已存在的额度账户上。
    CONSTRAINT fk_cards_credit_account FOREIGN KEY (credit_account_id)
        REFERENCES credit_accounts (id)
);

-- authorizations 表示一次刷卡/网购授权决策。
-- 关键学习点：idempotency_key 保护重复请求，row lock 保护额度扣减，状态机 CHECK 保护生命周期。
CREATE TABLE IF NOT EXISTS authorizations (
    -- authorization 的内部主键；样例：auth-001 只能定位一次刷卡/网购授权决策。
    -- 真正防“同一请求重复授权”的是下面的 idempotency_key 唯一键，PRIMARY KEY 只保证行身份不重复。
    id CHAR(36) PRIMARY KEY,
    -- API caller 传入的幂等键；同一个 key 只能产生一条 authorization。
    idempotency_key VARCHAR(100) NOT NULL,
    -- 请求指纹用于识别“同 key 不同请求体”的幂等冲突。
    request_fingerprint CHAR(64) NOT NULL,
    card_id VARCHAR(100) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    -- DECLINED 时记录原因；其他状态必须为空。
    decline_reason VARCHAR(50) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    decided_at TIMESTAMP(6) NULL,
    expires_at TIMESTAMP(6) NULL,
    -- presentment 成功入账后记录消费 authorization 的时间。
    posted_at TIMESTAMP(6) NULL,
    -- 授权到期释放 reservation 后记录时间。
    expired_at TIMESTAMP(6) NULL,
    -- 幂等唯一键：并发相同请求让 DB 决定谁 claim 成功。
    -- 样例：客户端超时后用同一个 idempotency_key=auth-req-20260703-001 重试，
    -- 第二次 INSERT 会撞唯一键，service 回读第一条 authorization，而不是再次冻结额度。
    CONSTRAINT uk_authorizations_idempotency_key UNIQUE (idempotency_key),
    -- 索引样例：查询 card-123 最近 10 次授权历史，WHERE card_id='card-123' ORDER BY created_at DESC。
    -- 没有它时，按卡查历史或计算 velocity 特征会更容易退化成全表扫描。
    INDEX idx_authorizations_card_created_at (card_id, created_at),
    -- 索引样例：授权过期任务扫描 APPROVED 且 expires_at<=now 的记录，ORDER BY id 分批处理。
    -- status 放第一列是为了先缩小“仍可过期”的候选集合，expires_at 再按时间窗口推进。
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
    -- statement 的内部主键；样例：stmt-202607-card-123 只能定位一张账单快照。
    -- 同账户同账期防重复靠 uk_statements_cycle，因为不同 id 也可能描述同一个业务账期。
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
    -- read-model/cache version；还款等状态变化递增，Redis CAS/tombstone 用它挡住 stale cache。
    version BIGINT NOT NULL DEFAULT 0,
    generated_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    -- 一个账户同一账期只能生成一张 statement，保证出账幂等。
    -- 样例：account A 的 2026-07-01~2026-07-31 账单，scheduler 重跑或两个 worker 竞争时，
    -- 第二次 INSERT 会撞唯一键，避免用户看到两张同周期账单。
    CONSTRAINT uk_statements_cycle UNIQUE (credit_account_id, period_start, period_end),
    -- 索引样例：还款页查询 account A 的 CLOSED/OVERDUE 账单，并按 due_date 找最早到期的一张。
    -- credit_account_id 放第一列，因为大多数查询先按账户收窄，再看 due date 和 status。
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
    -- transaction 的内部主键；样例：ctx-001 只能定位一条正式入账流水。
    -- 同一网络交易防重复靠 network_transaction_id 唯一键，而不是依赖随机 id。
    id CHAR(36) PRIMARY KEY,
    -- 卡组织/收单侧交易号；用于 presentment 幂等。
    network_transaction_id VARCHAR(100) NOT NULL,
    authorization_id CHAR(36) NOT NULL,
    card_id VARCHAR(100) NOT NULL,
    credit_account_id CHAR(36) NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    -- UNBILLED/BILLED：POSTED 只代表已入账，BILLED 才代表进入某期 statement。
    billing_status VARCHAR(20) NOT NULL,
    presentment_received_at TIMESTAMP(6) NOT NULL,
    posted_at TIMESTAMP(6) NULL,
    statement_id CHAR(36) NULL,
    statement_assigned_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    -- presentment 幂等唯一键，防止同一网络交易重复入账。
    -- 样例：卡组织重复发送 network_transaction_id=ntx-789 的 presentment，
    -- 第二次 INSERT 会撞唯一键，避免 posted_balance 和账本都多记一次消费。
    CONSTRAINT uk_card_transactions_network_transaction UNIQUE (network_transaction_id),
    -- 索引样例：presentment 入账时根据 authorization_id 找原授权，或排查某笔授权最终是否已入账。
    -- 它让 “authorization hold -> posted transaction” 的链路查询不需要扫 card_transactions 全表。
    INDEX idx_card_transactions_authorization (authorization_id),
    -- 索引样例：用户交易明细页查询 card-123 最近入账交易，WHERE card_id='card-123' ORDER BY posted_at DESC。
    -- posted_at 可空，但状态约束保证 POSTED 必有 posted_at，实际列表查询通常只看已入账记录。
    INDEX idx_card_transactions_card_posted_at (card_id, posted_at),
    -- 索引样例：statement generation 按账户扫描 POSTED + UNBILLED + statement_id IS NULL 的交易，
    -- 再用 posted_at 落入账期窗口，最后用 id 稳定分页，防止批处理漏扫或顺序抖动。
    INDEX idx_card_transactions_statement_candidates (
        credit_account_id, status, billing_status, statement_id, posted_at, id
    ),
    -- 索引样例：账单 scheduler 先统计某个账期内有哪些账户有 POSTED/UNBILLED 交易，
    -- WHERE status='POSTED' AND billing_status='UNBILLED' AND posted_at BETWEEN period_start AND period_end。
    -- credit_account_id 放在后面，是因为这个路径先按状态和时间窗口扫候选，再按账户聚合。
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
    -- repayment 的内部主键；样例：repay-row-001 只能定位一条还款处理记录。
    -- 同一还款请求防重复靠 idempotency_key 唯一键，避免客户端重试产生多笔还款。
    id CHAR(36) PRIMARY KEY,
    idempotency_key VARCHAR(100) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    statement_id CHAR(36) NOT NULL,
    -- PENDING 时可以为空；成功应用后记录归属账户，便于账户维度查询。
    credit_account_id CHAR(36) NULL,
    amount DECIMAL(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    received_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    -- 还款幂等唯一键：支付回调、客户端重试或 API 超时重放时，同一请求只能入账一次。
    -- 样例：idempotency_key=repay-20260703-001 的还款重复提交，第二次会回读原 repayment，
    -- 不会再次增加 paid_amount，也不会再次减少 posted_balance。
    CONSTRAINT uk_repayments_idempotency_key UNIQUE (idempotency_key),
    -- 索引样例：打开账单详情页时查询该 statement 下的还款记录，按 created_at 展示处理顺序。
    -- statement_id 是主过滤条件，created_at 让列表天然按时间线读取。
    INDEX idx_repayments_statement (statement_id, created_at),
    -- 索引样例：账户对账时查询 account A 在某天收到的所有还款，WHERE credit_account_id=? AND received_at BETWEEN ...
    -- credit_account_id 可空只发生在 PENDING；RECEIVED 状态约束保证成功还款一定能走账户维度查询。
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
    -- ledger entry 的内部主键；样例：ledger-001 只能定位一条账本事实。
    -- 同一事件重复落账靠 source_event_id + entry_type 唯一键拦截。
    id CHAR(36) PRIMARY KEY,
    -- 来源事件 id；同一事件重复消费时用于幂等。
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
    -- 同一个 event 对同一种 entry type 只能落一次账。
    -- 样例：CARD_TRANSACTION_POSTED event 因 Kafka redelivery 被消费两次，
    -- 第二次插入同一 source_event_id + CARD_TRANSACTION_POSTED 会撞唯一键，避免账本重复 DEBIT。
    CONSTRAINT uk_ledger_entries_source_event_type UNIQUE (source_event_id, entry_type),
    -- 索引样例：生成 statement 时按 account A + occurred_at 时间窗口读取账本事实，
    -- id 作为最后一列提供稳定排序/分页，避免同一时间戳的记录批处理顺序不稳定。
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
    -- statement line 的内部主键；样例：line-001 只能定位账单上的一行展示快照。
    -- 同一交易/账本事实重复进账单，靠下面两个 UNIQUE 约束拦截。
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
    -- 一笔 card transaction 只能进入一条 statement line，防止重复出账。
    -- 样例：statement worker 处理到一半超时重试，同一 card_transaction_id 再次生成账单行会撞唯一键，
    -- 避免一笔 1000 JPY 消费在同一张或不同账单里出现两次。
    CONSTRAINT uk_statement_lines_card_transaction UNIQUE (card_transaction_id),
    -- 一条 ledger entry 最多对应一条 statement line；MySQL 允许多个 NULL。
    -- 这里 ledger_entry_id 可空是为了保留迁移/兼容空间；但非空时必须唯一，防止同一账本事实被重复出账。
    -- 样例：ledger_entry_id=le-001 已经形成 statement line，重跑账单生成时再次引用它会撞唯一键；
    -- 但旧数据若 ledger_entry_id=NULL，多条 NULL 不会互相冲突，这是 MySQL UNIQUE 的特性。
    CONSTRAINT uk_statement_lines_ledger_entry UNIQUE (ledger_entry_id),
    -- 索引样例：账单详情页读取某 statement 的所有账单行，并按 posted_at 展示消费时间线。
    -- card_transaction_id 放最后，是为了同一 posted_at 下仍有稳定的二级顺序和回表定位线索。
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
    -- job 的内部主键；样例：job-001 只能定位一个 durable batch shard。
    -- 同一账期同一 shard 防重复靠 uk_statement_jobs_cycle_shard。
    id CHAR(36) PRIMARY KEY,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    due_date DATE NOT NULL,
    shard_no INT NOT NULL,
    shard_count INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    -- PROCESSING 时记录领取者，非 PROCESSING 必须为空。
    claimed_by VARCHAR(100) NULL,
    claimed_at TIMESTAMP(6) NULL,
    -- lease deadline；只回答 WHEN 到期，不回答 WHO 拥有。
    claim_until TIMESTAMP(6) NULL,
    -- lease owner token；worker finalize 前用它防止 stale worker 覆盖新 owner。
    claim_token CHAR(36) NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    processed_account_count INT NOT NULL DEFAULT 0,
    generated_statement_count INT NOT NULL DEFAULT 0,
    skipped_account_count INT NOT NULL DEFAULT 0,
    failed_account_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    last_error VARCHAR(500) NULL,
    -- scheduler 重复触发同一 cycle 时，唯一键保证每个 shard 只创建一次。
    -- 这就是 statement job creation 的 idempotency key：应用停机后补跑、多个 pod 同时跑，都不会建重复分片。
    -- 样例：2026-07 账期 shard 3 已存在，另一个 scheduler 实例再次创建同一 period_start/period_end/shard_no，
    -- INSERT 会撞唯一键；系统应把它当成“已创建”，而不是制造第二个 shard 3。
    CONSTRAINT uk_statement_jobs_cycle_shard UNIQUE (period_start, period_end, shard_no),
    -- 索引样例：dispatcher 扫描 status='PENDING' 的可领取 job；
    -- recoverer 扫描 status='PROCESSING' 且 claim_until<=now 的过期 lease job。
    -- status + claim_until + created_at 让“可领取/可恢复”的队列扫描保持小范围、有顺序。
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
    -- outbox event 的内部主键；样例：evt-001 只能定位一条待发布消息。
    -- 注意这里没有业务唯一键：同一 aggregate 可以产生多个不同 event，这是合法的。
    id CHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INT NOT NULL,
    partition_key VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    -- PENDING/FAILED 时是下次可发布时间；PROCESSING 时复用为 lease deadline。
    next_attempt_at TIMESTAMP(6) NOT NULL,
    -- lease owner token；next_attempt_at 是 deadline，不是 owner identity。
    lease_token CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    -- Kafka broker ack 后写入；不代表 consumer 已处理完成。
    published_at TIMESTAMP(6) NULL,
    last_error VARCHAR(500) NULL,
    -- 索引样例：Outbox poller 扫描 WHERE status='PENDING' AND next_attempt_at<=now ORDER BY created_at LIMIT 100。
    -- status 先过滤可发布消息，next_attempt_at 控制 retry/backoff 时间，created_at 保持近似 FIFO。
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
    -- delay job 的内部主键；样例：delay-001 只能定位一个未来业务动作。
    -- 同一 aggregate 同一 job_type 防重复靠 uk_delay_jobs_aggregate。
    id CHAR(36) PRIMARY KEY,
    job_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    -- 业务计划时间，例如 authorization expiresAt 或 statement dueDate。
    scheduled_at TIMESTAMP(6) NOT NULL,
    -- PENDING 时是下次可执行时间；PROCESSING 时复用为 lease deadline。
    next_attempt_at TIMESTAMP(6) NOT NULL,
    -- lease owner token；worker finalize 前比较 token，挡住迟到 worker。
    lease_token CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    last_error VARCHAR(500) NULL,
    -- 同一 aggregate 的同一 future action 只计划一次，保证 scheduler/重试幂等。
    -- 例如同一 authorization 只能有一个 AUTHORIZATION_EXPIRY job；重复 approve/replay 不会创建两条释放任务。
    -- 样例：authorization auth-001 批准后已经创建 AUTHORIZATION_EXPIRY job，API 重试或事件重放再次 schedule，
    -- 会撞 (job_type, aggregate_type, aggregate_id) 唯一键，避免到期时重复释放 reserved_amount。
    CONSTRAINT uk_delay_jobs_aggregate UNIQUE (job_type, aggregate_type, aggregate_id),
    -- 索引样例：DelayJob worker 扫描 WHERE status='PENDING' AND next_attempt_at<=now ORDER BY created_at。
    -- 它和 Outbox 的 publishable 索引形状相似，但语义是“执行业务动作”，不是“发布消息”。
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
    -- notification intent 的内部主键；样例：notif-001 只能定位一次通知意图。
    -- 同一 integration event 防重复靠 source_event_id 唯一键。
    id CHAR(36) PRIMARY KEY,
    -- 来源 integration event id；consumer replay 时用它保证同一事件只创建一个通知意图。
    source_event_id CHAR(36) NOT NULL,
    subject_type VARCHAR(50) NOT NULL,
    subject_id VARCHAR(100) NOT NULL,
    recipient_key VARCHAR(100) NOT NULL,
    -- 模板/通知业务类型，例如 AUTHORIZATION_APPROVED、STATEMENT_CLOSED。
    notification_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    -- 同一个 integration event 只能创建一个 notification intent；Kafka redelivery 靠它幂等。
    -- 样例：authorization.approved event 重投递两次，source_event_id 相同，
    -- 第二次 INSERT notification 会撞唯一键，避免同一业务事件 fan-out 出两组 delivery。
    CONSTRAINT uk_notifications_source_event UNIQUE (source_event_id),
    -- 索引样例：用户消息中心按 recipient_key=user-123 查询通知列表，ORDER BY created_at DESC。
    -- 即使当前项目没有完整消息中心 API，这个索引表达了 notification intent 的主要读模型方向。
    -- subject_type 不在 DB 层做白名单 CHECK：notification 是扩展型外围能力，
    -- 新业务主题应先由 Java enum/domain/listener 控制，避免每加一种通知对象都必须做 schema migration。
    INDEX idx_notifications_recipient (recipient_key, created_at)
);

-- notification_deliveries 是每个渠道的投递工作单元。
-- 关键学习点：APP_PUSH 成功、EMAIL 失败重试这种局部结果，必须拆到 delivery 层才能表达。
CREATE TABLE IF NOT EXISTS notification_deliveries (
    -- delivery 的内部主键；样例：delivery-001 只能定位一个渠道投递任务。
    -- 同一 notification/channel 防重复靠 uk_notification_deliveries_channel。
    id CHAR(36) PRIMARY KEY,
    notification_id CHAR(36) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    -- 下面三列是 notification 的 immutable 快照，让 delivery 自洽，不必 JOIN notification 才能发送。
    notification_type VARCHAR(50) NOT NULL,
    subject_id VARCHAR(100) NOT NULL,
    recipient_key VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    -- PENDING 时是下次可投递时间；PROCESSING 时复用为 lease deadline。
    next_attempt_at TIMESTAMP(6) NOT NULL,
    -- lease owner token；不能用 next_attempt_at 时间戳兼任 owner。
    lease_token CHAR(36) NULL,
    last_error VARCHAR(500) NULL,
    -- provider 返回的消息 id，作为送达证据和排障线索。
    provider_message_id VARCHAR(100) NULL,
    sent_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    -- 同一 notification 的同一 channel 只能有一条 delivery。
    -- 如果 APP_PUSH delivery 被重复创建，用户可能收到两次推送；如果 EMAIL 重复创建，也会破坏 retry 统计。
    -- 样例：notification n-001 应 fan-out APP_PUSH 和 EMAIL 各一条；重复构建 APP_PUSH 时撞唯一键，
    -- 但 APP_PUSH 与 EMAIL 可以同时存在，因为 channel 不同。
    CONSTRAINT uk_notification_deliveries_channel UNIQUE (notification_id, channel),
    -- 索引样例：delivery worker 扫描 status='PENDING' 且 next_attempt_at<=now 的投递任务，
    -- 失败 backoff 后仍按 next_attempt_at 重新进入队列，created_at 维持同一时间窗口内的处理顺序。
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
    consumer_name VARCHAR(100) NOT NULL,
    event_id CHAR(36) NOT NULL,
    processed_at TIMESTAMP(6) NOT NULL,
    -- 同一个 consumer 对同一个 event 只能处理一次；不同 consumer 可以各自处理。
    -- 样例：AuthorizationNotificationListener 已处理 event e-001 后 Kafka rebalance 又投递一次，
    -- 再插入 (consumer_name='authorization-notification', event_id='e-001') 会撞主键，业务逻辑直接跳过。
    -- 另一个 consumer_name 可以处理同一 event，因为它代表不同订阅者的独立进度。
    PRIMARY KEY (consumer_name, event_id)
);

-- card_risk_features 是风险读模型/projection。
-- 关键学习点：由 Kafka 事件异步投影，支持授权时快速读取 velocity/历史决策特征。
CREATE TABLE IF NOT EXISTS card_risk_features (
    -- card_id 既是业务 key 也是主键；样例：card-123 只有一行 velocity/projection 计数。
    -- Kafka 重复投影时应更新同一行，而不是插入第二行风险特征。
    card_id VARCHAR(100) PRIMARY KEY,
    authorization_count BIGINT NOT NULL DEFAULT 0,
    approved_count BIGINT NOT NULL DEFAULT 0,
    declined_count BIGINT NOT NULL DEFAULT 0,
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
