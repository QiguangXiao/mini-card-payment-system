package com.minicard.infrastructure.web.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthorizationRateLimitInterceptorTest {

    private RedisTokenBucketRateLimiter rateLimiter;
    private SimpleMeterRegistry meterRegistry;
    private AuthorizationRateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RedisTokenBucketRateLimiter.class);
        meterRegistry = new SimpleMeterRegistry();
        interceptor = new AuthorizationRateLimitInterceptor(rateLimiter, meterRegistry);
    }

    @Test
    void allowsRequestWhenTokenIsAvailable() {
        when(rateLimiter.tryConsume("192.0.2.1")).thenReturn(RateLimitDecision.allow());
        MockHttpServletRequest request = post();
        request.setRemoteAddr("192.0.2.1");

        boolean proceed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(proceed).isTrue();
    }

    @Test
    void skipsNonPostRequestsWithoutConsumingTokens() {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/authorizations");

        boolean proceed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        // 探测类请求不该消耗令牌，也不该被 429。
        assertThat(proceed).isTrue();
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void throwsWith429SemanticsWhenDenied() {
        when(rateLimiter.tryConsume("192.0.2.1")).thenReturn(RateLimitDecision.deny(1500));
        MockHttpServletRequest request = post();
        request.setRemoteAddr("192.0.2.1");

        // 1500ms 向上取整为 2 秒：Retry-After 是整数秒，宁可让客户端多等 500ms，
        // 也不能返回 1 秒后客户端重试仍差半个令牌再吃一次 429。
        assertThatThrownBy(() ->
                interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(exception -> assertThat(
                        ((RateLimitExceededException) exception).retryAfterSeconds()).isEqualTo(2));
        assertThat(meterRegistry.counter("api.ratelimit.denied").count()).isEqualTo(1.0);
    }

    @Test
    void usesFirstForwardedForEntryAsClientKey() {
        when(rateLimiter.tryConsume("203.0.113.7")).thenReturn(RateLimitDecision.allow());
        MockHttpServletRequest request = post();
        // XFF 是 "client, proxy1, proxy2" 链，第一个才是原始客户端。
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        request.setRemoteAddr("10.0.0.1");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        verify(rateLimiter).tryConsume("203.0.113.7");
    }

    private MockHttpServletRequest post() {
        return new MockHttpServletRequest("POST", "/api/authorizations");
    }
}
