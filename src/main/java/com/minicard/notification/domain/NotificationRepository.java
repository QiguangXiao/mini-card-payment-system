package com.minicard.notification.domain;

public interface NotificationRepository {

    /**
     * Uses sourceEventId uniqueness as the concurrency-safe creation boundary.
     */
    boolean insertIfAbsent(Notification notification);
}
