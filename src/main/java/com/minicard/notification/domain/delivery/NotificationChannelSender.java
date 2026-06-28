package com.minicard.notification.domain.delivery;

/**
 * 单个渠道的发送端口（push / email 各一个实现）。
 *
 * <p>关键词：渠道发送端口, 外部接口, 幂等发送, channel sender,
 * external provider, idempotent send, チャネル送信ポート(チャネルそうしんポート)。</p>
 *
 * <p>实现负责调用外部 provider 并返回回执；失败抛运行时异常，由上层(Resilience4j + 投递状态机)处理重试。
 * channel() 让 worker 把所有实现收集成 EnumMap 路由，新增渠道无需改 worker，复用 DelayJobHandler 同样的注册思路。</p>
 */
public interface NotificationChannelSender {

    /** 声明该实现负责的渠道，worker 据此构建 channel→sender 路由表。 */
    NotificationChannel channel();

    /**
     * 发送一次投递并等待 provider 接收回执。
     *
     * <p>必须把 dispatch.idempotencyKey() 透传给外部接口；失败抛异常（不要吞掉），
     * 让 Resilience4j 做 intra-attempt 快速重试、投递状态机做 durable 重试。</p>
     */
    ProviderReceipt send(NotificationDispatch dispatch);
}
