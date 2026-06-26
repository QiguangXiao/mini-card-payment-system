package com.minicard.repayment.domain.event;

import java.time.Instant;
import java.util.UUID;

import com.minicard.shared.domain.Money;

/**
 * Repayment 聚合对外发布的领域事件 marker interface。
 *
 * <p>关键词：还款事件, 领域事件, 入账, repayment event, domain event,
 * posting, 入金イベント(にゅうきんイベント), 入金処理(にゅうきんしょり)。</p>
 *
 * <p>RepaymentService 在同一个 transaction boundary 内保存 repayment、更新 statement/account，
 * 再写 Outbox；事件字段让后续通知或对账消费不需要反查全部上下文。</p>
 */
public interface RepaymentDomainEvent {

    /**
     * 本次还款 id，作为事件主体。
     */
    // 事件接口只定义消费者需要的业务字段，不定义 Kafka header/Outbox row。
    // 如果把发布机制放进 domain event，未来换消息系统会改动领域层。
    UUID repaymentId();

    /**
     * 被还款的账单 id。
     */
    UUID statementId();

    /**
     * 被扣减余额的信用账户 id。
     */
    UUID creditAccountId();

    /**
     * 本次入账金额，Money 保留 currency 和 scale 语义。
     */
    Money amount();

    /**
     * 领域事件发生时间。
     */
    Instant occurredAt();
}
