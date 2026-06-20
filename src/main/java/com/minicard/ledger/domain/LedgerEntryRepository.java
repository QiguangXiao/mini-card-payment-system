package com.minicard.ledger.domain;

/**
 * LedgerEntry repository 端口。
 *
 * <p>关键词：账本仓储, 幂等追加, append-only ledger,
 * ledger repository, idempotency, 仕訳リポジトリ(しわけリポジトリ)。</p>
 */
public interface LedgerEntryRepository {

    /**
     * 追加账本分录。
     *
     * <p>sourceEventId + entryType 的唯一约束是第二道 idempotency 防线：
     * 即使 Inbox 边界未来变化，重复 Kafka event 也不能创建重复 ledger entry。</p>
     */
    boolean appendIfAbsent(LedgerEntry entry);
}
