package com.minicard.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.minicard.messaging.inbox.ConsumerInboxRepository;
import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationRepository;
import com.minicard.notification.domain.delivery.NotificationDelivery;
import com.minicard.notification.domain.delivery.NotificationDeliveryRepository;
import com.minicard.notification.domain.delivery.NotificationRecipient;
import com.minicard.notification.domain.delivery.NotificationRecipientResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从 integration event 创建通知意图、并按收件人渠道扇出投递记录的 application use case。
 *
 * <p>关键词：通知请求, Inbox 幂等, 渠道扇出, notification request,
 * consumer idempotency, delivery fan-out, 通知依頼(つうちいらい)。</p>
 *
 * <p>类名按业务动作命名，不按 Kafka consumption 命名，让 authorization/transaction/repayment listener
 * 都复用同一 use case。意图(notifications)与投递(notification_deliveries)在<b>同一事务</b>内写入，
 * 保证"一旦记录了要通知，就一定有对应的 PENDING 投递"，不会出现意图无投递的断裂。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RequestNotificationService {

    public static final String CONSUMER_NAME = "notification-v1";

    private final ConsumerInboxRepository inboxRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final NotificationRecipientResolver recipientResolver;
    private final Clock clock;

    /**
     * 消费业务事件并创建 Notification 意图，再按收件人渠道扇出 delivery rows。
     */
    @Transactional
    public void request(RequestNotificationCommand command) {
        // 这个 MySQL transaction 由 Spring 管理：方法正常结束时先提交 DB，
        // 然后 listener 才正常 return，Kafka container 才会按 ack-mode=record 提交 offset。
        // 如果 DB 写入失败抛异常，listener 不会成功 return，Kafka offset 也不会被当作成功处理。
        Instant now = Instant.now(clock);
        // Inbox claim 是 consumer-side idempotency 的第一道门：Kafka at-least-once 可能重复投递，
        // 同一 eventId 对 notification-v1 只处理一次。没有它，Outbox 重发/offset 重放会重复创建通知与投递。
        // CONSUMER_NAME 是逻辑消费者身份，不是 Java class name；重构类名不要顺手改它。
        if (!inboxRepository.claim(CONSUMER_NAME, command.sourceEventId(), now)) {
            log.info("notification_event_duplicate eventId={}", command.sourceEventId());
            return;
        }

        Notification notification = Notification.requestFromEvent(
                command.sourceEventId(),
                command.subjectType(),
                command.subjectId(),
                command.recipientKey(),
                command.type(),
                now
        );
        if (!notificationRepository.insertIfAbsent(notification)) {
            // source_event_id 唯一键是 Inbox 之外的第二道幂等保护：即使历史数据修复或并发边界变化，
            // 也不会创建两条通知。命中重复就跳过，连带不创建第二批投递。
            log.info("notification_request_duplicate eventId={}", command.sourceEventId());
            return;
        }

        // 解析收件人并按其启用渠道扇出投递。当前 resolver 是 stub（无 User 域），默认 push + email。
        // sorted() 让扇出顺序确定(按 enum ordinal)，便于测试与日志稳定。
        NotificationRecipient recipient = recipientResolver.resolve(notification.recipientKey());
        List<NotificationDelivery> deliveries = recipient.channels().stream()
                .sorted()
                .map(channel -> NotificationDelivery.pendingFor(notification, channel, now))
                .toList();
        if (deliveries.isEmpty()) {
            // 没有任何渠道：通知意图已落库但无人投递。记一条 warn，便于发现 resolver/偏好配置问题。
            log.warn("notification_no_delivery_channels eventId={} recipientKey={}",
                    command.sourceEventId(), notification.recipientKey());
        }
        deliveryRepository.insertAll(deliveries);

        log.info(
                "notification_requested eventId={} notificationId={} type={} subjectType={} subjectId={} deliveries={}",
                command.sourceEventId(),
                notification.id(),
                notification.type(),
                notification.subjectType(),
                notification.subjectId(),
                deliveries.size()
        );
    }
}
