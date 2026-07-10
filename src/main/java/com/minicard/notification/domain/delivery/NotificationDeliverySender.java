package com.minicard.notification.domain.delivery;

/**
 * 单渠道通知投递 sender。
 *
 * <p>关键词：渠道 sender, provider 调用, 幂等键, notification delivery sender,
 * provider call, idempotency key, 配信送信(はいしんそうしん)。</p>
 *
 * <p>这是 worker 真正需要的唯一发送接缝：worker 负责 delivery 状态机、lease 校验和 retry/DEAD；
 * sender 负责某个 channel 的地址组装、文案组装、provider 调用与 provider-side resilience。
 * 当前项目没有 User 域和模板引擎，所以不再提前拆 resolver/renderer/dispatch/receipt 这些端口。</p>
 *
 * <p>真实 provider 的 connect/read timeout 应在 HTTP client 或 SDK 内配置；本接口外层只保留
 * Retry + RateLimiter + CircuitBreaker。不要为了超时重新引入 TimeLimiter，否则又需要额外线程池和 async 包装，
 * 会把这条同步 worker 链路重新复杂化。</p>
 */
public interface NotificationDeliverySender {

    /** 本 sender 支持的投递渠道。 */
    NotificationChannel channel();

    /**
     * 发送一条已经领取的 delivery，成功时返回 provider message id。
     *
     * <p>失败直接抛 {@link RuntimeException}：provider 失败由 worker markFailed，推进
     * attempts/backoff/DEAD；HTTP 前的本地 throttling 用专门异常只延后、不增加 attempts。
     * sender 不直接更新 DB，避免 provider 细节污染状态机边界。</p>
     */
    String send(NotificationDelivery delivery);
}
