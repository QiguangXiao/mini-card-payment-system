package com.minicard.messaging.outbox.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxEventMapper {

    int insert(OutboxEventRow event);

    List<OutboxEventRow> findPublishableBatchForUpdate(
            @Param("now") Instant now,
            @Param("batchSize") int batchSize
    );

    int updateDeliveryState(OutboxEventRow event);
}
