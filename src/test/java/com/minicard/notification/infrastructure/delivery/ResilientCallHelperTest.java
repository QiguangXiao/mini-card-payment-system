package com.minicard.notification.infrastructure.delivery;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.minicard.notification.application.delivery.NotificationDeliveryThrottledException;
import com.minicard.notification.domain.delivery.NotificationDeliveryPermanentException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientCallHelperTest {

    @Test
    void rateLimiterDenialDoesNotCallProviderOrPolluteCircuitBreaker() {
        CircuitBreakerRegistry circuitBreakers = circuitBreakerRegistry();
        RateLimiterRegistry rateLimiters = RateLimiterRegistry.of(rateLimiterConfig(1, Duration.ofHours(1)));
        RetryRegistry retries = RetryRegistry.of(retryConfig());
        ResilientCallHelper helper = new ResilientCallHelper(circuitBreakers, rateLimiters, retries);
        AtomicInteger calls = new AtomicInteger();

        String first = helper.call("notificationPush", () -> {
            calls.incrementAndGet();
            return "provider-message-1";
        });

        assertThat(first).isEqualTo("provider-message-1");

        assertThatThrownBy(() -> helper.call("notificationPush", () -> {
            calls.incrementAndGet();
            return "provider-message-2";
        }))
                .isInstanceOf(NotificationDeliveryThrottledException.class)
                .hasRootCauseInstanceOf(RequestNotPermitted.class);

        assertThat(calls).hasValue(1);
        CircuitBreaker.Metrics metrics = circuitBreakers.circuitBreaker("notificationPush").getMetrics();
        // RateLimiter 拒绝会经过 CircuitBreaker 外层，但 RequestNotPermitted 被 breaker ignore：
        // 我方主动节流不代表 provider brownout，不能被记成失败，否则高流量时会误把健康 provider 熔断。
        assertThat(metrics.getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(metrics.getNumberOfFailedCalls()).isZero();
    }

    @Test
    void openCircuitBreakerDoesNotConsumeRateLimiterPermit() {
        CircuitBreakerRegistry circuitBreakers = circuitBreakerRegistry();
        RateLimiterRegistry rateLimiters = RateLimiterRegistry.of(rateLimiterConfig(1, Duration.ofHours(1)));
        RetryRegistry retries = RetryRegistry.of(retryConfig());
        ResilientCallHelper helper = new ResilientCallHelper(circuitBreakers, rateLimiters, retries);
        AtomicInteger calls = new AtomicInteger();
        CircuitBreaker breaker = circuitBreakers.circuitBreaker("notificationPush");
        breaker.transitionToOpenState();

        assertThatThrownBy(() -> helper.call("notificationPush", () -> {
            calls.incrementAndGet();
            return "provider-message-open";
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseInstanceOf(CallNotPermittedException.class);

        assertThat(calls).hasValue(0);

        breaker.transitionToClosedState();
        String result = helper.call("notificationPush", () -> {
            calls.incrementAndGet();
            return "provider-message-after-close";
        });

        // 如果 OPEN breaker 的调用先消耗了 rate-limit permit，这里会被 RequestNotPermitted 拒绝。
        assertThat(result).isEqualTo("provider-message-after-close");
        assertThat(calls).hasValue(1);
    }

    @Test
    void transientFailureRetriesAndConsumesPermissionPerAttempt() {
        CircuitBreakerRegistry circuitBreakers = circuitBreakerRegistry();
        RateLimiterRegistry rateLimiters = RateLimiterRegistry.of(rateLimiterConfig(3, Duration.ofHours(1)));
        RetryRegistry retries = RetryRegistry.of(retryConfig());
        ResilientCallHelper helper = new ResilientCallHelper(circuitBreakers, rateLimiters, retries);
        AtomicInteger calls = new AtomicInteger();

        String result = helper.call("notificationEmail", () -> {
            int attempt = calls.incrementAndGet();
            if (attempt < 3) {
                throw new IllegalStateException("temporary provider error");
            }
            return "provider-message-3";
        });

        assertThat(result).isEqualTo("provider-message-3");
        assertThat(calls).hasValue(3);
    }

    @Test
    void permanentFailureIsNotWrapped() {
        ResilientCallHelper helper = new ResilientCallHelper(
                circuitBreakerRegistry(),
                RateLimiterRegistry.of(rateLimiterConfig(1, Duration.ofSeconds(1))),
                RetryRegistry.of(retryConfig())
        );

        assertThatThrownBy(() -> helper.call("notificationPush", () -> {
            throw new NotificationDeliveryPermanentException("bad request");
        }))
                .isInstanceOf(NotificationDeliveryPermanentException.class);
    }

    private RateLimiterConfig rateLimiterConfig(int limitForPeriod, Duration refreshPeriod) {
        return RateLimiterConfig.custom()
                .limitForPeriod(limitForPeriod)
                .limitRefreshPeriod(refreshPeriod)
                .timeoutDuration(Duration.ZERO)
                .build();
    }

    private CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                // 与 application.yml 对齐：本地保护拒绝/永久 4xx 不代表 provider brownout。
                .ignoreExceptions(
                        RequestNotPermitted.class,
                        NotificationDeliveryPermanentException.class
                )
                .build();
        return CircuitBreakerRegistry.of(config);
    }

    private RetryConfig retryConfig() {
        return RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ZERO)
                .ignoreExceptions(
                        RequestNotPermitted.class,
                        NotificationDeliveryPermanentException.class
                )
                .build();
    }
}
