package com.minicard.notification.infrastructure.mybatis;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.minicard.notification.domain.NotificationType;
import com.minicard.notification.domain.delivery.NotificationChannel;
import com.minicard.notification.domain.delivery.NotificationDelivery;
import com.minicard.notification.domain.delivery.NotificationDeliveryRepository;
import com.minicard.notification.domain.delivery.NotificationDeliveryStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * NotificationDeliveryRepository 的 MyBatis adapter。
 *
 * <p>只做 row/domain 映射与 SKIP LOCKED 领取；投递业务顺序仍在 worker/claimer，SQL 层不偷偷承担业务规则。
 * 结构与 MyBatisOutboxEventRepository 对称，便于把"可靠投递"这件事横向复用到不同副作用。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisNotificationDeliveryRepository implements NotificationDeliveryRepository {

    private final NotificationDeliveryMapper mapper;

    @Override
    public void insertAll(List<NotificationDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            // 空列表直接返回，避免 <foreach> 生成非法的 INSERT ... VALUES（空）。
            return;
        }
        mapper.insertBatch(deliveries.stream().map(this::toRow).toList());
    }

    @Override
    public List<NotificationDelivery> findDispatchableBatchForUpdate(Instant now, int limit) {
        return mapper.findDispatchableBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<NotificationDelivery> findStuckProcessingBatchForUpdate(Instant now, int limit) {
        return mapper.findStuckProcessingBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<NotificationDelivery> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(mapper.findByIdForUpdate(id.toString())).map(this::toDomain);
    }

    @Override
    public void updateDeliveryState(NotificationDelivery delivery) {
        mapper.updateDeliveryState(toRow(delivery));
    }

    private NotificationDeliveryRow toRow(NotificationDelivery delivery) {
        return new NotificationDeliveryRow(
                delivery.id().toString(),
                delivery.notificationId().toString(),
                delivery.channel().name(),
                delivery.notificationType().name(),
                delivery.subjectId(),
                delivery.recipientKey(),
                delivery.status().name(),
                delivery.attempts(),
                delivery.nextAttemptAt(),
                delivery.lastError(),
                delivery.providerMessageId(),
                delivery.sentAt(),
                delivery.createdAt(),
                delivery.updatedAt()
        );
    }

    private NotificationDelivery toDomain(NotificationDeliveryRow row) {
        return NotificationDelivery.restore(
                UUID.fromString(row.id()),
                UUID.fromString(row.notificationId()),
                NotificationChannel.valueOf(row.channel()),
                NotificationType.valueOf(row.notificationType()),
                row.subjectId(),
                row.recipientKey(),
                NotificationDeliveryStatus.valueOf(row.status()),
                row.attempts(),
                row.nextAttemptAt(),
                row.lastError(),
                row.providerMessageId(),
                row.sentAt(),
                row.createdAt(),
                row.updatedAt()
        );
    }
}
