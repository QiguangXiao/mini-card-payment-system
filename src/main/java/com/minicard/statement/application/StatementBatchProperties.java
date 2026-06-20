package com.minicard.statement.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Statement batch 的产品级配置。
 *
 * <p>当前学习项目先使用全局 billing day / payment day，而不是每个客户自定义。
 * 这让面试讨论聚焦在批处理、idempotency、row lock 和失败恢复。</p>
 */
@ConfigurationProperties(prefix = "statement.batch")
public record StatementBatchProperties(
        boolean enabled,
        long fixedDelayMs,
        int closeDayOfMonth,
        int paymentBaseDayOfMonth,
        int maxAccountsPerRun
) {

    public StatementBatchProperties {
        if (fixedDelayMs <= 0) {
            throw new IllegalArgumentException("fixedDelayMs must be positive");
        }
        validateDay(closeDayOfMonth, "closeDayOfMonth");
        validateDay(paymentBaseDayOfMonth, "paymentBaseDayOfMonth");
        if (maxAccountsPerRun <= 0) {
            throw new IllegalArgumentException("maxAccountsPerRun must be positive");
        }
    }

    private static void validateDay(int day, String fieldName) {
        if (day < 1 || day > 31) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and 31");
        }
    }
}
