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

    // 显式 Bean name 配合 @Qualifier("statementSnapshotCache") 使用。
    // 如果只靠 SnapshotCache<UUID, StatementReadModel> 泛型，运行期类型擦除会让多个 cache bean 更难区分。
    @Bean(name = "statementSnapshotCache")
    public SnapshotCache<UUID, StatementReadModel> statementSnapshotCache(
            SnapshotCacheFactory cacheFactory
    ) {
        // UUID::toString 是 key normalizer，确保 Redis key 里的 UUID 格式集中在 cache wiring。
        return cacheFactory.create(CACHE_NAME, StatementReadModel.class, UUID::toString);
    }
}
