package com.minicard.statement.application;

import com.minicard.statement.domain.event.StatementDomainEvent;

/**
 * Statement 领域事件发布端口。
 *
 * <p>关键词：账单事件发布, Outbox 端口, 事务内追加, statement event publisher,
 * outbox port, transactional append, 請求イベント発行(せいきゅうイベントはっこう),
 * アウトボックス。</p>
 *
 * <p>Application layer 只知道 append domain event；具体写 Outbox 还是同步发布由 infrastructure adapter 决定。
 * 这样 StatementGenerationService 不直接依赖 Kafka，未来 Notification 拆成独立微服务也只看 Kafka contract。</p>
 */
public interface StatementDomainEventPublisher {

    /**
     * 在当前 transaction boundary 内追加账单事件。
     *
     * <p>这里不直接发 Kafka，避免账单已生成但消息发送失败、或消息已发但事务回滚的 partial failure。</p>
     */
    void append(StatementDomainEvent event);
}
