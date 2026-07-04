package com.minicard.notification.infrastructure.delivery;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.minicard.notification.application.NotificationDeliveryProperties;
import com.minicard.notification.domain.delivery.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 模拟外部 push/email provider 的共享小组件。
 *
 * <p>关键词：模拟 provider, 下游幂等, 故障注入, 延迟注入, simulated provider,
 * provider-side idempotency, fault injection, 外部接口模拟(がいぶインターフェースもぎ)。</p>
 *
 * <p>它不是业务 domain，也不是真实 SDK，只是当前项目里的 fake provider：
 * email/push sender 先把地址、标题、正文准备好，再调用本类，模拟"外部网关已接收请求并返回 message id"。
 * 因为现在是 JVM 内部函数调用，所以没有 HTTP connect/read timeout；以后接 SendGrid/SES/FCM/APNs 时，
 * 这里会被 Feign/WebClient/SDK 调用替换，timeout 应配置在那个 HTTP client/SDK 层。</p>
 *
 * <p>它保留三件教学机制：
 * 1) 按 provider + idempotencyKey 去重，演示"at-least-once delivery + 下游幂等 = effectively-once"；
 * 2) 注入失败率，驱动 intra-attempt Retry/CircuitBreaker 和 durable attempts/backoff/DEAD；
 * 3) 注入延迟，让 slow-call CircuitBreaker 与 processing lease 的关系可观察。
 * 进程内 Map 只用于 demo；真实生产里去重发生在支持 Idempotency-Key 的 provider 侧。</p>
 */
@Component
@Slf4j
class SimulatedProvider {

    // key = providerName + ":" + idempotencyKey。
    // 这里按 provider 分桶很重要：同一个 delivery id 同时投 push/email 时，两个 provider 都应该各自得到一次发送。
    // 如果只用 idempotencyKey 做 key，push 成功后 email 会被误判成重复请求。
    private final Map<String, String> deliveredByProviderAndIdempotencyKey = new ConcurrentHashMap<>();
    // 读取 application.yml 的模拟参数：latency 用来制造 slow-call，failureRate 用来制造 provider 5xx/timeout 类失败。
    private final NotificationDeliveryProperties properties;

    SimulatedProvider(NotificationDeliveryProperties properties) {
        this.properties = properties;
    }

    String send(
            String providerName,
            NotificationChannel channel,
            String recipientAddress,
            String title,
            String body,
            String idempotencyKey
    ) {
        String dedupKey = providerName + ":" + idempotencyKey;
        // 阶段 1：先查 provider-side idempotency。重复 retry/网络重放直接返回原 message id，
        // 不再产生第二次外部副作用。
        // 真实 provider 的能力差异很大：有的支持 Idempotency-Key/requestId，有的只返回 messageId 不承诺去重。
        // 本项目用这段代码演示"如果下游支持幂等，我们的 delivery.id 就是可以传下去的稳定 key"。
        String existingMessageId = deliveredByProviderAndIdempotencyKey.get(dedupKey);
        if (existingMessageId != null) {
            log.info("notification_provider_deduplicated provider={} idempotencyKey={} messageId={}",
                    providerName, idempotencyKey, existingMessageId);
            return existingMessageId;
        }

        // 阶段 2：模拟外部系统的慢和失败。这里的 sleep/throw 代表真实 HTTP call 中的慢响应、5xx、连接失败等。
        // 这些异常会被 ResilientCallHelper 的 Retry/CircuitBreaker 观察到，最终由 worker 落库为 retry/DEAD。
        simulateLatency();
        simulateFailure(providerName, idempotencyKey);

        // 阶段 3：用 computeIfAbsent 模拟 provider 的原子去重承诺；并发重复请求只会得到同一个 message id。
        // providerMessageId 是"外部网关回执 id"，worker 只有拿到它才会 markSent。
        String providerMessageId = deliveredByProviderAndIdempotencyKey.computeIfAbsent(
                dedupKey,
                key -> providerName + "-" + UUID.randomUUID()
        );
        log.info(
                "notification_provider_sent provider={} channel={} address={} title=\"{}\" bodyLength={} "
                        + "idempotencyKey={} messageId={}",
                providerName, channel, recipientAddress, title, body.length(), idempotencyKey, providerMessageId
        );
        return providerMessageId;
    }

    private void simulateLatency() {
        long latency = properties.simulatedLatencyMillis();
        if (latency <= 0) {
            return;
        }
        try {
            // 注意：这是 demo 的阻塞 sleep，不是超时控制。真实超时应由 Feign/WebClient/SDK 的配置触发异常。
            // 这段 sleep 的价值是让 CircuitBreaker 的 slow-call-duration-threshold 能观察到"慢 provider"。
            Thread.sleep(latency);
        } catch (InterruptedException exception) {
            // 恢复 interrupt flag：应用关闭或测试中断时不能吞掉信号。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("simulated send interrupted", exception);
        }
    }

    private void simulateFailure(String providerName, String idempotencyKey) {
        int failureRate = properties.simulatedFailureRatePercent();
        if (failureRate > 0 && ThreadLocalRandom.current().nextInt(100) < failureRate) {
            // 抛异常且不写去重表：模拟 provider 没有确认投递，下一次 retry 可用同一 idempotencyKey 再试。
            // 如果这里先写去重表再抛异常，就变成"provider 已接收但我们没拿到回执"的另一种故障窗口；
            // 当前 demo 先覆盖更直观的 transient failure。
            throw new IllegalStateException(
                    "simulated " + providerName + " provider failure for " + idempotencyKey);
        }
    }
}
