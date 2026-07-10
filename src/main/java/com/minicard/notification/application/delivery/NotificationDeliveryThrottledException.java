package com.minicard.notification.application.delivery;

/**
 * 通知 provider 尚未被调用，因为本 Pod 的出站 RateLimiter 暂时没有 permit。
 *
 * <p>关键词：通知节流, 未执行外部调用, 不消耗投递次数, notification throttling,
 * client-side rate limit, 配信延期(はいしんえんき)。</p>
 *
 * <p>它和 provider timeout/5xx 不同：后两者已经发起过 HTTP attempt，应消耗 durable retry budget；
 * 本异常发生在 HTTP 之前，只应释放 PROCESSING lease、延后 {@code nextAttemptAt}，不增加 attempts。
 * 用专门类型跨过 infrastructure → application 边界，避免 worker 递归检查 R4j 异常 cause。</p>
 */
public class NotificationDeliveryThrottledException extends RuntimeException {

    public NotificationDeliveryThrottledException(String message, Throwable cause) {
        super(message, cause);
    }
}
