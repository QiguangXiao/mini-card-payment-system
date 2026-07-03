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
 *
 * <p>状态转换表（方法 / 推动方）：</p>
 * <pre>
 * (无)            -&gt; CLOSED          close()           异步 batch：StatementJobHandler 出账，生成即 CLOSED
 * CLOSED          -&gt; PARTIALLY_PAID  applyRepayment()  部分还款
 * CLOSED          -&gt; PAID            applyRepayment()  一次性全额结清（终态，PAID 后拒绝再收款）
 * PARTIALLY_PAID  -&gt; PAID            applyRepayment()  补足剩余金额（终态）
 * OVERDUE         -&gt; PAID            applyRepayment()  逾期后全额结清；部分还款保持 OVERDUE 不回 PARTIALLY_PAID
 * </pre>
 *
 * <p>划分逻辑：CLOSED 由 billing-cycle batch（StatementJob）推动，没有 API 能出账；
 * 还款转换由两条入口共同推动——手动还款 API（RepaymentController）和 due date 自动扣款
 * DelayJob（AutoRepaymentDelayJobHandler），二者最终都走 RepaymentService 同一事务。
 * OVERDUE 预留给未来的 due-date 逾期扫描：当前没有任何代码路径写入 OVERDUE，
 * restore()/applyRepayment() 只是提前兼容这个状态。</p>
 */
@Getter
@Accessors(fluent = true)
public final class Statement {

    private static final ZoneId JAPAN_BILLING_ZONE = ZoneId.of("Asia/Tokyo");

    /** Statement 主键；代表一个 credit account 在一个 billing cycle 的账单快照。 */
    private final UUID id;
    /** 账单所属信用账户 id。 */
    private final UUID creditAccountId;
    /** 账单周期开始日，闭区间起点。 */
    private final LocalDate periodStart;
    /** 账单周期结束日，闭区间终点。 */
    private final LocalDate periodEnd;
    /** 到期还款日，按日本营业日规则从 periodEnd 推导。 */
    private final LocalDate dueDate;
    /** 本期应还总额，由账单明细快照求和得到。 */
    private final Money totalAmount;
    /** 最低还款额；当前策略由 application service 计算后传入。 */
    private final Money minimumPaymentAmount;
    /** 已还金额；还款入账时增加，用于判断 PAID/PARTIALLY_PAID。 */
    private Money paidAmount;
    /** 出账时纳入的交易笔数，和 lines.size() 对齐但单独落库方便查询。 */
    private final int transactionCount;
    /** 账单状态，例如 CLOSED、PARTIALLY_PAID、PAID。 */
    private StatementStatus status;
    /** 读模型/cache version；还款更新账单后递增，用于避免 stale cache 覆盖新状态。 */
    private long version;
    /** 账单生成时间；代表快照切面的业务时间。 */
    private final Instant generatedAt;
    /** statement row 创建时间。 */
    private final Instant createdAt;
    /** 最近一次还款或状态变化时间。 */
    private Instant updatedAt;
    /** 账单明细快照；创建后整体不可变，避免后续交易变化污染历史账单。 */
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
            long version,
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
        this.version = version;
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
                0L,
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
            long version,
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
                version,
                generatedAt,
                createdAt,
                updatedAt,
                lines
        );
    }

    /**
     * 返回账单明细快照；保留 items 命名是为了兼容 API/DTO 的既有表达。
     */
    public List<StatementLine> items() {
        // API 暂时仍叫 items，但 domain 内部已经是 statement line。
        // 保留这个 alias 可以降低 controller/read model 的无关 churn。
        return lines;
    }

    /**
     * 计算账单剩余应还金额，用于还款校验和展示读模型。
     */
    public Money remainingAmount() {
        // remaining amount 是账单还款阶段的派生值，不单独落库，避免 total/paid/remaining 三者漂移。
        return totalAmount.subtract(paidAmount);
    }

    /**
     * 取出并清空账单生成时产生的领域事件，交给 application service 写入 Outbox。
     */
    public List<StatementDomainEvent> pullDomainEvents() {
        // Application service 在同一 transaction 内保存账单后调用这里。
        // 返回 copy 并清空，避免同一张账单的 statement.closed 被重复 append 到 Outbox。
        List<StatementDomainEvent> events = List.copyOf(domainEvents);
        domainEvents.clear();
        return events;
    }

    /**
     * 将还款应用到 statement，推进 PAID/PARTIALLY_PAID 等账单支付状态。
     */
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
            bumpVersion();
            return;
        }
        // OVERDUE 账单部分还款后仍然逾期；CLOSED 账单部分还款才进入 PARTIALLY_PAID。
        if (status != StatementStatus.OVERDUE) {
            status = StatementStatus.PARTIALLY_PAID;
        }
        bumpVersion();
    }

    /**
     * 推进 statement read model 版本，帮助 Redis/local cache 区分新旧账单状态。
     */
    private void bumpVersion() {
        // version 是 statement read model 的正式单调版本，供 Redis L2 的 CAS/tombstone 使用。
        // 如果用 paidAmount 代替 version，将来状态/到期日/展示字段变化但金额不变时，迟到写会被误判为同版本。
        version++;
    }

    /**
     * 校验账单周期、金额、明细和支付状态是否互相一致。
     */
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
        if (version < 0) {
            throw new IllegalArgumentException("statement version must not be negative");
        }
        Money itemTotal = sum(lines);
        if (!itemTotal.equals(totalAmount)) {
            throw new IllegalArgumentException("statement totalAmount must match item total");
        }
        validateItemOwnership();
        validatePaymentState();
    }

    /**
     * 校验每条 statement line 是否属于本账单且落在本 billing cycle 内。
     */
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

    /**
     * 校验 paidAmount 与 StatementStatus 的组合，避免 PAID/CLOSED 等状态和金额冲突。
     */
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

    /**
     * 汇总账单明细金额，生成 statement totalAmount 的唯一来源。
     */
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
