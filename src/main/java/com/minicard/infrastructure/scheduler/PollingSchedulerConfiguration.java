package com.minicard.infrastructure.scheduler;

import com.minicard.delayjob.DelayJobProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableScheduling
@EnableConfigurationProperties(DelayJobProperties.class)
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
     * Statement monthly batch 使用的 scheduler。
     */
    @Bean(name = "statementBatchTaskScheduler")
    public ThreadPoolTaskScheduler statementBatchTaskScheduler() {
        // Statement batch 只做轻量触发；每个账户的出账事务在 application service 内独立执行。
        return taskScheduler("statement-batch-scheduler-", 1);
    }

    /**
     * 创建 Spring ThreadPoolTaskScheduler。
     *
     * <p>这是基础设施 helper，不包含业务规则；不同机制用不同 threadNamePrefix 方便日志排查。</p>
     */
    private ThreadPoolTaskScheduler taskScheduler(String threadNamePrefix, int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setPoolSize(poolSize);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
