package com.minicard.infrastructure.cache;

import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Snapshot cache 的 transaction-aware evict helper。
 *
 * <p>关键词：事务后失效, 缓存删除, after commit,
 * transaction synchronization, cache eviction, トランザクション後処理(あとしょり),
 * キャッシュ削除(キャッシュさくじょ)。</p>
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
            // 没有事务时只能立即 evict；例如某些手工维护或测试路径不需要 afterCommit hook。
            cache.evict(key);
            return;
        }
        // TransactionSynchronization 是 Spring 事务的 callback API。
        // 如果不用它而在写库前/事务中删除缓存，rollback 或旧 DB 回填都会制造 stale cache。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 必须等 commit 后再删；否则另一个请求可能在旧 DB 值上 rebuild cache，制造 stale snapshot。
                cache.evict(key);
            }
        });
    }
}
