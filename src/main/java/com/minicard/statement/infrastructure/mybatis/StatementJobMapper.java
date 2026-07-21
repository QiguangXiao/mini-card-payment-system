package com.minicard.statement.infrastructure.mybatis;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Statement job MyBatis mapper。
 *
 * <p>关键词：账单任务 SQL, FOR UPDATE SKIP LOCKED, lease,
 * statement job mapper, 請求ジョブSQL(せいきゅうジョブエスキューエル)。</p>
 */
@Mapper
public interface StatementJobMapper {

    /**
     * 用 {@code INSERT IGNORE} 幂等插入一个 cycle shard；cycle/shard 唯一键冲突时返回 0。
     */
    int insert(StatementJobRow row);

    /**
     * 判断 cycle 是否已出现任意 shard，语义是“planner 已经规划过”，不代表全部 shard 已执行完成。
     */
    boolean existsForCycle(
            @Param("periodStart") LocalDate periodStart,
            @Param("periodEnd") LocalDate periodEnd
    );

    /**
     * 用 {@code FOR UPDATE SKIP LOCKED} 领取 PENDING jobs 的候选行。
     *
     * <p>必须在 dispatcher 的短事务中调用，并在同一事务内写 PROCESSING + claim token；
     * 否则 select 锁释放后其他 Pod 仍可能领取同一 job。</p>
     */
    List<StatementJobRow> findClaimableBatchForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    /**
     * 锁定 claim_until 已过期的 PROCESSING jobs，供 recovery 放回 PENDING 或推进 DEAD。
     *
     * <p>{@code SKIP LOCKED} 让多个 recoverer 分摊过期任务，不互相等待同一批行。</p>
     */
    List<StatementJobRow> findStuckProcessingBatchForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    /**
     * finalize 前按主键锁住 job，以数据库最新 claim token 判断当前 worker 是否仍是 owner。
     */
    StatementJobRow findByIdForUpdate(@Param("id") String id);

    /**
     * 持久化完整状态、lease 和执行统计。
     *
     * <p>UPDATE 本身只按 id 定位，因此调用方必须先用 findByIdForUpdate 锁行并校验 token；
     * 如果省略该顺序，过期 worker 可能覆盖新 owner 的 DONE/DEAD 结果。</p>
     */
    int updateExecutionState(StatementJobRow row);

    /**
     * 无锁确认 {@code status=PROCESSING + claim_token} 是否仍匹配，仅用于长循环中途提前止损。
     *
     * <p>它不是 finalize 的所有权证明：检查后状态仍可能变化，最终写入仍必须锁行并重新校验 token。</p>
     */
    int countCurrentLease(
            @Param("id") String id,
            @Param("claimToken") String claimToken
    );
}
