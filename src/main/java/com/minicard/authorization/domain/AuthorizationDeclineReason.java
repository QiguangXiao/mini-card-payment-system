package com.minicard.authorization.domain;

/**
 * Authorization 拒绝原因。
 *
 * <p>关键词：授权拒绝, 风险拒绝, 额度不足, decline reason,
 * risk decline, insufficient credit, オーソリ拒否(オーソリきょひ),
 * ご利用可能額不足(ごりようかのうがくぶそく)。</p>
 */
public enum AuthorizationDeclineReason {
    /** 单笔交易超过产品配置上限。 */
    SINGLE_TRANSACTION_LIMIT_EXCEEDED,
    /** 币种不支持。 */
    UNSUPPORTED_CURRENCY,
    /** 卡不存在。 */
    CARD_NOT_FOUND,
    /** 卡被冻结/不可用。 */
    CARD_BLOCKED,
    /** 卡已过期。 */
    CARD_EXPIRED,
    /** 本地 velocity 风控拒绝。 */
    RISK_VELOCITY_EXCEEDED,
    /**
     * 仅用于读取 historical risk projection 删除前已持久化的授权审计记录。
     * 新风控决策已无此分支，不得再产生该值；若直接删除，repository 读取旧行时会在 enum 解析处失败。
     */
    @Deprecated
    RISK_HISTORICAL_PROFILE,
    /** 金额过高触发风控。 */
    RISK_HIGH_AMOUNT,
    /** 地理位置不匹配触发风控。 */
    RISK_GEOLOCATION_MISMATCH,
    /** 商户在黑名单。 */
    RISK_BLOCKED_MERCHANT,
    /** 外部风控明确拒绝。 */
    RISK_EXTERNAL_DECLINED,
    /** 外部风控不可用，按保守策略拒绝。 */
    RISK_EXTERNAL_UNAVAILABLE,
    /** 额度账户不存在。 */
    CREDIT_ACCOUNT_NOT_FOUND,
    /** 额度账户被冻结。 */
    CREDIT_ACCOUNT_BLOCKED,
    /** 交易币种与账户币种不一致。 */
    CURRENCY_MISMATCH,
    /** 可用额度不足。 */
    INSUFFICIENT_AVAILABLE_CREDIT
}
