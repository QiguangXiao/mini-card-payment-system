package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

import com.minicard.authorization.domain.Money;
import com.minicard.statement.application.StatementBillingRepository;
import com.minicard.statement.domain.StatementLineSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * StatementBillingRepository 的 MyBatis adapter。
 *
 * <p>关键词：账单候选仓储, Ledger join, BILLED 标记,
 * statement billing repository, append-only ledger, 請求対象リポジトリ(せいきゅうたいしょうリポジトリ)。</p>
 *
 * <p>LedgerEntry 保持 append-only；这里只更新 CardTransaction 的 billing marker。
 * ledger_entry_id 通过 statement_lines 唯一约束防止重复出账。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisStatementBillingRepository implements StatementBillingRepository {

    private final StatementBillingMapper mapper;

    @Override
    public long countBillableAccounts(
            Instant periodStartInclusive,
            Instant periodEndExclusive
    ) {
        return mapper.countBillableAccounts(periodStartInclusive, periodEndExclusive);
    }

    @Override
    public List<UUID> findAccountIdsForJob(
            Instant periodStartInclusive,
            Instant periodEndExclusive,
            int shardNo,
            int shardCount
    ) {
        return mapper.findAccountIdsForJob(
                        periodStartInclusive,
                        periodEndExclusive,
                        shardNo,
                        shardCount
                )
                .stream()
                .map(UUID::fromString)
                .toList();
    }

    @Override
    public boolean existsUnbilledPostedTransactionMissingLedger(
            UUID creditAccountId,
            Instant periodStartInclusive,
            Instant periodEndExclusive
    ) {
        return mapper.countUnbilledPostedTransactionsMissingLedger(
                creditAccountId.toString(),
                periodStartInclusive,
                periodEndExclusive
        ) > 0;
    }

    @Override
    public List<StatementLineSource> findBillableLineSourcesForUpdate(
            UUID creditAccountId,
            Instant periodStartInclusive,
            Instant periodEndExclusive
    ) {
        return mapper.findBillableLineSourcesForUpdate(
                        creditAccountId.toString(),
                        periodStartInclusive,
                        periodEndExclusive
                )
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void markCardTransactionsBilled(
            UUID statementId,
            List<StatementLineSource> lineSources,
            Instant billedAt
    ) {
        if (lineSources.isEmpty()) {
            return;
        }
        List<String> cardTransactionIds = lineSources.stream()
                .map(source -> source.cardTransactionId().toString())
                .toList();
        int updated = mapper.markCardTransactionsBilled(
                statementId.toString(),
                cardTransactionIds,
                billedAt
        );
        if (updated != cardTransactionIds.size()) {
            // 这里必须 fail fast：statement_lines 已经准备写入，如果 card_transactions 没有全部标记 BILLED，
            // rollback 比留下“账单有 line 但交易仍 UNBILLED”的不一致状态更安全。
            throw new IllegalStateException(
                    "expected to mark " + cardTransactionIds.size()
                            + " card transactions billed but updated " + updated
            );
        }
    }

    private StatementLineSource toDomain(StatementLineSourceRow row) {
        return new StatementLineSource(
                UUID.fromString(row.cardTransactionId()),
                UUID.fromString(row.ledgerEntryId()),
                row.networkTransactionId(),
                UUID.fromString(row.authorizationId()),
                row.cardId(),
                new Money(row.amount(), Currency.getInstance(row.currency())),
                row.postedAt()
        );
    }
}
