package com.minicard.messaging;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.minicard.authorization.application.AuthorizationCommand;
import com.minicard.authorization.domain.Authorization;
import com.minicard.authorization.domain.AuthorizationStatus;
import com.minicard.risk.application.RiskAssessmentService;
import com.minicard.risk.domain.RiskDecision;
import com.minicard.support.MySqlIntegrationTestBase;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Outbox→Kafka→Inbox 端到端往返 + 重复投递幂等测试（真 MySQL + 真 Kafka broker）。
 *
 * <p>关键词：事务发件箱, 可靠投递, 消费者幂等, outbox pattern, at-least-once,
 * consumer inbox dedup, トランザクションアウトボックス, 重複配信冪等。</p>
 *
 * <p>这把整条异步链路从“口头声明”变成可运行证据：</p>
 * <ol>
 *   <li><b>往返</b>：{@code authorize()} 在业务事务内写一条 outbox_events，后台 OutboxPoller
 *       claim 后发到真 Kafka，risk-feature consumer 消费并把 card_risk_features 投影出来。</li>
 *   <li><b>重复投递幂等</b>：手工把同一个 envelope（同 eventId）再投一次模拟 broker at-least-once，
 *       consumer 靠 consumer_inbox 唯一键去重，投影计数不会被重复推进。</li>
 * </ol>
 *
 * <p>去重断言不靠 sleep：在重复消息后面，紧跟一条同 partition key、新 eventId 的 marker 事件。
 * 同 producer + 同 partition 保证 FIFO——当 marker 被消费（计数推进到 2）时，前面的重复消息一定已被处理过。
 * 若去重失效，重复消息会让计数变成 3，断言立刻失败。</p>
 */
@TestPropertySource(properties = "outbox.publisher.enabled=true")
// 本类启用了 outbox publisher（后台轮询线程）。跑完后关闭整个 context，避免缓存上下文里的 poller
// 在 Kafka 容器停掉后继续轮询、把其它测试类残留的 outbox 行投到已下线的 broker。
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OutboxKafkaInboxRoundtripIT extends MySqlIntegrationTestBase {

    // 复用宿主已缓存的 KRaft 原生镜像；新版 org.testcontainers.kafka.KafkaContainer 专门支持 apache/kafka。
    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.1"));

    private static final Currency JPY = Currency.getInstance("JPY");
    private static final String RISK_FEATURE_CONSUMER = "risk-feature-v1";
    private static final String APPROVED_EVENT_TYPE = "authorization.approved";

    @Autowired
    private com.minicard.authorization.application.AuthorizationService authorizationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${messaging.topics.authorization-events}")
    private String authorizationTopic;

    // authorize() 走真实风控会经 Feign 调外部服务，NONE web 环境下 fail-closed 拒绝；mock 成永远批准，
    // 让本测试专注于消息链路而不是风控结果。
    @MockitoBean
    private RiskAssessmentService riskAssessmentService;

    private String cardId;

    @BeforeEach
    void setUp() {
        when(riskAssessmentService.assess(any())).thenReturn(RiskDecision.approve(10));
        // 共享容器里可能残留其它测试类的投影/消息行，先清空与本测试断言相关的投影与消息表，保证计数干净。
        jdbc.update("DELETE FROM consumer_inbox");
        jdbc.update("DELETE FROM card_risk_features");
        jdbc.update("DELETE FROM notifications");
        jdbc.update("DELETE FROM outbox_events");

        String accountId = UUID.randomUUID().toString();
        cardId = "card-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO credit_accounts (id, credit_limit, reserved_amount, posted_balance, currency, status) "
                        + "VALUES (?, 1000000.00, 0.00, 0.00, 'JPY', 'ACTIVE')",
                accountId);
        jdbc.update(
                "INSERT INTO cards (id, credit_account_id, status) VALUES (?, ?, 'ACTIVE')",
                cardId, accountId);
    }

    @Test
    void outboxEventReachesConsumerThroughKafkaAndDuplicateDeliveryIsDeduplicated() throws Exception {
        // --- 1) 业务写路径：authorize() 在事务内落 outbox row ---
        Authorization auth = authorizationService.authorize(new AuthorizationCommand(
                "idem-" + UUID.randomUUID(), cardId, new BigDecimal("1000.00"), JPY,
                "merchant-1", "JP", "JP"));
        assertThat(auth.status()).isEqualTo(AuthorizationStatus.APPROVED);

        // outbox row 在 authorize 事务提交后立刻可见；eventId == outbox id，是整条链路的幂等键。
        Map<String, Object> outbox = approvedOutboxRow(auth.id().toString());
        UUID eventId = UUID.fromString((String) outbox.get("id"));
        String partitionKey = (String) outbox.get("partition_key");
        String envelopeJson = (String) outbox.get("payload");

        // --- 2) 往返：OutboxPoller 发到真 Kafka，consumer 消费并投影 + 写 inbox，outbox 标 PUBLISHED ---
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
            assertThat(inboxCount(eventId)).isEqualTo(1);
            assertThat(authorizationCount(cardId)).isEqualTo(1);
            assertThat(approvedCount(cardId)).isEqualTo(1);
        });

        // --- 3) 重复投递 + ordering barrier ---
        // 重复消息：完全相同的 envelope（同 eventId），模拟 broker at-least-once 再投一次。
        sendRaw(partitionKey, eventId, envelopeJson);
        // marker：同 partition key、新的 eventId（复用同一 payload 结构，仅换 eventId），紧跟在重复消息之后。
        UUID markerEventId = UUID.randomUUID();
        ObjectNode markerEnvelope = (ObjectNode) objectMapper.readTree(envelopeJson);
        markerEnvelope.put("eventId", markerEventId.toString());
        sendRaw(partitionKey, markerEventId, objectMapper.writeValueAsString(markerEnvelope));

        // marker 被消费意味着前面的重复消息一定已处理过（同 partition FIFO）。计数应推进到 2 而非 3。
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(authorizationCount(cardId)).isEqualTo(2));

        // 去重证据：重复的原 eventId 在 inbox 里仍只有一行；投影没有被重复推进。
        assertThat(inboxCount(eventId)).as("duplicate of the original event was deduplicated by the inbox").isEqualTo(1);
        assertThat(inboxCount(markerEventId)).isEqualTo(1);
        assertThat(authorizationCount(cardId)).as("two distinct events counted once each, duplicate not double-counted").isEqualTo(2);
        assertThat(approvedCount(cardId)).isEqualTo(2);
    }

    /** 按 publisher 完全一致的方式重投：topic + partition key + payload + eventId/eventType header。 */
    private void sendRaw(String partitionKey, UUID eventId, String payload) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(authorizationTopic, partitionKey, payload);
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", APPROVED_EVENT_TYPE.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private Map<String, Object> approvedOutboxRow(String aggregateId) {
        return jdbc.queryForMap(
                "SELECT id, partition_key, payload FROM outbox_events "
                        + "WHERE aggregate_id = ? AND event_type = ?",
                aggregateId, APPROVED_EVENT_TYPE);
    }

    private String outboxStatus(UUID eventId) {
        return jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE id = ?", String.class, eventId.toString());
    }

    private int inboxCount(UUID eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM consumer_inbox WHERE consumer_name = ? AND event_id = ?",
                Integer.class, RISK_FEATURE_CONSUMER, eventId.toString());
        return count == null ? 0 : count;
    }

    private int authorizationCount(String card) {
        return firstIntOrZero(
                "SELECT authorization_count FROM card_risk_features WHERE card_id = ?", card);
    }

    private int approvedCount(String card) {
        return firstIntOrZero(
                "SELECT approved_count FROM card_risk_features WHERE card_id = ?", card);
    }

    private int firstIntOrZero(String sql, Object arg) {
        List<Integer> rows = jdbc.queryForList(sql, Integer.class, arg);
        return rows.isEmpty() ? 0 : rows.get(0);
    }
}
