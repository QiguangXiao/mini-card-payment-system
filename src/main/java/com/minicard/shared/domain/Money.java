package com.minicard.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 金额值对象（shared kernel）。
 *
 * <p>关键词：金额, 币种, 精度, money, currency, scale, shared kernel,
 * 金額(きんがく), 通貨(つうか), 桁数(けたすう)。</p>
 *
 * <p>金融系统不能用 double 表示金额；这里用 BigDecimal + Currency，把非负、按币种小数位规则
 * 都收在值对象内。Money 是跨 bounded context 共享的内核值对象（statement/repayment/risk
 * 等都用它），刻意不属于任何单一业务上下文，因此放在 com.minicard.shared.domain，
 * 而不是某个 domain 包下——否则其他 context 会被迫“依赖 authorization”这种假依赖。</p>
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        // record compact constructor 是所有创建路径的入口，包含 Jackson/MyBatis 手工 mapping 和测试。
        // 如果只在 controller 校验金额，scheduler 或 repository restore 仍可能构造出非法 Money。
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        int fractionDigits = currency.getDefaultFractionDigits();
        if (fractionDigits < 0) {
            // ISO 4217 把 XXX/XAU 这类伪币种的小数位定义为 -1（无意义）；它们不该进入金额计算。
            throw new IllegalArgumentException(
                    "currency " + currency.getCurrencyCode() + " has no minor-unit definition");
        }
        if (amount.signum() < 0) {
            // 本项目的 Money 表示余额/额度/金额快照，不允许负数进入 domain。
            throw new IllegalArgumentException("amount must not be negative");
        }

        try {
            // 按币种法定小数位归一：JPY→0、USD→2、KWD→3。统一 scale 让 BigDecimal equality 可靠
            // （1.0 与 1.00 在 equals 上并不相等），也保证 JPY 不会出现“分以下日元”这种不存在的金额。
            // UNNECESSARY 让“尾零安全降级”(DB 里的 100000.00 → JPY 100000) 通过，
            // 但真正丢精度(1234.50 JPY)直接 fail fast，而不是被静默四舍五入成错误金额。
            amount = amount.setScale(fractionDigits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "amount " + amount.toPlainString() + " exceeds " + currency.getCurrencyCode()
                            + " precision of " + fractionDigits + " fraction digits", exception);
        }
    }

    /**
     * 比较同币种金额大小。
     */
    public boolean isGreaterThan(Money other) {
        ensureSameCurrency(other);
        return amount.compareTo(other.amount) > 0;
    }

    /**
     * 同币种加法。
     */
    public Money add(Money other) {
        ensureSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    /**
     * 同币种减法。
     */
    public Money subtract(Money other) {
        ensureSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    /**
     * 乘以一个无量纲系数（如最低还款比例），并按本币种小数位用指定 rounding 取整。
     *
     * <p>rounding policy 收在值对象里：调用方无法挑错 scale（例如对 JPY 误用 2 位小数）。
     * 如果让调用方自己 setScale，就会出现“按固定 2 位取整”这种对 JPY 错误的逻辑散落各处。</p>
     */
    public Money multiply(BigDecimal factor, RoundingMode rounding) {
        Objects.requireNonNull(factor, "factor must not be null");
        Objects.requireNonNull(rounding, "rounding must not be null");
        BigDecimal scaled = amount.multiply(factor)
                .setScale(currency.getDefaultFractionDigits(), rounding);
        return new Money(scaled, currency);
    }

    /**
     * 返回两者中较大的同币种金额。
     */
    public Money max(Money other) {
        return isGreaterThan(other) ? this : other;
    }

    /**
     * 判断金额是否大于 0。
     */
    public boolean isPositive() {
        return amount.signum() > 0;
    }

    /**
     * 防止 JPY 与 USD 这类不同 currency 被直接相加/相减。
     */
    private void ensureSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("money currencies must match");
        }
    }
}
