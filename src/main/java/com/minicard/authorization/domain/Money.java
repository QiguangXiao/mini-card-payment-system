package com.minicard.authorization.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * 金额值对象。
 *
 * <p>关键词：金额, 币种, 精度, money, currency, scale,
 * 金額(きんがく), 通貨(つうか), 桁数(けたすう)。</p>
 *
 * <p>金融系统不能用 double 表示金额；这里用 BigDecimal + Currency，把非负和小数位规则放在值对象内。</p>
 */
public record Money(BigDecimal amount, Currency currency) {

    public Money {
        // record compact constructor 是所有创建路径的入口，包含 Jackson/MyBatis 手工 mapping 和测试。
        // 如果只在 controller 校验金额，scheduler 或 repository restore 仍可能构造出非法 Money。
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (amount.signum() < 0) {
            // 本项目的 Money 表示余额/额度/金额快照，不允许负数进入 domain。
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (amount.scale() > 2) {
            // 当前 DB decimal scale 是 2；超出时 fail fast，避免写库时才被截断/四舍五入。
            throw new IllegalArgumentException("amount must have at most 2 decimal places");
        }

        // 当前数据库模型支持两位小数；生产多币种系统应按 currency 定义 scale rule。
        // setScale(2) 统一 BigDecimal equality/display；如果不归一化，1.0 和 1.00 在 equals 上并不相等。
        amount = amount.setScale(2);
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
