package com.minicard.monitoring.api;

/**
 * Stable public response contract for the lightweight application health API.
 */
public record HealthResponse(String status) {

    public static HealthResponse ok() {
        return new HealthResponse("OK");
    }
}
