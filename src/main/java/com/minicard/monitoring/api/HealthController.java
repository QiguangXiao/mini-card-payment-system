package com.minicard.monitoring.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight public liveness endpoint.
 *
 * <p>This endpoint only proves that the HTTP application is running. Detailed
 * dependency health, metrics, and operational diagnostics belong to Spring
 * Boot Actuator rather than being reimplemented in this controller.</p>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public HealthResponse health() {
        return HealthResponse.ok();
    }
}
