package com.minicard.notification.infrastructure.external;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.minicard.notification.application.NotificationDeliveryProperties;
import com.minicard.notification.infrastructure.delivery.NotificationProviderClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地模拟第三方 notification provider API，让 Feign 调用链可学习、可运行。
 *
 * <p>关键词：模拟通知网关, Feign 回环, provider 幂等, simulated notification provider,
 * HTTP loopback, provider-side idempotency, 外部通知模擬(がいぶつうちもぎ)。</p>
 *
 * <p>虽然 controller 和 worker 在同一个 Spring Boot 进程里，但 sender 访问它时仍经过 HTTP/JSON/Feign。
 * 这和 risk 模块的 {@code SimulatedExternalRiskController} 是同一种学习手法：本地就能观察
 * connect/read timeout、HTTP 序列化、Retry、CircuitBreaker 和 provider 慢/失败的交互。</p>
 *
 * <p>本地回环会同时占用 notification worker 线程和 Tomcat request 线程；默认 worker=4、Tomcat 默认线程较大，
 * 学习环境不会死锁。生产里 provider 是独立服务，这个自我竞争只存在于本地 demo。</p>
 *
 * <p>它保留三件教学机制：
 * 1) 按 provider + idempotencyKey 去重，演示 at-least-once delivery + 下游幂等 = effectively-once；
 * 2) 注入失败率，驱动 intra-attempt Retry/CircuitBreaker 和 durable attempts/backoff/DEAD；
 * 3) 注入延迟，让 Feign read timeout、slow-call CircuitBreaker 和 processing lease 的边界可观察。</p>
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SimulatedNotificationProviderController {

    // key = providerName + ":" + idempotencyKey。
    // 同一个 delivery id 同时投 push/email 时，两个 provider 都应该各自得到一次发送；
    // 如果只用 idempotencyKey 做 key，push 成功后 email 会被误判成重复请求。
    private final Map<String, String> deliveredByProviderAndIdempotencyKey = new ConcurrentHashMap<>();
    private final NotificationDeliveryProperties properties;

    @PostMapping("/simulated-provider/notifications/send")
    public NotificationProviderClient.NotificationProviderResponse send(
            @RequestBody NotificationProviderClient.NotificationProviderRequest request
    ) {
        String dedupKey = request.providerName() + ":" + request.idempotencyKey();
        // 阶段 1：先查 provider-side idempotency。重复 retry/网络重放直接返回原 message id，
        // 不再产生第二次外部副作用。真实 provider 是否支持 Idempotency-Key 要按供应商能力确认。
        String existingMessageId = deliveredByProviderAndIdempotencyKey.get(dedupKey);
        if (existingMessageId != null) {
            log.info("notification_provider_deduplicated provider={} idempotencyKey={} messageId={}",
                    request.providerName(), request.idempotencyKey(), existingMessageId);
            return new NotificationProviderClient.NotificationProviderResponse(existingMessageId);
        }

        // 阶段 2：模拟外部系统的慢和失败。现在慢的是 HTTP 响应本身，
        // Feign read-timeout 可以真实中断等待；R4j helper 只观察异常并做 retry/breaker。
        simulateLatency();
        simulateFailure(request.providerName(), request.idempotencyKey());

        // 阶段 3：用 computeIfAbsent 模拟 provider 的原子去重承诺；并发重复请求只会得到同一个 message id。
        // providerMessageId 是"外部网关回执 id"，worker 只有拿到它才会 markSent。
        String providerMessageId = deliveredByProviderAndIdempotencyKey.computeIfAbsent(
                dedupKey,
                key -> request.providerName() + "-" + UUID.randomUUID()
        );
        log.info(
                "notification_provider_sent provider={} channel={} address={} title=\"{}\" bodyLength={} "
                        + "idempotencyKey={} messageId={}",
                request.providerName(), request.channel(), request.recipientAddress(), request.title(),
                request.body().length(), request.idempotencyKey(), providerMessageId
        );
        return new NotificationProviderClient.NotificationProviderResponse(providerMessageId);
    }

    private void simulateLatency() {
        long latency = properties.simulatedLatencyMillis();
        if (latency <= 0) {
            return;
        }
        try {
            // 这是模拟第三方 HTTP 响应变慢，不是本地超时控制。
            // 真实切断等待的是 Feign/HTTP client 的 read-timeout。
            Thread.sleep(latency);
        } catch (InterruptedException exception) {
            // 捕获 InterruptedException 后恢复 interrupt flag，避免应用关闭时吞掉中断信号。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("simulated notification provider call was interrupted", exception);
        }
    }

    private void simulateFailure(String providerName, String idempotencyKey) {
        int failureRate = properties.simulatedFailureRatePercent();
        if (failureRate > 0 && ThreadLocalRandom.current().nextInt(100) < failureRate) {
            // 抛异常且不写去重表：模拟 provider 没有确认投递，下一次 retry 可用同一 idempotencyKey 再试。
            throw new IllegalStateException(
                    "simulated " + providerName + " provider failure for " + idempotencyKey);
        }
    }
}
