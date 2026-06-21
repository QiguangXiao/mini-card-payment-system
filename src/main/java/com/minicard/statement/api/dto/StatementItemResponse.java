package com.minicard.statement.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.statement.application.StatementItemReadModel;
import com.minicard.statement.domain.StatementItem;

/**
 * Statement 明细 API response DTO。
 *
 * <p>关键词：账单明细响应, 交易快照, API DTO, statement item response,
 * transaction snapshot, 請求明細レスポンス(せいきゅうめいさいレスポンス),
 * 取引スナップショット(とりひきスナップショット)。</p>
 */
public record StatementItemResponse(
        /** statement item id。 */
        UUID id,
        /** 对应 card transaction id。 */
        UUID cardTransactionId,
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

    /**
     * 从 domain item 转成 API 明细 DTO。
     */
    public static StatementItemResponse from(StatementItem item) {
        return new StatementItemResponse(
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

    /**
     * 从 cached read model 转成 API 明细 DTO。
     */
    public static StatementItemResponse from(StatementItemReadModel item) {
        return new StatementItemResponse(
                item.id(),
                item.cardTransactionId(),
                item.networkTransactionId(),
                item.authorizationId(),
                item.cardId(),
                item.amount(),
                item.currency(),
                item.postedAt()
        );
    }
}
