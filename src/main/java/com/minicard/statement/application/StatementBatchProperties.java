package com.minicard.statement.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Statement batch 的产品级配置。
 *
 * <p>关键词：账单配置, 关账日, 扣款基准日, statement batch properties,
 * close day, payment base day, 締め日(しめび), 支払基準日(しはらいきじゅんび),
 * バッチ設定(バッチせってい)。</p>
 *
 * <p>当前学习项目先使用全局 billing day / payment day，而不是每个客户自定义。
 * 这让面试讨论聚焦在批处理、idempotency、row lock 和失败恢复。</p>
 */
@ConfigurationProperties(prefix = "statement.batch")
public record StatementBatchProperties(
        /** 是否启用 monthly statement batch；本地测试可关闭 scheduler。 */
        boolean enabled,
        /** @Scheduled fixed delay，单位毫秒；用于控制 batch poll 频率。 */
        long fixedDelayMs,
        /** 月度締め日（statement close day / 締め日）。 */
        int closeDayOfMonth,
        /** 自动扣款基准日（payment base day / 支払基準日），当前为 27 日。 */
        int paymentBaseDayOfMonth,
        /** 单次最多处理账户数，避免一次 batch 长事务/长循环拖垮应用。 */
        int maxAccountsPerRun
) {

    public StatementBatchProperties {
        if (fixedDelayMs <= 0) {
            // 配置错误应在启动期 fail fast，避免 scheduler 用非法间隔运行。
            throw new IllegalArgumentException("fixedDelayMs must be positive");
        }
        validateDay(closeDayOfMonth, "closeDayOfMonth");
        validateDay(paymentBaseDayOfMonth, "paymentBaseDayOfMonth");
        if (maxAccountsPerRun <= 0) {
            // batch size 必须为正，否则 poller 会看似运行但永远不处理账户。
            throw new IllegalArgumentException("maxAccountsPerRun must be positive");
        }
    }

    /**
     * 校验日字段范围；短月由 dayInMonth 夹到月末，这里只拒绝明显无效配置。
     */
    private static void validateDay(int day, String fieldName) {
        if (day < 1 || day > 31) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and 31");
        }
    }
}
