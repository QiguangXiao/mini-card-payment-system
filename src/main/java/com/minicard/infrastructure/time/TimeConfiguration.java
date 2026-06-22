package com.minicard.infrastructure.time;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 时间基础设施配置，统一提供可注入的 Clock。
 *
 * <p>关键词：统一时钟, UTC, 可测试时间, Clock bean,
 * testable time, timezone, 時刻設定(じこくせってい),
 * 協定世界時(きょうていせかいじ)。</p>
 *
 * <p>interview重点：业务代码不直接调用 Instant.now()，测试可以注入固定时间，
 * 分布式系统也避免依赖单台机器的本地时区。</p>
 */
@Configuration
public class TimeConfiguration {

    /**
     * 提供全局 UTC clock。
     */
    @Bean
    public Clock clock() {
        // Domain timestamp 使用 UTC，避免 persistence 和分布式处理依赖本地 timezone。
        // 如果业务代码直接 Instant.now()，单元测试无法固定时间，跨时区日志/数据也更难对齐。
        return Clock.systemUTC();
    }
}
