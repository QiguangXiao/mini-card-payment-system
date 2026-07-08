package com.minicard.infrastructure.web.ratelimit;

import java.time.Clock;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * API 入口限流的 bean 装配。
 *
 * <p>关键词：限流装配, 条件装配, kill switch, rate limit configuration,
 * conditional bean, 条件付きBean(じょうけんつきBean)。</p>
 *
 * <p>限流器和拦截器都在这里用 @Bean 组装，<strong>刻意不给它们标 @Component</strong>：
 * {@code @WebMvcTest} 切片会扫描 HandlerInterceptor/WebMvcConfigurer 类型的组件，
 * 如果拦截器是被扫描的 @Component，每个 controller 切片测试都会因为它的
 * StringRedisTemplate 依赖不存在而无法启动。收进普通 @Configuration 后，
 * 切片测试天然拿不到这些 bean，注册侧再用 ObjectProvider 容忍缺席
 * （见 {@link RateLimitWebMvcConfiguration}）。</p>
 */
@Configuration
// matchIfMissing=true：默认开启；api.rate-limit.enabled=false 是显式 kill switch。
// bean 级条件意味着关闭后连 interceptor 都不注册（零开销），代价是切换需要重启——
// 生产里若要不重启切换，应把开关下沉到 interceptor 内每请求判断，或接配置中心。
@ConditionalOnProperty(
        prefix = "api.rate-limit",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    public RedisTokenBucketRateLimiter apiRateLimiter(
            StringRedisTemplate redisTemplate,
            Clock clock,
            RateLimitProperties properties,
            MeterRegistry meterRegistry
    ) {
        return new RedisTokenBucketRateLimiter(redisTemplate, clock, properties, meterRegistry);
    }

    @Bean
    public AuthorizationRateLimitInterceptor authorizationRateLimitInterceptor(
            RedisTokenBucketRateLimiter apiRateLimiter,
            RateLimitProperties properties,
            MeterRegistry meterRegistry
    ) {
        return new AuthorizationRateLimitInterceptor(apiRateLimiter, properties, meterRegistry);
    }
}
