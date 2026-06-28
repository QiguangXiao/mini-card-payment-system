package com.minicard.notification.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * NotificationDelivery MyBatis mapper。
 *
 * <p>关键词：投递 SQL, 批量插入, 租约领取, delivery mapper,
 * FOR UPDATE SKIP LOCKED, batch insert, 配信SQL(はいしんSQL)。</p>
 */
// @Mapper 生成 proxy；SQL 放在 XML，便于明确 SKIP LOCKED 与批量 VALUES。
@Mapper
public interface NotificationDeliveryMapper {

    /** 单条 SQL 批量插入多条投递记录。 */
    int insertBatch(@Param("deliveries") List<NotificationDeliveryRow> deliveries);

    /** 领取到期 PENDING 投递（FOR UPDATE SKIP LOCKED）。 */
    List<NotificationDeliveryRow> findDispatchableBatchForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    /** 领取 lease 超时的 PROCESSING 投递（FOR UPDATE SKIP LOCKED）。 */
    List<NotificationDeliveryRow> findStuckProcessingBatchForUpdate(
            @Param("now") Instant now,
            @Param("limit") int limit
    );

    /** finalize 前按 id 重新加锁。 */
    NotificationDeliveryRow findByIdForUpdate(@Param("id") String id);

    /** 只更新投递状态列。 */
    int updateDeliveryState(NotificationDeliveryRow delivery);
}
