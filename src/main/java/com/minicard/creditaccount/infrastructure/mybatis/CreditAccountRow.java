package com.minicard.creditaccount.infrastructure.mybatis;

import java.math.BigDecimal;

public record CreditAccountRow(
        String id,
        BigDecimal creditLimit,
        BigDecimal reservedAmount,
        BigDecimal postedBalance,
        String currency,
        String status
) {
}
