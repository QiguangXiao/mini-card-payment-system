package com.minicard.creditaccount.infrastructure.mybatis;

import java.math.BigDecimal;

public record CreditAccountRow(
        String id,
        BigDecimal creditLimit,
        BigDecimal reservedAmount,
        String currency,
        String status
) {
}
