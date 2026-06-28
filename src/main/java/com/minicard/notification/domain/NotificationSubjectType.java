package com.minicard.notification.domain;

/**
 * 通知关联的业务对象类型。
 *
 * <p>Notification 是独立 bounded context，不应该只认识 Authorization。
 * 用 subjectType + subjectId 可以同时承接授权、交易入账、账单和还款通知；
 * 新主题等到对应通知切片落地时再加枚举值。</p>
 */
// subjectType + subjectId 是泛化关联方式；如果每种通知建一套字段/表，学习项目会过早复杂化。
// 枚举顺序与 notifications.chk_notifications_subject_type CHECK 白名单保持一致，便于对照排查。
public enum NotificationSubjectType {
    AUTHORIZATION,
    CARD_TRANSACTION,
    STATEMENT,
    REPAYMENT
}
