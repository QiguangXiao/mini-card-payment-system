package com.minicard.card.infrastructure.cache;

import java.util.Optional;

import com.minicard.card.application.CardSnapshot;
import com.minicard.card.application.CardSnapshotCacheInvalidator;
import com.minicard.card.domain.Card;
import com.minicard.card.domain.CardRepository;
import com.minicard.card.infrastructure.mybatis.MyBatisCardRepository;
import com.minicard.infrastructure.cache.SnapshotCache;
import com.minicard.infrastructure.cache.TransactionAwareSnapshotCacheEvictor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * CardRepository 的 cache decorator。
 *
 * <p>关键词：卡片缓存仓储, 仓储装饰器, Card snapshot cache,
 * repository decorator, negative cache, リポジトリ装飾(リポジトリそうしょく),
 * カード参照(カードさんしょう)。</p>
 *
 * <p>Card snapshot 是授权链路里的低频变化 reference data：card 是否存在、是否 blocked/expired、
 * 以及它指向哪个 credit account。缓存它可以减少高频 authorization/posting 对 cards 表的重复读取；
 * 但它仍然会影响授权决策，所以 TTL 比 statement read model 更短，并且不做 negative cache。</p>
 */
// @Primary 让业务注入 CardRepository 时默认拿到 cache decorator，而不是绕过缓存直连 MyBatis。
// 如果省掉它，Spring 会看到两个 CardRepository bean，启动时出现 ambiguous dependency。
@Primary
@Repository
public class CachedCardRepository implements CardRepository, CardSnapshotCacheInvalidator {

    private final MyBatisCardRepository delegate;
    private final SnapshotCache<String, CardSnapshot> cardSnapshotCache;
    private final TransactionAwareSnapshotCacheEvictor snapshotCacheEvictor;

    public CachedCardRepository(
            MyBatisCardRepository delegate,
            @Qualifier("cardSnapshotCache") SnapshotCache<String, CardSnapshot> cardSnapshotCache,
            TransactionAwareSnapshotCacheEvictor snapshotCacheEvictor
    ) {
        // @Qualifier 明确选择 card-snapshot-v1；否则 statementSnapshotCache 也是 SnapshotCache，会造成注入歧义。
        this.delegate = delegate;
        this.cardSnapshotCache = cardSnapshotCache;
        this.snapshotCacheEvictor = snapshotCacheEvictor;
    }

    @Override
    public Optional<Card> findById(String cardId) {
        // 不缓存 card-not-found：发卡/补数据后，未知卡可能很快变成有效卡。
        // 如果做 negative cache，高并发错误请求会轻松，但会增加新卡可见性的 stale risk。
        CardSnapshot snapshot = cardSnapshotCache.get(
                cardId,
                () -> delegate.findById(cardId)
                        .map(CardSnapshot::from)
                        .orElse(null)
        );
        return Optional.ofNullable(snapshot).map(CardSnapshot::toCard);
    }

    @Override
    public void evictAfterCommit(String cardId) {
        snapshotCacheEvictor.evictAfterCommit(cardSnapshotCache, cardId);
    }
}
