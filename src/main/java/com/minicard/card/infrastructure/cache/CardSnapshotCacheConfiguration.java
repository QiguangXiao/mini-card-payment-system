package com.minicard.card.infrastructure.cache;

import com.minicard.card.application.CardSnapshot;
import com.minicard.infrastructure.cache.SnapshotCache;
import com.minicard.infrastructure.cache.SnapshotCacheFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Card snapshot cache wiring。
 *
 * <p>关键词：卡片缓存装配, Bean 配置, Card snapshot cache,
 * cache name versioning, Bean wiring, Bean定義(Beanていぎ),
 * カードキャッシュ。</p>
 *
 * <p>Card id 是外部请求入口字段，用它作为 cache key；cache name 带版本号，方便以后字段变化时切 key。</p>
 */
@Configuration
public class CardSnapshotCacheConfiguration {

    public static final String CACHE_NAME = "card-snapshot-v1";

    // 显式 Bean name 用来区分多个 SnapshotCache<String, ?>。
    // 如果只靠泛型，Spring 运行期类型擦除后可能难以稳定区分 card/statement 两个 cache bean。
    @Bean(name = "cardSnapshotCache")
    public SnapshotCache<String, CardSnapshot> cardSnapshotCache(
            SnapshotCacheFactory cacheFactory
    ) {
        // 第三个参数是 key normalizer。这里 cardId 本身就是稳定 key；
        // 如果未来 key 要大小写归一化或加租户前缀，应集中写在这里，而不是散在 repository 里。
        return cacheFactory.create(CACHE_NAME, CardSnapshot.class, cardId -> cardId);
    }
}
