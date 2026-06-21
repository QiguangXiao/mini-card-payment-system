package com.minicard.statement.infrastructure.cache;

import java.util.UUID;

import com.minicard.infrastructure.cache.ReadModelCache;
import com.minicard.infrastructure.cache.ReadModelCacheFactory;
import com.minicard.statement.application.StatementReadModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Statement read model cache wiring。
 *
 * <p>cache name 带版本号；如果 response/read model 字段发生不兼容变化，改名即可自然切换 Redis key。</p>
 */
@Configuration
public class StatementReadModelCacheConfiguration {

    public static final String CACHE_NAME = "statement-response-v1";

    @Bean(name = "statementReadModelCache")
    public ReadModelCache<UUID, StatementReadModel> statementReadModelCache(
            ReadModelCacheFactory cacheFactory
    ) {
        return cacheFactory.create(CACHE_NAME, StatementReadModel.class, UUID::toString);
    }
}
