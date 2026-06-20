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
 *
 * <p>关键词：自动扣款计划, 延迟任务, 唯一任务, auto repayment scheduling,
 * delay job, unique job, 口座振替予定(こうざふりかえよてい),
 * 遅延ジョブ(ちえんジョブ), 一意ジョブ(いちいジョブ)。</p>
 *
 * <p>这是 StatementDueJobScheduler 的 DelayJob adapter：statement 业务层只知道要安排支払日动作，
 * 不知道底层表名、job type 或 retry policy。</p>
 */
@Component
@RequiredArgsConstructor
public class AutoRepaymentDelayJobScheduler implements StatementDueJobScheduler {

    /** aggregate_type 进入 DelayJob contract，handler 会用它做 defensive check。 */
    private static final String AGGREGATE_TYPE = "Statement";

    /** 通用 DelayJob repository，负责 insertIfAbsent 和后续 scheduler claim。 */
    private final DelayJobRepository delayJobRepository;
    /** 注入 clock 便于测试 createdAt，避免直接依赖系统时间。 */
    private final Clock clock;

    /**
     * 为 statement 到期日创建 AUTO_REPAYMENT job。
     *
     * <p>scheduledAt 当前取 dueDate 的 UTC 零点；真实日本业务可能需要指定 JST 银行批处理时间，
     * 这里先保留“日期级”调度，降低面试 demo 的复杂度。</p>
     */
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
