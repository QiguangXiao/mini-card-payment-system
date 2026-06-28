package com.minicard.notification.domain.delivery;

/**
 * 外部 provider 接受投递后回执的消息 id。
 *
 * <p>关键词：投递回执, provider message id, 对账, provider receipt,
 * delivery acknowledgement, 受付ID(うけつけID)。</p>
 *
 * <p>等价于 Kafka 的 broker ack：只有拿到回执才能把 delivery 标 SENT。providerMessageId 也用于
 * 排查"是否重复投递"，因为我们透传了稳定的 idempotencyKey，理想情况下重复请求会得到同一回执。</p>
 */
public record ProviderReceipt(String providerMessageId) {

    public ProviderReceipt {
        if (providerMessageId == null || providerMessageId.isBlank()) {
            throw new IllegalArgumentException("providerMessageId must not be blank");
        }
    }
}
