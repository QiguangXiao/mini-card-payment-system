package com.minicard.notification.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;

/**
 * Notification MyBatis mapper。
 *
 * <p>关键词：通知 SQL, 幂等插入, 唯一键, notification mapper,
 * insert, duplicate key, 通知SQL(つうちSQL),
 * 重複キー(じゅうふくキー)。</p>
 */
@Mapper
public interface NotificationMapper {

    /**
     * 插入通知；source_event_id 唯一键冲突表示同一事件已创建过通知。
     */
    int insert(NotificationRow notification);
}
