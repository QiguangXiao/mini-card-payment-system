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
 * 从 integration event 创建通知请求的 application use case。
 *
 * <p>关键词：通知请求, Inbox 幂等, 事件消费, notification request,
 * consumer idempotency, integration event, 通知依頼(つうちいらい),
 * 重複配信(じゅうふくはいしん)。</p>
 *
 * <p>类名按业务动作命名，而不是按 Kafka consumption 命名。
 * 这样 authorization、card transaction、statement listener 都可以复用同一个 use case。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RequestNotificationService {

    public static final String CONSUMER_NAME = "notification-v1";

    private final ConsumerInboxRepository inboxRepository;
    private final NotificationRepository notificationRepository;
    private final Clock clock;

    @Transactional
    public void request(RequestNotificationCommand command) {
        Instant now = Instant.now(clock);
        // Inbox claim 是 consumer-side idempotency 的第一道门：
        // Kafka at-least-once 可能重复投递，同一个 eventId 对 notification-v1 只处理一次。
        // 如果没有 Inbox，Outbox 重发或 Kafka offset 重放会创建多条相同客户通知。
        if (!inboxRepository.claim(CONSUMER_NAME, command.sourceEventId(), now)) {
            log.info("notification_event_duplicate eventId={}", command.sourceEventId());
            return;
        }

        // 警示：通知创建失败不应该影响 statement/authorization/posting 的主业务事务。
        // 主事务早已通过 Outbox 提交；这里失败时让 Kafka listener/DLT 和人工重放处理。
        Notification notification = Notification.requestFromEvent(
                command.sourceEventId(),
                command.subjectType(),
                command.subjectId(),
                command.recipientKey(),
                command.type(),
                now
        );
        if (!notificationRepository.insertIfAbsent(notification)) {
            // notifications.source_event_id 是第二道保护，防止历史数据修复或并发边界变化造成重复通知。
            // 如果未来 Inbox 数据迁移/清理出错，这个 unique guard 还能挡住 duplicate side effect。
            log.info("notification_request_duplicate eventId={}", command.sourceEventId());
            return;
        }
        log.info(
                "notification_requested eventId={} notificationId={} type={} subjectType={} subjectId={}",
                command.sourceEventId(),
                notification.id(),
                notification.type(),
                notification.subjectType(),
                notification.subjectId()
        );
    }
}
