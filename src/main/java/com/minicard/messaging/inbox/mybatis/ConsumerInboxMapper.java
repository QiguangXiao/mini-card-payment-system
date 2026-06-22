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
// @Mapper 让 MyBatis 生成 INSERT proxy；唯一键冲突会被 Spring 翻译成 DuplicateKeyException。
@Mapper
public interface ConsumerInboxMapper {

    /**
     * 插入消费记录；唯一键冲突代表该 consumer 已处理过同一个 event。
     */
    int insert(
            // 三个 @Param 对应 XML 里的 #{consumerName}/#{eventId}/#{processedAt}。
            // 如果 XML 绑定错，Inbox 幂等会在运行期才失效。
            @Param("consumerName") String consumerName,
            @Param("eventId") String eventId,
            @Param("processedAt") Instant processedAt
    );
}
