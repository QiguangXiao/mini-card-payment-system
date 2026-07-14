package com.minicard.infrastructure.web.ratelimit;

import java.time.Clock;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * API 入口限流的 bean 装配。
 *
 * <p>关键词：限流装配, 条件装配, kill switch, rate limit configuration,
 * conditional bean, 条件付きBean(じょうけんつきBean)。</p>
 *
 * <p>基础背景：{@code @Configuration} 是“集中创建一组 Spring bean”的配置类，
 * {@code @Bean} 方法的返回值会交给 Spring 容器管理。这里没有把 limiter/interceptor
 * 直接标成 {@code @Component}，因为它们是否存在取决于限流开关，不是永远必须注册的组件。</p>
 *
 * <p>测试边界：{@code @WebMvcTest} 只加载 MVC 相关组件，不会完整启动 Redis 等基础设施。
 * 如果 interceptor 被组件扫描无条件发现，所有 controller slice test 都要额外 mock Redis。
 * 因此本类负责“条件创建”，{@link RateLimitWebMvcConfiguration} 再用 {@code ObjectProvider}
 * 负责“存在才挂载”。这不是为了解决循环依赖，而是在表达一个合法的 optional bean。</p>
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
// RateLimitProperties 由主类 @ConfigurationPropertiesScan 注册，因此即使 kill switch 关闭、
// 本配置类整体不生效，properties bean 依然存在——这没有副作用，limiter/interceptor 才是条件 bean。
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
            MeterRegistry meterRegistry
    ) {
        return new AuthorizationRateLimitInterceptor(apiRateLimiter, meterRegistry);
    }
}
