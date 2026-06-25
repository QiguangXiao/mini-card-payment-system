package com.minicard.statement.infrastructure.mybatis;

import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementLine;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * StatementRepository 的 MyBatis adapter。
 *
 * <p>它负责把 aggregate + statement items snapshot 持久化到两张表。
 * 账单生成顺序、锁和 minimum payment 规则仍留在 StatementGenerationService/domain。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisStatementRepository implements StatementRepository {

    private final StatementMapper mapper;

    @Override
    public boolean insert(Statement statement) {
        try {
            // 只把 statement 主表的 cycle unique conflict 转成 false。
            // 这样 StatementGenerationService 可以把“同周期已出账”当成幂等结果处理。
            mapper.insertStatement(toRow(statement));
        } catch (DuplicateKeyException exception) {
            // 只在 statement header insert 阶段把 duplicate key 当成幂等命中。
            // 后续 line insert 的 duplicate key 代表数据不一致，必须继续抛出并 rollback。
            return false;
        }
        // 只有 statement 主表唯一键冲突才是 cycle-level idempotency。
        // statement_lines 的唯一键冲突代表数据不一致，必须继续抛出并 rollback。
        for (StatementLine line : statement.items()) {
            // line 插入失败不吞掉：主表已插入但 line 冲突说明数据不一致，应 rollback 整个 transaction。
            mapper.insertItem(toRow(line));
        }
        return true;
    }

    @Override
    public Optional<Statement> findByCycleForUpdate(
            UUID creditAccountId,
            java.time.LocalDate periodStart,
            java.time.LocalDate periodEnd
    ) {
        return Optional.ofNullable(mapper.findByCycleForUpdate(
                        creditAccountId.toString(),
                        periodStart,
                        periodEnd
                ))
                .map(this::toDomainWithItems);
    }

    @Override
    public Optional<Statement> findById(UUID id) {
        return Optional.ofNullable(mapper.findById(id.toString()))
                .map(this::toDomainWithItems);
    }

    @Override
    public Optional<Statement> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(mapper.findByIdForUpdate(id.toString()))
                .map(this::toDomainWithItems);
    }

    @Override
    public void updatePayment(Statement statement) {
        // Repayment 只推进 paidAmount/status，不允许重写 totalAmount 或 statement_lines 快照。
        mapper.updatePayment(toRow(statement));
    }

    private Statement toDomainWithItems(StatementRow row) {
        List<StatementLine> lines = mapper.findItemsByStatementId(row.id())
                .stream()
                .map(this::toDomain)
                .toList();
        Currency currency = Currency.getInstance(row.currency());
        return Statement.restore(
                UUID.fromString(row.id()),
                UUID.fromString(row.creditAccountId()),
                row.periodStart(),
                row.periodEnd(),
                row.dueDate(),
                new Money(row.totalAmount(), currency),
                new Money(row.minimumPaymentAmount(), currency),
                new Money(row.paidAmount(), currency),
                row.transactionCount(),
                StatementStatus.valueOf(row.status()),
                row.generatedAt(),
                row.createdAt(),
                row.updatedAt(),
                lines
        );
    }

    private StatementRow toRow(Statement statement) {
        return new StatementRow(
                statement.id().toString(),
                statement.creditAccountId().toString(),
                statement.periodStart(),
                statement.periodEnd(),
                statement.dueDate(),
                statement.totalAmount().amount(),
                statement.minimumPaymentAmount().amount(),
                statement.paidAmount().amount(),
                statement.totalAmount().currency().getCurrencyCode(),
                statement.transactionCount(),
                statement.status().name(),
                statement.generatedAt(),
                statement.createdAt(),
                statement.updatedAt()
        );
    }

    private StatementLineRow toRow(StatementLine line) {
        return new StatementLineRow(
                line.id().toString(),
                line.statementId().toString(),
                line.cardTransactionId().toString(),
                line.ledgerEntryId().map(UUID::toString).orElse(null),
                line.networkTransactionId(),
                line.authorizationId().toString(),
                line.cardId(),
                line.amount().amount(),
                line.amount().currency().getCurrencyCode(),
                line.postedAt(),
                line.createdAt()
        );
    }

    private StatementLine toDomain(StatementLineRow row) {
        return StatementLine.restore(
                UUID.fromString(row.id()),
                UUID.fromString(row.statementId()),
                UUID.fromString(row.cardTransactionId()),
                optionalUuid(row.ledgerEntryId()),
                row.networkTransactionId(),
                UUID.fromString(row.authorizationId()),
                row.cardId(),
                new Money(row.amount(), Currency.getInstance(row.currency())),
                row.postedAt(),
                row.createdAt()
        );
    }

    private UUID optionalUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
