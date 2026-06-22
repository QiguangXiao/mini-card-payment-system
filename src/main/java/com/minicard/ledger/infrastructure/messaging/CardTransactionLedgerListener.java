package com.minicard.ledger.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

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
    @KafkaListener(
            topics = "${messaging.topics.transaction-events}",
            groupId = "${messaging.consumers.ledger.group-id}",
            containerFactory = "ledgerKafkaListenerContainerFactory"
    )
    public void onCardTransactionEvent(ConsumerRecord<String, String> record) {
        IntegrationEvent event = eventReader.read(record);
        if (!CARD_TRANSACTION_POSTED.equals(event.eventType())) {
            return;
        }
        JsonNode payload = event.payload();
        // 消费入账已经在 PostingService 的主事务中完成；这里仅做 ledger projection。
        // 如果本 consumer 失败，Kafka retry/DLT 处理，不会回滚 posted transaction。
        service.record(RecordLedgerEntryCommand.cardTransactionPosted(
                event.eventId(),
                UUID.fromString(eventReader.requiredText(payload, "cardTransactionId")),
                UUID.fromString(eventReader.requiredText(payload, "creditAccountId")),
                // 金额从 JSON text 构造 BigDecimal，避免 asDouble() 这类二进制浮点转换丢精度。
                new BigDecimal(eventReader.requiredText(payload, "amount")),
                Currency.getInstance(eventReader.requiredText(payload, "currency")),
                // 时间和 UUID 在 adapter 边界解析；解析失败会让 listener 失败并交给 Kafka retry/DLT。
                Instant.parse(eventReader.requiredText(payload, "postedAt"))
        ));
    }
}
