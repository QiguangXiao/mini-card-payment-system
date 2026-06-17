package com.minicard.messaging.contract.authorization;

import java.time.Instant;
import java.util.UUID;

/**
 * Authorization 对外发布的 public message contract：authorization.approved。
 *
 * <p>这个类故意放在共享 contract 包，而不是 Authorization infrastructure 包。
 * 生产者和消费者只共享稳定消息格式，不共享彼此的 adapter/reader 实现。</p>
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
