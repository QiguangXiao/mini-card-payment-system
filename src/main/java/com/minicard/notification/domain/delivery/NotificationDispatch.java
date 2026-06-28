package com.minicard.notification.domain.delivery;

/**
 * 交给 channel sender 的一次投递请求（自洽，不含领域对象）。
 *
 * <p>关键词：投递请求, 幂等键, 收件地址, notification dispatch,
 * idempotency key, send request, 配信リクエスト(はいしんリクエスト)。</p>
 *
 * <p>它把"发什么(content)、发给哪个地址(recipientAddress)、用哪个幂等键(idempotencyKey)"打包，
 * 让 sender 端口与 NotificationDelivery 聚合解耦——sender 不该看到投递状态机，只该看到一次发送。</p>
 */
public record NotificationDispatch(
        NotificationChannel channel,
        String recipientAddress,
        NotificationContent content,
        String idempotencyKey
) {

    public NotificationDispatch {
        if (channel == null) {
            throw new IllegalArgumentException("channel must not be null");
        }
        if (recipientAddress == null || recipientAddress.isBlank()) {
            throw new IllegalArgumentException("recipientAddress must not be blank");
        }
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }
}
