package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.minicard.statement.application.StatementBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * StatementBatchRepository 的 MyBatis 实现。
 *
 * <p>关键词：批处理仓储, UUID 转换, MyBatis adapter, statement batch repository,
 * persistence adapter, 対象口座検索(たいしょうこうざけんさく),
 * 永続化アダプター(えいぞくかアダプター)。</p>
 *
 * <p>Repository 层把 mapper 的 String id 转回 UUID，让 application service 不暴露数据库表示细节。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisStatementBatchRepository implements StatementBatchRepository {

    /** MyBatis mapper 只负责 SQL，repository 负责类型转换和接口适配。 */
    private final StatementBatchMapper mapper;

    /**
     * 查询本轮 statement batch 的候选账户。
     */
    @Override
    public List<UUID> findCreditAccountIdsWithUnbilledPostedTransactions(
            Instant periodStartInclusive,
            Instant periodEndExclusive,
            int limit
    ) {
        // Stream + UUID::fromString 是 Java method reference；这里是纯类型转换，不做业务判断。
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
