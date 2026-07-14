package com.minicard.statement.application.read;

import java.util.UUID;

/**
 * Statement read cache 跨 pod L1 失效广播 port。
 *
 * <p>关键词：缓存失效广播, 跨实例失效, Redis Pub/Sub, cache invalidation broadcast,
 * cross-pod L1 eviction, キャッシュ無効化通知(キャッシュむこうかつうち)。</p>
 *
 * <p>背景：{@link StatementReadService#evictAfterCommit} 的本地 evict 只清掉<strong>本 pod</strong>
 * 的 Caffeine L1，其他 pod 的 L1 仍留旧值最长一个 {@code local-ttl}。这个 port 负责把"某 statement
 * 已失效"广播给所有 pod，让它们清各自的 L1，把跨 pod L1 stale 窗口从 {@code local-ttl} 压到一次广播延迟。</p>
 *
 * <p>为什么抽成 port：application 层只表达"广播失效"这个意图，不依赖 Redis/Kafka 等具体广播实现。
 * 默认是 no-op（单实例/测试无需广播，L1 TTL 兜底）；显式开启后才走 Redis Pub/Sub 实现。</p>
 *
 * <p>语义约定：广播是 <strong>best-effort</strong>。它对应 Redis Pub/Sub 的 at-most-once——订阅者此刻
 * 断连就漏收，漏收时退回 L1 TTL 兜底。因此实现里广播失败只告警、不抛异常，更不能反过来影响已提交的业务。</p>
 */
public interface StatementReadCacheBroadcaster {

    /**
     * 广播"该 statement 的 read model 已失效，请各 pod 清本地 L1"。
     *
     * <p>注意接收侧只能做 L1-only 失效（见 {@link StatementReadService#invalidateLocal}），
     * 绝不能再触发删 L2 + 再广播，否则会形成 pod 间互相广播的风暴。</p>
     */
    void broadcastEvict(UUID statementId);
}
