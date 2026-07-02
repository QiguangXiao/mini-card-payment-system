package com.minicard.ledger.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.shared.domain.Money;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 最小 Ledger aggregate root，记录一条内部账本分录。
 *
 * <p>关键词：账本分录, 聚合, 会计事实, ledger entry,
 * aggregate, accounting entry, 仕訳(しわけ)。</p>
 *
 * <p>interview重点：这里不是完整 double-entry general ledger。
 * 它先作为 learning projection，消费已经发生的 integration event，
 * 把“消费入账增加应收”和“还款入账减少应收”保存成 append-only 分录。</p>
 */
@Getter
@Accessors(fluent = true)
public class LedgerEntry {

    /** Ledger entry 主键；每条分录 append-only，不在原地修改金额或方向。 */
    private final UUID id;
    /** 来源 Kafka event id；ledger_entries 唯一键用它兜底防止重复记账。 */
    private final UUID sourceEventId;
    /** 分录业务类型，例如消费入账或还款入账。 */
    private final LedgerEntryType entryType;
    /** 借贷方向；金额保持正数，由 direction 表达 receivable 增减。 */
    private final LedgerDirection direction;
    /** 来源业务对象类型，例如 CARD_TRANSACTION 或 REPAYMENT。 */
    private final LedgerSourceType sourceType;
    /** 来源业务对象 id，用来从账本追回 card transaction/repayment。 */
    private final UUID sourceId;
    /** 所属信用账户 id；账本查询通常按 account 汇总。 */
    private final UUID creditAccountId;
    /** 分录金额；不使用负数金额，避免和 direction 表达重复。 */
    private final Money amount;
    /** 业务事实发生时间，例如 postedAt 或 receivedAt。 */
    private final Instant occurredAt;
    /** 分录写入 ledger_entries 的时间，可能晚于 occurredAt，因为消费是 eventual consistency。 */
    private final Instant createdAt;

    private LedgerEntry(
            UUID id,
            UUID sourceEventId,
            LedgerEntryType entryType,
            LedgerDirection direction,
            LedgerSourceType sourceType,
            UUID sourceId,
            UUID creditAccountId,
            Money amount,
            Instant occurredAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.sourceEventId = Objects.requireNonNull(sourceEventId);
        this.entryType = Objects.requireNonNull(entryType);
        this.direction = Objects.requireNonNull(direction);
        this.sourceType = Objects.requireNonNull(sourceType);
        this.sourceId = Objects.requireNonNull(sourceId);
        this.creditAccountId = Objects.requireNonNull(creditAccountId);
        this.amount = Objects.requireNonNull(amount);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("ledger entry amount must be positive");
        }
        this.occurredAt = Objects.requireNonNull(occurredAt);
        this.createdAt = Objects.requireNonNull(createdAt);
    }

    /**
     * 根据 card_transaction.posted 记录消费入账分录。
     *
     * <p>这里生成 ledger entry id；sourceEventId 保留 Kafka event id，作为 consumer-side
     * idempotency 的业务侧防线。消费入账让 issuer 对持卡人的 receivable 增加，所以记 DEBIT。</p>
     */
    public static LedgerEntry recordPurchasePosted(
            UUID sourceEventId,
            UUID cardTransactionId,
            UUID creditAccountId,
            Money amount,
            Instant postedAt,
            Instant createdAt
    ) {
        return new LedgerEntry(
                UUID.randomUUID(),
                sourceEventId,
                LedgerEntryType.CARD_TRANSACTION_POSTED,
                LedgerDirection.DEBIT,
                LedgerSourceType.CARD_TRANSACTION,
                cardTransactionId,
                creditAccountId,
                amount,
                postedAt,
                createdAt
        );
    }

    /**
     * 根据 repayment.received 记录还款入账分录。
     *
     * <p>还款会减少 issuer 对持卡人的 receivable，所以记 CREDIT。金额仍然保持正数，
     * 方向由 direction 字段表达，避免用负数金额混淆 domain invariant。</p>
     */
    public static LedgerEntry recordRepaymentReceived(
            UUID sourceEventId,
            UUID repaymentId,
            UUID creditAccountId,
            Money amount,
            Instant receivedAt,
            Instant createdAt
    ) {
        return new LedgerEntry(
                UUID.randomUUID(),
                sourceEventId,
                LedgerEntryType.REPAYMENT_RECEIVED,
                LedgerDirection.CREDIT,
                LedgerSourceType.REPAYMENT,
                repaymentId,
                creditAccountId,
                amount,
                receivedAt,
                createdAt
        );
    }
}
