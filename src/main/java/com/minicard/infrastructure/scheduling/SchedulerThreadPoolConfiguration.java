package com.minicard.infrastructure.scheduling;

import com.minicard.scheduling.application.DelayJobSchedulerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduler 基础设施：复用相同 ThreadPoolTaskScheduler 创建方式，但按机制分 poller pool。
 *
 * <p>Outbox 和 DelayJob 都是 polling scheduler，所以配置方式对称。
 * DelayJob 的业务 worker executor 放在 TaskExecutorConfig，避免 poller pool 和 worker pool 混在一起。</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DelayJobSchedulerProperties.class)
public class SchedulerThreadPoolConfiguration {

    @Bean(name = "outboxTaskScheduler")
    public ThreadPoolTaskScheduler outboxTaskScheduler() {
        // Outbox 只负责消息发布，单线程更容易保持 publication order。
        return taskScheduler("outbox-scheduler-", 1);
    }

    @Bean(name = "delayJobTaskScheduler")
    public ThreadPoolTaskScheduler delayJobTaskScheduler() {
        // DelayJob poller/recoverer 共用这个 scheduler；真正业务动作在 scheduledJobWorkerExecutor。
        return taskScheduler("delay-job-scheduler-", 2);
    }

    @Bean
    public TransactionOperations transactionOperations(
            PlatformTransactionManager transactionManager
    ) {
        // TransactionOperations 让 worker 能显式拆分 handle 和 finalize 的事务边界。
        return new TransactionTemplate(transactionManager);
    }

    private ThreadPoolTaskScheduler taskScheduler(String threadNamePrefix, int poolSize) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setPoolSize(poolSize);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
