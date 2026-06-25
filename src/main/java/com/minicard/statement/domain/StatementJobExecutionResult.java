package com.minicard.statement.domain;

/**
 * Statement job 单次执行统计。
 *
 * <p>关键词：账单任务结果, 处理计数, job result,
 * execution counters, 実行結果(じっこうけっか)。</p>
 */
public record StatementJobExecutionResult(
        int processedAccountCount,
        int generatedStatementCount,
        int skippedAccountCount,
        int failedAccountCount
) {

    public StatementJobExecutionResult {
        if (processedAccountCount < 0
                || generatedStatementCount < 0
                || skippedAccountCount < 0
                || failedAccountCount < 0) {
            throw new IllegalArgumentException("statement job result counters must be non-negative");
        }
    }
}
