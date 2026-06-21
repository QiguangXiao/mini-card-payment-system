package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.statement.domain.StatementItem;

/**
 * Statement item 的低风险 read model。
 *
 * <p>关键词：账单明细快照, 交易快照, statement item read model,
 * line item snapshot, query projection, 明細スナップショット(めいさいスナップショット),
 * 取引明細(とりひきめいさい)。</p>
 *
 * <p>它只承载查询响应需要的快照字段，不包含 domain state transition 方法，
 * 因此可以安全放入 Caffeine/Redis。</p>
 */
public record StatementItemReadModel(
        UUID id,
        UUID cardTransactionId,
        String networkTransactionId,
        UUID authorizationId,
        String cardId,
        BigDecimal amount,
        String currency,
        Instant postedAt
) {

    public static StatementItemReadModel from(StatementItem item) {
        return new StatementItemReadModel(
                item.id(),
                item.cardTransactionId(),
                item.networkTransactionId(),
                item.authorizationId(),
                item.cardId(),
                item.amount().amount(),
                item.amount().currency().getCurrencyCode(),
                item.postedAt()
        );
    }
}
