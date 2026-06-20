package com.minicard.messaging.inbox.mybatis;

import java.time.Instant;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Consumer Inbox MyBatis mapper。
 *
 * <p>关键词：Inbox SQL, 唯一键, 重复消息, inbox mapper,
 * unique key, duplicate delivery, Inbox SQL,
 * 重複配信(じゅうふくはいしん)。</p>
 */
@Mapper
public interface ConsumerInboxMapper {

    /**
     * 插入消费记录；唯一键冲突代表该 consumer 已处理过同一个 event。
     */
    int insert(
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("processedAt") Instant processedAt
    );
}
