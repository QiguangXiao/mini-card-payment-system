package com.minicard.statement.domain.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Statement 聚合对外发布的领域事件 marker interface。
 *
 * <p>关键词：账单事件, 领域事件, 关账, statement event, domain event,
 * billing close, 請求イベント(せいきゅうイベント), 締め処理(しめしょり)。</p>
 *
 * <p>StatementGenerationService 在同一个 transaction boundary 内创建 statement、标记交易 BILLED、
 * 安排自动扣款，再写 Outbox；事件字段让后续通知或对账消费不需要反查整张账单。</p>
 */
public interface StatementDomainEvent {

    /**
     * 关账生成的账单 id，作为事件主体（aggregate id）。
     */
    // 事件接口只暴露 consumer 需要的业务字段，不暴露 Kafka header/Outbox row。
    // 如果把发布机制塞进 domain event，未来换消息系统就得改动领域层。
    UUID statementId();

    /**
     * 账单所属的信用账户 id，同时用作 Kafka partition key 与通知 recipientKey。
     */
    UUID creditAccountId();

    /**
     * 领域事件发生时间（账单生成时刻）。
     */
    Instant occurredAt();
}
