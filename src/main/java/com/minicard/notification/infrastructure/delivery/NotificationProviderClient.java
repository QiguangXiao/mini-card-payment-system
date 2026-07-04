package com.minicard.notification.infrastructure.delivery;

import com.minicard.notification.domain.delivery.NotificationChannel;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 通知 provider 的 Feign client。
 *
 * <p>关键词：通知 provider, Feign, HTTP timeout, notification provider,
 * provider client, HTTP 連携(HTTPれんけい)。</p>
 *
 * <p>这是 notification 对外部 email/push 网关的 HTTP 边界。当前 url 默认指向本应用内的
 * {@code SimulatedNotificationProviderController}，但调用仍走真实 HTTP/JSON/Feign 链路，
 * 因此 connect/read timeout 可以由 {@code spring.cloud.openfeign.client.config.notification-provider}
 * 配置并真实生效。</p>
 *
 * <p>Feign 自身保持默认不重试（Spring Cloud OpenFeign 默认 {@code Retryer.NEVER_RETRY}）；
 * notification 的快速重试统一由 {@link ResilientCallHelper} 管理，避免两层 retry 相乘。</p>
 *
 * <p>注意这里没有再抽一个 provider gateway port：当前只有两个 sender 使用它，且 worker 已经通过
 * {@code NotificationDeliverySender} 隔离了状态机和 provider 细节。再加 port 会把刚压平的链路重新撑厚。</p>
 */
@FeignClient(
        name = "notification-provider",
        url = "${notification.delivery.provider-base-url}",
        configuration = NotificationProviderFeignConfiguration.class
)
public interface NotificationProviderClient {

    /**
     * 发送一条已经渲染好的通知请求。
     */
    @PostMapping("/simulated-provider/notifications/send")
    NotificationProviderResponse send(@RequestBody NotificationProviderRequest request);

    /**
     * 发给外部 provider 的请求快照。
     *
     * <p>provider/channel/address/title/body/idempotencyKey 放在同一个 DTO 里，是为了让 HTTP 边界自洽：
     * Feign 调用失败重试时不需要回查 Notification 或业务表。</p>
     */
    record NotificationProviderRequest(
            /** provider 名，例如 push/email；用于模拟不同外部系统各自独立幂等。 */
            String providerName,
            /** 投递渠道，例如 APP_PUSH/EMAIL。 */
            NotificationChannel channel,
            /** 已解析好的收件地址，例如 device token 或 email。 */
            String recipientAddress,
            /** 已渲染标题。 */
            String title,
            /** 已渲染正文。 */
            String body,
            /** delivery id 派生的稳定幂等键，跨 retry 不变。 */
            String idempotencyKey
    ) {
    }

    /**
     * 外部 provider 回执。
     */
    record NotificationProviderResponse(
            /** provider 返回的 message id；worker 拿到它后才能 mark SENT。 */
            String providerMessageId
    ) {
    }
}
