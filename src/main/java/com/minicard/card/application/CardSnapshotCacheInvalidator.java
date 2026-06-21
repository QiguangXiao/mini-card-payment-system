package com.minicard.card.application;

/**
 * Card snapshot cache invalidation port。
 *
 * <p>关键词：卡片缓存失效, 状态变更, Card snapshot invalidation,
 * after commit, stale ACTIVE, キャッシュ失効(キャッシュしっこう),
 * カード停止(カードていし)。</p>
 *
 * <p>当前项目还没有 card block/unblock 写路径；未来加入时，状态变更事务提交后必须 evict，
 * 否则 stale ACTIVE snapshot 可能让刚 blocked 的卡短时间继续通过授权检查。</p>
 */
public interface CardSnapshotCacheInvalidator {

    void evictAfterCommit(String cardId);
}
