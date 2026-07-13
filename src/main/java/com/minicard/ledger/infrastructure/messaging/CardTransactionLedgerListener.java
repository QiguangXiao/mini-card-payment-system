package com.minicard.ledger.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.minicard.ledger.application.RecordLedgerEntryCommand;
import com.minicard.ledger.application.RecordLedgerEntryService;
import com.minicard.messaging.event.IntegrationEvent;
import com.minicard.messaging.kafka.IntegrationEventReader;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * CardTransaction 事件进入 Ledger bounded context 的 Kafka inbound adapter。
 *
 * <p>CardTransaction 是用户可见交易流水；LedgerEntry 是内部账本分录。
 * 两者通过 card_transaction.posted 事件连接，避免 posting 主事务直接依赖 Ledger。</p>
 */
@Component
@RequiredArgsConstructor
public class CardTransactionLedgerListener {

    private static final String CARD_TRANSACTION_POSTED = "card_transaction.posted";

    private final IntegrationEventReader eventReader;
    private final RecordLedgerEntryService service;

    // ledger consumer 用独立 groupId，保证它和 notification/risk 各自消费同一事件。
    // 如果多个 bounded context 共用一个 groupId，Kafka 会把事件分给其中一个，而不是广播给所有下游。
    // 这个 groupId 同时是失败路由键：本 listener 的失败进 ledger DLT，即使同一条 record
    // 在 notification group 消费成功（KafkaConsumerConfiguration 按失败 group 查表）。
    @KafkaListener(
            topics = "${messaging.topics.transaction-events}",
            groupId = "${messaging.consumers.ledger.group-id}",
            concurrency = "${messaging.consumers.ledger.concurrency}"
    )
    public void onCardTransactionEvent(ConsumerRecord<String, String> record) {
        // consumer correctness 只依赖 self-describing envelope：永远解析 body，
        // 用 body 的 eventType 判断是否感兴趣；无关类型直接跳过，不进入 retry/DLT。
        IntegrationEvent event = eventReader.read(record);
        if (!CARD_TRANSACTION_POSTED.equals(event.eventType())) {
            return;
        }
        JsonNode payload = event.payload();
        // 消费入账已经在 PostingService 的主事务中完成；这里仅做 ledger projection。
        // 如果本 consumer 失败，Kafka retry/DLT 处理，不会回滚 posted transaction。
        service.record(RecordLedgerEntryCommand.cardTransactionPosted(
                event.eventId(),
                eventReader.requiredUuid(payload, "cardTransactionId"),
                eventReader.requiredUuid(payload, "creditAccountId"),
                // 金额从 JSON text 构造 BigDecimal，避免 asDouble() 这类二进制浮点转换丢精度。
                // typed helper 把坏 UUID/金额/币种/时间统一包装成 InvalidIntegrationEventException，直接进入 DLT；
                // 如果在 listener 里直接调用 JDK parser，永久坏消息会被误当成瞬时异常重复 retry。
                eventReader.requiredDecimal(payload, "amount"),
                eventReader.requiredCurrency(payload, "currency"),
                eventReader.requiredInstant(payload, "postedAt")
        ));
    }
}
