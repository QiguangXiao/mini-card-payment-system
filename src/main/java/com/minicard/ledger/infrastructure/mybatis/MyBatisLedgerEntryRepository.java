package com.minicard.ledger.infrastructure.mybatis;

import com.minicard.ledger.domain.LedgerEntry;
import com.minicard.ledger.domain.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * LedgerEntryRepository 的 MyBatis 实现。
 *
 * <p>关键词：账本持久化, 幂等追加, DuplicateKeyException,
 * ledger persistence, append if absent, 重複キー(じゅうふくキー)。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisLedgerEntryRepository implements LedgerEntryRepository {

    private final LedgerEntryMapper mapper;

    @Override
    public boolean appendIfAbsent(LedgerEntry entry) {
        try {
            return mapper.insert(new LedgerEntryRow(
                    entry.id().toString(),
                    entry.sourceEventId().toString(),
                    entry.entryType().name(),
                    entry.direction().name(),
                    entry.sourceType().name(),
                    entry.sourceId().toString(),
                    entry.creditAccountId().toString(),
                    entry.amount().amount(),
                    entry.amount().currency().getCurrencyCode(),
                    entry.occurredAt(),
                    entry.createdAt()
            )) == 1;
        } catch (DuplicateKeyException exception) {
            // Kafka at-least-once 或人工 replay 下，重复 source event 不应该生成第二条分录。
            return false;
        }
    }
}
