package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.statement.domain.StatementLine;

/**
 * Statement line 的低风险 read model。
 *
 * <p>关键词：账单明细快照, 交易快照, ledger reference,
 * statement line read model, query projection, 明細スナップショット(めいさいスナップショット)。</p>
 *
 * <p>它只承载查询响应需要的快照字段，不包含 domain state transition 方法，
 * 因此可以安全放入 Caffeine/Redis。</p>
 */
public record StatementLineReadModel(
        UUID id,
        UUID cardTransactionId,
        UUID ledgerEntryId,
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
                line.ledgerEntryId().orElse(null),
                line.networkTransactionId(),
                line.authorizationId(),
                line.cardId(),
                line.amount().amount(),
                line.amount().currency().getCurrencyCode(),
                line.postedAt()
        );
    }
}
