package com.minicard.statement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import com.minicard.statement.domain.event.StatementClosedDomainEvent;
import com.minicard.statement.domain.event.StatementDomainEvent;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 信用卡账单 aggregate root。
 *
 * <p>关键词：账单聚合, billing cycle, 明细快照, statement aggregate,
 * due date, payment status, 請求集約(せいきゅうしゅうやく),
 * 支払状態(しはらいじょうたい)。</p>
 *
 * <p>Statement 表达一个 credit account 在固定 billing cycle 内的已入账消费快照。
 * 它不是实时余额视图：一旦生成，total/minimum payment/line items 就应该可审计、可解释。</p>
 */
@Getter
@Accessors(fluent = true)
public final class Statement {

    private static final ZoneId JAPAN_BILLING_ZONE = ZoneId.of("Asia/Tokyo");

    private final UUID id;
    private final UUID creditAccountId;
    private final LocalDate periodStart;
    private final LocalDate periodEnd;
    private final LocalDate dueDate;
    private final Money totalAmount;
    private final Money minimumPaymentAmount;
    private Money paidAmount;
    private final int transactionCount;
    private StatementStatus status;
    private final Instant generatedAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<StatementLine> lines;
    // Domain event buffer 只存在于内存中：只有 close() 新生成的账单会记录 statement.closed，
    // restore() 重建历史账单不会重新发布事件，避免 reload 触发重复通知。
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
            List<StatementLine> lines
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
        this.lines = List.copyOf(lines);
        validateState();
    }

    public static Statement close(
            UUID creditAccountId,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueDate,
            List<StatementLineSource> lineSources,
            Money minimumPaymentAmount,
            Instant generatedAt
    ) {
        Objects.requireNonNull(lineSources, "lineSources must not be null");
        UUID statementId = UUID.randomUUID();
        List<StatementLine> snapshotLines = lineSources.stream()
                .map(source -> StatementLine.snapshot(statementId, source, generatedAt))
                .toList();
        Money totalAmount = sum(snapshotLines);
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
                snapshotLines.size(),
                StatementStatus.CLOSED,
                generatedAt,
                generatedAt,
                generatedAt,
                snapshotLines
        );
        // 在 close() 而不是 service 里记录事件：账单“已关账”是 Statement 自己的业务事实，
        // 字段（total/minimum/dueDate）也都在这层语境内确定。StatementGenerationService 只负责在
        // 真正 INSERT 成功后 pull 并 append 到 Outbox，幂等返回已有账单的路径不会重复发事件。
        statement.domainEvents.add(new StatementClosedDomainEvent(
                statementId,
                creditAccountId,
                periodStart,
                periodEnd,
                dueDate,
                totalAmount,
                minimumPaymentAmount,
                snapshotLines.size(),
                generatedAt
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
            List<StatementLine> lines
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
                lines
        );
    }

    public List<StatementLine> items() {
        // API 暂时仍叫 items，但 domain 内部已经是 statement line。
        // 保留这个 alias 可以降低 controller/read model 的无关 churn。
        return lines;
    }

    public Money remainingAmount() {
        // remaining amount 是账单还款阶段的派生值，不单独落库，避免 total/paid/remaining 三者漂移。
        return totalAmount.subtract(paidAmount);
    }

    public List<StatementDomainEvent> pullDomainEvents() {
        // Application service 在同一 transaction 内保存账单后调用这里。
        // 返回 copy 并清空，避免同一张账单的 statement.closed 被重复 append 到 Outbox。
        List<StatementDomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    public void applyRepayment(Money amount, Instant paidAt) {
        Objects.requireNonNull(amount);
        Instant actualPaidAt = Objects.requireNonNull(paidAt);
        // Statement 的职责是保护账单级还款状态，不直接改 credit account postedBalance。
        // RepaymentService 会在同一个 transaction boundary 内同时更新 statement 和 account。
        if (!totalAmount.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("repayment currency must match statement currency");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("repayment amount must be positive");
        }
        if (status == StatementStatus.PAID) {
            throw new IllegalStateException("paid statement cannot accept repayment");
        }
        if (amount.isGreaterThan(remainingAmount())) {
            throw new IllegalStateException("repayment amount exceeds statement remaining amount");
        }

        paidAmount = paidAmount.add(amount);
        updatedAt = actualPaidAt;
        if (paidAmount.equals(totalAmount)) {
            status = StatementStatus.PAID;
            return;
        }
        // OVERDUE 账单部分还款后仍然逾期；CLOSED 账单部分还款才进入 PARTIALLY_PAID。
        if (status != StatementStatus.OVERDUE) {
            status = StatementStatus.PARTIALLY_PAID;
        }
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
        if (transactionCount <= 0 || transactionCount != lines.size()) {
            throw new IllegalArgumentException("statement transactionCount must match lines");
        }
        Money itemTotal = sum(lines);
        if (!itemTotal.equals(totalAmount)) {
            throw new IllegalArgumentException("statement totalAmount must match item total");
        }
        validateItemOwnership();
        validatePaymentState();
    }

    private void validateItemOwnership() {
        for (StatementLine line : lines) {
            if (!line.statementId().equals(id)) {
                throw new IllegalArgumentException("statement line belongs to another statement");
            }
            if (!line.amount().currency().equals(totalAmount.currency())) {
                throw new IllegalArgumentException("statement line currency differs");
            }
            // 本项目固定按日本发卡业务的 JST billing day 切账期，而不是按 UTC 日期。
            // 如果这里用 UTC，JST 月初 00:30 的交易会被误判成上一天，导致合法账单明细被拒绝。
            LocalDate postedDate = line.postedAt().atZone(JAPAN_BILLING_ZONE).toLocalDate();
            if (postedDate.isBefore(periodStart) || postedDate.isAfter(periodEnd)) {
                throw new IllegalArgumentException("statement line postedAt is outside billing period");
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

    private static Money sum(List<StatementLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("statement requires at least one posted transaction");
        }
        Currency currency = lines.getFirst().amount().currency();
        Money total = zero(currency);
        for (StatementLine line : lines) {
            total = total.add(line.amount());
        }
        return total;
    }

    private static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }
}
