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
 * Shared scheduling infrastructure with separate worker pools per mechanism.
 *
 * <p>Outbox and DelayJob both use polling schedulers, so they share the same
 * ThreadPoolTaskScheduler construction pattern. They do not share a pool: Kafka
 * publication and delayed business actions have different latency and locking
 * profiles.</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DelayJobSchedulerProperties.class)
public class SchedulerThreadPoolConfiguration {

    @Bean(name = "outboxTaskScheduler")
    public ThreadPoolTaskScheduler outboxTaskScheduler() {
        return taskScheduler("outbox-scheduler-", 1);
    }

    @Bean(name = "delayJobTaskScheduler")
    public ThreadPoolTaskScheduler delayJobTaskScheduler() {
        return taskScheduler("delay-job-scheduler-", 2);
    }

    @Bean
    public TransactionOperations transactionOperations(
            PlatformTransactionManager transactionManager
    ) {
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
