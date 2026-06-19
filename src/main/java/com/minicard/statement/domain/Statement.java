package com.minicard.statement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.statement.domain.event.StatementClosedDomainEvent;
import com.minicard.statement.domain.event.StatementDomainEvent;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 信用卡账单 aggregate root。
 *
 * <p>Statement 表达一个 credit account 在固定 billing cycle 内的已入账消费快照。
 * 它不是实时余额视图：一旦生成，total/minimum payment/line items 就应该可审计、可解释。</p>
 */
@Getter
@Accessors(fluent = true)
public final class Statement {

    private final UUID id;
    private final UUID creditAccountId;
    private final LocalDate periodStart;
    private final LocalDate periodEnd;
    private final LocalDate dueDate;
    private final Money totalAmount;
    private final Money minimumPaymentAmount;
    private final Money paidAmount;
    private final int transactionCount;
    private StatementStatus status;
    private final Instant generatedAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<StatementItem> items;
    // Domain event buffer 只存在于内存中；restore 历史账单不会重新发布 statement.closed。
    private final List<StatementDomainEvent> domainEvents = new ArrayList<>();

    private Statement(
            UUID id,
            UUID creditAccountId,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            Money totalAmount,
            Money minimumPaymentAmount,
            Money paidAmount,
            int transactionCount,
            StatementStatus status,
            Instant generatedAt,
            Instant createdAt,
            Instant updatedAt,
            List<StatementItem> items
    ) {
        this.id = Objects.requireNonNull(id);
        this.creditAccountId = Objects.requireNonNull(creditAccountId);
        this.periodStart = Objects.requireNonNull(periodStart);
        this.periodEnd = Objects.requireNonNull(periodEnd);
        this.dueDate = Objects.requireNonNull(dueDate);
        this.totalAmount = Objects.requireNonNull(totalAmount);
        this.minimumPaymentAmount = Objects.requireNonNull(minimumPaymentAmount);
        this.paidAmount = Objects.requireNonNull(paidAmount);
        this.transactionCount = transactionCount;
        this.status = Objects.requireNonNull(status);
        this.generatedAt = Objects.requireNonNull(generatedAt);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
        this.items = List.copyOf(items);
        validateState();
    }

    public static Statement close(
            UUID creditAccountId,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            List<StatementTransaction> transactions,
            Money minimumPaymentAmount,
            Instant generatedAt
    ) {
        Objects.requireNonNull(transactions, "transactions must not be null");
        UUID statementId = UUID.randomUUID();
        List<StatementItem> snapshotItems = transactions.stream()
                .map(transaction -> StatementItem.snapshot(statementId, transaction, generatedAt))
                .toList();
        Money totalAmount = sum(snapshotItems);
        Currency currency = totalAmount.currency();
        Statement statement = new Statement(
                statementId,
                creditAccountId,
                periodStart,
                periodEnd,
                dueDate,
                totalAmount,
                minimumPaymentAmount,
                zero(currency),
                snapshotItems.size(),
                StatementStatus.CLOSED,
                generatedAt,
                generatedAt,
                generatedAt,
                snapshotItems
        );
        // CLOSED 是账单周期被固定的业务事实。事件由 aggregate 产生，
        // application service 只负责把它交给 Outbox，避免在 service 里反向拼业务事件。
        statement.domainEvents.add(new StatementClosedDomainEvent(
                statement.id,
                statement.creditAccountId,
                statement.periodStart,
                statement.periodEnd,
                statement.dueDate,
                statement.totalAmount,
                statement.minimumPaymentAmount,
                statement.transactionCount,
                statement.generatedAt
        ));
        return statement;
    }

    public static Statement restore(
            UUID id,
            UUID creditAccountId,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            Money totalAmount,
            Money minimumPaymentAmount,
            Money paidAmount,
            int transactionCount,
            StatementStatus status,
            Instant generatedAt,
            Instant createdAt,
            Instant updatedAt,
            List<StatementItem> items
    ) {
        return new Statement(
                id,
                creditAccountId,
                periodStart,
                periodEnd,
                dueDate,
                totalAmount,
                minimumPaymentAmount,
                paidAmount,
                transactionCount,
                status,
                generatedAt,
                createdAt,
                updatedAt,
                items
        );
    }

    public List<StatementDomainEvent> pullDomainEvents() {
        // Application service 在同一 transaction 内保存 aggregate 后调用这里。
        // 返回 copy 并清空，避免同一个 statement.closed 被重复 append 到 Outbox。
        List<StatementDomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    private void validateState() {
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("statement periodEnd must not be before periodStart");
        }
        if (!dueDate.isAfter(periodEnd)) {
            throw new IllegalArgumentException("statement dueDate must be after periodEnd");
        }
        if (!totalAmount.isPositive()) {
            throw new IllegalArgumentException("statement totalAmount must be positive");
        }
        if (!minimumPaymentAmount.isPositive()) {
            throw new IllegalArgumentException("minimumPaymentAmount must be positive");
        }
        if (minimumPaymentAmount.isGreaterThan(totalAmount)) {
            throw new IllegalArgumentException("minimumPaymentAmount cannot exceed totalAmount");
        }
        if (!totalAmount.currency().equals(paidAmount.currency())
                || !totalAmount.currency().equals(minimumPaymentAmount.currency())) {
            throw new IllegalArgumentException("statement money currencies differ");
        }
        if (transactionCount <= 0 || transactionCount != items.size()) {
            throw new IllegalArgumentException("statement transactionCount must match items");
        }
        Money itemTotal = sum(items);
        if (!itemTotal.equals(totalAmount)) {
            throw new IllegalArgumentException("statement totalAmount must match item total");
        }
        validateItemOwnership();
        validatePaymentState();
    }

    private void validateItemOwnership() {
        for (StatementItem item : items) {
            if (!item.statementId().equals(id)) {
                throw new IllegalArgumentException("statement item belongs to another statement");
            }
            if (!item.amount().currency().equals(totalAmount.currency())) {
                throw new IllegalArgumentException("statement item currency differs");
            }
            LocalDate postedDate = item.postedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
            if (postedDate.isBefore(periodStart) || postedDate.isAfter(periodEnd)) {
                throw new IllegalArgumentException("statement item postedAt is outside billing period");
            }
        }
    }

    private void validatePaymentState() {
        int paidComparison = paidAmount.amount().compareTo(totalAmount.amount());
        if (paidComparison > 0) {
            throw new IllegalArgumentException("paidAmount cannot exceed totalAmount");
        }
        boolean paidZero = paidAmount.amount().compareTo(BigDecimal.ZERO) == 0;
        boolean paidFull = paidComparison == 0;
        switch (status) {
            case CLOSED -> {
                if (!paidZero) {
                    throw new IllegalArgumentException("closed statement cannot have paidAmount");
                }
            }
            case PARTIALLY_PAID -> {
                if (paidZero || paidFull) {
                    throw new IllegalArgumentException("partially paid statement requires partial paidAmount");
                }
            }
            case PAID -> {
                if (!paidFull) {
                    throw new IllegalArgumentException("paid statement requires full paidAmount");
                }
            }
            case OVERDUE -> {
                if (paidFull) {
                    throw new IllegalArgumentException("overdue statement cannot be fully paid");
                }
            }
        }
    }

    private static Money sum(List<StatementItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("statement requires at least one posted transaction");
        }
        Currency currency = items.getFirst().amount().currency();
        Money total = zero(currency);
        for (StatementItem item : items) {
            total = total.add(item.amount());
        }
        return total;
    }

    private static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }
}
