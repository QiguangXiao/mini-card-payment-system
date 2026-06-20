package com.minicard.statement.infrastructure.mybatis;

import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.statement.domain.Statement;
import com.minicard.statement.domain.StatementItem;
import com.minicard.statement.domain.StatementRepository;
import com.minicard.statement.domain.StatementStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * StatementRepository 的 MyBatis adapter。
 *
 * <p>它负责把 aggregate + statement items snapshot 持久化到两张表。
 * 账单生成顺序、锁和 minimum payment 规则仍留在 StatementService/domain。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisStatementRepository implements StatementRepository {

    private final StatementMapper mapper;

    @Override
    public boolean insert(Statement statement) {
        try {
            mapper.insertStatement(toRow(statement));
        } catch (DuplicateKeyException exception) {
            return false;
        }
        // 只有 statement 主表唯一键冲突才是 cycle-level idempotency。
        // statement_items 的唯一键冲突代表数据不一致，必须继续抛出并 rollback。
        for (StatementItem item : statement.items()) {
            mapper.insertItem(toRow(item));
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
        // Repayment 只推进 paidAmount/status，不允许重写 totalAmount 或 statement_items 快照。
        mapper.updatePayment(toRow(statement));
    }

    private Statement toDomainWithItems(StatementRow row) {
        List<StatementItem> items = mapper.findItemsByStatementId(row.id())
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
                items
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

    private StatementItemRow toRow(StatementItem item) {
        return new StatementItemRow(
                item.id().toString(),
                item.statementId().toString(),
                item.cardTransactionId().toString(),
                item.networkTransactionId(),
                item.authorizationId().toString(),
                item.cardId(),
                item.amount().amount(),
                item.amount().currency().getCurrencyCode(),
                item.postedAt(),
                item.createdAt()
        );
    }

    private StatementItem toDomain(StatementItemRow row) {
        return StatementItem.restore(
                UUID.fromString(row.id()),
                UUID.fromString(row.statementId()),
                UUID.fromString(row.cardTransactionId()),
                row.networkTransactionId(),
                UUID.fromString(row.authorizationId()),
                row.cardId(),
                new Money(row.amount(), Currency.getInstance(row.currency())),
                row.postedAt(),
                row.createdAt()
        );
    }
}
