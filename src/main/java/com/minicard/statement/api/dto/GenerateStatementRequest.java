package com.minicard.statement.api.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * 手动生成账单的 API request。
 *
 * <p>关键词：手动出账, 请求 DTO, Bean Validation, generate statement request,
 * API boundary, 請求明細作成依頼(せいきゅうめいさいさくせいいらい),
 * 入力検証(にゅうりょくけんしょう)。</p>
 *
 * <p>真实主路径是 monthly batch；这个 request 主要用于本地演示和人工补偿。</p>
 */
public record GenerateStatementRequest(
        /** 要生成账单的 credit account。 */
        @NotNull
        UUID creditAccountId,

        /** 账单周期开始日。 */
        @NotNull
        LocalDate periodStart,

        /** 账单周期结束日，也就是締め日。 */
        @NotNull
        LocalDate periodEnd,

        /** 付款到期日，通常由 batch 规则计算；手动 API 允许显式传入。 */
        @NotNull
        LocalDate dueDate
) {
}
