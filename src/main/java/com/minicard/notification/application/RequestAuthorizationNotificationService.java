package com.minicard.notification.application;

import java.time.Clock;
import java.time.Instant;

import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 从授权事件创建通知请求的 application use case。
 *
 * <p>类名按业务动作命名，而不是按 Kafka consumption 命名。
 * 这样 Kafka listener、后台重试或未来 admin endpoint 都可以复用同一个 use case。</p>
 */
@Service
public class RequestAuthorizationNotificationService {

    private static final Logger log =
            LoggerFactory.getLogger(RequestAuthorizationNotificationService.class);

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public RequestAuthorizationNotificationService(
            NotificationRepository notificationRepository,
            Clock clock
    ) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    @Transactional
    public void request(RequestAuthorizationNotificationCommand command) {
        // Notification 使用 sourceEventId 做 idempotency key，应对 Kafka at-least-once delivery。
        Notification notification = Notification.requestAuthorizationDecision(
                command.sourceEventId(),
                command.authorizationId(),
                command.cardId(),
                command.approved(),
                Instant.now(clock)
        );
        if (!notificationRepository.insertIfAbsent(notification)) {
            // duplicate event 直接成功返回，相当于确认重复投递；
            // source_event_id 唯一索引保护多实例并发消费。
            log.info("notification_request_duplicate eventId={}", command.sourceEventId());
            return;
        }
        log.info(
                "notification_requested eventId={} notificationId={} template={}",
                command.sourceEventId(),
                notification.id(),
                notification.template()
        );
    }
}
