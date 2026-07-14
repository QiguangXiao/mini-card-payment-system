package com.minicard.risk.domain;

/**
 * 风控拒绝原因。
 *
 * <p>关键词：风控拒绝, velocity, 外部风控, risk decline,
 * fraud rule, external risk, リスク拒否(リスクきょひ),
 * 外部審査(がいぶしんさ)。</p>
 */
public enum RiskDeclineReason {
    /** 短时间授权次数过多。 */
    VELOCITY_EXCEEDED,
    /** 金额超过高风险阈值。 */
    HIGH_RISK_AMOUNT,
    /** 商户/持卡人地区不匹配。 */
    GEOLOCATION_MISMATCH,
    /** 商户黑名单。 */
    BLOCKED_MERCHANT,
    /** 外部风控拒绝。 */
    EXTERNAL_RISK_DECLINED,
    /** 外部风控不可用。 */
    EXTERNAL_RISK_UNAVAILABLE
}
