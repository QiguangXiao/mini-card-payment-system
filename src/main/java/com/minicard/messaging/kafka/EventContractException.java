package com.minicard.messaging.kafka;

/**
 * Permanent message-contract failure that should not consume retry capacity.
 */
public class EventContractException extends RuntimeException {

    public EventContractException(String message) {
        super(message);
    }

    public EventContractException(String message, Throwable cause) {
        super(message, cause);
    }
}
