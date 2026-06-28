package com.minicard.notification.infrastructure.delivery;

import com.minicard.notification.domain.NotificationType;
import com.minicard.notification.domain.delivery.NotificationChannel;
import com.minicard.notification.domain.delivery.NotificationContent;
import com.minicard.notification.domain.delivery.NotificationTemplateRenderer;
import org.springframework.stereotype.Component;

/**
 * 默认模板渲染：按 NotificationType 给标题/正文，按渠道微调形态。
 *
 * <p>关键词：模板渲染实现, 类型路由, 渠道形态, template rendering,
 * type routing, channel formatting, 文面生成(ぶんめんせいせい)。</p>
 *
 * <p>当前是纯函数式的简化实现：用 switch 把 type 映射成文案，email 比 push 多一行落款。
 * 接模板引擎/多语言/AB 文案时只换本类。switch 用 enum 穷举，新增 type 时编译器(或测试)会提醒补文案。</p>
 */
@Component
public class DefaultNotificationTemplateRenderer implements NotificationTemplateRenderer {

    @Override
    public NotificationContent render(NotificationType type, NotificationChannel channel, String subjectId) {
        String title = titleFor(type);
        String body = bodyFor(type, subjectId);
        if (channel == NotificationChannel.EMAIL) {
            // email 形态更正式，附落款；push 保持短。真实系统会用不同模板文件，而不是字符串拼接。
            body = body + "\n\n— Mini Card";
        }
        return new NotificationContent(title, body);
    }

    private String titleFor(NotificationType type) {
        return switch (type) {
            case AUTHORIZATION_APPROVED -> "Authorization approved";
            case AUTHORIZATION_DECLINED -> "Authorization declined";
            case CARD_TRANSACTION_POSTED -> "Transaction posted";
            case STATEMENT_CLOSED -> "Statement ready";
            case REPAYMENT_RECEIVED -> "Repayment received";
        };
    }

    private String bodyFor(NotificationType type, String subjectId) {
        return switch (type) {
            case AUTHORIZATION_APPROVED ->
                    "Your card authorization " + subjectId + " was approved.";
            case AUTHORIZATION_DECLINED ->
                    "Your card authorization " + subjectId + " was declined.";
            case CARD_TRANSACTION_POSTED ->
                    "Your card transaction " + subjectId + " has been posted to your account.";
            case STATEMENT_CLOSED ->
                    "Your statement " + subjectId + " is ready. Please review your amount due and due date.";
            case REPAYMENT_RECEIVED ->
                    "We received your repayment " + subjectId + ". Thank you.";
        };
    }
}
