package com.minicard.delayjob;

/**
 * DelayJob 执行状态。
 *
 * <p>关键词：任务状态, 处理中租约, 死信, delay job status,
 * processing lease, dead job, ジョブ状態(ジョブじょうたい),
 * 処理中リース(しょりちゅうリース)。</p>
 */
public enum DelayJobStatus {
    /** 等待执行，poller 可以领取。 */
    PENDING,
    /** 已被 worker 领取；nextAttemptAt 临时表示 lease deadline。 */
    PROCESSING,
    /** 业务动作已成功完成。 */
    DONE,
    /** 超过最大重试次数，需要人工排查或补偿。 */
    DEAD
}
