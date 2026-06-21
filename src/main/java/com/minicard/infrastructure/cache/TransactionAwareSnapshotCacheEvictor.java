package com.minicard.infrastructure.cache;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Snapshot cache 的 transaction-aware evict helper。
 *
 * <p>写路径改了 source of truth 后，应该在 transaction commit 后再删缓存。
 * 如果提交前 evict，其他请求可能读到旧 DB 值并重新写回 Redis，制造 stale snapshot。</p>
 */
@Component
public class TransactionAwareSnapshotCacheEvictor {

    public <K> void evictAfterCommit(SnapshotCache<K, ?> cache, K key) {
        Objects.requireNonNull(cache, "snapshot cache must not be null");
        Objects.requireNonNull(key, "snapshot cache key must not be null");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cache.evict(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cache.evict(key);
            }
        });
    }
}
