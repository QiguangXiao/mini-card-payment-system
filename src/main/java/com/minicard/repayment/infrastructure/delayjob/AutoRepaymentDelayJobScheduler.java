package com.minicard.repayment.infrastructure.delayjob;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.minicard.delayjob.DelayJob;
import com.minicard.delayjob.DelayJobRepository;
import com.minicard.delayjob.DelayJobType;
import com.minicard.statement.application.StatementDueJobScheduler;
import com.minicard.statement.domain.Statement;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 把 statement due-date 自动扣款计划写入通用 delay_jobs 表。
 */
@Component
@RequiredArgsConstructor
public class AutoRepaymentDelayJobScheduler implements StatementDueJobScheduler {

    private static final String AGGREGATE_TYPE = "Statement";

    private final DelayJobRepository delayJobRepository;
    private final Clock clock;

    @Override
    public void scheduleAutoRepayment(Statement statement) {
        Instant scheduledAt = statement.dueDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant now = Instant.now(clock);

        // DelayJob 与 statement 生成在同一个 MySQL transaction boundary 内提交。
        // 如果 statement rollback，自动扣款计划也 rollback；如果重试生成同一期账单，
        // unique(job_type, aggregate_type, aggregate_id) 保证一个 statement 只有一个扣款计划。
        delayJobRepository.insertIfAbsent(DelayJob.pending(
                UUID.randomUUID(),
                DelayJobType.AUTO_REPAYMENT,
                AGGREGATE_TYPE,
                statement.id().toString(),
                scheduledAt,
                now
        ));
    }
}
