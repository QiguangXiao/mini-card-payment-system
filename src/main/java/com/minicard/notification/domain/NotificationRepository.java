package com.minicard.notification.domain;

/**
 * Notification repository 端口。
 *
 * <p>关键词：通知仓储, 事件幂等, 唯一键, notification repository,
 * source event id, idempotency, 通知リポジトリ(つうちリポジトリ),
 * 冪等性(べきとうせい)。</p>
 */
public interface NotificationRepository {

    /**
     * 使用 sourceEventId 唯一性作为并发安全的创建边界。
     */
    boolean insertIfAbsent(Notification notification);
}
