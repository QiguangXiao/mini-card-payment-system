package com.minicard.notification.domain;

/**
 * Notification delivery lifecycle owned by the Notification aggregate.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}
