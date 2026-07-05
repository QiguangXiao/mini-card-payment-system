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
 * <p>这里的 Spring MVC 注解语义和 Controller 里相反，容易混淆：
 * 在 {@code @RestController} 方法上，{@code @PostMapping} 表示"我接收这个 POST"；
 * 在 {@code @FeignClient} interface 方法上，{@code @PostMapping} 表示"Feign 代理要发出这个 POST"。
 * Spring Cloud OpenFeign 复用这些注解来描述 outbound HTTP contract。</p>
 *
 * <p>运行时调用链：</p>
 * <pre>
 * sender 调用 notificationProviderClient.send(request)
 *   -> Feign 动态代理读取 @FeignClient.url 作为 base URL
 *   -> 读取 @PostMapping 作为 path 和 HTTP method
 *   -> 把 @RequestBody request 序列化成 JSON
 *   -> POST {provider-base-url}/simulated-provider/notifications/send
 *   -> 把 JSON response 反序列化成 NotificationProviderResponse
 * </pre>
 *
 * <p>生产用法和本地模拟的分界：到本 interface 为止都是一般生产写法；当前之所以打回本应用，
 * 是因为 {@code notification.delivery.provider-base-url=http://localhost:8080}，接收方是本地
 * {@code SimulatedNotificationProviderController}。生产环境只需要把 base URL 改成真实 provider
 * 或 provider wrapper 服务，Feign client 的调用方式不变。</p>
 *
 * <p>请求/响应 record 是"调用方眼里的 provider API contract"。真实跨服务时，调用方和 provider 方必须提前约定
 * JSON 字段名、类型、含义、必填性和兼容变更策略；双方通常各自定义 DTO，或从 OpenAPI/AsyncAPI schema
 * codegen。当前本地模拟 controller 也定义了自己的 request/response record：名字和 Java class 可以不同，
 * 但 JSON 字段必须对齐，这样才像真实外部服务，而不是两个模块共享同一个内部对象。
 * 如果以后字段变更，要按兼容方式演进：新增可选字段通常安全；删除/改名/改类型会破坏旧客户端，必须版本化或双写双读。</p>
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
    // Feign client 上的 @PostMapping 不是 Controller endpoint，而是 outbound request 描述：
    // method=POST，path=/simulated-provider/notifications/send。
    // @FeignClient.url 提供 base URL；二者拼起来就是最终请求地址。
    @PostMapping("/simulated-provider/notifications/send")
    NotificationProviderResponse send(@RequestBody NotificationProviderRequest request);

    /**
     * 发给外部 provider 的请求快照。
     *
     * <p>provider/channel/address/title/body/idempotencyKey 放在同一个 DTO 里，是为了让 HTTP 边界自洽：
     * Feign 调用失败重试时不需要回查 Notification 或业务表。</p>
     *
     * <p>调用方和 provider 方必须提前约定JSON 字段名、类型、含义、必填性和兼容变更策略。
     * 这些字段不是随便起名的内部对象字段，而是 HTTP JSON contract。真实 provider 也必须能理解同名字段，
     * 或者通过自己的 schema 映射到等价字段。</p>
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
     *
     * <p>调用方和 provider 方必须提前约定JSON 字段名、类型、含义、必填性和兼容变更策略。
     *     调用方只依赖 providerMessageId，是因为 worker 只需要一个"外部已接收/已发送"的证据来 mark SENT。
     * 真实 provider 若返回更多字段，除非状态机或对账需要，不要把无用字段扩散进 domain。</p>
     */
    record NotificationProviderResponse(
            /** provider 返回的 message id；worker 拿到它后才能 mark SENT。 */
            String providerMessageId
    ) {
    }
}
