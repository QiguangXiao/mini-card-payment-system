package com.minicard.notification.application.delivery;

/**
 * 通知 provider 返回的永久性失败信号。
 *
 * <p>关键词：永久失败, 不可重试, provider contract failure,
 * permanent failure, non-retryable, 恒久失敗(こうきゅうしっぱい)。</p>
 *
 * <p>这是 application port 的控制信号，不是 Notification aggregate invariant。
 * 用于表达"再试一次也不会变好"的失败，例如 provider 返回 400/401/403 这类请求契约或配置错误。
 * 它和 timeout/5xx 不同：timeout/5xx 可能是短暂抖动，适合 retry；4xx 多半需要修配置、修 token、
 * 修请求格式，继续自动重试只会浪费 retry budget 并污染 provider。</p>
 */
public class NotificationDeliveryPermanentException extends RuntimeException {

    public NotificationDeliveryPermanentException(String message) {
        super(message);
    }
}
