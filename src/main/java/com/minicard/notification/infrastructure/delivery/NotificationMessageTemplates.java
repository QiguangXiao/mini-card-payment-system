package com.minicard.notification.infrastructure.delivery;

import com.minicard.notification.domain.NotificationType;
import com.minicard.notification.domain.delivery.NotificationChannel;

/**
 * 当前 demo sender 共用的文案模板。
 *
 * <p>关键词：通知文案, 模板快照, 类型路由, notification template,
 * type routing, 文面生成(ぶんめんせいせい)。</p>
 *
 * <p>这不是可替换端口，只是避免 email/push sender 复制 switch。真实模板引擎、多语言、AB 文案出现前，
 * 保持一个包私有 helper 比提前抽象 TemplateRenderer 更容易读。</p>
 */
final class NotificationMessageTemplates {

    private NotificationMessageTemplates() {
    }

    static String titleFor(NotificationType type) {
        return switch (type) {
            case AUTHORIZATION_APPROVED -> "Authorization approved";
            case AUTHORIZATION_DECLINED -> "Authorization declined";
            case CARD_TRANSACTION_POSTED -> "Transaction posted";
            case REPAYMENT_RECEIVED -> "Repayment received";
        };
    }

    static String bodyFor(NotificationType type, NotificationChannel channel, String subjectId) {
        String body = switch (type) {
            case AUTHORIZATION_APPROVED ->
                    "Your card authorization " + subjectId + " was approved.";
            case AUTHORIZATION_DECLINED ->
                    "Your card authorization " + subjectId + " was declined.";
            case CARD_TRANSACTION_POSTED ->
                    "Your card transaction " + subjectId + " has been posted to your account.";
            case REPAYMENT_RECEIVED ->
                    "We received your repayment " + subjectId + ". Thank you.";
        };
        if (channel == NotificationChannel.EMAIL) {
            // email 形态更正式，附落款；push 保持短。真实系统会用不同模板文件，而不是字符串拼接。
            return body + "\n\n- Mini Card";
        }
        return body;
    }
}
