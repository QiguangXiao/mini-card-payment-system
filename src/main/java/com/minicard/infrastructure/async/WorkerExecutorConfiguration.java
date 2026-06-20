package com.minicard.infrastructure.async;

import com.minicard.delayjob.DelayJobProperties;
import com.minicard.messaging.outbox.OutboxProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 后台 worker executor 配置。
 *
 * <p>关键词：后台线程池, worker pool, 队列容量, thread pool,
 * TaskExecutor, graceful shutdown, ワーカープール,
 * キュー容量(キューようりょう)。</p>
 *
 * <p>Scheduler 线程只负责 poll/claim；真正可能持有业务 row lock 的 delay job
 * 在这个 executor 中执行，避免定时触发线程被长业务动作占满。</p>
 */
@Configuration
public class WorkerExecutorConfiguration {

    /**
     * Outbox publish worker pool。
     */
    @Bean(name = "outboxWorkerExecutor")
    public ThreadPoolTaskExecutor outboxWorkerExecutor(
            OutboxProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("outbox-worker-");
        // core=max 固定线程数，避免流量波动时频繁创建/销毁线程，interview里可解释为 bounded worker pool。
        executor.setCorePoolSize(properties.workerPoolSize());
        executor.setMaxPoolSize(properties.workerPoolSize());
        // queue 满会让 poller 捕获 TaskRejectedException，并把事件/任务放回 retry。
        executor.setQueueCapacity(properties.workerQueueCapacity());
        // shutdown 时尽量等待已提交任务完成，减少 PROCESSING lease 恢复压力。
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    /**
     * DelayJob business worker pool。
     */
    @Bean(name = "delayJobWorkerExecutor")
    public ThreadPoolTaskExecutor delayJobWorkerExecutor(
            DelayJobProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("delay-job-worker-");
        // DelayJob 业务可能拿 row lock，线程数要受控，避免把数据库压满。
        executor.setCorePoolSize(properties.workerPoolSize());
        executor.setMaxPoolSize(properties.workerPoolSize());
        executor.setQueueCapacity(properties.workerQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
