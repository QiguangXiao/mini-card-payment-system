package com.minicard.notification.domain;

/**
 * 用户通知类型。
 *
 * <p>这里用 enum 明确表达“要发哪一种通知”，避免用 boolean approved
 * 这种只能表达两种 decision 的字段。后续 refund/reversal/dispute 通知也可以继续加枚举值。</p>
 */
public enum NotificationType {
    AUTHORIZATION_APPROVED,
    AUTHORIZATION_DECLINED,
    CARD_TRANSACTION_POSTED,
    /**
     * 来自 statement.closed 事件，但用户看到的是“账单已生成/可查看”。
     */
    STATEMENT_READY,
    /**
     * 来自 repayment.received 事件，表达用户还款已成功入账。
     */
    REPAYMENT_RECEIVED
}
