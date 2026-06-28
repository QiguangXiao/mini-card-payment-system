package com.minicard.notification.domain.delivery;

/**
 * 渲染后的通知内容（标题 + 正文）。
 *
 * <p>关键词：渲染内容, 模板输出, 标题正文, notification content,
 * rendered template, 配信内容(はいしんないよう)。</p>
 *
 * <p>它是 {@link NotificationTemplateRenderer} 的输出：把 (type, channel, subjectId) 渲染成
 * 面向用户的文案。channel sender 只负责把它送达，不再关心模板与变量。</p>
 */
public record NotificationContent(String title, String body) {

    public NotificationContent {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("body must not be blank");
        }
    }
}
