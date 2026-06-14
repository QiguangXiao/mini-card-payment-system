package com.minicard.notification.infrastructure.mybatis;

import com.minicard.notification.domain.Notification;
import com.minicard.notification.domain.NotificationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
public class MyBatisNotificationRepository implements NotificationRepository {

    private final NotificationMapper mapper;

    public MyBatisNotificationRepository(NotificationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean insertIfAbsent(Notification notification) {
        try {
            return mapper.insert(new NotificationRow(
                    notification.id().toString(),
                    notification.sourceEventId().toString(),
                    notification.authorizationId().toString(),
                    notification.cardId(),
                    notification.template(),
                    notification.status().name(),
                    notification.deliveryAttempts(),
                    notification.lastError(),
                    notification.sentAt(),
                    notification.createdAt(),
                    notification.updatedAt()
            )) == 1;
        } catch (DuplicateKeyException exception) {
            return false;
        }
    }
}
