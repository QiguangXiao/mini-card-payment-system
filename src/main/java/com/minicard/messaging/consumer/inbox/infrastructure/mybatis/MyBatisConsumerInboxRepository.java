package com.minicard.messaging.consumer.inbox.infrastructure.mybatis;

import java.time.Instant;
import java.util.UUID;

import com.minicard.messaging.consumer.inbox.domain.ConsumerInboxRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisConsumerInboxRepository implements ConsumerInboxRepository {

    private final ConsumerInboxMapper mapper;

    public MyBatisConsumerInboxRepository(ConsumerInboxMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean claim(String consumerName, UUID eventId, Instant processedAt) {
        try {
            // Catch only the expected unique-key duplicate. Other database
            // failures propagate so Kafka retry/DLT handling can respond.
            return mapper.insert(consumerName, eventId.toString(), processedAt) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }
}
