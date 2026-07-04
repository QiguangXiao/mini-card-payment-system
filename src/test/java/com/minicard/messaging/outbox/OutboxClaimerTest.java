package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxClaimerTest {

    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    @Test
    // 测试目的：验证 Outbox claim 只在短事务中把 due PENDING event 改成 PROCESSING lease。
    // variant：repository 返回 1 条可发布事件，claimer 应写入 lease deadline 并持久化执行状态。
    void claimsPublishableEventsInShortTransaction() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxEvent event = pendingEvent();
        when(repository.findPublishableBatchForUpdate(NOW, 10)).thenReturn(List.of(event));
        OutboxClaimer claimer = new OutboxClaimer(
                repository,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        List<OutboxEvent> claimed = claimer.claimPublishableEvents();

        assertThat(claimed).containsExactly(event);
        assertThat(event.status()).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(event.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        verify(repository).updateDeliveryState(event);
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

    private OutboxProperties properties() {
        return new OutboxProperties(true, 1000, 5000, 10, 5000, 30, 3, 4, 100);
    }
}
