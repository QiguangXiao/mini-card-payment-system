package com.minicard.notification.domain.delivery;

/**
 * 单条 per-channel 投递的生命周期状态。
 *
 * <p>关键词：投递状态, 处理租约, 死信, delivery status,
 * processing lease, dead letter, 配信状態(はいしんじょうたい)。</p>
 *
 * <p>状态机与 outbox_events / delay_jobs 同构：PENDING 等待调度，PROCESSING 是 worker 领取后的
 * 短租约(lease)，SENT 是 provider 已确认的终态，DEAD 是超过 maxAttempts 的终态。失败会回到 PENDING
 * 并带指数退避的 nextAttemptAt。</p>
 */
public enum NotificationDeliveryStatus {
    /** 待投递（nextAttemptAt 是下次可投递时间）。 */
    PENDING,
    /** 已被 worker 领取，nextAttemptAt 临时复用为 lease deadline。 */
    PROCESSING,
    /** provider 已确认接收。 */
    SENT,
    /** 超过最大重试次数，需人工介入。 */
    DEAD
}
