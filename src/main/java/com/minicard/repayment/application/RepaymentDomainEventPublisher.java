package com.minicard.repayment.application;

import com.minicard.repayment.domain.event.RepaymentDomainEvent;

/**
 * Repayment 领域事件发布端口。
 *
 * <p>关键词：还款事件发布, Outbox 端口, 事务内追加, repayment event publisher,
 * outbox port, transactional append, 入金イベント発行(にゅうきんイベントはっこう),
 * アウトボックス。</p>
 *
 * <p>Application layer 只知道 append domain event；具体写 Outbox 还是同步发布由 infrastructure adapter 决定。</p>
 */
public interface RepaymentDomainEventPublisher {

    /**
     * 在当前 transaction boundary 内追加还款事件。
     *
     * <p>这里不直接发 Kafka，避免业务提交失败但消息已发出的 partial failure。</p>
     */
    void append(RepaymentDomainEvent event);
}
