package com.minicard.notification.infrastructure.mybatis;

import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * NotificationRepository 的 MyBatis 实现。
 *
 * <p>关键词：通知持久化, 幂等创建, DuplicateKeyException,
 * notification persistence, insert if absent, 通知永続化(つうちえいぞくか),
 * 重複キー(じゅうふくキー)。</p>
 */
@Repository
@RequiredArgsConstructor
public class MyBatisNotificationRepository implements NotificationRepository {

    /** MyBatis SQL mapper。 */
    private final NotificationMapper mapper;

    /**
     * 幂等插入通知。
     */
    @Override
    public boolean insertIfAbsent(Notification notification) {
        try {
            return mapper.insert(new NotificationRow(
                    notification.id().toString(),
                    notification.sourceEventId().toString(),
                    notification.subjectType().name(),
                    notification.subjectId(),
                    notification.recipientKey(),
                    notification.type().name(),
                    notification.status().name(),
                    notification.deliveryAttempts(),
                    notification.lastError(),
                    notification.sentAt(),
                    notification.createdAt(),
                    notification.updatedAt()
            )) == 1;
        } catch (DuplicateKeyException exception) {
            // DuplicateKeyException 是 Inbox 之外的第二层幂等保护：
            // 即使同一事件被重复投递，也不能创建两条通知。
            // 并发/重复消息下，唯一键冲突表示通知已经存在；返回 false 让调用方跳过。
            return false;
        }
    }
}
