package com.minicard.notification.infrastructure.delivery;

import java.util.LinkedHashMap;
import java.util.Map;

import com.minicard.notification.domain.delivery.NotificationChannel;
import com.minicard.notification.domain.delivery.NotificationRecipient;
import com.minicard.notification.domain.delivery.NotificationRecipientResolver;
import org.springframework.stereotype.Component;

/**
 * 收件人解析的 stub 实现（当前还没有 User/Customer 聚合）。
 *
 * <p>关键词：收件人 stub, 合成地址, 渠道默认, recipient stub,
 * synthesized address, 宛先スタブ(あてさきスタブ)。</p>
 *
 * <p>它把 recipientKey 直接当作客户标识，并合成确定性的 email / push token，默认启用全部渠道。
 * 这是"没有用户域"这一缺口的唯一收口点：接入真实用户模型后，只替换本类——
 * 改成按 customerId 查联系方式、并读取"用户是否关闭了某渠道"的偏好——其余代码不动。</p>
 */
@Component
public class StubNotificationRecipientResolver implements NotificationRecipientResolver {

    @Override
    public NotificationRecipient resolve(String recipientKey) {
        // LinkedHashMap 固定渠道顺序，让扇出的 delivery 顺序在测试里可预期。
        Map<NotificationChannel, String> addresses = new LinkedHashMap<>();
        addresses.put(NotificationChannel.APP_PUSH, "push-token-" + recipientKey);
        addresses.put(NotificationChannel.EMAIL, "user-" + recipientKey + "@example.com");
        // customerId 暂时等于 recipientKey；真实实现这里才是 cardId/accountId -> customerId 的映射点。
        return new NotificationRecipient(recipientKey, addresses);
    }
}
