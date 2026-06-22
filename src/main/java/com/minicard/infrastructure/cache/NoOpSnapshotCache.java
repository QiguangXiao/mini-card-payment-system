package com.minicard.infrastructure.cache;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 空实现 snapshot cache。
 *
 * <p>关键词：空缓存, 降级开关, snapshot cache, no-op cache,
 * feature toggle, キャッシュ無効化(キャッシュむこうか),
 * 代替実装(だいたいじっそう)。</p>
 */
final class NoOpSnapshotCache<K, V> implements SnapshotCache<K, V> {

    @Override
    public V get(K key, Supplier<V> loader) {
        Objects.requireNonNull(key, "cache key must not be null");
        // NoOp 仍然执行 loader，保持“关闭缓存时仍从 source of truth 读取”的语义。
        // 如果这里直接返回 null，调用方会把 cache disabled 误认为数据不存在。
        return Objects.requireNonNull(loader, "cache loader must not be null").get();
    }

    @Override
    public void evict(K key) {
        Objects.requireNonNull(key, "cache key must not be null");
    }
}
