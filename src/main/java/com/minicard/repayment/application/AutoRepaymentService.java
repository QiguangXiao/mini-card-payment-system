package com.minicard.repayment.application;

import java.util.NoSuchElementException;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.repayment.domain.Repayment;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 到期自动扣款 use case。
 *
 * <p>它不是直接改余额，而是先模拟 bank debit result。只有银行扣款 SUCCESS 后，
 * 才调用 RepaymentService.receive(...) 进入已有的 idempotency、row lock 和 transaction boundary。
 * 失败结果先让 DelayJob 记录 retry/DEAD，未来可以扩展失败通知、逾期标记或人工重试。</p>
 */
@Service
@RequiredArgsConstructor
public class AutoRepaymentService {

    private static final String IDEMPOTENCY_PREFIX = "auto-debit:";

    private final StatementRepository statementRepository;
    private final BankDebitGateway bankDebitGateway;
    private final RepaymentService repaymentService;

    public AutoRepaymentResult debitStatement(UUID statementId) {
        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new NoSuchElementException("statement not found: " + statementId));
        if (statement.status() == StatementStatus.PAID || !statement.remainingAmount().isPositive()) {
            return AutoRepaymentResult.alreadyPaid(statementId);
        }

        Money debitAmount = statement.remainingAmount();
        BankDebitResult debitResult = bankDebitGateway.debit(new BankDebitRequest(
                statement.id(),
                statement.creditAccountId(),
                debitAmount,
                statement.dueDate()
        ));
        if (debitResult.status() == BankDebitStatus.FAILED) {
            // 失败时不能调用 RepaymentService，否则会把未收到的银行资金误记为已还款。
            // 当前先把失败交给 DelayJob retry/DEAD；后续可在这里追加通知或 overdue flow。
            throw new AutoRepaymentFailedException(
                    "bank debit failed for statement " + statement.id()
                            + ": " + debitResult.failureReason()
            );
        }

        ReceiveRepaymentCommand command = new ReceiveRepaymentCommand(
                idempotencyKey(statement.id()),
                statement.id(),
                debitAmount.amount(),
                debitAmount.currency()
        );
        Repayment repayment = repaymentService.receive(command);
        return AutoRepaymentResult.succeeded(statement.id(), repayment.id());
    }

    private String idempotencyKey(UUID statementId) {
        // 自动扣款一张 statement 只应该入账一次；确定性 key 让 DelayJob 重试保持 idempotency。
        return IDEMPOTENCY_PREFIX + statementId;
    }
}
