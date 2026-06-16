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
 * Scheduler 基础设施：复用相同 ThreadPoolTaskScheduler 创建方式，但按机制分 worker pool。
 *
 * <p>Outbox 和 DelayJob 都是 polling scheduler，所以配置方式对称。
 * 但 Kafka publication 和 delayed business action 的耗时/锁竞争不同，因此线程池隔离。</p>
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
        // DelayJob 可能执行不同业务动作；预留小 worker pool，后续可按吞吐调整。
        return taskScheduler("delay-job-scheduler-", 2);
    }

    @Bean
    public TransactionOperations transactionOperations(
            PlatformTransactionManager transactionManager
    ) {
        // TransactionOperations 让 DelayJobService 能显式拆分 claim/handle/finalize 三段事务。
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
