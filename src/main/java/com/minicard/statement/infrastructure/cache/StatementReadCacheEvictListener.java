package com.minicard.statement.infrastructure.cache;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.minicard.statement.application.StatementReadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

/**
 * Redis Pub/Sub 订阅者：收到某 statement 的失效广播后，只清本 pod 的 L1。
 *
 * <p>关键词：Redis Pub/Sub subscriber, MessageListener, L1 invalidation,
 * cross-pod cache eviction, 購読者。</p>
 *
 * <p>它实现 Spring Data Redis 的 {@link MessageListener}，由 {@code RedisMessageListenerContainer}
 * 在一条独立订阅连接、独立线程上回调。每个 pod 都订阅同一频道，所以一条 PUBLISH 会扇出给所有 pod。</p>
 *
 * <p><strong>关键约束</strong>：这里只调用 {@link StatementReadService#invalidateLocal}（L1-only）。
 * 绝不能调用会再删 L2 并再次广播的 evict，否则 N 个 pod 会就同一条失效互相广播，形成风暴。
 * 失效广播必须单向：写侧 evict 负责"删 L2 + 广播"，订阅侧只负责"清自己的 L1"。</p>
 */
@RequiredArgsConstructor
@Slf4j
public class StatementReadCacheEvictListener implements MessageListener {

    private final StatementReadService statementReadService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        // StringRedisTemplate 用 StringRedisSerializer 发布，所以 body 是裸 UTF-8 的 statement id 字符串。
        String body = new String(message.getBody(), StandardCharsets.UTF_8).trim();
        try {
            UUID statementId = UUID.fromString(body);
            statementReadService.invalidateLocal(statementId);
        } catch (IllegalArgumentException exception) {
            // 毒消息不该毒死订阅者：记录后丢弃，本 pod 退回 local-ttl 兜底。
            // 广播本就是 best-effort，单条坏消息不影响正确性。
            log.warn("statement_read_cache_evict_message_invalid body={} action=ignore", body, exception);
        }
    }
}
