package com.minicard.notification.infrastructure.delivery;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import com.minicard.notification.application.NotificationDeliveryProperties;
import com.minicard.notification.domain.delivery.NotificationChannelSender;
import com.minicard.notification.domain.delivery.NotificationDispatch;
import com.minicard.notification.domain.delivery.ProviderReceipt;
import lombok.extern.slf4j.Slf4j;

/**
 * 模拟外部 provider 的 channel sender 基类（push/email 共用）。
 *
 * <p>关键词：模拟外部接口, 幂等去重, 故障注入, 延迟注入, simulated provider,
 * idempotent dedup, fault injection, 外部接口模拟(がいぶインターフェースもぎ)。</p>
 *
 * <p>它替代真实 push/email SDK，刻意做三件用于教学/演示的事：
 * 1) 按 idempotencyKey 去重——证明"至少一次投递 + 下游去重 = 有效恰好一次"；
 * 2) 可注入失败率——驱动 Resilience4j 重试/断路器与投递状态机的退避/DEAD；
 * 3) 可注入延迟——让 Resilience4j TimeLimiter 的超时可被观察到。
 * 接真实 provider 时只换这层实现（可用 OpenFeign/WebClient），上层 worker/状态机不动。</p>
 */
@Slf4j
public abstract class SimulatedChannelSender implements NotificationChannelSender {

    // idempotencyKey -> 回执：模拟外部接口按幂等键去重。真实 provider 通常也提供 Idempotency-Key 头。
    // 进程内 Map 仅用于演示；真实下游的去重发生在 provider 侧，跨进程持久。
    private final Map<String, ProviderReceipt> deliveredByIdempotencyKey = new ConcurrentHashMap<>();
    private final NotificationDeliveryProperties properties;

    protected SimulatedChannelSender(NotificationDeliveryProperties properties) {
        this.properties = properties;
    }

    /** provider 名，用于回执前缀与日志，例如 "push" / "email"。 */
    protected abstract String providerName();

    @Override
    public ProviderReceipt send(NotificationDispatch dispatch) {
        // 第一步就按幂等键短路：重复请求(网络重放/我们自己的重试)直接返回原回执，不再产生第二次副作用。
        ProviderReceipt existing = deliveredByIdempotencyKey.get(dispatch.idempotencyKey());
        if (existing != null) {
            log.info("notification_provider_deduplicated provider={} idempotencyKey={} messageId={}",
                    providerName(), dispatch.idempotencyKey(), existing.providerMessageId());
            return existing;
        }

        simulateLatency();
        simulateFailure(dispatch);

        // computeIfAbsent 保证并发下同一 idempotencyKey 只生成一个回执，模拟 provider 的幂等承诺。
        ProviderReceipt receipt = deliveredByIdempotencyKey.computeIfAbsent(
                dispatch.idempotencyKey(),
                key -> new ProviderReceipt(providerName() + "-" + UUID.randomUUID())
        );
        log.info("notification_provider_sent provider={} channel={} address={} idempotencyKey={} messageId={}",
                providerName(), dispatch.channel(), dispatch.recipientAddress(),
                dispatch.idempotencyKey(), receipt.providerMessageId());
        return receipt;
    }

    private void simulateLatency() {
        long latency = properties.simulatedLatencyMillis();
        if (latency <= 0) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException exception) {
            // 恢复 interrupt flag：TimeLimiter 取消任务时会 interrupt 工作线程，不能吞掉信号。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("simulated send interrupted", exception);
        }
    }

    private void simulateFailure(NotificationDispatch dispatch) {
        int failureRate = properties.simulatedFailureRatePercent();
        if (failureRate > 0 && ThreadLocalRandom.current().nextInt(100) < failureRate) {
            // 抛异常(不写入去重表)：模拟一次"未送达"，让上层重试。下次重试拿同一 idempotencyKey 再试。
            throw new IllegalStateException(
                    "simulated " + providerName() + " provider failure for " + dispatch.idempotencyKey());
        }
    }
}
