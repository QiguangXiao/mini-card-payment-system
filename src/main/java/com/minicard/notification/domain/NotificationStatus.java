package com.minicard.notification.domain;

/**
 * Notification aggregate 拥有的投递生命周期。
 *
 * <p>关键词：通知状态, 投递生命周期, 失败重试, notification status,
 * delivery lifecycle, failed delivery, 通知状態(つうちじょうたい),
 * 配信失敗(はいしんしっぱい)。</p>
 */
public enum NotificationStatus {
    /** 已创建，等待投递。 */
    PENDING,
    /** 已成功投递。 */
    SENT,
    /** 投递失败或超过重试上限。 */
    FAILED
}
