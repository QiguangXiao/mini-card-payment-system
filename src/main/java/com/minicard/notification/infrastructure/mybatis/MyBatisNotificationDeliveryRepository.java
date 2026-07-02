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
    /**
     * 批量写入 Notification 分渠道投递记录。
     */
    public void insertAll(List<NotificationDelivery> deliveries) {
        if (deliveries.isEmpty()) {
            // 空列表直接返回，避免 <foreach> 生成非法的 INSERT ... VALUES（空）。
            return;
        }
        mapper.insertBatch(deliveries.stream().map(this::toRow).toList());
    }

    @Override
    /**
     * 锁定一批可投递记录，供 claimer 写入 PROCESSING lease。
     */
    public List<NotificationDelivery> findDispatchableBatchForUpdate(Instant now, int limit) {
        return mapper.findDispatchableBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    /**
     * 锁定一批 lease 已超时的 PROCESSING 投递，供 recoverer 恢复。
     */
    public List<NotificationDelivery> findStuckProcessingBatchForUpdate(Instant now, int limit) {
        return mapper.findStuckProcessingBatchForUpdate(now, limit)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    /**
     * 按 id 锁定当前投递行，供 worker finalize 前重新校验 lease。
     */
    public Optional<NotificationDelivery> findByIdForUpdate(UUID id) {
        return Optional.ofNullable(mapper.findByIdForUpdate(id.toString())).map(this::toDomain);
    }

    @Override
    /**
     * 更新投递状态字段，不修改通知意图快照。
     */
    public void updateDeliveryState(NotificationDelivery delivery) {
        mapper.updateDeliveryState(toRow(delivery));
    }

    /**
     * 将 NotificationDelivery domain object 转成数据库 row DTO。
     */
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
                delivery.leaseToken(),
                delivery.lastError(),
                delivery.providerMessageId(),
                delivery.sentAt(),
                delivery.createdAt(),
                delivery.updatedAt()
        );
    }

    /**
     * 将数据库 row DTO 还原成带状态机校验的 NotificationDelivery。
     */
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
                row.leaseToken(),
                row.lastError(),
                row.providerMessageId(),
                row.sentAt(),
                row.createdAt(),
                row.updatedAt()
        );
    }
}
