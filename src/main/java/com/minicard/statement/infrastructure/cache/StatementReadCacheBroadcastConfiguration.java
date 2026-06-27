package com.minicard.statement.infrastructure.cache;

import com.minicard.statement.application.StatementReadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * statement read cache 失效广播的<strong>订阅侧</strong>装配。
 *
 * <p>关键词：RedisMessageListenerContainer, ChannelTopic, Pub/Sub subscribe,
 * 订阅容器, リスナーコンテナ。</p>
 *
 * <p>仅在 {@code statement.read-cache.broadcast.enabled=true} 时存在——关闭时既没有 Redis 广播器，
 * 也没有订阅容器，整条广播链路完全不装配，单实例/测试零负担。</p>
 *
 * <p>{@link RedisMessageListenerContainer} 是 Spring Data Redis 管理 Pub/Sub 订阅的标准方式：
 * 它在一条<strong>专用连接</strong>上 SUBSCRIBE 指定频道，并用自己的线程池把消息回调给 listener，
 * 既不占业务请求线程，也独立于 publish 用的连接。容器随 Spring 生命周期启动/优雅关闭。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "statement.read-cache.broadcast", name = "enabled", havingValue = "true")
public class StatementReadCacheBroadcastConfiguration {

    @Bean
    public StatementReadCacheEvictListener statementReadCacheEvictListener(
            StatementReadService statementReadService
    ) {
        return new StatementReadCacheEvictListener(statementReadService);
    }

    @Bean
    public RedisMessageListenerContainer statementReadCacheEvictContainer(
            RedisConnectionFactory connectionFactory,
            StatementReadCacheEvictListener listener
    ) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 订阅与广播器同一个频道常量，保证 publish 与 subscribe 对齐。
        container.addMessageListener(
                listener,
                new ChannelTopic(RedisStatementReadCacheBroadcaster.EVICT_CHANNEL)
        );
        return container;
    }
}
