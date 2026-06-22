package com.minicard.transaction.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * CardTransaction bounded context 内部的领域事件 marker。
 *
 * <p>它只表达“卡交易流水发生了什么业务事实”，不依赖 Kafka、Outbox 或 Notification。
 * 生产上 Notification 可能是独立微服务，所以这里不能引用 notification 包。</p>
 */
public interface CardTransactionDomainEvent {

    // 事件接口只暴露业务身份和发生时间，不暴露 Kafka topic/header。
    // 如果 domain event 直接带 infrastructure 字段，领域模型会被发布技术绑住。
    UUID cardTransactionId();

    Instant occurredAt();
}
