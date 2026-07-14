package com.minicard.infrastructure.scheduler;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Scheduler 基础设施：复用相同 ThreadPoolTaskScheduler 创建方式，但按机制分 poller pool。
 *
 * <p>关键词：定时任务, poller pool, 调度线程, scheduler configuration,
 * ThreadPoolTaskScheduler, polling, スケジューラー,
 * 定期実行(ていきじっこう)。</p>
 *
 * <p>Outbox 和 DelayJob 都是 polling scheduler，所以配置方式对称。
 * DelayJob 的业务 worker executor 放在 infrastructure.async，避免 poller pool 和 worker pool 混在一起。</p>
 */
@Configuration
// @EnableScheduling 打开 @Scheduled 扫描。没有它，poller/recoverer 方法会正常编译但永远不会被定时触发。
@EnableScheduling
public class PollingSchedulerConfiguration {

    /**
     * Outbox poller/recoverer 使用的 scheduler。
     */
    @Bean(name = "outboxTaskScheduler")
    public ThreadPoolTaskScheduler outboxTaskScheduler() {
        // Outbox 只负责消息发布，单线程更容易保持 publication order。
        return taskScheduler("outbox-scheduler-", 1);
    }

    /**
     * DelayJob poller/recoverer 使用的 scheduler。
     */
    @Bean(name = "delayJobTaskScheduler")
    public ThreadPoolTaskScheduler delayJobTaskScheduler() {
        // DelayJob poller/recoverer 共用这个 scheduler；真正业务动作在 delayJobWorkerExecutor。
        return taskScheduler("delay-job-scheduler-", 2);
    }

    /**
     * Billing cycle daily scheduler。
     */
    @Bean(name = "billingCycleTaskScheduler")
    public ThreadPoolTaskScheduler billingCycleTaskScheduler() {
        // BillingCycleScheduler 每天只创建 batch/jobs，不直接跑百万账户出账。
        return taskScheduler("billing-cycle-scheduler-", 1);
    }

    /**
     * Statement job dispatcher 使用的 scheduler。
     */
    @Bean(name = "statementJobTaskScheduler")
    public ThreadPoolTaskScheduler statementJobTaskScheduler() {
        // dispatcher 的 claim/recover 共用 scheduler；真正生成账单在 statementJobWorkerExecutor。
        return taskScheduler("statement-job-scheduler-", 2);
    }

    /**
     * 通知投递 poller/recoverer 使用的 scheduler。
     */
    @Bean(name = "notificationDeliveryTaskScheduler")
    public ThreadPoolTaskScheduler notificationDeliveryTaskScheduler() {
        // poller + recoverer 共用；真正调 provider 的动作在 notificationDeliveryWorkerExecutor，scheduler 只负责定时领取。
        return taskScheduler("notif-delivery-scheduler-", 2);
    }

    /**
     * 创建 Spring ThreadPoolTaskScheduler。
     *
     * <p>这是基础设施 helper，不包含业务规则；不同机制用不同 threadNamePrefix 方便日志排查。</p>
     */
    private ThreadPoolTaskScheduler taskScheduler(String threadNamePrefix, int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // threadNamePrefix 是生产排障习惯：日志、thread dump、Actuator metrics 都能直接看出是哪类任务。
        // 如果全用默认线程名，Outbox 卡住和 DelayJob 卡住会混在一起。
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setPoolSize(poolSize);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
