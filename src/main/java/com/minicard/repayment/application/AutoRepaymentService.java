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
 * <p>关键词：自动还款, 银行扣款, 幂等入账, auto repayment,
 * bank debit, idempotency, 自動引き落とし(じどうひきおとし),
 * 口座振替(こうざふりかえ), 冪等性(べきとうせい)。</p>
 *
 * <p>它不是直接改余额，而是先模拟 bank debit result。只有银行扣款 SUCCESS 后，
 * 才调用 RepaymentService.receive(...) 进入已有的 idempotency、row lock 和 transaction boundary。
 * 失败结果先让 DelayJob 记录 retry/DEAD，未来可以扩展失败通知、逾期标记或人工重试。</p>
 */
@Service
@RequiredArgsConstructor
public class AutoRepaymentService {

    /** 自动扣款使用 deterministic idempotency key，确保 DelayJob retry 不重复入账。 */
    private static final String IDEMPOTENCY_PREFIX = "auto-debit:";

    /** 查询 statement 当前剩余应还金额；真正加锁和扣余额在 RepaymentService 内完成。 */
    private final StatementRepository statementRepository;
    /** 银行扣款 gateway，当前为本地模拟，未来可替换成银行 API/口座振替文件。 */
    private final BankDebitGateway bankDebitGateway;
    /** 复用人工还款入账逻辑，保留 idempotency、row lock 和 domain event。 */
    private final RepaymentService repaymentService;

    /**
     * 对一张到期 statement 执行自动扣款。
     *
     * <p>顺序非常关键：先得到 bank debit SUCCESS，再调用 repayment posting；
     * 否则会把银行未实际收到的钱错误记入 credit account。</p>
     */
    public AutoRepaymentResult debitStatement(UUID statementId) {
        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new NoSuchElementException("statement not found: " + statementId));
        if (statement.status() == StatementStatus.PAID || !statement.remainingAmount().isPositive()) {
            // DelayJob 可能晚到或重复执行；已经还清时直接视为幂等成功。
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
        // 从这里进入已有 repayment transaction boundary：锁 statement/account 后再扣减余额。
        Repayment repayment = repaymentService.receive(command);
        return AutoRepaymentResult.succeeded(statement.id(), repayment.id());
    }

    /**
     * 生成自动扣款的幂等键（idempotency key / 冪等キー）。
     */
    private String idempotencyKey(UUID statementId) {
        // 自动扣款一张 statement 只应该入账一次；确定性 key 让 DelayJob 重试保持 idempotency。
        return IDEMPOTENCY_PREFIX + statementId;
    }
}
