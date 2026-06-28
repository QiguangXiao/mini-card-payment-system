package com.minicard.notification.domain.delivery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * NotificationDelivery 仓储端口。
 *
 * <p>关键词：投递仓储, 批量插入, 租约领取, delivery repository,
 * FOR UPDATE SKIP LOCKED, processing lease, 配信リポジトリ(はいしんリポジトリ)。</p>
 *
 * <p>形状刻意对齐 OutboxEventRepository：claim/recover 都靠 FOR UPDATE SKIP LOCKED 在短事务内领取，
 * worker 在另一个短事务里 finalize。这样多 pod 可水平扩展，互不重复处理同一行。</p>
 */
public interface NotificationDeliveryRepository {

    /**
     * 与通知意图同事务批量插入投递记录。
     *
     * <p>批量而非逐条，减少 round trip；(notification_id, channel) 唯一键兜底防重复。</p>
     */
    void insertAll(List<NotificationDelivery> deliveries);

    /**
     * 领取到期待投递记录（status=PENDING 且 next_attempt_at<=now），SKIP LOCKED 支持多实例并发。
     */
    List<NotificationDelivery> findDispatchableBatchForUpdate(Instant now, int limit);

    /**
     * 领取 lease 超时的 PROCESSING 记录，交给 recoverer 放回重试。
     */
    List<NotificationDelivery> findStuckProcessingBatchForUpdate(Instant now, int limit);

    /**
     * finalize 前按 id 重新加锁，配合 lease token 校验防并发覆盖。
     */
    Optional<NotificationDelivery> findByIdForUpdate(UUID id);

    /**
     * 只更新投递状态列；快照字段(type/subjectId/recipientKey)与 notification_id 不变。
     */
    void updateDeliveryState(NotificationDelivery delivery);
}
