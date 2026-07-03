package com.minicard.notification.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import com.minicard.messaging.inbox.ConsumerInboxRepository;
import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationRepository;
import com.minicard.notification.domain.delivery.NotificationChannel;
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
 * 都复用同一 use case。方法名用 {@code requestNotification} 而不是裸 {@code request}：
 * listener 调用点会更清楚地表达"把上游事件转成通知请求"，而不是像 HTTP request 或风控 request。</p>
 *
 * <p>意图(notifications)与投递(notification_deliveries)在<b>同一事务</b>内写入，
 * 保证"一旦记录了要通知，就一定有对应的 PENDING 投递"，不会出现意图无投递的断裂。
 * 注意这里没有 {@code NotificationEntry} 概念：本项目拆成一条不可变 Notification 意图，
 * 再按渠道拆成多条 NotificationDelivery work rows。</p>
 *
 * <p>流程总览（mini trace，全部在一个 DB transaction 内；编号对应方法内的"阶段 N"注释）：</p>
 * <pre>
 * Kafka listener 收到 integration event（at-least-once，可能重放）
 * 1. claim consumer inbox (consumer_name, event_id)；重复消息: return（不再创建第二批投递）
 * 2. INSERT Notification 意图（source_event_id 唯一键 = 第二道幂等保护）
 * 3. resolve recipient，取启用渠道集合（enum 排序保证确定性）；
 *    无渠道: 意图保留 + warn，不让 Kafka retry
 * 4. fan-out: 每渠道一条 PENDING notification_delivery，批量 INSERT（同事务）
 * 5. COMMIT 后 listener 正常返回，Kafka offset 才提交
 *    （任一步抛异常: 整体回滚，offset 不提交，重放时重新走 claim）
 * </pre>
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
    public void requestNotification(RequestNotificationCommand command) {
        // 这个 MySQL transaction 由 Spring 管理：方法正常结束时先提交 DB，
        // 然后 listener 才正常 return，Kafka container 才会按 ack-mode=record 提交 offset。
        // 如果 DB 写入失败抛异常，listener 不会成功 return，Kafka offset 也不会被当作成功处理。
        Instant now = Instant.now(clock);

        // 阶段 1：先 claim Inbox。Kafka 是 at-least-once；同一 eventId 可能因为 offset 重放、
        // Outbox 重发或 consumer rebalance 被再次投递。claim 成功才允许继续创建通知。
        // 如果这一步放到 Notification 插入之后，重复消息可能已经制造出第二批投递行。
        // CONSUMER_NAME 是逻辑消费者身份，不是 Java class name；重构类名不要顺手改它。
        if (!inboxRepository.claim(CONSUMER_NAME, command.sourceEventId(), now)) {
            log.info("notification_event_duplicate eventId={}", command.sourceEventId());
            return;
        }

        // 阶段 2：创建 Notification 意图并插入 notifications 表。
        // Notification 只表达"要通知谁、关于哪个业务对象、用哪种模板"，不表达每个渠道是否发送成功。
        // 这些投递生命周期留给 notification_deliveries，否则 push 成功/email 失败会被一个 status 字段揉坏。
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

        // 阶段 3：解析收件人，再把一条 Notification 意图 fan-out 成多条 per-channel delivery。
        // 当前 resolver 是 stub（无 User/Cardholder 域），所以 recipientKey 只是 cardId/creditAccountId 线索；
        // 未来接真实用户模型时，这里仍只依赖 resolver 输出，不让 Kafka listener 知道联系方式细节。
        NotificationRecipient recipient = recipientResolver.resolve(notification.recipientKey());
        // channels() 返回启用渠道集合，例如 APP_PUSH、EMAIL。没有渠道不代表上游事件坏了，
        // 而是收件人偏好/联系方式缺失；因此保留 Notification 意图并 warn，不让 Kafka retry 同一消息。
        Set<NotificationChannel> enabledChannels = recipient.channels();
        // 排序只为确定性：Map/Set 遍历顺序不该影响测试、日志或批量 insert 顺序。
        // 这里按 enum 自然顺序排序，未来新增 SMS/LINE 时插入顺序仍稳定。
        List<NotificationChannel> orderedChannels = enabledChannels.stream()
                .sorted()
                .toList();
        // 每个 channel 各自生成一条 PENDING delivery row。这样 push/email 可以独立 claim、retry、DEAD；
        // 如果只在 Notification 上放一个状态，就无法表达"push 已发、email 还在重试"。
        List<NotificationDelivery> deliveries = orderedChannels.stream()
                .map(channel -> NotificationDelivery.pendingFor(notification, channel, now))
                .toList();
        if (deliveries.isEmpty()) {
            // 没有任何渠道：通知意图已落库但无人投递。记一条 warn，便于发现 resolver/偏好配置问题。
            log.warn("notification_no_delivery_channels eventId={} recipientKey={}",
                    command.sourceEventId(), notification.recipientKey());
        }
        // 阶段 4：与 Notification 意图在同一个 transaction 内批量插入 delivery rows。
        // 如果这里失败，整个事务回滚，listener 抛异常，Kafka offset 不会提交；下一次重放会重新 claim/insert。
        // 批量插入比逐条 insert 少 DB round trip；(notification_id, channel) 唯一键在 DB 层兜底防重复。
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
