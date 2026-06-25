package com.minicard.statement.application;

import java.util.UUID;

import com.minicard.infrastructure.cache.SnapshotCache;
import com.minicard.infrastructure.cache.TransactionAwareSnapshotCacheEvictor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Statement 查询 service。
 *
 * <p>关键词：账单查询服务, 缓存边界, Statement read model service,
 * cache boundary, after-commit eviction, 請求照会(せいきゅうしょうかい),
 * キャッシュ境界(キャッシュきょうかい)。</p>
 *
 * <p>这里是 GET /api/statements/{id} 的 cache boundary：Controller 不知道 L1/L2，
 * StatementGenerationService 仍然负责从 repository 读取 aggregate，cache 只保存
 * presentation-friendly read model。</p>
 */
@Service
public class StatementReadService {

    private final StatementGenerationService statementGenerationService;
    private final SnapshotCache<UUID, StatementReadModel> statementSnapshotCache;
    private final TransactionAwareSnapshotCacheEvictor snapshotCacheEvictor;

    public StatementReadService(
            StatementGenerationService statementGenerationService,
            @Qualifier("statementSnapshotCache")
            SnapshotCache<UUID, StatementReadModel> statementSnapshotCache,
            TransactionAwareSnapshotCacheEvictor snapshotCacheEvictor
    ) {
        this.statementGenerationService = statementGenerationService;
        this.statementSnapshotCache = statementSnapshotCache;
        this.snapshotCacheEvictor = snapshotCacheEvictor;
    }

    public StatementReadModel get(UUID id) {
        // 只缓存 GET read model。NoSuchElementException 不做 negative cache，
        // 防止先查 404 后很快生成同 id 测试数据时被短期错误缓存挡住。
        return statementSnapshotCache.get(
                id,
                () -> StatementReadModel.from(statementGenerationService.get(id))
        );
    }

    public void evictAfterCommit(UUID statementId) {
        snapshotCacheEvictor.evictAfterCommit(statementSnapshotCache, statementId);
    }
}
