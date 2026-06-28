package com.minicard.notification.domain.delivery;

import java.util.Map;
import java.util.Set;

/**
 * 解析后的收件人：customerId + 各渠道地址。
 *
 * <p>关键词：收件人解析, 渠道地址, 渠道偏好, notification recipient,
 * channel address, contact resolution, 宛先解決(あてさきかいけつ)。</p>
 *
 * <p>它是 {@link NotificationRecipientResolver} 的输出，也是"当前还没有 User 聚合"这一缺口的唯一收口点：
 * 现在由 stub 合成地址；接真实用户模型后，只换 resolver 实现，delivery/worker/sender 都不动。</p>
 */
public record NotificationRecipient(String customerId, Map<NotificationChannel, String> channelAddresses) {

    public NotificationRecipient {
        if (customerId == null || customerId.isBlank()) {
            throw new IllegalArgumentException("customerId must not be blank");
        }
        // 防御性拷贝成不可变 Map：避免调用方在解析之后又改了地址表，造成"渲染时看到的地址"前后不一致。
        channelAddresses = Map.copyOf(channelAddresses);
    }

    /** 该收件人启用的渠道集合（创建投递时据此扇出）。 */
    public Set<NotificationChannel> channels() {
        return channelAddresses.keySet();
    }

    /** 某渠道的目标地址；未配置返回 null，由 worker 判失败。 */
    public String addressFor(NotificationChannel channel) {
        return channelAddresses.get(channel);
    }
}
