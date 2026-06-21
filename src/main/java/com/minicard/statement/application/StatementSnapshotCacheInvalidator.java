package com.minicard.statement.application;

import java.util.UUID;

/**
 * Statement snapshot cache invalidation port。
 *
 * <p>关键词：账单缓存失效, 还款后失效, Statement snapshot invalidation,
 * after commit, stale read model, 請求キャッシュ失効(せいきゅうキャッシュしっこう),
 * コミット後処理(コミットごしょり)。</p>
 *
 * <p>写路径只知道 statement read model 需要失效，不依赖 Redis/Caffeine 细节。</p>
 */
public interface StatementSnapshotCacheInvalidator {

    /**
     * 在当前 transaction commit 后 evict。
     *
     * <p>如果提交前清缓存，另一个 GET 可能读到尚未提交的旧 DB 值并重新写入缓存，
     * 造成 stale read model；after-commit evict 可以避开这个竞态。</p>
     */
    void evictAfterCommit(UUID statementId);
}
