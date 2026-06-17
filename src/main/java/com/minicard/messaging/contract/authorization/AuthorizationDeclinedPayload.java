package com.minicard.messaging.contract.authorization;

import java.time.Instant;
import java.util.UUID;

/**
 * Authorization 对外发布的 public message contract：authorization.declined。
 *
 * <p>Payload 是跨 bounded context 的契约，不是 Authorization aggregate 内部对象。</p>
 */
public record AuthorizationDeclinedPayload(
        UUID authorizationId,
        String cardId,
        String amount,
        String currency,
        String declineReason,
        Instant declinedAt
) {

    public static final String EVENT_TYPE = "authorization.declined";
    public static final int EVENT_VERSION = 1;
}
