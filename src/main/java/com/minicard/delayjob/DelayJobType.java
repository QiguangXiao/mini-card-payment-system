package com.minicard.delayjob;

/**
 * DelayJob 业务类型。
 *
 * <p>关键词：任务类型, 授权过期, 自动还款, delay job type,
 * authorization expiry, auto repayment, ジョブ種別(ジョブしゅべつ),
 * 自動引き落とし(じどうひきおとし)。</p>
 */
public enum DelayJobType {
    /** 授权过期后释放 reservation。 */
    AUTHORIZATION_EXPIRY,
    /** Statement 到期后执行自动扣款。 */
    AUTO_REPAYMENT
}
