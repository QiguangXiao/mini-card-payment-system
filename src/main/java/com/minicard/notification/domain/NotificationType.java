package com.minicard.notification.domain;

/**
 * 用户通知类型。
 *
 * <p>这里用 enum 明确表达“要发哪一种通知”，避免用 boolean approved
 * 这种只能表达两种 decision 的字段。后续 refund/reversal/dispute 通知也可以继续加枚举值。</p>
 */
// enum 是模板路由的白名单；如果用自由字符串，拼写错误会到发送通知时才暴露。
public enum NotificationType {
    AUTHORIZATION_APPROVED,
    AUTHORIZATION_DECLINED,
    CARD_TRANSACTION_POSTED,
    /**
     * 来自 statement.closed 事件，表达本期账单已生成、可查看应还金额与到期日。
     */
    // 命名与事件类型 statement.closed 保持同构（authorization.approved -> AUTHORIZATION_APPROVED）。
    // 这条枚举值连同 StatementOutboxAdapter 生产者和 StatementNotificationListener 消费者一起落地，
    // 不再是“没人发布的占位枚举值”。
    STATEMENT_CLOSED,
    /**
     * 来自 repayment.received 事件，表达用户还款已成功入账。
     */
    REPAYMENT_RECEIVED
}
