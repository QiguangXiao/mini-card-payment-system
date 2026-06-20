package com.minicard.statement.application;

import java.time.LocalDate;

/**
 * Statement batch 的运行摘要。
 *
 * <p>关键词：批处理结果, 候选账户, 失败计数, batch result, candidate count,
 * failure count, バッチ結果(バッチけっか), 対象口座(たいしょうこうざ),
 * 失敗件数(しっぱいけんすう)。</p>
 *
 * <p>它是 scheduler log/测试断言用的 result DTO，不参与持久化；真实系统通常还会有
 * batch_runs 表记录每个账户的成功/失败明细。</p>
 */
public record StatementBatchResult(
        /** 本次 runDate 是否命中締め日后的批处理日期。 */
        boolean due,
        /** scheduler 实际运行日期。 */
        LocalDate runDate,
        /** 本期账单开始日，not due 时为 null。 */
        LocalDate periodStart,
        /** 本期账单締め日，not due 时为 null。 */
        LocalDate periodEnd,
        /** 本期自动扣款支払日（payment due date），not due 时为 null。 */
        LocalDate dueDate,
        /** SQL 找到的候选账户数。 */
        int candidateCount,
        /** 成功生成 statement 的账户数。 */
        int generatedCount,
        /** 业务拒绝但无需报警的账户数，例如重复出账。 */
        int skippedCount,
        /** 非预期异常账户数，需要日志/人工排查。 */
        int failedCount
) {

    /**
     * 非出账日的空结果，方便 scheduler 使用同一种返回结构记录日志。
     */
    public static StatementBatchResult notDue(LocalDate runDate) {
        return new StatementBatchResult(
                false,
                runDate,
                null,
                null,
                null,
                0,
                0,
                0,
                0
        );
    }
}
