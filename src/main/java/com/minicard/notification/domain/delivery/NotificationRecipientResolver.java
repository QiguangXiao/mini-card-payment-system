package com.minicard.notification.domain.delivery;

/**
 * 把 recipientKey 解析成可投递收件人的端口。
 *
 * <p>关键词：收件人解析端口, 渠道偏好, 联系方式, recipient resolver,
 * channel preference, contact lookup, 宛先解決ポート(あてさきかいけつポート)。</p>
 *
 * <p>这是未来 User/Customer 域接入的接缝(seam)。当前 stub 按 recipientKey 合成 email/push token 并默认
 * 启用全部渠道；真实实现会按 customerId 查联系方式与"用户是否关闭了某渠道"的偏好。</p>
 */
public interface NotificationRecipientResolver {

    /**
     * 解析收件人。返回的渠道集合决定一条通知会扇出成几条 delivery。
     */
    NotificationRecipient resolve(String recipientKey);
}
