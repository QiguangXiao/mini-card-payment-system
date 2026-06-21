package com.minicard.statement.infrastructure.cache;

import java.util.UUID;

import com.minicard.infrastructure.cache.SnapshotCache;
import com.minicard.infrastructure.cache.SnapshotCacheFactory;
import com.minicard.statement.application.StatementReadModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Statement snapshot cache wiring。
 *
 * <p>关键词：账单缓存装配, 查询快照, Statement read model cache,
 * cache name versioning, Bean wiring, 請求キャッシュ(せいきゅうキャッシュ),
 * Bean定義(Beanていぎ)。</p>
 *
 * <p>cache name 带版本号；如果 response/read model 字段发生不兼容变化，改名即可自然切换 Redis key。</p>
 */
@Configuration
public class StatementSnapshotCacheConfiguration {

    public static final String CACHE_NAME = "statement-read-model-v1";

    @Bean(name = "statementSnapshotCache")
    public SnapshotCache<UUID, StatementReadModel> statementSnapshotCache(
            SnapshotCacheFactory cacheFactory
    ) {
        return cacheFactory.create(CACHE_NAME, StatementReadModel.class, UUID::toString);
    }
}
