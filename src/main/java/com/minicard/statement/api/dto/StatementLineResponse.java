package com.minicard.statement.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.statement.domain.StatementLine;

/**
 * Statement line API response DTO。
 *
 * <p>关键词：账单明细响应, 交易快照, ledger reference, statement line response,
 * transaction snapshot, 請求明細レスポンス(せいきゅうめいさいレスポンス)。</p>
 */
public record StatementLineResponse(
        /** statement line id。 */
        UUID id,
        /** 对应 card transaction id。 */
        UUID cardTransactionId,
        /** 对应 append-only ledger entry id；老数据可能为空，新生成账单应有值。 */
        UUID ledgerEntryId,
        /** 外部网络交易 id。 */
        String networkTransactionId,
        /** 原 authorization id。 */
        UUID authorizationId,
        /** 卡号业务 id。 */
        String cardId,
        /** 明细金额。 */
        BigDecimal amount,
        /** 货币代码。 */
        String currency,
        /** 交易 posted 时间。 */
        Instant postedAt
) {

    public static StatementLineResponse from(StatementLine line) {
        return new StatementLineResponse(
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
