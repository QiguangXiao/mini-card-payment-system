package com.minicard.messaging.outbox.infrastructure.mybatis;

import java.time.Instant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxEventMapper {

    int insert(OutboxEventRow event);

    OutboxEventRow findNextPublishableForUpdate(@Param("now") Instant now);

    OutboxEventRow findByIdForUpdate(@Param("id") String id);

    int updateDeliveryState(OutboxEventRow event);
}
