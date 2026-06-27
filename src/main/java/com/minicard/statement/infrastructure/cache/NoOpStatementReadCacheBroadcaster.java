package com.minicard.statement.infrastructure.cache;

import java.util.UUID;

import com.minicard.statement.application.StatementReadCacheBroadcaster;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 默认的 no-op 失效广播器。
 *
 * <p>关键词：no-op broadcaster, 默认实现, 单实例部署, feature toggle。</p>
 *
 * <p>未开启 {@code statement.read-cache.broadcast.enabled} 时装配它，于是 evict 退化成原有行为
 * （只清本 pod L1 + 删共享 L2）。单实例部署或测试不需要 Pub/Sub，跨 pod L1 stale 由 local-ttl 兜底。</p>
 *
 * <p>用一个显式 no-op bean 而不是允许 broadcaster 为 null：让 {@code StatementReadService} 的依赖
 * 永远非空，evict 路径无需写 null 判断，行为也更好测试和解释。</p>
 */
@Component
@ConditionalOnProperty(prefix = "statement.read-cache.broadcast", name = "enabled",
        havingValue = "false", matchIfMissing = true)
public class NoOpStatementReadCacheBroadcaster implements StatementReadCacheBroadcaster {

    @Override
    public void broadcastEvict(UUID statementId) {
        // 故意不做任何事：未开启广播时，跨 pod L1 一致性由 local-ttl 兜底。
    }
}
