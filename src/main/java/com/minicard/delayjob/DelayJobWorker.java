package com.minicard.delayjob;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Delay job worker，负责执行业务 handler，并由 worker 自己 finalize。
 *
 * <p>关键词：任务执行, lease 校验, finalize, delay job worker,
 * processing lease, retry policy, ジョブ実行(ジョブじっこう),
 * リース検証(リースけんしょう)。</p>
 *
 * <p>interview重点：claim 已经在短事务内完成；worker 只处理已经拿到 PROCESSING lease 的 job。
 * handler 执行业务动作时不持有 job row lock；成功/失败后再开一个短事务重新锁 row 并校验
 * lease token，确认"这轮 claim 仍属于我"，再标 DONE 或按 retry policy 回到 PENDING/DEAD。</p>
 */
@Service
@Slf4j
public class DelayJobWorker {

    /** finalize 前会重新 FOR UPDATE 锁住 job row，防止迟到 worker 覆盖新 lease。 */
    private final DelayJobRepository delayJobRepository;
    /** 最大重试次数等 retry policy。 */
    private final DelayJobProperties properties;
    /** jobType -> handler 的 dispatch map。 */
    private final Map<DelayJobType, DelayJobHandler> handlers;
    /** 统一时间来源。 */
    private final Clock clock;
    /** 显式事务工具，用于拆分 handler transaction 和 finalize transaction。 */
    private final TransactionOperations transactionOperations;

    public DelayJobWorker(
            DelayJobRepository delayJobRepository,
            DelayJobProperties properties,
            List<DelayJobHandler> handlers,
            Clock clock,
            TransactionOperations transactionOperations
    ) {
        this.delayJobRepository = delayJobRepository;
        this.properties = properties;
        // Spring 会把所有 DelayJobHandler bean 注入成 List。这里启动时转成 EnumMap，
        // 如果运行中每次用 stream 查找 handler，热路径会更啰嗦，也更难发现重复/缺失类型。
        this.handlers = handlersByType(handlers);
        this.clock = clock;
        this.transactionOperations = transactionOperations;
    }

    /**
     * 执行一条已领取的 DelayJob，并在成功/失败后由 worker 自己 finalize。
     */
    public void handleClaimedJob(DelayJob claimedJob) {
        // claimedJob 是 claimer 在短事务里从 PENDING 改成 PROCESSING 后返回的快照。
        // worker 从这里开始只负责"执行业务动作 + finalize"，不再做扫描/抢任务。
        // 第一步：按 jobType 找业务 handler。DelayJob 本身是通用机制，不应该写死授权过期或自动还款逻辑。
        DelayJobHandler handler = handlers.get(claimedJob.jobType());
        if (handler == null) {
            // 没有 handler 是配置错误，必须进入失败路径而不是静默跳过。
            // 如果静默标 DONE，业务动作其实没有执行，后续也不会再重试。
            markFailed(claimedJob, "no handler registered for job type " + claimedJob.jobType(), null);
            return;
        }

        try {
            // 第二步：真正执行业务动作。handler 自己决定 transaction boundary，
            // 例如授权过期会锁 authorization/account，自动还款会调用 bank debit 再复用 repayment 入账。
            // handler.handle() 执行业务 transaction，例如 authorization expiry 会释放额度并写 Outbox。
            handler.handle(claimedJob);
            // 第三步：业务动作成功后才 finalize 为 DONE。
            // 这一步仍会重新校验 lease，避免 handler 执行太久导致 lease 已被 recoverer 接管。
            // 业务成功后，worker 自己 finalize，避免 poller 提前标 DONE。
            // 如果 poller 领取后就标 DONE，handler 失败时 job 会消失，授权 hold 或自动扣款都可能漏执行。
            markDone(claimedJob);
        } catch (RuntimeException exception) {
            // 第四步：业务异常转成 retry state，而不是让异常冒泡到线程池后丢失。
            // 这样 attempts、nextAttemptAt 和 lastError 都能持久化，后续 recover/retry 才有依据。
            markFailed(
                    claimedJob,
                    exception.getMessage() == null
                            ? exception.getClass().getSimpleName()
                            : exception.getMessage(),
                    exception
            );
        }
    }

    /**
     * worker pool 拒绝执行时，把已领取 job 放回 retry/DEAD 状态机。
     *
     * <p>事务归属：本方法本身不加 {@code @Transactional}；它委托
     * {@link #markFailed(DelayJob, String, RuntimeException)} 开启 finalize 短事务。</p>
     */
    public void markRejectedForRetry(DelayJob claimedJob, RuntimeException exception) {
        // worker pool 拒绝也按失败处理，把 job 从 PROCESSING 放回 retry/DEAD。
        // 这是 submit 阶段的失败：handler 根本没开始执行，但 lease 已经写入 DB，所以仍要 finalize。
        markFailed(claimedJob, "worker pool rejected job", exception);
    }

    /**
     * 在独立短事务中重新校验 lease，并把业务已完成的 job 标记为 DONE。
     *
     * <p>事务归属：本方法通过 {@code TransactionOperations.executeWithoutResult(...)}
     * 自己开启短事务；业务 handler 已经在外层、通常是另一个事务里完成。</p>
     */
    private void markDone(DelayJob claimedJob) {
        // finalize 单独开短事务：handler 可能做外部/跨 aggregate 业务，不能让 job row lock 跟着长时间持有。
        // 如果 handler 执行期间一直锁着 delay_jobs，同一批 scheduler 会被不必要地串行化。
        transactionOperations.executeWithoutResult(status -> {
            DelayJob job = lockCurrentLease(claimedJob);
            if (job == null) {
                return;
            }
            // markDone() 只推进 DelayJob 自己的执行状态；业务表已经由 handler 在前一步完成。
            // DONE 后清空 leaseToken，表示这条 durable plan 已结束，不会再被 poller/recoverer 领取。
            job.markDone(Instant.now(clock));
            delayJobRepository.updateExecutionState(job);
            log.info(
                    "delay_job_done jobId={} jobType={} aggregateType={} aggregateId={}",
                    job.id(),
                    job.jobType(),
                    job.aggregateType(),
                    job.aggregateId()
            );
        });
    }

    /**
     * 在独立短事务中记录 handler 失败，并推进 retry/backoff/DEAD。
     *
     * <p>事务归属：本方法通过 {@code TransactionOperations.executeWithoutResult(...)}
     * 自己开启短事务；它不和 handler 的业务事务合并。</p>
     */
    private void markFailed(
            DelayJob claimedJob,
            String error,
            RuntimeException exception
    ) {
        // 失败也必须落库 attempts/nextAttemptAt/lastError；否则 worker 抛异常后 job 会停在 PROCESSING，
        // 只能等 recoverer 超时扫描，retry/backoff 的原因也会丢失。
        transactionOperations.executeWithoutResult(status -> {
            DelayJob job = lockCurrentLease(claimedJob);
            if (job == null) {
                return;
            }
            // markFailed() 内部会自增 attempts，并按 retry policy 计算下一次 nextAttemptAt。
            // 未到上限回 PENDING；达到 maxAttempts 进 DEAD，避免坏任务无限打业务系统。
            job.markFailed(error, Instant.now(clock), properties.maxAttempts());
            delayJobRepository.updateExecutionState(job);
            log.warn(
                    "delay_job_failed jobId={} jobType={} attempts={} status={}",
                    job.id(),
                    job.jobType(),
                    job.attempts(),
                    job.status(),
                    exception
            );
        });
    }

    /**
     * 重新锁定当前 job row 并确认本 worker 仍持有 PROCESSING lease。
     *
     * <p>事务归属：只能在 {@link #markDone(DelayJob)} 或
     * {@link #markFailed(DelayJob, String, RuntimeException)} 创建的 finalize 短事务内部调用。</p>
     */
    private DelayJob lockCurrentLease(DelayJob claimedJob) {
        // worker 收到的是 claim 事务提交后的内存快照。finalize 前必须重新 SELECT ... FOR UPDATE，
        // 读取当前 DB row；否则 recoverer/新 worker 已经接管时，旧快照仍可能把状态写回 DONE/FAILED。
        DelayJob job = delayJobRepository.findByIdForUpdate(claimedJob.id())
                .orElseThrow(() -> new IllegalStateException(
                        "claimed delay job disappeared " + claimedJob.id()
        ));
        // 这三个条件共同定义"当前 lease 仍属于本 worker"：
        // 1) DB row 仍是 PROCESSING。若 recoverer 已把它改回 PENDING/DEAD，旧 worker 不能再 finalize。
        // 2) claimedJob 必须带 leaseToken。若没有 token，说明调用方不是从 claim 路径拿到的合法租约快照。
        // 3) token 必须完全相等。nextAttemptAt 只是 lease deadline/retry 时间，TIMESTAMP(6) 精度和重领都会变化，
        //    不能当 owner identity；UUID leaseToken 才能挡住 stale worker 覆盖新 worker 的结果。
        if (job.status() != DelayJobStatus.PROCESSING
                || claimedJob.leaseToken() == null
                || !claimedJob.leaseToken().equals(job.leaseToken())) {
            // 返回 null 表示"本 worker 已失去 lease"。上层只跳过 finalize，不抛异常：
            // 因为新 worker/recoverer 已经按当前 DB 状态负责后续处理，旧 worker 再报错反而会制造噪音。
            log.warn(
                    "delay_job_lease_changed jobId={} claimedToken={} currentStatus={} currentToken={} currentLease={}",
                    claimedJob.id(),
                    claimedJob.leaseToken(),
                    job.status(),
                    job.leaseToken(),
                    job.nextAttemptAt()
            );
            return null;
        }
        return job;
    }

    /**
     * 把 Spring 注入的 handler list 转成按 enum 查找的 map。
     *
     * <p>EnumMap 是 Java 针对 enum key 优化的 Map，实现简单且比 HashMap 更省。</p>
     */
    private Map<DelayJobType, DelayJobHandler> handlersByType(List<DelayJobHandler> handlers) {
        Map<DelayJobType, DelayJobHandler> result = new EnumMap<>(DelayJobType.class);
        for (DelayJobHandler handler : handlers) {
            result.put(handler.jobType(), handler);
        }
        return result;
    }
}
