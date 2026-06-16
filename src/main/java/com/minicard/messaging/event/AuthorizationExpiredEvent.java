package com.minicard.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 授权 hold 过期并释放额度后发布的 public integration contract。
 *
 * <p>同时携带 expiresAt 和 expiredAt，用来区分业务计划时间和 scheduler 实际处理时间。</p>
 */
public record AuthorizationExpiredEvent(
        UUID authorizationId,
        String cardId,
        String amount,
        String currency,
        Instant expiresAt,
        Instant expiredAt
) {

    public static final String EVENT_TYPE = "authorization.expired";
    public static final int EVENT_VERSION = 1;
}
