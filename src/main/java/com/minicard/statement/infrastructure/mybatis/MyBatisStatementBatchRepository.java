package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.minicard.statement.application.StatementBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MyBatisStatementBatchRepository implements StatementBatchRepository {

    private final StatementBatchMapper mapper;

    @Override
    public List<UUID> findCreditAccountIdsWithUnbilledPostedTransactions(
            Instant periodStartInclusive,
            Instant periodEndExclusive,
            int limit
    ) {
        return mapper.findCreditAccountIdsWithUnbilledPostedTransactions(
                        periodStartInclusive,
                        periodEndExclusive,
                        limit
                )
                .stream()
                .map(UUID::fromString)
                .toList();
    }
}
