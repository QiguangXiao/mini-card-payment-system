package com.minicard.messaging.inbox.mybatis;

import java.time.Instant;
import java.util.UUID;

import com.minicard.messaging.inbox.ConsumerInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * Consumer Inbox 的 MyBatis 实现，用数据库唯一键完成 consumer-side idempotency。
 *
 * <p>Kafka 可能重复投递，同一个 logical consumer 只应处理同一个 eventId 一次。
 * 这里属于 messaging infrastructure，不进入具体业务 bounded context。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisConsumerInboxRepository implements ConsumerInboxRepository {

    private final ConsumerInboxMapper mapper;

    @Override
    public boolean claim(String consumerName, UUID eventId, Instant processedAt) {
        try {
            // INSERT-first + unique key 让数据库裁决“第一次消费”。
            // 如果先查再插，两个 consumer 线程可能都读到不存在，然后都执行业务副作用。
            // 只捕获预期的 unique-key duplicate。其他 DB 异常继续抛出，
            // 让 Kafka retry/DLT 机制感知真实故障。
            return mapper.insert(consumerName, eventId.toString(), processedAt) == 1;
        } catch (DuplicateKeyException exception) {
            // DuplicateKeyException 在这里是正常 duplicate delivery，不应该抛到 Kafka error handler。
            // 如果抛出，已经处理过的消息也可能进入 retry/DLT。
            return false;
        }
    }
}
