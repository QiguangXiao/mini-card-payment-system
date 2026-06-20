package com.minicard.transaction.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.transaction.domain.CardTransaction;

/**
 * CardTransaction API response DTO。
 *
 * <p>关键词：交易响应, 入账状态, 账单分配, card transaction response,
 * posting status, statement assignment, 取引レスポンス(とりひきレスポンス),
 * 請求明細への紐づけ(せいきゅうめいさいへのひもづけ)。</p>
 */
public record CardTransactionResponse(
        /** card transaction id。 */
        UUID id,
        /** 外部网络交易 id。 */
        String networkTransactionId,
        /** 原 authorization id。 */
        UUID authorizationId,
        /** card id。 */
        String cardId,
        /** credit account id。 */
        UUID creditAccountId,
        /** 交易金额。 */
        BigDecimal amount,
        /** 币种。 */
        String currency,
        /** CardTransactionStatus 字符串。 */
        String status,
        /** 收到 presentment 的时间。 */
        Instant presentmentReceivedAt,
        /** 入账完成时间。 */
        Instant postedAt,
        /** 所属 statement id；尚未出账时为空。 */
        UUID statementId,
        /** 分配到账单的时间；尚未出账时为空。 */
        Instant statementAssignedAt
) {

    /**
     * 从 domain aggregate 转成 API DTO。
     */
    public static CardTransactionResponse from(CardTransaction transaction) {
        return new CardTransactionResponse(
                transaction.id(),
                transaction.networkTransactionId(),
                transaction.authorizationId(),
                transaction.cardId(),
                transaction.creditAccountId(),
                transaction.amount().amount(),
                transaction.amount().currency().getCurrencyCode(),
                transaction.status().name(),
                transaction.presentmentReceivedAt(),
                transaction.postedAt(),
                transaction.statementId().orElse(null),
                transaction.statementAssignedAt().orElse(null)
        );
    }
}
