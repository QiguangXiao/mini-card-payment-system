package com.minicard.notification.application;

import java.util.UUID;

/**
 * Transport-neutral input for requesting an authorization notification.
 */
public record RequestAuthorizationNotificationCommand(
        UUID sourceEventId,
        UUID authorizationId,
        String cardId,
        boolean approved
) {
}
