package com.minicard.notification.domain;

/**
 * 通知关联的业务对象类型。
 *
 * <p>Notification 是独立 bounded context，不应该只认识 Authorization。
 * 用 subjectType + subjectId 可以同时承接授权、交易入账和账单生成通知。</p>
 */
public enum NotificationSubjectType {
    AUTHORIZATION,
    CARD_TRANSACTION,
    STATEMENT
}
