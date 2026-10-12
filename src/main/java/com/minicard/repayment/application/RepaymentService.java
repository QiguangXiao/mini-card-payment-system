package com.minicard.repayment.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.repayment.domain.Repayment;
import com.minicard.repayment.domain.RepaymentRepository;
import com.minicard.repayment.domain.RepaymentStatus;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementStatus;
import com.minicard.statement.application.read.StatementReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 还款入账 use case。
 *
 * <p>关键词：还款入账, 幂等键, 余额扣减, repayment service,
 * idempotency key, posted balance, 入金処理(にゅうきんしょり),
 * 参照整合性(さんしょうせいごうせい), 外键检查, foreign key lock。</p>
 *
 * <p>interview重点：Repayment 同时影响 repayment row、credit account postedBalance、
 * statement paidAmount/status/version。它们必须在同一个 transaction boundary 内提交。</p>
 *
 * <p>流程总览（mini trace，全部在一个 DB transaction 内；编号对应方法内的"阶段 N"注释。
 * 锁顺序固定：credit account 先于 statement）：</p>
 * <pre>
 * POST /api/repayments
 * 1. 只查询 statement 的 creditAccountId（不锁、不加载明细），计算一次 Money/fingerprint
 * 2. SELECT credit_account FOR UPDATE，再 SELECT statement FOR UPDATE
 * 3. INSERT-first claim repayment（本事务第一笔写入，但不是第一把锁）
 *    winner 直接使用 pending；loser FOR SHARE + fingerprint 校验，RECEIVED 返回 / PENDING 拒绝
 * 4. 只有 winner 校验剩余金额/状态并应用还款，注册 after-commit cache invalidation
 * 5. update repayment RECEIVED，与 account/statement 一起 COMMIT，再失效读缓存
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class RepaymentService {

    private final RepaymentRepository repaymentRepository;
    private final StatementRepository statementRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final StatementReadService statementReadService;
    private final Clock clock;

    /**
     * 处理还款 API 写路径：幂等 claim、锁账户和账单、入账、失效账单读缓存。
     */
    @Transactional
    public Repayment receive(ReceiveRepaymentCommand command) {
        // TIMESTAMP(6) 只保存微秒；winner 直接使用 pending 时，首次响应与重放也必须精度一致。
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        Money amount = command.money();
        String requestFingerprint = command.requestFingerprint();
        // 阶段 1：轻量读取账户归属。这里只做路由，后面会锁定完整账单并校验最新状态。
        // 如果在这里加载整个 Statement，明细会在锁前/锁后各加载一次，增加查询和对象构造成本。
        UUID accountId = statementRepository.findCreditAccountIdById(command.statementId())
                .orElseThrow(() -> new NoSuchElementException(
                        "statement not found: " + command.statementId()
                ));

        // 阶段 2：先 account X，再 statement X，然后才 INSERT repayment，保留两个外键。
        // 外键检查也会加锁：若先 INSERT，可能已隐式取得 statement S，再去等 account X。
        // 两个还同一账单的请求就可能出现“A 持 account 等 B 的 statement S，B 持 statement S 等 A”。
        // 提前按 account -> statement 锁定，让后续 FK 检查发生在本事务已经持有排他锁之后。
        CreditAccount account = creditAccountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new RepaymentRejectedException(
                        "statement references missing credit account " + accountId));
        Statement statement = statementRepository.findByIdForUpdate(command.statementId())
                .orElseThrow(() -> new NoSuchElementException("statement not found: " + command.statementId()));
        Repayment pending = Repayment.pending(
                command.idempotencyKey(),
                requestFingerprint,
                command.statementId(),
                amount,
                now
        );

        // 阶段 3：INSERT-first claim 是第一笔写入，用幂等唯一键裁决 ownership。
        // 同账户请求通常已在阶段 2 的 account 锁上排队；若相同 key 来自不同账户，
        // 则还可能在 INSERT 唯一键检查处等待 winner commit/rollback，而不是等到回读才排队。
        // 如果没有这层 claim，客户端或银行回调重复提交会多次减少 postedBalance，造成 double repayment。
        boolean claimed = repaymentRepository.claim(pending);
        Repayment repayment;
        if (claimed) {
            // INSERT 已持有 repayment 排他锁，直接用本次 pending，省掉回读和恒真的 fingerprint 校验。
            repayment = pending;
        } else {
            // loser 只读结果；重复 INSERT 可能持有 S，FOR SHARE 避免不必要的 S -> X 升级循环。
            // 这里必须 locking/current read：阶段 1 已建立 RR 快照，普通 SELECT 可能看不到刚提交的 winner。
            repayment = repaymentRepository.findByIdempotencyKeyForShare(command.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("existing repayment was not visible"));
            assertSameIdempotentRequest(requestFingerprint, repayment);
            if (repayment.status() == RepaymentStatus.RECEIVED) {
                return repayment;
            }
            throw new RepaymentRejectedException(
                    "repayment is already being processed: " + command.idempotencyKey()
            );
        }

        // 阶段 4：只有 winner 才校验 PAID/remaining 并改变余额。若在 claim 前拒绝 PAID，
        // 首次全额还清后的合法 retry 就无法返回原 RECEIVED 结果，破坏 idempotency。
        applyToStatementAndAccount(repayment, statement, account, amount, now);
        // 阶段 5：保存 RECEIVED，与 account/statement 同事务提交；commit 后才执行缓存失效。
        repaymentRepository.update(repayment);
        // 如果在 commit 前直接删除缓存，另一个请求可能把旧 DB 快照重新写回，造成脏缓存。
        return repayment;
    }

    /**
     * 查询单笔 repayment，不改变业务状态。
     */
    @Transactional(readOnly = true)
    public Repayment get(UUID id) {
        return repaymentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("repayment not found: " + id));
    }

    /**
     * 在同一 transaction boundary 内把还款同时应用到账单和信用账户。
     *
     * <p>事务归属：只由 {@link #receive(ReceiveRepaymentCommand)} 调用，加入同一个
     * {@code @Transactional} 边界；statement、credit account、repayment 和 cache afterCommit
     * 注册必须基于同一次提交结果。</p>
     */
    private void applyToStatementAndAccount(
            Repayment repayment,
            Statement statement,
            CreditAccount account,
            Money amount,
            Instant now
    ) {
        // 应用阶段 1：使用 receive 已锁定的两个 aggregate，校验最新金额/状态及账户归属。
        // 初始账户路由读取不是余额决策依据，不能用它代替这里的 locked state。
        validateCanApply(statement, account, amount);
        // 应用阶段 2：推进两个 aggregate 和 repayment 自身的业务状态。
        // 这两个 aggregate state changes 在同一个 transaction boundary 内提交：
        // account.postedBalance 释放额度，statement.paidAmount/status/version 推进账单生命周期。
        account.applyRepayment(amount);
        statement.applyRepayment(amount, now);
        repayment.markReceived(account.id(), now);

        creditAccountRepository.update(account);
        statementRepository.updatePayment(statement);
        // 应用阶段 3：只注册 after-commit cache invalidation，不在事务中直接写缓存。
        // 写路径不"顺手更新 cache"，而是更新 MySQL source of truth 后失效 cache。
        // 原因是 statement response 由多张表/字段组装而来，并发下手工更新 cache 容易漏字段或写旧值。
        // 传整个 statement（而不是只传 id）：Statement.applyRepayment 已经推进正式 version，
        // StatementReadService 用它写墓碑版本地板，让"迟到写"(旧版本)被 L2 的 CAS 拒绝。
        // 失效只在 DB commit 后触发（见 evictAfterCommit）。
        // 跨 pod L1 仍最长 stale 一个 localTtl（除非开启 Pub/Sub 广播），这是刻意接受的 tradeoff：
        // 账单查询容忍秒级 stale；要强一致就直接读 DB。
        statementReadService.evictAfterCommit(statement);
    }

    /**
     * 校验还款金额、币种、账单状态和账户余额是否允许本次入账。
     *
     * <p>事务归属：由 {@link #applyToStatementAndAccount(Repayment, Statement, CreditAccount, Money, Instant)}
     * 在锁住 account 与 statement row 后调用；它本身不写库，但依赖同一事务内的最新锁定快照。</p>
     */
    private void validateCanApply(
            Statement statement,
            CreditAccount account,
            Money amount
    ) {
        if (!statement.creditAccountId().equals(account.id())) {
            // 没有这个 guard，脏数据或错误调用可能把还款金额扣到不属于该 statement 的账户上。
            throw new RepaymentRejectedException("statement does not belong to locked credit account");
        }
        if (!statement.totalAmount().currency().equals(amount.currency())) {
            throw new RepaymentRejectedException("repayment currency must match statement currency");
        }
        if (!account.creditLimit().currency().equals(amount.currency())) {
            throw new RepaymentRejectedException("repayment currency must match account currency");
        }
        if (!amount.isPositive()) {
            throw new RepaymentRejectedException("repayment amount must be positive");
        }
        if (statement.status() == StatementStatus.PAID) {
            throw new RepaymentRejectedException("statement is already paid");
        }
        if (amount.isGreaterThan(statement.remainingAmount())) {
            throw new RepaymentRejectedException("repayment amount exceeds statement remaining amount");
        }
        if (amount.isGreaterThan(account.postedBalance())) {
            throw new RepaymentRejectedException("repayment amount exceeds account posted balance");
        }
    }

    /**
     * 校验同一个 idempotency key 是否仍代表同一笔还款请求。
     *
     * <p>只由 loser 分支调用；fingerprint 本次请求只计算一次，已有对象来自 FOR SHARE 最新读取。</p>
     */
    private void assertSameIdempotentRequest(
            String requestFingerprint,
            Repayment repayment
    ) {
        if (!requestFingerprint.equals(repayment.requestFingerprint())) {
            throw new RepaymentConflictException();
        }
    }
}
