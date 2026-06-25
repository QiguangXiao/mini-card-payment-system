package com.minicard.statement.domain;

/**
 * Statement batch 的生命周期状态。
 *
 * <p>关键词：账单批次状态, 批处理生命周期, statement batch status,
 * billing batch lifecycle, 請求バッチ状態(せいきゅうバッチじょうたい)。</p>
 */
public enum StatementBatchStatus {
    /** batch 已创建，job 正在等待或执行。 */
    RUNNING,
    /** 所有 job 成功完成。 */
    COMPLETED,
    /** 所有 job 都结束了，但至少一个进入 DEAD，需要人工排查或补偿。 */
    PARTIALLY_FAILED
}
