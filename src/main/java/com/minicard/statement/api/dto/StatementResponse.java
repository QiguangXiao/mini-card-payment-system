package com.minicard.statement.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.minicard.statement.application.StatementReadModel;
import com.minicard.statement.domain.Statement;

/**
 * Statement API response DTO。
 *
 * <p>关键词：账单响应, 明细列表, API DTO, statement response,
 * response mapping, 請求レスポンス(せいきゅうレスポンス),
 * 明細一覧(めいさいいちらん)。</p>
 *
 * <p>Controller 返回 DTO 而不是 domain object，避免把 aggregate 内部方法和 Money 类型直接暴露给 API。</p>
 */
public record StatementResponse(
        /** statement id。 */
        UUID id,
        /** 所属 credit account id。 */
        UUID creditAccountId,
        /** 账单周期开始日。 */
        LocalDate periodStart,
        /** 账单締め日。 */
        LocalDate periodEnd,
        /** 付款到期日。 */
        LocalDate dueDate,
        /** 账单总额。 */
        BigDecimal totalAmount,
        /** 最低还款额。 */
        BigDecimal minimumPaymentAmount,
        /** 已还金额。 */
        BigDecimal paidAmount,
        /** 货币代码。 */
        String currency,
        /** 明细交易数量。 */
        int transactionCount,
        /** StatementStatus 的 API 字符串。 */
        String status,
        /** 账单生成时间。 */
        Instant generatedAt,
        /** 账单明细列表。 */
        List<StatementLineResponse> items
) {

    /**
     * 从 domain aggregate 转成 API DTO。
     *
     * <p>Money 在 API 层拆成 amount + currency，便于 JSON 客户端理解。</p>
     */
    public static StatementResponse from(Statement statement) {
        return new StatementResponse(
                statement.id(),
                statement.creditAccountId(),
                statement.periodStart(),
                statement.periodEnd(),
                statement.dueDate(),
                statement.totalAmount().amount(),
                statement.minimumPaymentAmount().amount(),
                statement.paidAmount().amount(),
                statement.totalAmount().currency().getCurrencyCode(),
                statement.transactionCount(),
                statement.status().name(),
                statement.generatedAt(),
                statement.items().stream()
                        // Java Stream mapping；这里只做 presentation 转换，不改变业务状态。
                        .map(StatementLineResponse::from)
                        .toList()
        );
    }

    /**
     * 从 cached read model 转成 API DTO。
     *
     * <p>GET 查询路径只需要展示字段，不需要 domain behavior；缓存 read model 可以降低
     * Redis/L1 miss 后 DB 重建成本，同时避免把 aggregate 放进 cache。</p>
     */
    public static StatementResponse from(StatementReadModel statement) {
        // 这里和 from(Statement) 是 overload：Controller 按静态类型选择走 domain 还是 cached read model mapping。
        // 如果把两种来源混成 Object/Map，编译器就帮不了我们检查字段是否齐全。
        return new StatementResponse(
                statement.id(),
                statement.creditAccountId(),
                statement.periodStart(),
                statement.periodEnd(),
                statement.dueDate(),
                statement.totalAmount(),
                statement.minimumPaymentAmount(),
                statement.paidAmount(),
                statement.currency(),
                statement.transactionCount(),
                statement.status(),
                statement.generatedAt(),
                statement.items().stream()
                        .map(StatementLineResponse::from)
                        .toList()
        );
    }
}
