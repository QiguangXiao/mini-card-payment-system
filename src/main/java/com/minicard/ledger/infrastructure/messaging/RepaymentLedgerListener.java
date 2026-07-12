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
 * Repayment 事件进入 Ledger bounded context 的 Kafka inbound adapter。
 *
 * <p>还款入账在 RepaymentService 主事务中完成；Ledger 只消费 repayment.received
 * 这个业务事实，记录“应收款减少”的 CREDIT 分录。</p>
 */
@Component
@RequiredArgsConstructor
public class RepaymentLedgerListener {

    private static final String REPAYMENT_RECEIVED = "repayment.received";

    private final IntegrationEventReader eventReader;
    private final RecordLedgerEntryService service;

    // containerFactory 绑定 ledger 的 retry/DLT 策略。
    // 如果直接用默认 Kafka listener factory，反序列化或业务异常可能没有进入预期 dead-letter topic。
    @KafkaListener(
            topics = "${messaging.topics.repayment-events}",
            groupId = "${messaging.consumers.ledger.group-id}",
            containerFactory = "ledgerKafkaListenerContainerFactory"
    )
    public void onRepaymentEvent(ConsumerRecord<String, String> record) {
        // consumer correctness 只依赖 self-describing envelope：永远解析 body，
        // 用 body 的 eventType 判断是否感兴趣；无关类型直接跳过，不进入 retry/DLT。
        IntegrationEvent event = eventReader.read(record);
        if (!REPAYMENT_RECEIVED.equals(event.eventType())) {
            return;
        }
        JsonNode payload = event.payload();
        service.record(RecordLedgerEntryCommand.repaymentReceived(
                event.eventId(),
                eventReader.requiredUuid(payload, "repaymentId"),
                eventReader.requiredUuid(payload, "creditAccountId"),
                // repayment.amount 也从 text 转 BigDecimal；不要把 JSON number 转 double 后再转金额。
                // reader 只校验 transport format；金额为正、币种匹配等业务规则仍留在 domain。
                eventReader.requiredDecimal(payload, "amount"),
                eventReader.requiredCurrency(payload, "currency"),
                eventReader.requiredInstant(payload, "receivedAt")
        ));
    }
}
