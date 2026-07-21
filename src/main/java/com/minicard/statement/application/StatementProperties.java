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
        /** billing cycle reconciliation 与分片规划配置。 */
        Batch batch,
        /** statement job claim、lease、retry 与 worker capacity 配置。 */
        Jobs jobs,
        /** 最低还款额计算规则。 */
        Policy policy
) {

    public StatementProperties {
        // 三段配置缺任意一段都不能静默使用零值：零值可能关闭调度、制造 0 秒 lease 或算错最低还款额。
        if (batch == null || jobs == null || policy == null) {
            throw new IllegalArgumentException("statement properties sections must be configured");
        }
    }

    /**
     * 从“何时关账”到“一个 cycle 拆多少 statement jobs”的 planner 配置。
     */
    public record Batch(
            /** 是否启用 BillingCycleScheduler；关闭后已存在 jobs 仍可由 worker 执行。 */
            boolean enabled,
            /** scheduler cron，只负责周期 reconciliation，不直接生成单账户 statement。 */
            String cron,
            /** 账务日切时区，同时用于 cron 和 LocalDate 到 Instant 的边界转换。 */
            String zone,
            /** 每月关账日；31 在短月会收敛到该月最后一天。 */
            int closeDayOfMonth,
            /** 次月计划付款日，遇非营业日再由 BusinessDayCalendar 顺延。 */
            int paymentBaseDayOfMonth,
            /** 每次 scheduler 回看多少个已过去 cycle，用于补应用停机期间漏掉的关账任务。 */
            int reconciliationLookbackCycles,
            /** 每个 durable statement job 期望处理的账户数，用于由候选账户数计算 shardCount。 */
            int targetAccountsPerJob
    ) {
        public Batch {
            // planner 参数在应用启动绑定时 fail fast；运行中才发现无效时区/负分片规模会留下缺失 cycle。
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

    /**
     * StatementJobDispatcher 的轮询、PROCESSING lease、retry budget 与本地线程池配置。
     */
    public record Jobs(
            /** 是否领取新的 PENDING jobs。 */
            boolean enabled,
            /** claim poller 两次扫描之间的固定延迟。 */
            long fixedDelayMs,
            /** recovery 扫描过期 PROCESSING lease 的固定延迟。 */
            long recoveryFixedDelayMs,
            /** 单轮最多 claim 的 job 数；过大时队列等待会提前消耗 claim lease。 */
            int maxPerRun,
            /** 一个 shard 达到该失败次数后进入 DEAD，等待人工排查。 */
            int maxAttempts,
            /** PROCESSING claim 的最长持有秒数，必须覆盖一个分片的正常执行时间。 */
            long processingTimeoutSeconds,
            /** statement job worker 并发线程数。 */
            int workerPoolSize,
            /** 有界 worker queue 容量；满时拒绝并把 job 放回 retry，而不是无界堆积。 */
            int workerQueueCapacity
    ) {
        public Jobs {
            // queue capacity 允许为 0，表示只接受可立即交给 worker 的任务；其余时间/数量必须为正。
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

    /**
     * 最低还款额策略：{@code min(total, max(total * rate, currency floor))}。
     */
    public record Policy(
            /** 按账单总额计算的比例，例如 0.10 表示 10%。 */
            BigDecimal minimumPaymentRate,
            /** 各币种最低金额下限；币种缺失时无法安全生成该币种账单。 */
        Map<String, BigDecimal> minimumPaymentFloors
    ) {
        public Policy {
            // 当前只检查配置结构完整；缺币种或非法 rate/floor 会在生成账单时由 service/domain 拒绝。
            // 这是一个可见的简化边界：生产系统更适合在启动阶段遍历所有币种并 fail fast，避免运行到月末才暴露配置错。
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
