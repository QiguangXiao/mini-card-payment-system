package com.minicard.authorization.infrastructure.mybatis;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * authorizations 表的 MyBatis row DTO。
 *
 * <p>关键词：授权行, 持久化模型, 决策时间, authorization row,
 * persistence row, decision timestamp, オーソリ行(オーソリぎょう),
 * 永続化(えいぞくか)。</p>
 */
public record AuthorizationRow(
        /** authorization id。 */
        String id,
        /** 幂等请求 fingerprint，用于检测同 key 不同 payload。 */
        String requestFingerprint,
        /** 业务 card id。 */
        String cardId,
        /** 授权金额。 */
        BigDecimal amount,
        /** 币种代码。 */
        String currency,
        /** AuthorizationStatus 字符串。 */
        String status,
        /** AuthorizationDeclineReason 字符串。 */
        String declineReason,
        /** 创建时间。 */
        Instant createdAt,
        /** 批准/拒绝决策时间。 */
        Instant decidedAt,
        /** authorization 过期时间。 */
        Instant expiresAt,
        /** presentment 入账时间。 */
        Instant postedAt,
        /** 实际过期处理时间。 */
        Instant expiredAt
) {
}
