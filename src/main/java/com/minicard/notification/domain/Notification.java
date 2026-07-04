package com.minicard.notification.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 通知 aggregate root：只表达"要发给谁、就哪个业务事实、发哪一种通知"这一意图(intent)。
 *
 * <p>关键词：通知意图, 投递解耦, source event id, notification intent,
 * delivery decoupling, idempotency, 通知意図(つうちいと), 配信(はいしん)。</p>
 *
 * <p>历史版本把"投递生命周期"(已发送/失败/重试次数/sentAt)塞在这个聚合里。但投递是 <b>per-channel</b>
 * 的事实：同一条通知可能 app push 成功、email 仍在重试，单个 status 字段无法同时表达。现在投递状态
 * 完全搬到 {@link com.minicard.notification.domain.delivery.NotificationDelivery}；Notification 退回成
 * 不可变意图，由 integration event 创建后不再变化。Kafka listener、admin replay、未来 User 域都复用它。</p>
 */
// 只用 Lombok getter、且所有字段 final：意图不可变，避免任何代码顺手 set 出一条半截通知。
// fluent accessor 让 notification.type() 贴近 record 风格，和 domain 其它聚合一致。
@Getter
@Accessors(fluent = true)
public final class Notification {

    /** Notification 意图 id；delivery 表通过 notificationId 关联到这条不可变意图。 */
    private final UUID id;
    /** 来源 integration event id；repository 唯一键用它抵御 Kafka duplicate delivery。 */
    private final UUID sourceEventId;
    /** 通知围绕的业务对象类型，例如 AUTHORIZATION、CARD_TRANSACTION、STATEMENT。 */
    private final NotificationSubjectType subjectType;
    /** 业务对象 id；用于模板渲染、audit 和排查通知来自哪条业务事实。 */
    private final String subjectId;
    /** 收件人解析 key；当前还没有 User/Cardholder 聚合，所以不是最终联系方式。 */
    private final String recipientKey;
    /** 通知类型，表达为什么发通知，例如授权通过、还款入账、账单可用。 */
    private final NotificationType type;
    /** 通知意图创建时间；投递时间由 NotificationDelivery.sentAt 表达。 */
    private final Instant createdAt;
    /** 保留 updatedAt 是为了表结构一致和未来 admin 修正；当前意图创建后不再业务性变更。 */
    private final Instant updatedAt;

    private Notification(
            UUID id,
            UUID sourceEventId,
            NotificationSubjectType subjectType,
            String subjectId,
            String recipientKey,
            NotificationType type,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = Objects.requireNonNull(id);
        this.sourceEventId = Objects.requireNonNull(sourceEventId);
        this.subjectType = Objects.requireNonNull(subjectType);
        this.subjectId = requireText(subjectId, "subjectId");
        this.recipientKey = requireText(recipientKey, "recipientKey");
        this.type = Objects.requireNonNull(type);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt);
    }

    /**
     * 根据 integration event 创建通知意图。
     *
     * <p>这里生成 notification id；sourceEventId 被保留为 idempotency key。
     * Kafka 是 at-least-once，同一事件可能重复触发创建请求，repository 的 source_event_id 唯一索引
     * 保证最终只落一条通知。投递记录在 application service 内、同一事务里随之创建。</p>
     *
     * <p>提醒：当前项目还没有 User/Cardholder 聚合，recipientKey 暂时是账户/卡线索
     * (authorization/transaction 用 cardId，repayment 用 creditAccountId)。接真实用户模型时，
     * 这里应改成 customerId，并由 sender/未来的 channel selector 去查联系方式与渠道偏好。</p>
     */
    public static Notification requestFromEvent(
            UUID sourceEventId,
            NotificationSubjectType subjectType,
            String subjectId,
            String recipientKey,
            NotificationType type,
            Instant now
    ) {
        return new Notification(
                UUID.randomUUID(),
                sourceEventId,
                subjectType,
                subjectId,
                recipientKey,
                type,
                now,
                now
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
