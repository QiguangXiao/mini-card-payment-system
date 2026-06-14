package com.minicard.messaging.consumer.inbox.infrastructure.mybatis;

import java.time.Instant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConsumerInboxMapper {

    int insert(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("processedAt") Instant processedAt
    );
}
