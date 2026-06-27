package com.minicard.statement.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
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
                statement.generatedAt(),
                statement.items().stream()
                        .map(StatementLineReadModel::from)
                        .toList()
        );
    }

    /**
     * L2 缓存乐观并发用的单调版本：本期已还款金额的最小货币单位（minor units）。
     *
     * <p>为什么用 paidAmount：read model 的可变部分只有 paidAmount 和 status，而 status 由 paidAmount
     * 推导，所以 paidAmount 单调非减就能完整代表"这份快照新到什么程度"。每次还款都严格增大它，于是
     * 旧 reader 的版本一定小于还款后的版本——这正是 CAS 拒绝迟到写所需的全序。</p>
     *
     * <p>用 minor units（整数 long）而不是 BigDecimal：Lua 端按数字比较，整数最稳。一张 statement 币种固定，
     * 比较永远在同币种内发生，所以不同币种的 minor unit 口径不一致也不影响正确性。</p>
     *
     * <p>前提：本域不存在 un-repay / 退款使 paidAmount 变小。若将来引入退款冲正，或出现"不改 paidAmount
     * 却改 read model"的字段（如争议标记），这个版本就不再完整，需换成专用单调 version 列。</p>
     */
    public long version() {
        return minorUnits(paidAmount, currency);
    }

    /** 把金额折算成最小货币单位的整数版本号。和 {@link #version()} 共用同一口径，供写侧 evict 复用。 */
    public static long minorUnits(BigDecimal amount, String currencyCode) {
        int fractionDigits = Math.max(0, Currency.getInstance(currencyCode).getDefaultFractionDigits());
        return amount.movePointRight(fractionDigits).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
