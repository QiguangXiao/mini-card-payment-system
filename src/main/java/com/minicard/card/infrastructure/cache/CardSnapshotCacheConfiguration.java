package com.minicard.card.infrastructure.cache;

import com.minicard.card.application.CardSnapshot;
import com.minicard.infrastructure.cache.SnapshotCache;
import com.minicard.infrastructure.cache.SnapshotCacheFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Card snapshot cache wiring。
 *
 * <p>Card id 是外部请求入口字段，用它作为 cache key；cache name 带版本号，方便以后字段变化时切 key。</p>
 */
@Configuration
public class CardSnapshotCacheConfiguration {

    public static final String CACHE_NAME = "card-snapshot-v1";

    @Bean(name = "cardSnapshotCache")
    public SnapshotCache<String, CardSnapshot> cardSnapshotCache(
            SnapshotCacheFactory cacheFactory
    ) {
        return cacheFactory.create(CACHE_NAME, CardSnapshot.class, cardId -> cardId);
    }
}
