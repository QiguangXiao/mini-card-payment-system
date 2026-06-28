package com.minicard.notification.domain.delivery;

/**
 * 通知投递渠道。
 *
 * <p>关键词：投递渠道, app push, email, notification channel,
 * delivery channel, 配信チャネル(はいしんチャネル)。</p>
 *
 * <p>channel 和 {@link com.minicard.notification.domain.NotificationType} 是两个正交概念：
 * type 表达"发生了什么"(AUTHORIZATION_APPROVED)，channel 表达"怎么触达用户"(APP_PUSH/EMAIL)。
 * 一条通知意图会按收件人启用的渠道扇出(fan-out)成多条 delivery，各自独立投递与重试。</p>
 */
// 用 enum 而非自由字符串：channel 进 DB CHECK、Resilience4j 实例名、模板选择都靠它做白名单路由。
// 新增 SMS/LINE 等渠道时，编译器会强制在 sender 注册表、模板渲染、DDL CHECK 三处同步，不会漏。
public enum NotificationChannel {
    /** 移动端推送。 */
    APP_PUSH,
    /** 电子邮件。 */
    EMAIL
}
