package com.minicard.repayment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.repayment.domain.Repayment;
import com.minicard.repayment.domain.RepaymentRepository;
import com.minicard.repayment.domain.RepaymentStatus;
import com.minicard.repayment.domain.event.RepaymentDomainEvent;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementStatus;
import com.minicard.statement.application.StatementReadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 还款入账 use case。
 *
 * <p>关键词：还款入账, 幂等键, 余额扣减, repayment service,
 * idempotency key, posted balance, 入金処理(にゅうきんしょり),
 * 入金処理(にゅうきんしょり)。</p>
 *
 * <p>interview重点：Repayment 同时影响 repayment row、credit account postedBalance、
 * statement paidAmount/status/version 和 Outbox event。它们必须在同一个 transaction boundary 内提交。</p>
 *
 * <p>流程总览（mini trace，全部在一个 DB transaction 内；锁顺序固定 account -> statement）：</p>
 * <pre>
 * POST /api/repayments
 *  -> load statement snapshot（不锁，只为拿 creditAccountId + 提前挡 FK 异常）
 *  -> INSERT-first claim repayment by idempotency_key
 *  -> SELECT repayment FOR UPDATE + fingerprint 校验
 *  -> loser: RECEIVED 返回幂等结果 / 处理中则拒绝
 *  -> winner: SELECT credit_account FOR UPDATE（先于 statement，和账单生成同锁顺序）
 *  -> SELECT statement FOR UPDATE
 *  -> 锁内重新校验金额/币种/账单状态/账户归属（不信任阶段 1 快照）
 *  -> account.applyRepayment: postedBalance 下降，额度释放
 *  -> statement.applyRepayment: paidAmount/status/version 推进
 *  -> 注册 after-commit cache invalidation（不在事务内直接写缓存）
 *  -> update repayment RECEIVED
 *  -> append Outbox event repayment.received
 *  -> COMMIT -> 触发 statement 读缓存失效
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class RepaymentService {

    private final RepaymentRepository repaymentRepository;
    private final StatementRepository statementRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final RepaymentDomainEventPublisher eventPublisher;
    private final StatementReadService statementReadService;
    private final Clock clock;

    /**
     * 处理还款 API 写路径：幂等 claim、锁账户和账单、入账、失效账单读缓存、写 Outbox。
     */
    @Transactional
    public Repayment receive(ReceiveRepaymentCommand command) {
        Instant now = Instant.now(clock);
        // 阶段 1：先确认 statement 存在，取得 creditAccountId 快照。
        // 这里不是最终状态判断；真正的金额/状态校验会在后面 SELECT ... FOR UPDATE 后重新做。
        // 先查这一步是为了避免 repayment claim 先撞 FK，变成晦涩的数据库异常。
        Statement statementSnapshot = statementRepository.findById(command.statementId())
                .orElseThrow(() -> new NoSuchElementException(
                        "statement not found: " + command.statementId()
                ));
        Repayment pending = Repayment.pending(
                command.idempotencyKey(),
                command.requestFingerprint(),
                command.statementId(),
                command.money(),
                now
        );

        // 阶段 2：INSERT-first idempotency claim，争夺本还款幂等键的处理权。
        // INSERT-first idempotency claim：同一 Idempotency-Key 的并发请求只有一个 winner。
        // loser 会在 findByIdempotencyKeyForUpdate 等待 winner commit 后读取最终 RECEIVED 结果。
        // 如果没有这层 claim，客户端或银行回调重复提交会多次减少 postedBalance，造成 double repayment。
        boolean claimed = repaymentRepository.claim(pending);
        Repayment repayment = repaymentRepository
                .findByIdempotencyKeyForUpdate(command.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("repayment claim was not visible"));
        assertSameIdempotentRequest(command, repayment);
        if (!claimed) {
            if (repayment.status() == RepaymentStatus.RECEIVED) {
                return repayment;
            }
            throw new RepaymentRejectedException(
                    "repayment is already being processed: " + command.idempotencyKey()
            );
        }

        // 阶段 3：winner 才能把还款应用到 statement 和 credit account。
        // 这里会锁 account/statement，推进 paidAmount/status/postedBalance，并注册 cache after-commit 失效。
        applyToStatementAndAccount(repayment, statementSnapshot, command.money(), now);
        // 阶段 4：保存 repayment RECEIVED 状态。它与 account/statement 更新处于同一 transaction boundary。
        repaymentRepository.update(repayment);
        // 阶段 5：把 repayment.received 事实追加到 Outbox。Kafka 发布由后台 worker 做，API 不等待 broker。
        publishDomainEvents(repayment);
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
            Statement statementSnapshot,
            Money amount,
            Instant now
    ) {
        // 应用阶段 1：按全项目锁顺序先锁 credit account，再锁 statement。
        // 全项目保持同一锁顺序：credit account row lock 先于 statement row lock。
        // 这样 repayment 不会和 StatementGenerationService.generate(account -> statement) 形成死锁。
        // 如果 repayment 反过来先锁 statement，再等 account，就可能和 statement batch 互相等待。
        CreditAccount account = creditAccountRepository
                .findByIdForUpdate(statementSnapshot.creditAccountId())
                .orElseThrow(() -> new RepaymentRejectedException(
                        "statement references missing credit account "
                                + statementSnapshot.creditAccountId()
                ));
        Statement statement = statementRepository.findByIdForUpdate(repayment.statementId())
                .orElseThrow(() -> new NoSuchElementException(
                        "statement not found: " + repayment.statementId()
                ));

        // 应用阶段 2：在锁内重新校验金额、币种、账单状态和账户归属。
        // 不能只相信阶段 1 的 statementSnapshot，因为它不是锁定快照，期间可能已有其它还款推进状态。
        validateCanApply(statement, account, amount);
        // 应用阶段 3：推进两个 aggregate 和 repayment 自身的业务状态。
        // 这两个 aggregate state changes 在同一个 transaction boundary 内提交：
        // account.postedBalance 释放额度，statement.paidAmount/status/version 推进账单生命周期。
        account.applyRepayment(amount);
        statement.applyRepayment(amount, now);
        repayment.markReceived(
                account.id(),
                statement.paidAmount(),
                statement.remainingAmount(),
                now
        );

        creditAccountRepository.update(account);
        statementRepository.updatePayment(statement);
        // 应用阶段 4：只注册 after-commit cache invalidation，不在事务中直接写缓存。
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
     * <p>事务归属：由 {@link #applyToStatementAndAccount(Repayment, Statement, Money, Instant)}
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
     * 把 repayment.received 领域事件追加到 Outbox。
     *
     * <p>事务归属：只由 {@link #receive(ReceiveRepaymentCommand)} 调用，加入同一个
     * {@code @Transactional} 边界；Outbox row 必须和 repayment/account/statement 状态一起提交。</p>
     */
    private void publishDomainEvents(Repayment repayment) {
        for (RepaymentDomainEvent event : repayment.pullDomainEvents()) {
            // Outbox row 和 repayment/account/statement 状态一起 commit。
            // Kafka publish 由后台 worker 处理，所以还款 API 不等待 broker。
            eventPublisher.append(event);
        }
    }

    /**
     * 校验同一个 idempotency key 是否仍代表同一笔还款请求。
     *
     * <p>事务归属：当前由 {@link #receive(ReceiveRepaymentCommand)} 在锁住 repayment winner row
     * 后调用；它本身是纯校验，但校验对象来自同一事务内的 {@code SELECT ... FOR UPDATE}。</p>
     */
    private void assertSameIdempotentRequest(
            ReceiveRepaymentCommand command,
            Repayment repayment
    ) {
        if (!command.matches(repayment)) {
            throw new RepaymentConflictException();
        }
    }
}
