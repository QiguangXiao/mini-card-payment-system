package com.minicard.statement.application.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.statement.domain.StatementLine;

/**
 * Statement line 查询快照。
 *
 * <p>关键词：账单明细缓存, read model, transaction snapshot,
 * statement line read model, 請求明細(せいきゅうめいさい)。</p>
 */
public record StatementLineReadModel(
        UUID id,
        UUID cardTransactionId,
        String networkTransactionId,
        UUID authorizationId,
        String cardId,
        BigDecimal amount,
        String currency,
        Instant postedAt
) {

    public static StatementLineReadModel from(StatementLine line) {
        return new StatementLineReadModel(
                line.id(),
                line.cardTransactionId(),
                line.networkTransactionId(),
                line.authorizationId(),
                line.cardId(),
                line.amount().amount(),
                line.amount().currency().getCurrencyCode(),
                line.postedAt()
        );
    }
}
