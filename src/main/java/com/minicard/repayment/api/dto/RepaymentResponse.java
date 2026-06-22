package com.minicard.repayment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.minicard.repayment.domain.Repayment;

/**
 * Repayment API response DTO。
 *
 * <p>关键词：还款响应, 入账状态, API DTO, repayment response,
 * posting status, 入金レスポンス(にゅうきんレスポンス),
 * 入金状態(にゅうきんじょうたい)。</p>
 *
 * <p>Repayment domain 中部分字段是 Optional；API 层明确用 null 表示 PENDING 阶段尚未产生的数据。</p>
 */
public record RepaymentResponse(
        /** repayment id。 */
        UUID id,
        /** 被还款的 statement id。 */
        UUID statementId,
        /** 入账后的 credit account id；PENDING 时可能为空。 */
        UUID creditAccountId,
        /** 还款金额。 */
        BigDecimal amount,
        /** 货币代码。 */
        String currency,
        /** RepaymentStatus 的 API 字符串。 */
        String status,
        /** 实际收到还款时间；PENDING 时为空。 */
        Instant receivedAt,
        /** repayment 创建时间。 */
        Instant createdAt
) {

    /**
     * 从 Repayment aggregate 转成 API DTO。
     */
    public static RepaymentResponse from(Repayment repayment) {
        return new RepaymentResponse(
                repayment.id(),
                repayment.statementId(),
                // API response 用 null 表示“阶段上尚未产生”，而不是把 Java Optional 泄漏到 JSON contract。
                repayment.creditAccountId().orElse(null),
                repayment.amount().amount(),
                repayment.amount().currency().getCurrencyCode(),
                repayment.status().name(),
                // Jackson 能序列化 Optional，但客户端契约会变奇怪；DTO 层显式拆成 nullable 字段更直观。
                repayment.receivedAt().orElse(null),
                repayment.createdAt()
        );
    }
}
