package com.minicard.infrastructure.web.ratelimit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.minicard.authorization.api.AuthorizationController;
import com.minicard.authorization.application.AuthorizationService;
import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.infrastructure.web.error.GlobalExceptionHandler;
import com.minicard.shared.domain.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 拦截器 + controller + 全局异常处理的贯通测试。
 *
 * <p>用 standalone MockMvc 手动组装三件套，验证的是协作契约：preHandle 抛出的
 * {@code RateLimitExceededException} 确实会被 {@code @RestControllerAdvice} 接住并
 * 映射成 429 + Retry-After + 标准 ErrorResponse——这条链路是配置出来的行为，
 * 单测拦截器或单测 advice 都覆盖不到。</p>
 */
class RateLimitedAuthorizationApiTest {

    private RedisTokenBucketRateLimiter rateLimiter;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RedisTokenBucketRateLimiter.class);
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        when(authorizationService.authorize(any())).thenReturn(approvedAuthorization());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthorizationController(authorizationService))
                .addInterceptors(new AuthorizationRateLimitInterceptor(
                        rateLimiter,
                        new RateLimitProperties(true, 20, 10, false),
                        new SimpleMeterRegistry()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returns429WithRetryAfterWhenRateLimitExceeded() throws Exception {
        when(rateLimiter.tryConsume(anyString())).thenReturn(RateLimitDecision.deny(3000));

        mockMvc.perform(authorizationRequest())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "3"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void passesThroughToControllerWhenAllowed() throws Exception {
        when(rateLimiter.tryConsume(anyString())).thenReturn(RateLimitDecision.allow());

        mockMvc.perform(authorizationRequest())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorizationRequest() {
        return post("/api/authorizations")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "cardId": "card-123",
                          "amount": 100,
                          "currency": "JPY",
                          "merchantId": "merchant-123",
                          "merchantCountry": "JP",
                          "cardholderCountry": "JP"
                        }
                        """);
    }

    private Authorization approvedAuthorization() {
        return Authorization.restore(
                UUID.fromString("fb6933e2-20ea-4268-b1c2-21c6705b1884"),
                "fingerprint-1",
                "card-123",
                new Money(new BigDecimal("100.00"), Currency.getInstance("JPY")),
                AuthorizationStatus.APPROVED,
                null,
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:01Z"),
                Instant.parse("2026-06-14T00:00:01Z"),
                null,
                null
        );
    }
}
