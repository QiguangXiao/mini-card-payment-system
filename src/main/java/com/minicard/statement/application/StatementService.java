package com.minicard.statement.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementTransaction;
import com.minicard.statement.domain.event.StatementDomainEvent;
import com.minicard.transaction.domain.CardTransaction;
import com.minicard.transaction.domain.CardTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 账单生成 use case。
 *
 * <p>关键词：账单生成, 交易快照, Outbox 事件, statement generation,
 * transaction snapshot, billing cycle, 請求明細作成(せいきゅうめいさいさくせい),
 * 明細(めいさい)。</p>
 *
 * <p>面试重点：Statement generation 是批处理业务，但仍然需要清楚的 transaction boundary。
 * 同一事务里锁账户、锁待出账交易、创建 statement/item 快照、标记交易已归账、写 Outbox event。</p>
 */
@Service
@RequiredArgsConstructor
public class StatementService {

    private final StatementRepository statementRepository;
    private final CardTransactionRepository transactionRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final StatementDomainEventPublisher eventPublisher;
    private final StatementDueJobScheduler dueJobScheduler;
    private final StatementPolicyProperties policyProperties;
    private final Clock clock;

    @Transactional
    public Statement generate(GenerateStatementCommand command) {
        Instant now = Instant.now(clock);

        // 账户 row lock 是账单生成的并发门(concurrency gate)。
        // PostingService 也会先锁同一个 credit account，再创建/更新 CardTransaction，
        // 因此 statement 生成期间不会漏掉正在入账的交易，也不会和 posting 产生锁顺序死锁。
        CreditAccount account = creditAccountRepository
                .findByIdForUpdate(command.creditAccountId())
                .orElseThrow(() -> new NoSuchElementException(
                        "credit account not found: " + command.creditAccountId()
                ));

        // 一个 credit account 在同一 billing cycle 只能有一张账单。
        // 这个自然键就是 statement generation 的 idempotency key。
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
        List<CardTransaction> transactions = transactionRepository
                .findUnbilledPostedByCreditAccountForUpdate(
                        account.id(),
                        periodStartInstant(command.periodStart()),
                        periodEndExclusiveInstant(command.periodEnd())
                );
        if (transactions.isEmpty()) {
            // 当前阶段不生成 zero-activity statement，避免空账单把同一周期锁死。
            // 真实系统可按产品策略选择是否给无交易账户生成 0 元账单。
            throw new StatementGenerationRejectedException(
                    "no unbilled posted transactions in statement period"
            );
        }

        List<StatementTransaction> statementTransactions = transactions.stream()
                .map(this::toStatementTransaction)
                .toList();
        Money totalAmount = totalAmount(statementTransactions);
        Statement statement = Statement.close(
                account.id(),
                command.periodStart(),
                command.periodEnd(),
                command.dueDate(),
                statementTransactions,
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

        // CardTransaction 仍然是用户交易流水；这里仅写 statementId/billedAt，
        // 表达它已经被某一期 statement snapshot 收录，避免下次账单重复计入。
        transactions.forEach(transaction -> transaction.assignToStatement(statement.id(), now));
        transactionRepository.assignStatement(transactions);
        // 自动扣款计划是 future business action，使用 DelayJob 而不是 Outbox。
        // 它和 statement/items/transaction assignment 同事务提交，防止账单生成后漏掉 due-date 扣款。
        dueJobScheduler.scheduleAutoRepayment(statement);
        publishDomainEvents(statement);
        return statement;
    }

    private StatementTransaction toStatementTransaction(CardTransaction transaction) {
        return new StatementTransaction(
                transaction.id(),
                transaction.networkTransactionId(),
                transaction.authorizationId(),
                transaction.cardId(),
                transaction.amount(),
                transaction.postedAt()
        );
    }

    private Money totalAmount(List<StatementTransaction> transactions) {
        Currency currency = transactions.getFirst().amount().currency();
        Money total = new Money(BigDecimal.ZERO, currency);
        for (StatementTransaction transaction : transactions) {
            total = total.add(transaction.amount());
        }
        return total;
    }

    private Money minimumPayment(Money totalAmount) {
        BigDecimal floor = policyProperties.minimumPaymentFloors()
                .get(totalAmount.currency().getCurrencyCode());
        if (floor == null) {
            throw new StatementGenerationRejectedException(
                    "minimum payment floor is not configured for currency "
                            + totalAmount.currency().getCurrencyCode()
            );
        }
        BigDecimal percentageAmount = totalAmount.amount()
                .multiply(policyProperties.minimumPaymentRate())
                .setScale(2, RoundingMode.CEILING);
        BigDecimal minimum = percentageAmount.max(floor);
        if (minimum.compareTo(totalAmount.amount()) > 0) {
            minimum = totalAmount.amount();
        }
        return new Money(minimum, totalAmount.currency());
    }

    private Instant periodStartInstant(LocalDate periodStart) {
        // 当前教学项目统一按 UTC 日切账单。生产系统通常会把 billing timezone 存在账户配置中。
        return periodStart.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private Instant periodEndExclusiveInstant(LocalDate periodEnd) {
        return periodEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private void publishDomainEvents(Statement statement) {
        for (StatementDomainEvent event : statement.pullDomainEvents()) {
            // 仍在同一个 MySQL transaction boundary 内：statement/items/transaction assignment
            // 和 Outbox row 一起 commit，Kafka publish 交给后台 worker。
            eventPublisher.append(event);
        }
    }
}
