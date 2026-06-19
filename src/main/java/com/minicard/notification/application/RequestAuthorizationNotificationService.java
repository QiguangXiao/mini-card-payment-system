package com.minicard.notification.application;

import java.time.Clock;
import java.time.Instant;

import com.minicard.messaging.inbox.ConsumerInboxRepository;
import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从授权事件创建通知请求的 application use case。
 *
 * <p>类名按业务动作命名，而不是按 Kafka consumption 命名。
 * 这样 Kafka listener、后台重试或未来 admin endpoint 都可以复用同一个 use case。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RequestAuthorizationNotificationService {

    public static final String CONSUMER_NAME = "notification-v1";

    private final ConsumerInboxRepository inboxRepository;
    private final NotificationRepository notificationRepository;
    private final Clock clock;

    @Transactional
    public void request(RequestAuthorizationNotificationCommand command) {
        Instant now = Instant.now(clock);
        // Inbox claim 是 consumer-side idempotency 的第一道门：
        // Kafka at-least-once 可能重复投递，同一个 eventId 对 notification-v1 只处理一次。
        if (!inboxRepository.claim(CONSUMER_NAME, command.sourceEventId(), now)) {
            log.info("notification_event_duplicate eventId={}", command.sourceEventId());
            return;
        }

        // 这里不解析 eventType，也不碰 Kafka API；职责只是一条“创建通知请求”的 use case。
        Notification notification = Notification.requestFromAuthorizationEvent(
                command.sourceEventId(),
                command.authorizationId(),
                command.cardId(),
                command.type(),
                now
        );
        if (!notificationRepository.insertIfAbsent(notification)) {
            // notifications.source_event_id 是第二道保护，防止历史数据修复或并发边界变化造成重复通知。
            log.info("notification_request_duplicate eventId={}", command.sourceEventId());
            return;
        }
        log.info(
                "notification_requested eventId={} notificationId={} type={}",
                command.sourceEventId(),
                notification.id(),
                notification.type()
        );
    }
}
