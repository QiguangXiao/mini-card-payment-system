package com.minicard.notification.domain.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationSubjectType;
import com.minicard.notification.domain.NotificationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-06-14T00:00:00Z");

    @Test
    // 测试目的：验证 delivery 创建时会快照 Notification 意图里的稳定字段。
    // variant：新建 APP_PUSH delivery，状态应为 PENDING，attempts=0，且 provider 幂等键等于 delivery id。
    void pendingForSnapshotsNotificationFields() {
        NotificationDelivery delivery = pushDelivery();

        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.attempts()).isZero();
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW);
        // 尚未被领取：无 lease token。
        assertThat(delivery.leaseToken()).isNull();
        assertThat(delivery.channel()).isEqualTo(NotificationChannel.APP_PUSH);
        assertThat(delivery.notificationType()).isEqualTo(NotificationType.CARD_TRANSACTION_POSTED);
        assertThat(delivery.subjectId()).isEqualTo("txn-1");
        assertThat(delivery.recipientKey()).isEqualTo("card-1");
        // 幂等键稳定且等于 delivery id：透传给 provider 做去重的依据。
        assertThat(delivery.idempotencyKey()).isEqualTo(delivery.id().toString());
    }

    @Test
    // 测试目的：验证成功投递的完整状态机路径 PENDING -> PROCESSING -> SENT。
    // variant：先写 lease deadline/token，再收到 provider receipt，终态必须清空 lease。
    void leaseThenSentReachesTerminalSuccess() {
        NotificationDelivery delivery = pushDelivery();

        delivery.markProcessing(NOW, 30, "lease-token-1");
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PROCESSING);
        // PROCESSING 复用 nextAttemptAt 作为 lease deadline；lease_token 作为持有者身份。
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(delivery.leaseToken()).isEqualTo("lease-token-1");

        delivery.markSent(NOW.plusSeconds(1), "push-abc");
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.SENT);
        assertThat(delivery.sentAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(delivery.providerMessageId()).isEqualTo("push-abc");
        assertThat(delivery.lastError()).isNull();
        // 终态释放 lease：token 清空，迟到 worker 不可能再"匹配上"。
        assertThat(delivery.leaseToken()).isNull();
    }

    @Test
    // 测试目的：验证 transient failure 会释放本轮 lease，允许后续重新 claim。
    // variant：PROCESSING 状态下失败，状态回 PENDING，旧 leaseToken 必须清空。
    void failureReleasesLeaseToken() {
        NotificationDelivery delivery = pushDelivery();
        delivery.markProcessing(NOW, 30, "lease-token-1");

        delivery.markFailed("boom", NOW.plusSeconds(1), 5);

        // 回到 PENDING 后 token 清空：下一轮 claim 会换新 token，旧 worker 的 finalize 不再匹配。
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.leaseToken()).isNull();
    }

    @Test
    // 测试目的：验证 retry/backoff 和 maxAttempts DEAD 终态。
    // variant：第一次失败回 PENDING 并推迟 nextAttemptAt；第二次达到上限进入 DEAD。
    void failureBacksOffThenGoesDeadAtMaxAttempts() {
        NotificationDelivery delivery = pushDelivery();

        delivery.markFailed("boom", NOW.plusSeconds(1), 2);
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.attempts()).isEqualTo(1);
        // 退避：2^(attempts-1)=1s，nextAttemptAt = failedAt + 1s。
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(delivery.lastError()).isEqualTo("boom");

        delivery.markFailed("boom again", NOW.plusSeconds(5), 2);
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.DEAD);
        assertThat(delivery.attempts()).isEqualTo(2);
    }

    @Test
    // 测试目的：验证 provider 尚未调用时只延后，不消耗真正失败的 retry budget。
    // variant：RateLimiter/worker pool 拒绝后释放 lease、回 PENDING，attempts 仍为 0。
    void rescheduleWithoutAttemptDefersAndPreservesRetryBudget() {
        NotificationDelivery delivery = pushDelivery();
        delivery.markProcessing(NOW, 30, "lease-token-1");

        delivery.rescheduleWithoutAttempt(
                "notification provider throttled",
                NOW.plusSeconds(1),
                Duration.ofSeconds(2)
        );

        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.attempts()).isZero();
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(delivery.lastError()).isEqualTo("notification provider throttled");
        assertThat(delivery.leaseToken()).isNull();
    }

    @Test
    // 测试目的：验证 provider 4xx 这类 permanent failure 不消耗完整 retry budget。
    // variant：PROCESSING delivery 收到永久失败后直接 DEAD，并释放 lease，等待人工修 contract/config。
    void permanentFailureGoesDeadImmediately() {
        NotificationDelivery delivery = pushDelivery();
        delivery.markProcessing(NOW, 30, "lease-token-1");

        delivery.markPermanentFailed("provider 400 bad request", NOW.plusSeconds(1));

        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.DEAD);
        assertThat(delivery.attempts()).isEqualTo(1);
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(delivery.lastError()).isEqualTo("provider 400 bad request");
        assertThat(delivery.leaseToken()).isNull();
    }

    @Test
    // 测试目的：验证 recoverer 把超时 PROCESSING lease 当作一次失败处理。
    // variant：worker 可能宕机或卡死，timeout 后 attempts+1 并回到 PENDING 等待下一轮。
    void processingTimeoutCountsAsOneFailure() {
        NotificationDelivery delivery = pushDelivery();
        delivery.markProcessing(NOW, 30, "lease-token-1");

        delivery.markProcessingTimedOut(NOW.plusSeconds(31), 5);

        assertThat(delivery.attempts()).isEqualTo(1);
        assertThat(delivery.status()).isEqualTo(NotificationDeliveryStatus.PENDING);
        assertThat(delivery.lastError()).contains("lease expired");
    }

    private NotificationDelivery pushDelivery() {
        return NotificationDelivery.pendingFor(intent(), NotificationChannel.APP_PUSH, NOW);
    }

    private Notification intent() {
        return Notification.requestFromEvent(
                UUID.randomUUID(),
                NotificationSubjectType.CARD_TRANSACTION,
                "txn-1",
                "card-1",
                NotificationType.CARD_TRANSACTION_POSTED,
                NOW
        );
    }
}
