package com.minicard.statement.application;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Statement 模块的统一配置。
 *
 * <p>关键词：账单配置, 批次任务, 最低还款, statement properties,
 * billing batch, minimum payment, 請求設定(せいきゅうせってい)。</p>
 *
 * <p>mini-card 是学习项目，所以把 batch/job/policy 放在一个配置类里；
 * 仍保留清晰的 nested records，避免配置散落成多个小类。</p>
 */
@ConfigurationProperties(prefix = "statement")
public record StatementProperties(
        Batch batch,
        Jobs jobs,
        Policy policy
) {

    public StatementProperties {
        if (batch == null || jobs == null || policy == null) {
            throw new IllegalArgumentException("statement properties sections must be configured");
        }
    }

    public record Batch(
            boolean enabled,
            String cron,
            String zone,
            int closeDayOfMonth,
            int paymentBaseDayOfMonth,
            int reconciliationLookbackCycles,
            int targetAccountsPerJob
    ) {
        public Batch {
            requireText(cron, "cron");
            requireText(zone, "zone");
            validateDay(closeDayOfMonth, "closeDayOfMonth");
            validateDay(paymentBaseDayOfMonth, "paymentBaseDayOfMonth");
            if (reconciliationLookbackCycles <= 0) {
                throw new IllegalArgumentException("reconciliationLookbackCycles must be positive");
            }
            if (targetAccountsPerJob <= 0) {
                throw new IllegalArgumentException("targetAccountsPerJob must be positive");
            }
        }
    }

    public record Jobs(
            boolean enabled,
            long fixedDelayMs,
            long recoveryFixedDelayMs,
            int maxPerRun,
            int maxAttempts,
            long processingTimeoutSeconds,
            int workerPoolSize,
            int workerQueueCapacity
    ) {
        public Jobs {
            if (fixedDelayMs <= 0
                    || recoveryFixedDelayMs <= 0
                    || maxPerRun <= 0
                    || maxAttempts <= 0
                    || processingTimeoutSeconds <= 0
                    || workerPoolSize <= 0
                    || workerQueueCapacity < 0) {
                throw new IllegalArgumentException("statement job properties must be positive");
            }
        }
    }

    public record Policy(
            BigDecimal minimumPaymentRate,
            Map<String, BigDecimal> minimumPaymentFloors
    ) {
        public Policy {
            if (minimumPaymentRate == null || minimumPaymentFloors == null || minimumPaymentFloors.isEmpty()) {
                throw new IllegalArgumentException("statement policy must be configured");
            }
        }
    }

    private static void validateDay(int day, String fieldName) {
        if (day < 1 || day > 31) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and 31");
        }
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
