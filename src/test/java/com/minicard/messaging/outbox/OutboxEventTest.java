package com.minicard.messaging.outbox;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    @Test
    // 测试目的：验证 PENDING -> PROCESSING 会写入 lease deadline 和 owner token。
    // variant：nextAttemptAt 在 PROCESSING 阶段临时表示 deadline，不再表示 retry 时间。
    void marksEventProcessingWithLeaseDeadline() {
        OutboxEvent event = pendingEvent();

        event.markProcessing(NOW, 30, "lease-token-1");

        assertThat(event.status()).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(event.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(event.leaseToken()).isEqualTo("lease-token-1");
    }

    @Test
    // 测试目的：验证 publish failure 走统一 retry/backoff/DEAD 状态机。
    // variant：前两次失败回 PENDING 并指数退避，第三次达到 maxAttempts 进入 DEAD。
    void retriesWithExponentialBackoffBeforeBecomingDead() {
        OutboxEvent event = pendingEvent();

        event.markFailed("Kafka unavailable", NOW, 3);
        assertThat(event.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.attempts()).isEqualTo(1);
        assertThat(event.nextAttemptAt()).isEqualTo(NOW.plusSeconds(1));

        event.markFailed("Kafka unavailable", NOW.plusSeconds(1), 3);
        assertThat(event.nextAttemptAt()).isEqualTo(NOW.plusSeconds(3));

        event.markFailed("Kafka unavailable", NOW.plusSeconds(3), 3);
        assertThat(event.status()).isEqualTo(OutboxEventStatus.DEAD);
        assertThat(event.attempts()).isEqualTo(3);
    }

    @Test
    // 测试目的：验证 broker ack 后的成功终态。
    // variant：PROCESSING event markPublished 后进入 PUBLISHED，清空 lastError 和 leaseToken。
    void marksAcknowledgedEventPublished() {
        OutboxEvent event = pendingEvent();
        event.markProcessing(NOW, 30, "lease-token-1");

        event.markPublished(NOW.plusSeconds(1));

        assertThat(event.status()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.publishedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(event.lastError()).isNull();
        assertThat(event.leaseToken()).isNull();
    }

    private OutboxEvent pendingEvent() {
        return OutboxEvent.pending(
                UUID.randomUUID(),
                "Authorization",
                UUID.randomUUID().toString(),
                "authorization.approved",
                1,
                UUID.randomUUID().toString(),
                "{\"eventType\":\"authorization.approved\"}",
                NOW
        );
    }
}
