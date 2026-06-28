package com.minicard.notification.domain.delivery;

/**
 * 投递失败异常（provider 报错 / 超时 / 断路器打开 / 重试耗尽）。
 *
 * <p>关键词：投递异常, provider 失败, 超时, delivery exception,
 * provider failure, timeout, 配信失敗(はいしんしっぱい)。</p>
 *
 * <p>ResilientNotificationSender 把底层各种失败(TimeoutException/CallNotPermittedException/provider error)
 * 统一包成它；worker 捕获后转 markFailed，进入退避重试或 DEAD。</p>
 */
public class NotificationDeliveryException extends RuntimeException {

    public NotificationDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
