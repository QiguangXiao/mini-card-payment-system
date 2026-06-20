package com.minicard.statement.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Statement 聚合对外发布的领域事件 marker interface。
 *
 * <p>关键词：账单事件, 领域事件, 出账通知, statement event, domain event,
 * aggregate event, 請求イベント(せいきゅうイベント), ドメインイベント。</p>
 *
 * <p>这里保留最小公共字段，具体事件例如 StatementClosedDomainEvent 决定更多业务 payload。
 * Outbox adapter 会把这些 domain event 转成 integration event。</p>
 */
public interface StatementDomainEvent {

    /**
     * 发生状态变化的 statement id，用作 Outbox aggregate id 和消费者查询 key。
     */
    UUID statementId();

    /**
     * statement 所属 credit account，通知和 repayment 后续流程都需要这个关联。
     */
    UUID creditAccountId();

    /**
     * 事件发生时间；使用 Instant 保持跨时区一致。
     */
    Instant occurredAt();
}
