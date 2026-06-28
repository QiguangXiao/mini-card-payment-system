package com.minicard.notification.domain.delivery;

/**
 * 投递发送门面端口：按渠道路由并保证弹性(超时/重试/断路)的"发一次"。
 *
 * <p>关键词：发送门面, 渠道路由, 弹性发送, notification sender,
 * resilient facade, 送信ファサード(そうしんファサード)。</p>
 *
 * <p>它与 per-channel 的 {@link NotificationChannelSender} 分工不同：ChannelSender 是某个 provider 的
 * 原始调用；NotificationSender 是 worker 依赖的门面——按 dispatch.channel() 选 sender，并叠加
 * Resilience4j。worker 依赖这个端口而非具体实现，等价于 OutboxWorker 依赖 OutboxMessagePublisher。</p>
 */
public interface NotificationSender {

    /**
     * 发送一次投递；失败(超时/断路打开/重试耗尽/provider 异常)统一抛 NotificationDeliveryException。
     */
    ProviderReceipt send(NotificationDispatch dispatch);
}
