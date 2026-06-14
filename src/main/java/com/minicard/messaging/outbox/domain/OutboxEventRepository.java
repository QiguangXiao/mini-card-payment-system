package com.minicard.messaging.outbox.domain;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository {

    void insert(OutboxEvent event);

    List<OutboxEvent> findPublishableBatchForUpdate(Instant now, int batchSize);

    void updateDeliveryState(OutboxEvent event);
}
