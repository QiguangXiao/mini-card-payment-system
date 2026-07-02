package com.minicard.repayment.application;

import java.util.NoSuchElementException;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.repayment.domain.Repayment;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 到期自动扣款 use case（每月账单到期日的口座振替 / auto debit）。
 *
 * <p>关键词：自动还款, 银行扣款, 幂等入账, auto repayment,
 * bank debit, idempotency, 自動引き落とし(じどうひきおとし),
 * 口座振替(こうざふりかえ), 冪等性(べきとうせい)。</p>
 *
 * <p>它不直接改余额，而是先向 bank debit gateway 请求扣款。只有银行扣款成功后，
 * 才调用 RepaymentService.receive(...) 复用已有的 idempotency、row lock 和 transaction boundary。</p>
 *
 * <p>方法刻意不返回 result 对象：DelayJob 完全靠“是否抛异常”决定 retry / DONE，
 * 一个没有消费方的 result 在这里只是多余包装。业务 outcome 用结构化日志表达即可。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
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
     * <p>事务归属：本方法刻意不加 {@code @Transactional}，因为银行扣款是事务外副作用。
     * 银行成功后才调用 {@link RepaymentService#receive(ReceiveRepaymentCommand)} 进入还款写事务；
     * 如果把外部扣款包进数据库事务，银行延迟会放大 MySQL 锁时间，且数据库 rollback 也无法撤销真实扣款。</p>
     *
     * <p>顺序非常关键：先拿到 bank debit 成功，再调用 repayment posting；
     * 否则会把银行未实际收到的钱错误记入 credit account。</p>
     *
     * <p>无返回值：成功 / 已还清都正常结束（DelayJob 标 DONE），
     * 失败抛 {@link AutoRepaymentFailedException} 让 DelayJob 进入 retry / DEAD。</p>
     */
    public void debitStatement(UUID statementId) {
        Statement statement = statementRepository.findById(statementId)
                .orElseThrow(() -> new NoSuchElementException("statement not found: " + statementId));
        if (statement.status() == StatementStatus.PAID || !statement.remainingAmount().isPositive()) {
            // DelayJob 可能晚到或重复执行；已经还清时直接视为幂等成功，不再扣款。
            // 如果这里继续扣款，用户手动还清后到期 job 仍会再次入账，形成 overpayment。
            log.info("auto_repayment_skip_already_paid statementId={}", statementId);
            return;
        }

        Money debitAmount = statement.remainingAmount();
        // BankDebitRequest 是 application 层 typed command，不把 Statement aggregate 直接传给外部 gateway。
        // 如果 gateway 依赖整个 Statement，外部 adapter 会被迫了解账单内部状态。
        // 用与 repayment 入账相同的 deterministic key：整条“自动扣款这张账单一次”端到端幂等，
        // DelayJob retry 时 gateway 凭此 key 复用首次结果，不会从客户银行账户重复出金。
        BankDebitResult debitResult = bankDebitGateway.debit(new BankDebitRequest(
                idempotencyKey(statement.id()),
                statement.id(),
                statement.creditAccountId(),
                debitAmount,
                statement.dueDate()
        ));
        if (!debitResult.successful()) {
            // 失败时不能调用 RepaymentService，否则会把未收到的银行资金误记为已还款。
            // 抛异常让 DelayJob retry/DEAD；后续可在这里追加通知或 overdue flow。
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
        // 如果 handler 自己直接改余额，会绕过 RepaymentService 的 idempotency、row lock 和 Outbox event。
        Repayment repayment = repaymentService.receive(command);
        log.info(
                "auto_repayment_posted statementId={} repaymentId={} amount={}",
                statement.id(),
                repayment.id(),
                debitAmount.amount()
        );
    }

    /**
     * 生成自动扣款的幂等键（idempotency key / 冪等キー）。
     */
    private String idempotencyKey(UUID statementId) {
        // 自动扣款一张 statement 只应该入账一次；确定性 key 让 DelayJob 重试保持 idempotency。
        // 如果每次 retry 都生成随机 key，RepaymentService 会把同一笔自动扣款当成多笔新还款。
        return IDEMPOTENCY_PREFIX + statementId;
    }
}
