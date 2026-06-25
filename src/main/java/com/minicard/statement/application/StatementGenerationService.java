package com.minicard.statement.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Currency;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementLineSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账单生成 use case。
 *
 * <p>关键词：账单生成, 交易快照, BILLED 标记, statement generation,
 * transaction snapshot, billing cycle, 請求明細作成(せいきゅうめいさいさくせい),
 * 明細(めいさい)。</p>
 *
 * <p>interview重点：Statement generation 是批处理业务，但仍然需要清楚的 transaction boundary。
 * 同一事务里锁账户、锁待出账交易、创建 statement line 快照、标记交易已归账。</p>
 */
@Service
@RequiredArgsConstructor
public class StatementGenerationService {

    private final StatementRepository statementRepository;
    private final StatementBillingRepository billingRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final StatementDueJobScheduler dueJobScheduler;
    private final StatementProperties properties;
    private final Clock clock;

    @Transactional
    public Statement generate(GenerateStatementCommand command) {
        Instant now = Instant.now(clock);

        // 账户 row lock 是账单生成的并发门(concurrency gate)。
        // PostingService 也会先锁同一个 credit account，再创建/更新 CardTransaction，
        // 因此 statement 生成期间不会漏掉正在入账的交易，也不会和 posting 产生锁顺序死锁。
        // 如果不先锁 account，账单扫描和 posting 可能交错，导致一笔已入账交易漏进下一期或重复归账。
        CreditAccount account = creditAccountRepository
                .findByIdForUpdate(command.creditAccountId())
                .orElseThrow(() -> new NoSuchElementException(
                        "credit account not found: " + command.creditAccountId()
                ));

        // 一个 credit account 在同一 billing cycle 只能有一张账单。
        // 这个自然键就是 statement generation 的 idempotency key。
        // 如果没有 cycle-level idempotency，batch retry 可能给同一账户同一期生成两张账单。
        return statementRepository
                .findByCycleForUpdate(
                        command.creditAccountId(),
                        command.periodStart(),
                        command.periodEnd()
                )
                .orElseGet(() -> createStatement(command, account, now));
    }

    @Transactional(readOnly = true)
    public Statement get(UUID id) {
        return statementRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("statement not found: " + id));
    }

    private Statement createStatement(
            GenerateStatementCommand command,
            CreditAccount account,
            Instant now
    ) {
        if (billingRepository.existsUnbilledPostedTransactionMissingLedger(
                account.id(),
                periodStartInstant(command.periodStart()),
                periodEndExclusiveInstant(command.periodEnd())
        )) {
            // Ledger 是 append-only projection，但 statement line 必须能追到 ledger_entry_id。
            // 如果这里静默跳过缺 ledger 的交易，本期账单会漏消费，生产上会变成对账缺口。
            throw StatementGenerationException.retryable(
                    "posted card transactions are waiting for ledger entries"
            );
        }

        List<StatementLineSource> lineSources = billingRepository
                .findBillableLineSourcesForUpdate(
                        account.id(),
                        periodStartInstant(command.periodStart()),
                        periodEndExclusiveInstant(command.periodEnd())
                );
        if (lineSources.isEmpty()) {
            // 当前阶段不生成 zero-activity statement，避免空账单把同一周期锁死。
            // 真实系统可按产品策略选择是否给无交易账户生成 0 元账单。
            throw StatementGenerationException.rejected(
                    "no unbilled posted transactions in statement period"
            );
        }

        Money totalAmount = totalAmount(lineSources);
        Statement statement = Statement.close(
                account.id(),
                command.periodStart(),
                command.periodEnd(),
                command.dueDate(),
                lineSources,
                minimumPayment(totalAmount),
                now
        );

        boolean inserted = statementRepository.insert(statement);
        if (!inserted) {
            // 理论上账户 row lock 已经串行化同账户同周期生成；这里是 DB unique constraint 的
            // defensive idempotency fallback，防止未来入口绕过本 service。
            return statementRepository
                    .findByCycleForUpdate(
                            command.creditAccountId(),
                            command.periodStart(),
                            command.periodEnd()
                    )
                    .orElseThrow(() -> new IllegalStateException(
                            "statement unique conflict but existing statement was not visible"
                    ));
        }

        // CardTransaction 是用户可见消费明细；StatementLine 是账单快照。
        // 同一 transaction boundary 内把交易标记为 BILLED，防止下一轮 job 重复生成 line。
        billingRepository.markCardTransactionsBilled(statement.id(), lineSources, now);
        // 自动扣款计划是 future business action，使用 DelayJob 而不是 Outbox。
        // 它和 statement/items/transaction assignment 同事务提交，防止账单生成后漏掉 due-date 扣款。
        // 如果这一步不和 statement 同事务提交，就可能出现账单已生成但没有任何到期扣款计划。
        dueJobScheduler.scheduleAutoRepayment(statement);
        return statement;
    }

    private Money totalAmount(List<StatementLineSource> transactions) {
        // getFirst() 是 Java 21 SequencedCollection API；前面已经保证 transactions 非空。
        // 如果没有非空保护，这里会抛 NoSuchElementException，比金额计算错误更早暴露输入问题。
        Currency currency = transactions.getFirst().amount().currency();
        Money total = new Money(BigDecimal.ZERO, currency);
        for (StatementLineSource transaction : transactions) {
            total = total.add(transaction.amount());
        }
        return total;
    }

    private Money minimumPayment(Money totalAmount) {
        BigDecimal floor = properties.policy().minimumPaymentFloors()
                .get(totalAmount.currency().getCurrencyCode());
        if (floor == null) {
            throw StatementGenerationException.rejected(
                    "minimum payment floor is not configured for currency "
                            + totalAmount.currency().getCurrencyCode()
            );
        }
        BigDecimal percentageAmount = totalAmount.amount()
                .multiply(properties.policy().minimumPaymentRate())
                // CEILING 对最低还款更保守：分以下的小数向上取整到 0.01，避免少收最低还款。
                // 如果使用 HALF_UP，某些边界金额会被舍入到更低的 minimum payment。
                .setScale(2, RoundingMode.CEILING);
        BigDecimal minimum = percentageAmount.max(floor);
        if (minimum.compareTo(totalAmount.amount()) > 0) {
            minimum = totalAmount.amount();
        }
        return new Money(minimum, totalAmount.currency());
    }

    private Instant periodStartInstant(LocalDate periodStart) {
        // 账单日切必须用 billing timezone。当前项目使用全局 Asia/Tokyo；
        // 如果这里继续用 UTC，JST 月末 00:00 附近的交易会被分到错误账期。
        return periodStart.atStartOfDay(ZoneId.of(properties.batch().zone())).toInstant();
    }

    private Instant periodEndExclusiveInstant(LocalDate periodEnd) {
        return periodEnd.plusDays(1).atStartOfDay(ZoneId.of(properties.batch().zone())).toInstant();
    }
}
