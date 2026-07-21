package com.minicard.statement.domain;

/**
 * Statement job 单次执行统计。
 *
 * <p>关键词：账单任务结果, 处理计数, job result,
 * execution counters, 実行結果(じっこうけっか)。</p>
 */
public record StatementJobExecutionResult(
        /** 本 worker 实际尝试过的账户数；lease 中途丢失时可能小于分片查询出的账户总数。 */
        int processedAccountCount,
        /** 成功生成新 statement 的账户数。 */
        int generatedStatementCount,
        /** 确定性无需出账的账户数，例如锁后发现没有候选交易。 */
        int skippedAccountCount,
        /** 出现 retryable 或未预期异常的账户数；大于 0 会让整个分片进入 retry/DEAD 决策。 */
        int failedAccountCount
) {

    public StatementJobExecutionResult {
        // 统计会持久化到 statement_jobs 并参与终态决策，负数会让监控和重试语义都不可解释。
        if (processedAccountCount < 0
                || generatedStatementCount < 0
                || skippedAccountCount < 0
                || failedAccountCount < 0) {
            throw new IllegalArgumentException("statement job result counters must be non-negative");
        }
    }
}
