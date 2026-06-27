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
     * 来自 repayment.received 事件，表达用户还款已成功入账。
     */
    REPAYMENT_RECEIVED
    // 提醒：STATEMENT_READY 等账单通知等到真正实现“账单生成 -> 通知”整条切片时再加，
    // 那时会连同事件生产者、模板和投递渠道一起设计，而不是先留一个没人发布的枚举值。
}
