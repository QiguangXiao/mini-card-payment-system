package com.minicard.repayment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.repayment.domain.Repayment;
import com.minicard.repayment.domain.RepaymentRepository;
import com.minicard.repayment.domain.RepaymentStatus;
import com.minicard.repayment.domain.event.RepaymentDomainEvent;
import com.minicard.statement.application.StatementSnapshotCacheInvalidator;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementStatus;
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
 * statement paidAmount/status 和 Outbox event。它们必须在同一个 transaction boundary 内提交。</p>
 */
@Service
@RequiredArgsConstructor
public class RepaymentService {

    private final RepaymentRepository repaymentRepository;
    private final StatementRepository statementRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final RepaymentDomainEventPublisher eventPublisher;
    private final StatementSnapshotCacheInvalidator statementSnapshotCacheInvalidator;
    private final Clock clock;

    @Transactional
    public Repayment receive(ReceiveRepaymentCommand command) {
        Instant now = Instant.now(clock);
        // 先确认 statement 存在并取得 immutable accountId，避免 repayment claim 先撞 FK
        // 变成晦涩的数据库异常。余额和状态决策仍会在后面 FOR UPDATE 后重新校验。
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

        applyToStatementAndAccount(repayment, statementSnapshot, command.money(), now);
        repaymentRepository.update(repayment);
        publishDomainEvents(repayment);
        return repayment;
    }

    @Transactional(readOnly = true)
    public Repayment get(UUID id) {
        return repaymentRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("repayment not found: " + id));
    }

    private void applyToStatementAndAccount(
            Repayment repayment,
            Statement statementSnapshot,
            Money amount,
            Instant now
    ) {
        // 全项目保持同一锁顺序：credit account row lock 先于 statement row lock。
        // 这样 repayment 不会和 StatementService.generate(account -> statement) 形成死锁。
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

        validateCanApply(statement, account, amount);
        // 这两个 aggregate state changes 在同一个 transaction boundary 内提交：
        // account.postedBalance 释放额度，statement.paidAmount/status 推进账单生命周期。
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
        // statement.paidAmount/status 改变后必须 evict GET read model。
        // 注意注册到 after commit：如果在 transaction boundary 内提前删缓存，另一个 GET
        // 可能读到旧 DB 快照并重新写入 Redis，造成还款后短时间 stale response。
        statementSnapshotCacheInvalidator.evictAfterCommit(statement.id());
    }

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

    private void publishDomainEvents(Repayment repayment) {
        for (RepaymentDomainEvent event : repayment.pullDomainEvents()) {
            // Outbox row 和 repayment/account/statement 状态一起 commit。
            // Kafka publish 由后台 worker 处理，所以还款 API 不等待 broker。
            eventPublisher.append(event);
        }
    }

    private void assertSameIdempotentRequest(
            ReceiveRepaymentCommand command,
            Repayment repayment
    ) {
        if (!command.matches(repayment)) {
            throw new RepaymentConflictException();
        }
    }
}
