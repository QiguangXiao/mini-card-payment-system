package com.minicard.messaging.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 授权最终决策的 public integration contract。
 *
 * <p>事件只包含内部 card token，不包含明文 PAN。典型 consumer 包括持卡人通知、
 * risk-feature analytics 和 operations/audit projection。</p>
 */
public record AuthorizationDecidedEvent(
        UUID authorizationId,
        String cardId,
        String amount,
        String currency,
        String status,
        String declineReason,
        Instant decidedAt
) {

    public static final String EVENT_TYPE = "authorization.decided";
    public static final int EVENT_VERSION = 1;
}
