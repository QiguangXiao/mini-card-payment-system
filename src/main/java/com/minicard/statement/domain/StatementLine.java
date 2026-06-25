package com.minicard.statement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.minicard.authorization.domain.Money;

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

    private final UUID id;
    private final UUID statementId;
    private final UUID cardTransactionId;
    private final UUID ledgerEntryId;
    private final String networkTransactionId;
    private final UUID authorizationId;
    private final String cardId;
    private final Money amount;
    private final Instant postedAt;
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
        return restore(
                UUID.randomUUID(),
                statementId,
                source.cardTransactionId(),
                source.ledgerEntryId(),
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
