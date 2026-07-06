package com.minicard.statement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.minicard.shared.domain.Money;

/**
 * Statement line，是账单生成时对 posted CardTransaction + purchase LedgerEntry 的历史快照。
 *
 * <p>关键词：账单明细, 交易快照, 账本引用, statement line,
 * transaction snapshot, ledger entry reference, 請求明細(せいきゅうめいさい),
 * 仕訳参照(しわけさんしょう)。</p>
 *
 * <p>interview重点：用户看到的明细来自 CardTransaction；正式入账依据来自 LedgerEntry。
 * line 同时保存两个 id，既能解释用户账单，又能追到内部账务事实。</p>
 */
public final class StatementLine {

    /** Statement line 主键；代表账单上的一条历史明细快照。 */
    private final UUID id;
    /** 所属 statement id。 */
    private final UUID statementId;
    /** 来源 card transaction id；用于从账单明细追回用户可见交易。 */
    private final UUID cardTransactionId;
    /** 来源 ledger entry id；用于从账单明细追回内部账务事实。 */
    private final UUID ledgerEntryId;
    /** 外部网络交易 id；方便和 presentment/清算侧排查同一笔交易。 */
    private final String networkTransactionId;
    /** 原始 authorization id；串起授权预占和最终入账。 */
    private final UUID authorizationId;
    /** 发生消费的卡 id；用于用户侧展示和排查。 */
    private final String cardId;
    /** 明细金额；是出账时的快照，不随后续交易对象变化。 */
    private final Money amount;
    /** 交易入账时间；账单按 posted transaction 归集。 */
    private final Instant postedAt;
    /** 明细快照创建时间，通常等于 statement generatedAt。 */
    private final Instant createdAt;

    private StatementLine(
            UUID id,
            UUID statementId,
            UUID cardTransactionId,
            UUID ledgerEntryId,
            String networkTransactionId,
            UUID authorizationId,
            String cardId,
            Money amount,
            Instant postedAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.statementId = Objects.requireNonNull(statementId);
        this.cardTransactionId = Objects.requireNonNull(cardTransactionId);
        // 老本地数据可能没有 ledger_entry_id；新生成的 line 必须通过 snapshot(...) 带上 ledgerEntryId。
        this.ledgerEntryId = ledgerEntryId;
        this.networkTransactionId = requireText(networkTransactionId, "networkTransactionId");
        this.authorizationId = Objects.requireNonNull(authorizationId);
        this.cardId = requireText(cardId, "cardId");
        this.amount = Objects.requireNonNull(amount);
        this.postedAt = Objects.requireNonNull(postedAt);
        this.createdAt = Objects.requireNonNull(createdAt);
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("statement line amount must be positive");
        }
    }

    public static StatementLine snapshot(
            UUID statementId,
            StatementLineSource source,
            Instant createdAt
    ) {
        // line id 是账单快照行自己的身份；cardTransactionId/ledgerEntryId 是来源事实的身份。
        // 如果复用来源 id，就会把“交易生命周期”和“账单快照生命周期”混在一起。
        // 两个工厂的宽严差异：snapshot（新出账）必须带 ledger 链路，restore（DB 还原）
        // 容忍历史数据的 null。StatementLineSource 构造器已经校验过非空，这里再显式声明一次，
        // 是把"新 line 必须有 ledger entry"固定在工厂契约上——将来即使 source 放宽，本工厂也不放宽。
        return restore(
                UUID.randomUUID(),
                statementId,
                source.cardTransactionId(),
                Objects.requireNonNull(
                        source.ledgerEntryId(),
                        "new statement line requires a ledger entry id"
                ),
                source.networkTransactionId(),
                source.authorizationId(),
                source.cardId(),
                source.amount(),
                source.postedAt(),
                createdAt
        );
    }

    public static StatementLine restore(
            UUID id,
            UUID statementId,
            UUID cardTransactionId,
            UUID ledgerEntryId,
            String networkTransactionId,
            UUID authorizationId,
            String cardId,
            Money amount,
            Instant postedAt,
            Instant createdAt
    ) {
        return new StatementLine(
                id,
                statementId,
                cardTransactionId,
                ledgerEntryId,
                networkTransactionId,
                authorizationId,
                cardId,
                amount,
                postedAt,
                createdAt
        );
    }

    public Optional<UUID> ledgerEntryId() {
        return Optional.ofNullable(ledgerEntryId);
    }

    public UUID id() {
        return id;
    }

    public UUID statementId() {
        return statementId;
    }

    public UUID cardTransactionId() {
        return cardTransactionId;
    }

    public String networkTransactionId() {
        return networkTransactionId;
    }

    public UUID authorizationId() {
        return authorizationId;
    }

    public String cardId() {
        return cardId;
    }

    public Money amount() {
        return amount;
    }

    public Instant postedAt() {
        return postedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
