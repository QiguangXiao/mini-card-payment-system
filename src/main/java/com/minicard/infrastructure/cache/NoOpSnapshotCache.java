package com.minicard.infrastructure.cache;

import java.util.Objects;
import java.util.function.Supplier;

final class NoOpSnapshotCache<K, V> implements SnapshotCache<K, V> {

    @Override
    public V get(K key, Supplier<V> loader) {
        Objects.requireNonNull(key, "cache key must not be null");
        return Objects.requireNonNull(loader, "cache loader must not be null").get();
    }

    @Override
    public void evict(K key) {
        Objects.requireNonNull(key, "cache key must not be null");
    }
}
