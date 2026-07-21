package com.minicard.messaging.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Outbox publish、broker ack 后状态推进和 stale lease 防护测试。
 *
 * <p>关键词：事务发件箱, 发布确认, 重试退避, outbox worker,
 * broker ack, lease token, トランザクションアウトボックス。</p>
 *
 * <p>{@code PUBLISHED} 只表示 publisher 正常返回（生产实现等待 Kafka ack），不表示 consumer 已完成。
 * 本类验证 publish 失败可持久化重试，以及旧 worker 的结果不会越过 lease ownership 检查。</p>
 */
class OutboxWorkerTest {

    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    @Test
    // 测试目的：验证正常 publish 路径：事务外等 Kafka ack，随后 finalize 为 PUBLISHED。
    // variant：RecordingPublisher 记录 publish 时 event 仍是 PROCESSING，最终由 worker 写 PUBLISHED。
    void publishesAndMarksEventPublished() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        RecordingPublisher publisher = new RecordingPublisher();
        OutboxEvent event = claimedEvent();
        when(repository.findByIdForUpdate(event.id())).thenReturn(Optional.of(event));
        OutboxWorker worker = worker(repository, publisher);

        worker.publishClaimedEvent(event);

        assertThat(publisher.publishedStatus).isEqualTo(OutboxEventStatus.PROCESSING);
        assertThat(event.status()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.publishedAt()).isEqualTo(NOW);
        verify(repository).updateDeliveryState(event);
    }

    @Test
    // 测试目的：验证 Kafka publish 失败会被 worker 转成可持久化 retry 状态。
    // variant：publisher 抛异常，event 回 PENDING、attempts+1、nextAttemptAt 退避。
    void failedPublicationReturnsEventToPendingWithBackoff() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxEvent event = claimedEvent();
        when(repository.findByIdForUpdate(event.id())).thenReturn(Optional.of(event));
        OutboxWorker worker = worker(repository, (outboxEvent, timeout) -> {
            throw new IllegalStateException("Kafka unavailable");
        });

        worker.publishClaimedEvent(event);

        assertThat(event.status()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.attempts()).isEqualTo(1);
        assertThat(event.nextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(event.lastError()).isEqualTo("Kafka unavailable");
        verify(repository).updateDeliveryState(event);
    }

    @Test
    // 测试目的：验证 stale worker 不能覆盖 recoverer/新 worker 已接手的 lease。
    // variant：claimed snapshot 和 DB current row 的 leaseToken 不同，即使 deadline 相同也跳过 finalize。
    void staleWorkerResultDoesNotOverrideNewerLease() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxEvent claimed = claimedEvent();
        OutboxEvent current = copy(claimed);
        // deadline 相同但 token 不同：证明 finalize ownership 不再依赖 nextAttemptAt timestamp。
        current.markProcessing(NOW, 30, "lease-token-2");
        when(repository.findByIdForUpdate(claimed.id())).thenReturn(Optional.of(current));
        OutboxWorker worker = worker(repository, new RecordingPublisher());

        worker.publishClaimedEvent(claimed);

        verify(repository, never()).updateDeliveryState(current);
    }

    private OutboxWorker worker(
            OutboxEventRepository repository,
            OutboxMessagePublisher publisher
    ) {
        return new OutboxWorker(
                repository,
                publisher,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                immediateTransactions()
        );
    }

    private OutboxEvent claimedEvent() {
        OutboxEvent event = OutboxEvent.pending(
                UUID.randomUUID(),
                "Authorization",
                UUID.randomUUID().toString(),
                "authorization.approved",
                1,
                UUID.randomUUID().toString(),
                "{\"eventType\":\"authorization.approved\"}",
                NOW
        );
        event.markProcessing(NOW, 30, "lease-token-1");
        return event;
    }

    private OutboxProperties properties() {
        return new OutboxProperties(true, 1000, 5000, 10, 5000, 30, 3, 4, 100);
    }

    private OutboxEvent copy(OutboxEvent source) {
        return OutboxEvent.restore(
                source.id(),
                source.aggregateType(),
                source.aggregateId(),
                source.eventType(),
                source.eventVersion(),
                source.partitionKey(),
                source.payload(),
                source.status(),
                source.attempts(),
                source.nextAttemptAt(),
                source.leaseToken(),
                source.createdAt(),
                source.publishedAt(),
                source.lastError()
        );
    }

    private TransactionOperations immediateTransactions() {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                return action.doInTransaction(new SimpleTransactionStatus());
            }
        };
    }

    private static final class RecordingPublisher implements OutboxMessagePublisher {

        private OutboxEventStatus publishedStatus;

        @Override
        public void publish(OutboxEvent event, Duration timeout) {
            publishedStatus = event.status();
        }
    }
}
