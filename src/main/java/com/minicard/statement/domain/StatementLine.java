package com.minicard.statement.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.minicard.shared.domain.Money;

/**
 * Statement line，是账单生成时对 posted CardTransaction 的历史快照。
 *
 * <p>关键词：账单明细, 交易快照, statement line,
 * transaction snapshot, 請求明細(せいきゅうめいさい)。</p>
 *
 * <p>interview重点：用户看到的明细来自 CardTransaction；line 保存交易 id 和出账时金额、币种、
 * postedAt 快照，后续交易对象变化也不会改写历史账单。生产系统可以另外关联 accounting journal，
 * 但本项目不实现不完整的 Ledger projection。</p>
 */
public final class StatementLine {

    /** Statement line 主键；代表账单上的一条历史明细快照。 */
    private final UUID id;
    /** 所属 statement id。 */
    private final UUID statementId;
    /** 来源 card transaction id；用于从账单明细追回用户可见交易。 */
    private final UUID cardTransactionId;
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
        // line id 是账单快照行自己的身份；cardTransactionId 是来源交易事实的身份。
        // 如果复用来源 id，就会把“交易生命周期”和“账单快照生命周期”混在一起。
        return restore(
                UUID.randomUUID(),
                statementId,
                source.cardTransactionId(),
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
                networkTransactionId,
                authorizationId,
                cardId,
                amount,
                postedAt,
                createdAt
        );
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
