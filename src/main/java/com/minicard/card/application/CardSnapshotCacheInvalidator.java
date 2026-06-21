package com.minicard.card.application;

/**
 * Card snapshot cache invalidation port。
 *
 * <p>当前项目还没有 card block/unblock 写路径；未来加入时，状态变更事务提交后必须 evict，
 * 否则 stale ACTIVE snapshot 可能让刚 blocked 的卡短时间继续通过授权检查。</p>
 */
public interface CardSnapshotCacheInvalidator {

    void evictAfterCommit(String cardId);
}
