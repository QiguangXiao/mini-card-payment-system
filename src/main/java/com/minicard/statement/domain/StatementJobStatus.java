package com.minicard.statement.domain;

/**
 * Statement job 的领取和执行状态。
 *
 * <p>关键词：账单任务状态, PROCESSING lease, retry, statement job status,
 * claim status, 請求ジョブ状態(せいきゅうジョブじょうたい)。</p>
 */
public enum StatementJobStatus {
    /** 等待 worker claim。 */
    PENDING,
    /** 已被某个 worker claim，claim_until 到期前由该 worker 拥有。 */
    PROCESSING,
    /** 分片处理成功完成。 */
    DONE,
    /** 超过最大重试次数，进入人工排查状态。 */
    DEAD
}
