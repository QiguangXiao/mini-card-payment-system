package com.minicard.statement.application;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.minicard.statement.domain.StatementBatch;
import com.minicard.statement.domain.StatementJob;
import com.minicard.statement.domain.StatementJobExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 处理一个 statement_job 分片内的账户。
 *
 * <p>关键词：账单任务处理, 分片账户, 小事务, statement job handler,
 * account-level transaction, 請求ジョブ処理(せいきゅうジョブしょり)。</p>
 *
 * <p>Job handler 不持有一个覆盖全部账户的大事务。每个 account 调用
 * StatementGenerationService.generate(...)，由该 use case 自己打开 transaction boundary
 * 并锁定 credit account。这样 1000-account job 不会长时间持有一组账户锁。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementJobHandler {

    private final StatementBatchRepository batchRepository;
    private final StatementBillingRepository billingRepository;
    private final StatementGenerationService statementGenerationService;
    private final StatementProperties properties;

    public StatementJobExecutionResult handle(StatementJob job) {
        StatementBatch batch = batchRepository.findById(job.batchId());
        ZoneId zone = ZoneId.of(properties.batch().zone());
        List<UUID> accountIds = billingRepository.findAccountIdsForJob(
                batch.periodStart().atStartOfDay(zone).toInstant(),
                batch.periodEnd().plusDays(1).atStartOfDay(zone).toInstant(),
                job.shardNo(),
                job.shardCount()
        );

        int generated = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID accountId : accountIds) {
            try {
                statementGenerationService.generate(new GenerateStatementCommand(
                        accountId,
                        batch.periodStart(),
                        batch.periodEnd(),
                        batch.dueDate()
                ));
                generated++;
            } catch (StatementGenerationException exception) {
                if (exception.retryable()) {
                    failed++;
                    log.warn(
                            "statement_account_retryable_failure jobId={} accountId={} reason={}",
                            job.id(),
                            accountId,
                            exception.getMessage()
                    );
                } else {
                    skipped++;
                    log.info(
                            "statement_account_skipped jobId={} accountId={} reason={}",
                            job.id(),
                            accountId,
                            exception.getMessage()
                    );
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn(
                        "statement_account_failed jobId={} accountId={}",
                        job.id(),
                        accountId,
                        exception
                );
            }
        }

        return new StatementJobExecutionResult(
                accountIds.size(),
                generated,
                skipped,
                failed
        );
    }
}
