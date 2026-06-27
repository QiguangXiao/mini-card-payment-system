package com.minicard.statement.infrastructure.cache;

import java.util.UUID;

import com.minicard.statement.application.StatementReadCacheBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 基于 Redis Pub/Sub 的 statement read cache 失效广播。
 *
 * <p>关键词：Redis Pub/Sub, PUBLISH, 缓存失效广播, fan-out, cache invalidation,
 * at-most-once, パブサブ。</p>
 *
 * <p>只在 {@code statement.read-cache.broadcast.enabled=true} 时装配；否则用
 * {@link NoOpStatementReadCacheBroadcaster}，evict 退化成"只清本 pod L1 + 删 L2"。</p>
 *
 * <p>为什么是 Redis Pub/Sub 而不是 Kafka：缓存失效要的是<strong>广播给所有 pod</strong>，而 Kafka
 * 消费者组是竞争消费（一条消息只有一个 pod 收到），要广播得给每个 pod 配唯一 group，别扭且慢。
 * Redis Pub/Sub 天生广播、亚毫秒、fire-and-forget，正好配 L1 TTL 兜底。详见
 * docs/cache-invalidation-broadcast-cn.md。</p>
 */
@Component
@ConditionalOnProperty(prefix = "statement.read-cache.broadcast", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class RedisStatementReadCacheBroadcaster implements StatementReadCacheBroadcaster {

    // 频道名带 cache 版本号，必须与 L2 key 的 CACHE_NAME 对齐：换 read model schema 时一起换，
    // 避免新旧 pod 订阅同一频道却用不兼容的 value contract。
    public static final String EVICT_CHANNEL = "mini-card:cache-evict:statement-read-model-v1";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void broadcastEvict(UUID statementId) {
        try {
            // convertAndSend = Redis PUBLISH。StringRedisTemplate 用 StringRedisSerializer，
            // 所以 channel 和 message body 都是裸 UTF-8 字符串，订阅者直接按字符串解析即可。
            // Redis Pub/Sub 是 at-most-once、无持久化：订阅者此刻断连就收不到，所以必须有 L1 TTL 兜底。
            redisTemplate.convertAndSend(EVICT_CHANNEL, statementId.toString());
        } catch (RuntimeException exception) {
            // 广播发生在 after-commit，业务已提交成功。广播失败不能反过来影响请求，
            // 其他 pod 退回 local-ttl 兜底即可，所以这里只告警不抛。
            log.warn("statement_read_cache_evict_broadcast_failed statementId={} fallback=local_ttl",
                    statementId, exception);
        }
    }
}
