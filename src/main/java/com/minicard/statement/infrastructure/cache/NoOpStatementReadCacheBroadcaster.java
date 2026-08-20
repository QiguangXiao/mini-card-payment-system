package com.minicard.statement.infrastructure.cache;

import java.util.UUID;

import com.minicard.statement.application.read.StatementReadCacheBroadcaster;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 关闭广播时使用的 no-op 失效广播器（null object）。
 *
 * <p>关键词：no-op broadcaster, null object, 单实例部署, feature toggle。</p>
 *
 * <p>广播默认开启；只有显式配置 {@code statement.read-cache.broadcast.enabled=false} 时才装配它，
 * evict 退化成"只清本 pod L1 + 写 L2 墓碑"，跨 pod L1 stale 由 local-ttl 兜底。
 * 当前唯一的关闭场景是 MySQL 集成测试（{@code application-it.yml}）：那里不起 Redis 容器，
 * 开着广播只会让 {@code RedisMessageListenerContainer} 在启动期反复重连刷日志。</p>
 *
 * <p>用一个显式 no-op bean 而不是允许 broadcaster 为 null：让 {@code StatementReadService} 的依赖
 * 永远非空，evict 路径无需写 null 判断，行为也更好测试和解释。</p>
 */
@Component
@ConditionalOnProperty(prefix = "statement.read-cache.broadcast", name = "enabled",
        havingValue = "false")
public class NoOpStatementReadCacheBroadcaster implements StatementReadCacheBroadcaster {

    @Override
    public void broadcastEvict(UUID statementId) {
        // 故意不做任何事：未开启广播时，跨 pod L1 一致性由 local-ttl 兜底。
    }
}
