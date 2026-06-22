package com.minicard.creditaccount.infrastructure.mybatis;

import java.math.BigDecimal;

/**
 * credit_accounts 表的 MyBatis row DTO。
 *
 * <p>关键词：额度账户行, 可用额度, 已入账余额, credit account row,
 * credit limit, posted balance, 利用枠管理行(りようわくかんりぎょう),
 * 利用可能枠(りようかのうわく)。</p>
 */
// BigDecimal 保留数据库 decimal 语义；不要在 row 层用 double，否则金额精度会丢。
public record CreditAccountRow(
        /** credit account id。 */
        String id,
        /** 总额度。 */
        BigDecimal creditLimit,
        /** 已授权但未入账的 reservation 金额。 */
        BigDecimal reservedAmount,
        /** 已入账未还款余额。 */
        BigDecimal postedBalance,
        /** 账户币种。 */
        String currency,
        /** CreditAccountStatus 字符串。 */
        String status
) {
}
