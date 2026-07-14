package com.minicard.statement.application.read;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.minicard.statement.domain.Statement;

/**
 * Statement GET 查询快照。
 *
 * <p>关键词：账单查询缓存, read model, cache-aside,
 * statement read model, cached snapshot, 請求照会(せいきゅうしょうかい)。</p>
 *
 * <p>它没有 domain behavior，只承载 GET response 需要的字段，因此可以放进
 * Caffeine L1 + Redis L2；source of truth 仍然是 MySQL 中的 Statement aggregate。</p>
 *
 * <p>生产经验里，不要把带行为的 aggregate 直接塞进 Redis：反序列化出来的对象很容易绕过
 * constructor/factory 的业务语义，也会让 cache schema 和 domain 演进绑得太死。
 * 这里用单独 read model，就是把“给 API 展示的数据”和“负责状态转换的 domain object”分开。</p>
 */
public record StatementReadModel(
        UUID id,
        UUID creditAccountId,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        BigDecimal totalAmount,
        BigDecimal minimumPaymentAmount,
        BigDecimal paidAmount,
        String currency,
        int transactionCount,
        String status,
        /**
         * L2 cache CAS/tombstone 用的正式单调版本，来自 statements.version。
         *
         * <p>如果用 paidAmount 代替 version，将来 overdue 标记、due date 调整、争议标记或展示字段修正
         * 不改变金额时，迟到写会被 Redis CAS 误判为同版本。</p>
         */
        long version,
        Instant generatedAt,
        List<StatementLineReadModel> items
) {

    public static StatementReadModel from(Statement statement) {
        return new StatementReadModel(
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
                statement.version(),
                statement.generatedAt(),
                statement.items().stream()
                        .map(StatementLineReadModel::from)
                        .toList()
        );
    }
}
