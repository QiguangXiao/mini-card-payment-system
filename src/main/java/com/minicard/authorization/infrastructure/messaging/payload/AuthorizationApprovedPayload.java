package com.minicard.authorization.infrastructure.messaging.payload;

import java.time.Instant;
import java.util.UUID;

/**
 * Kafka/Outbox 对外 payload：authorization.approved。
 *
 * <p>Payload 是 public message contract，不是 domain event。
 * 它只保存 consumer 需要稳定读取的字段。</p>
 */
public record AuthorizationApprovedPayload(
        UUID authorizationId,
        String cardId,
        String amount,
        String currency,
        Instant approvedAt,
        Instant expiresAt
) {

    public static final String EVENT_TYPE = "authorization.approved";
    public static final int EVENT_VERSION = 1;
}
