package com.minicard.delayjob.mybatis;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * DelayJob MyBatis mapper。
 *
 * <p>关键词：延迟任务 SQL, 行锁, 跳过已锁行, delay job mapper,
 * row lock, SKIP LOCKED, 遅延ジョブSQL(ちえんジョブSQL),
 * 行ロック(ぎょうロック)。</p>
 */
@Mapper
public interface DelayJobMapper {

    /**
     * 插入新 job；唯一键避免同一业务动作重复计划。
     */
    int insert(DelayJobRow job);

    /**
     * 查找可运行 job 并加 FOR UPDATE SKIP LOCKED。
     *
     * <p>多个 scheduler pod 并发扫描时会跳过彼此已锁住的行，减少重复执行和等待。</p>
     */
    List<DelayJobRow> findRunnableBatchForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    /**
     * 查找 PROCESSING lease 已过期的 job 并加锁，供 recoverer 恢复。
     */
    List<DelayJobRow> findStuckProcessingBatchForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    /**
     * 按 id 加锁读取 job；worker finalize 前必须重新确认 lease。
     */
    DelayJobRow findByIdForUpdate(@Param("id") String id);

    /**
     * 更新状态、attempts、nextAttemptAt 和 lastError。
     */
    int updateExecutionState(DelayJobRow job);
}
