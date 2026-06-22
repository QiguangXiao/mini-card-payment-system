package com.minicard.messaging.outbox.mybatis;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Outbox MyBatis mapper。
 *
 * <p>关键词：Outbox SQL, 行锁, 跳过已锁行, outbox mapper,
 * row lock, SKIP LOCKED, アウトボックスSQL,
 * 行ロック(ぎょうロック)。</p>
 */
// @Mapper 生成的是 MyBatis proxy，不是手写实现类。
// 这让 repository adapter 可以只依赖接口，同时把 SQL 留在 XML 中便于学习锁和索引。
@Mapper
public interface OutboxEventMapper {

    /**
     * 插入待发布事件；通常和业务状态变更在同一个数据库事务内提交。
     */
    int insert(OutboxEventRow event);

    /**
     * 查找可发布事件并加 FOR UPDATE SKIP LOCKED。
     *
     * <p>多个 publisher 实例可以横向扩展，互相跳过已经锁住的事件。</p>
     */
    List<OutboxEventRow> findPublishableBatchForUpdate(
            // @Param 让 XML 使用 #{now}/#{limit}，避免依赖编译器是否保留参数名。
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    /**
     * 查找 PROCESSING lease 超时的事件，供 recoverer 重新放回 retry。
     */
    List<OutboxEventRow> findStuckProcessingBatchForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    /**
     * worker finalize 前按 id 加锁确认当前 lease。
     */
    OutboxEventRow findByIdForUpdate(@Param("id") String id);

    /**
     * 更新发布状态、attempts、publishedAt 和 lastError。
     */
    int updateDeliveryState(OutboxEventRow event);
}
