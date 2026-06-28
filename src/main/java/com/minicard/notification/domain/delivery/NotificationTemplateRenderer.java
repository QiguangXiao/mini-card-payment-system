package com.minicard.notification.domain.delivery;

import com.minicard.notification.domain.NotificationType;

/**
 * 把 (type, channel) 渲染成用户可见文案的端口。
 *
 * <p>关键词：模板渲染, 渠道差异, 文案生成, template renderer,
 * channel-specific rendering, 文面生成(ぶんめんせいせい)。</p>
 *
 * <p>type 决定"说什么"，channel 决定"用什么形态说"(push 短、email 长)。把渲染独立成端口，
 * 以后接模板引擎/多语言/AB 文案时只换实现，不动投递状态机与 sender。</p>
 */
public interface NotificationTemplateRenderer {

    /**
     * 渲染指定类型、渠道的通知内容；subjectId 作为可填入文案的业务标识。
     */
    NotificationContent render(NotificationType type, NotificationChannel channel, String subjectId);
}
