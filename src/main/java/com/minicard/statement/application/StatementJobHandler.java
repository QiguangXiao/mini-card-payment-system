package com.minicard.statement.application;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

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
 * 并锁定 credit account。这样一个分片（可能上千账户）不会长时间持有一组账户锁。
 * cycle 信息（period/dueDate）直接来自 job，handler 不再读取 parent batch。</p>
 *
 * <p>单个账户失败被隔离：rejected（无可出账交易）算 skipped，retryable（如 ledger 未就绪）
 * 算 failed，其余异常也算 failed。整个分片不会因为一个坏账户而全部回滚。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementJobHandler {

    private final StatementBillingRepository billingRepository;
    private final StatementGenerationService statementGenerationService;
    private final StatementProperties properties;

    /**
     * 执行一个 statement job 分片，为分片内每个账户生成账单并汇总处理结果。
     */
    public StatementJobExecutionResult handle(StatementJob job) {
        ZoneId zone = ZoneId.of(properties.batch().zone());
        List<UUID> accountIds = billingRepository.findAccountIdsForJob(
                job.periodStart().atStartOfDay(zone).toInstant(),
                job.periodEnd().plusDays(1).atStartOfDay(zone).toInstant(),
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
                        job.periodStart(),
                        job.periodEnd(),
                        job.dueDate()
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
