package com.minicard.infrastructure.async;

import com.minicard.delayjob.DelayJobProperties;
import com.minicard.messaging.outbox.OutboxProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 后台 worker executor 配置。
 *
 * <p>Scheduler 线程只负责 poll/claim；真正可能持有业务 row lock 的 delay job
 * 在这个 executor 中执行，避免定时触发线程被长业务动作占满。</p>
 */
@Configuration
public class WorkerExecutorConfiguration {

    @Bean(name = "outboxWorkerExecutor")
    public ThreadPoolTaskExecutor outboxWorkerExecutor(
            OutboxProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("outbox-worker-");
        executor.setCorePoolSize(properties.workerPoolSize());
        executor.setMaxPoolSize(properties.workerPoolSize());
        executor.setQueueCapacity(properties.workerQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean(name = "delayJobWorkerExecutor")
    public ThreadPoolTaskExecutor delayJobWorkerExecutor(
            DelayJobProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("delay-job-worker-");
        executor.setCorePoolSize(properties.workerPoolSize());
        executor.setMaxPoolSize(properties.workerPoolSize());
        executor.setQueueCapacity(properties.workerQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
