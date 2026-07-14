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
    CARD_TRANSACTION_POSTED
}
