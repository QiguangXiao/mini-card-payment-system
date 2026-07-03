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

import com.minicard.shared.domain.Money;
import com.minicard.creditaccount.domain.CreditAccount;
import com.minicard.creditaccount.domain.CreditAccountRepository;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementLineSource;
import com.minicard.statement.domain.event.StatementDomainEvent;
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
    private final StatementDomainEventPublisher eventPublisher;
    private final StatementProperties properties;
    private final Clock clock;

    /**
     * 为一个 credit account 和 billing cycle 生成 statement，或返回已存在的幂等结果。
     */
    @Transactional
    public Statement generate(GenerateStatementCommand command) {
        Instant now = Instant.now(clock);

        // 阶段 1：锁 credit account，作为账单生成和 posting 的共同并发门。
        // 账户 row lock 是账单生成的并发门(concurrency gate)。
        // PostingService 也会先锁同一个 credit account，再创建/更新 CardTransaction，
        // 因此 statement 生成期间不会漏掉正在入账的交易，也不会和 posting 产生锁顺序死锁。
        // 如果不先锁 account，账单扫描和 posting 可能交错，导致一笔已入账交易漏进下一期或重复归账。
        CreditAccount account = creditAccountRepository
                .findByIdForUpdate(command.creditAccountId())
                .orElseThrow(() -> new NoSuchElementException(
                        "credit account not found: " + command.creditAccountId()
                ));

        // 阶段 2：用 (creditAccountId, periodStart, periodEnd) 做账单幂等查询。
        // 命中已有账单就直接返回，不再重复扫描交易或插入 statement_lines。
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

    /**
     * 查询单张 statement，不改变账单状态。
     */
    @Transactional(readOnly = true)
    public Statement get(UUID id) {
        return statementRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("statement not found: " + id));
    }

    /**
     * 创建新 statement、冻结明细快照、标记交易已出账，并安排自动扣款和通知事件。
     *
     * <p>事务归属：只由 {@link #generate(GenerateStatementCommand)} 调用，加入同一个
     * {@code @Transactional} 边界；statement、line、BILLED 标记、DelayJob 和 Outbox
     * 必须一起 commit/rollback。</p>
     */
    private Statement createStatement(
            GenerateStatementCommand command,
            CreditAccount account,
            Instant now
    ) {
        // 创建阶段 1：先检查 posted transaction 是否已具备 ledger entry。
        // Statement line 需要 ledger_entry_id 做审计链路；缺 projection 时应 retry，而不是生成不完整账单。
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

        // 创建阶段 2：锁定本期可出账交易快照。
        // FOR UPDATE 防止这些交易在本事务完成前被另一轮 statement job 同时归账。
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

        // 创建阶段 3：计算账单金额，构造 CLOSED statement 和不可变 statement lines。
        // lineSources 是本事务锁定后的快照；Statement.close 会把这些来源转成账单行。
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

        // 创建阶段 4：插入 statements/statement_lines，并用自然唯一键兜底幂等。
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

        // 创建阶段 5：把本期交易标记为 BILLED，防止后续批次再次生成相同 statement line。
        // CardTransaction 是用户可见消费明细；StatementLine 是账单快照。
        // 同一 transaction boundary 内把交易标记为 BILLED，防止下一轮 job 重复生成 line。
        billingRepository.markCardTransactionsBilled(statement.id(), lineSources, now);
        // 创建阶段 6：创建到期自动还款 DelayJob，与账单同事务提交。
        // 自动扣款计划是 future business action，使用 DelayJob 而不是 Outbox。
        // 它和 statement/items/transaction assignment 同事务提交，防止账单生成后漏掉 due-date 扣款。
        // 如果这一步不和 statement 同事务提交，就可能出现账单已生成但没有任何到期扣款计划。
        dueJobScheduler.scheduleAutoRepayment(statement);
        // 创建阶段 7：追加 statement.closed Outbox event。Kafka publish 仍由后台 Outbox worker 完成。
        // statement.closed 的 Outbox row 和账单/明细/BILLED 标记/扣款计划在同一事务内提交，
        // 避免“账单已生成但通知消息丢失”或“消息已发但账单回滚”的不一致。Kafka 发布由后台 worker 处理，
        // 所以账单生成批处理不等待 broker。只有这条新插入成功的路径才发事件；幂等命中已有账单的分支不会到这里。
        publishDomainEvents(statement);
        return statement;
    }

    /**
     * 把 statement.closed 领域事件追加到 Outbox。
     *
     * <p>事务归属：只由 {@link #createStatement(GenerateStatementCommand, CreditAccount, Instant)}
     * 调用，因此属于 {@link #generate(GenerateStatementCommand)} 的写事务；幂等命中已有账单时不会调用。</p>
     */
    private void publishDomainEvents(Statement statement) {
        for (StatementDomainEvent event : statement.pullDomainEvents()) {
            eventPublisher.append(event);
        }
    }

    /**
     * 汇总本期待出账交易金额，作为 statement totalAmount。
     *
     * <p>事务归属：纯计算方法；当前由 {@link #createStatement(GenerateStatementCommand,
     * CreditAccount, Instant)} 在账单生成事务中调用，但不依赖事务能力。</p>
     */
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

    /**
     * 按配置计算最低还款额，并限制不超过本期总额。
     *
     * <p>事务归属：纯计算方法；当前由 {@link #createStatement(GenerateStatementCommand,
     * CreditAccount, Instant)} 在账单生成事务中调用。</p>
     */
    private Money minimumPayment(Money totalAmount) {
        BigDecimal floor = properties.policy().minimumPaymentFloors()
                .get(totalAmount.currency().getCurrencyCode());
        if (floor == null) {
            throw StatementGenerationException.rejected(
                    "minimum payment floor is not configured for currency "
                            + totalAmount.currency().getCurrencyCode()
            );
        }
        // CEILING 对最低还款更保守：向上取整到本币种最小单位（JPY 到 1 円、USD 到 0.01），避免少收最低还款。
        // 如果使用 HALF_UP，某些边界金额会被舍入到更低的 minimum payment。
        // 取整 scale 由 Money.multiply 按 currency 决定，这里不再硬编码 2 位——否则对 JPY 会算出“分以下日元”。
        Money percentage = totalAmount.multiply(properties.policy().minimumPaymentRate(), RoundingMode.CEILING);
        Money minimum = percentage.max(new Money(floor, totalAmount.currency()));
        return minimum.isGreaterThan(totalAmount) ? totalAmount : minimum;
    }

    /**
     * 把账期开始日转换为 billing timezone 下的闭区间起点。
     *
     * <p>事务归属：纯时间转换方法，不依赖事务；当前由
     * {@link #createStatement(GenerateStatementCommand, CreditAccount, Instant)} 调用。</p>
     */
    private Instant periodStartInstant(LocalDate periodStart) {
        // 账单日切必须用 billing timezone。当前项目使用全局 Asia/Tokyo；
        // 如果这里继续用 UTC，JST 月末 00:00 附近的交易会被分到错误账期。
        return periodStart.atStartOfDay(ZoneId.of(properties.batch().zone())).toInstant();
    }

    /**
     * 把账期结束日转换为 billing timezone 下的开区间终点。
     *
     * <p>事务归属：纯时间转换方法，不依赖事务；当前由
     * {@link #createStatement(GenerateStatementCommand, CreditAccount, Instant)} 调用。</p>
     */
    private Instant periodEndExclusiveInstant(LocalDate periodEnd) {
        return periodEnd.plusDays(1).atStartOfDay(ZoneId.of(properties.batch().zone())).toInstant();
    }
}
