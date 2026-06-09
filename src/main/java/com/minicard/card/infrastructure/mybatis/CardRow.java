package com.minicard.card.infrastructure.mybatis;

public record CardRow(
        String id,
        String creditAccountId,
        String status
) {
}
