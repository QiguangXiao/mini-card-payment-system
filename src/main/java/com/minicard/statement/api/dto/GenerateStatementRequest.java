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
// record 把四个日期/账户字段固定成一个不可变 API contract。
// 如果用 Map 或可变 POJO，periodStart/periodEnd/dueDate 的语义更容易在调用链中混淆。
public record GenerateStatementRequest(
        /** 要生成账单的 credit account。 */
        // @NotNull 让缺字段在 controller boundary 失败，而不是等到 YearMonth/SQL 计算时 NPE。
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
