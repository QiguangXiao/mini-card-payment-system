package com.minicard.statement.application;

/**
 * 某个 statement batch 下所有 jobs 的状态汇总。
 *
 * <p>关键词：任务状态汇总, batch completion, job summary,
 * completion check, 完了判定(かんりょうはんてい)。</p>
 */
public record StatementJobStatusSummary(
        int totalCount,
        int pendingCount,
        int processingCount,
        int doneCount,
        int deadCount
) {

    public boolean allFinished() {
        return totalCount > 0 && pendingCount == 0 && processingCount == 0;
    }

    public boolean hasDeadJobs() {
        return deadCount > 0;
    }
}
