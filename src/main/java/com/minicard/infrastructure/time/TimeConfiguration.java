package com.minicard.infrastructure.time;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间基础设施配置，统一提供可注入的 Clock。
 *
 * <p>面试重点：业务代码不直接调用 Instant.now()，测试可以注入固定时间，
 * 分布式系统也避免依赖单台机器的本地时区。</p>
 */
@Configuration
public class TimeConfiguration {

    @Bean
    public Clock clock() {
        // Domain timestamp 使用 UTC，避免 persistence 和分布式处理依赖本地 timezone。
        return Clock.systemUTC();
    }
}
