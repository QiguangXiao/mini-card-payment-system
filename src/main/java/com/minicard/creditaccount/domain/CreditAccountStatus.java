package com.minicard.creditaccount.domain;

/**
 * CreditAccount 可用状态。
 *
 * <p>关键词：额度账户状态, 可用额度, 冻结账户, credit account status,
 * available credit, blocked account, 利用枠管理状態(りようわくかんりじょうたい),
 * 利用停止(りようていし)。</p>
 */
public enum CreditAccountStatus {
    /** 可以进行 authorization reservation 和 repayment。 */
    ACTIVE,
    /** 账户冻结，不能新增授权。 */
    BLOCKED
}
