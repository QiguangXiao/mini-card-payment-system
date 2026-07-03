package com.minicard.notification.infrastructure.delivery;

import java.util.List;

import com.minicard.notification.application.NotificationDeliveryClaimer;
import com.minicard.notification.application.NotificationDeliveryWorker;
import com.minicard.notification.domain.delivery.NotificationDelivery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 投递 poller：只负责 poll、claim、submit worker，不直接调 provider。
 *
 * <p>关键词：投递扫描, 领取提交, worker pool, delivery poller,
 * reliable delivery, task rejection, 配信ポーラー(はいしんポーラー)。</p>
 *
 * <p>结构与 OutboxPoller 一致：claim 在短事务里完成并提交后，才把已领取投递交给 worker pool；
 * worker 在 finalize 前会重新校验 PROCESSING lease。queue 满触发 TaskRejectedException 时，
 * 立刻把投递放回 retry，避免它卡在 PROCESSING 直到 lease 过期才被 recoverer 捡回。</p>
 */
@Component
// 用配置开关保护：开发/迁移时可只创建 PENDING 投递、不实际外发，便于隔离排查副作用。
@ConditionalOnProperty(prefix = "notification.delivery", name = "enabled", havingValue = "true")
@Slf4j
public class NotificationDeliveryPoller {

    private final NotificationDeliveryClaimer claimer;
    private final NotificationDeliveryWorker worker;
    private final TaskExecutor deliveryWorkerExecutor;

    public NotificationDeliveryPoller(
            NotificationDeliveryClaimer claimer,
            NotificationDeliveryWorker worker,
            @Qualifier("notificationDeliveryWorkerExecutor") TaskExecutor deliveryWorkerExecutor
    ) {
        this.claimer = claimer;
        this.worker = worker;
        this.deliveryWorkerExecutor = deliveryWorkerExecutor;
    }

    /**
     * 周期性领取待投递记录并提交给 worker pool。
     */
    @Scheduled(
            fixedDelayString = "${notification.delivery.fixed-delay-ms:1000}",
            scheduler = "notificationDeliveryTaskScheduler"
    )
    public void pollDispatchableDeliveries() {
        // 阶段 1：短事务 claim 可投递记录。这里只写 PROCESSING lease，不调用外部 provider。
        // claim commit 后，其他 pod 会跳过这些 delivery row，避免同一通知并发外发。
        List<NotificationDelivery> deliveries = claimer.claimDispatchableDeliveries();
        // 阶段 2：把已领取 delivery 提交给投递 worker pool。
        // poller 线程保持很薄，provider timeout/retry 不会拖住下一轮扫描。
        for (NotificationDelivery delivery : deliveries) {
            try {
                // 阶段 3：worker 在事务外解析收件人、渲染模板、调用 provider，并在短事务里 finalize。
                deliveryWorkerExecutor.execute(() -> worker.handleClaimedDelivery(delivery));
            } catch (TaskRejectedException exception) {
                // 阶段 4：worker pool 拒绝时立即推进 retry/DEAD，不等 lease timeout 后 recoverer 再兜底。
                worker.markRejectedForRetry(delivery, exception);
                log.warn("notification_delivery_worker_rejected deliveryId={}", delivery.id(), exception);
            }
        }
    }
}
