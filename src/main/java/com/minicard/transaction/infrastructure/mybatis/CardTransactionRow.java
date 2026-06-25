package com.minicard.transaction.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * card_transactions 表的 MyBatis row DTO。
 *
 * <p>关键词：交易行, presentment, 账单分配, card transaction row,
 * presentment row, statement assignment, 取引行(とりひきぎょう),
 * 請求明細への紐づけ(せいきゅうめいさいへのひもづけ)。</p>
 */
public record CardTransactionRow(
        /** card transaction id。 */
        String id,
        /** 外部网络交易 id，用于幂等。 */
        String networkTransactionId,
        /** 原 authorization id。 */
        String authorizationId,
        /** card id。 */
        String cardId,
        /** credit account id。 */
        String creditAccountId,
        /** 交易金额。 */
        BigDecimal amount,
        /** 币种。 */
        String currency,
        /** CardTransactionStatus 字符串。 */
        String status,
        /** CardTransactionBillingStatus 字符串，表达是否已经进入 statement line。 */
        String billingStatus,
        /** 收到 presentment 的时间。 */
        Instant presentmentReceivedAt,
        /** 入账完成时间。 */
        Instant postedAt,
        /** 分配到的 statement id。 */
        String statementId,
        /** 被 statement batch 分配的时间。 */
        Instant statementAssignedAt,
        /** 创建时间。 */
        Instant createdAt,
        /** 更新时间。 */
        Instant updatedAt
) {
}
