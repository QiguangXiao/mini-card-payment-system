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
 * account-level transaction, CRC32 shard, stable account sharding,
 * 請求ジョブ処理(せいきゅうジョブしょり)。</p>
 *
 * <p>Job handler 不持有一个覆盖全部账户的大事务。每个 account 调用
 * StatementGenerationService.generate(...)，由该 use case 自己打开 transaction boundary
 * 并锁定 credit account。这样一个分片（可能上千账户）不会长时间持有一组账户锁。
 * cycle 信息（period/dueDate）直接来自 job，handler 不再读取 parent batch。</p>
 *
 * <p>分片规则在 SQL 层实现：{@code MOD(CRC32(credit_account_id), shardCount) = shardNo}。
 * {@code CRC32} 是 MySQL 内置的确定性 checksum 函数：同一个 {@code credit_account_id}
 * 每次都会得到同一个整数；再对 {@code shardCount} 取模，就能把账户稳定映射到
 * {@code 0..shardCount-1} 中的某一个 shard。这里按“账户”分片，而不是按“交易”分片，
 * 是为了让同一账户本账期所有消费都由同一个 job 处理，避免两个 job 同时给同一账户生成 statement。</p>
 *
 * <p>注意：handler 先拿到账户 id 列表，再逐个调用 {@link StatementGenerationService#generate}。
 * 真正锁交易、创建 {@code statements + statement_lines}、标记交易 {@code BILLED} 的动作都在
 * {@code StatementGenerationService} 内部完成；本类只负责 fan-out 和结果计数。</p>
 *
 * <p>单个账户失败被隔离：rejected（无可出账交易）算 skipped，retryable（如短暂数据库故障）
 * 算 failed，其余异常也算 failed。整个分片不会因为一个坏账户而全部回滚。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StatementJobHandler {

    /**
     * 每处理多少个账户做一次 lease 中途检查（无锁快查 claim_token）。
     * lease 被 recoverer/新 worker 接管后，旧 worker 的重叠执行窗口从"整片跑完"
     * 缩到最多这么多账户；查询本身是主键点查，摊到每账户的成本可忽略。
     * 不做成配置：这个值不需要按环境调，常量让 StatementProperties 保持稳定。
     */
    private static final int ACCOUNTS_PER_LEASE_CHECK = 100;

    private final StatementBillingRepository billingRepository;
    private final StatementGenerationService statementGenerationService;
    private final StatementJobRepository jobRepository;
    private final StatementProperties properties;

    /**
     * 执行一个 statement job 分片，为分片内每个账户生成账单并汇总处理结果。
     *
     * <p>执行阶段：</p>
     * <pre>
     * 1. 用 job.periodStart/periodEnd 转成 billing timezone 下的查询窗口
     * 2. 用 job.shardNo/shardCount 查询本 shard 负责的 account ids
     * 3. 逐账户调用 StatementGenerationService.generate(...)；
     *    每 100 个账户无锁快查一次 claim_token，lease 已被接管就提前放弃剩余账户
     * 4. 汇总 generated/skipped/failed，交给 StatementJobDispatcher finalize
     * </pre>
     *
     * <p>为什么不用一个大 SQL 直接生成全部账单：账单生成需要锁 account、锁候选交易、
     * 插入 statement/line、写 DelayJob/Outbox。逐账户小事务更容易恢复，也不会让一个慢账户
     * 长时间拖住整片账户的 row lock。</p>
     */
    public StatementJobExecutionResult handle(StatementJob job) {
        ZoneId zone = ZoneId.of(properties.batch().zone());
        // 阶段 1：用 statement job 上固化的 cycle 日期生成查询窗口。
        // 这里必须用 billing timezone，而不是 JVM 默认时区；否则月末 00:00 附近的交易可能进错账期。
        // job.shardNo/shardCount 是 StatementCycleService 创建 shard 时写入 DB 的稳定分片参数。
        // findAccountIdsForJob 内部明确使用：
        //   MOD(CRC32(credit_account_id), shardCount) = shardNo
        // 同一个账户 id 的 CRC32 结果稳定，因此这个账户本期所有 POSTED/UNBILLED 交易会落到同一个 shard。
        List<UUID> accountIds = billingRepository.findAccountIdsForJob(
                job.periodStart().atStartOfDay(zone).toInstant(),
                job.periodEnd().plusDays(1).atStartOfDay(zone).toInstant(),
                job.shardNo(),
                job.shardCount()
        );

        int attempted = 0;
        int generated = 0;
        int skipped = 0;
        int failed = 0;
        for (UUID accountId : accountIds) {
            // 中途 lease 检查：handler 可能跑几分钟，期间 lease 可能超时被 recoverer 收回重发。
            // 旧 worker 继续跑不会出错（账户级幂等 + finalize token 校验兜底），但和新 worker
            // 并发扫同一批账户是纯浪费，还会给账户 row lock 添堵。每 100 个账户花一次主键点查，
            // 发现自己已不是 owner 就立即放弃剩余账户——finalize 反正会被 token 校验丢弃。
            if (attempted > 0 && attempted % ACCOUNTS_PER_LEASE_CHECK == 0
                    && !jobRepository.holdsCurrentLease(job.id(), job.claimToken())) {
                log.warn(
                        "statement_job_lease_lost_midway jobId={} attemptedAccounts={} remainingAccounts={}",
                        job.id(),
                        attempted,
                        accountIds.size() - attempted
                );
                break;
            }
            attempted++;
            try {
                // 阶段 2：每个账户单独生成 statement。
                // StatementGenerationService 会自己开启 transaction boundary，并在内部锁 credit account + candidate rows。
                // 如果这里把整个 shard 放进一个事务，上千账户会共享一个超长事务，锁等待和回滚成本都会被放大。
                statementGenerationService.generate(new GenerateStatementCommand(
                        accountId,
                        job.periodStart(),
                        job.periodEnd(),
                        job.dueDate()
                ));
                generated++;
            } catch (StatementGenerationException exception) {
                if (exception.retryable()) {
                    // 可恢复失败：例如短暂数据库故障或锁超时。
                    // 这类失败让整个 shard 最终回到 PENDING/DEAD，由 dispatcher 的 job retry 机制处理。
                    failed++;
                    log.warn(
                            "statement_account_retryable_failure jobId={} accountId={} reason={}",
                            job.id(),
                            accountId,
                            exception.getMessage()
                    );
                } else {
                    // 确定性业务跳过：例如该账户本期没有可出账交易。
                    // 这不是系统故障，不应该让 job 重试；计入 skipped 方便看本片实际有效账户数。
                    skipped++;
                    log.info(
                            "statement_account_skipped jobId={} accountId={} reason={}",
                            job.id(),
                            accountId,
                            exception.getMessage()
                    );
                }
            } catch (RuntimeException exception) {
                // 未预期异常按 failed 处理，让 shard 进入 retry/DEAD，而不是吞掉异常后误标 DONE。
                failed++;
                log.warn(
                        "statement_account_failed jobId={} accountId={}",
                        job.id(),
                        accountId,
                        exception
                );
            }
        }

        // 阶段 3：这里只返回统计，不直接改 statement_jobs。
        // DONE / PENDING retry / DEAD 的状态推进由 StatementJobDispatcher 在 finalize 短事务中完成，
        // 并会重新校验 claim token，防止过期 worker 覆盖新 owner。
        // processed 用 attempted 而不是 accountIds.size()：中途放弃时统计只反映真正尝试过的账户
        // （此时 finalize 也会因 token 不符被丢弃，但返回值本身应当诚实）。
        return new StatementJobExecutionResult(
                attempted,
                generated,
                skipped,
                failed
        );
    }
}
