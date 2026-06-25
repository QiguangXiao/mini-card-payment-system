package com.minicard.infrastructure.async;

import com.minicard.delayjob.DelayJobProperties;
import com.minicard.messaging.outbox.OutboxProperties;
import com.minicard.statement.application.StatementProperties;
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
// @Configuration 让下面的 @Bean 方法被 Spring 管理，确保 executor 是 singleton 并参与生命周期关闭。
// 如果业务代码自己 new ThreadPoolTaskExecutor，shutdown、命名和注入都会分散，排查线程问题更难。
@Configuration
public class WorkerExecutorConfiguration {

    /**
     * Outbox publish worker pool。
     */
    // 显式 bean name 配合 @Qualifier 使用。没有名字时，多个 TaskExecutor 会让注入点变成 ambiguous bean。
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
    // DelayJob 与 Outbox 使用不同 executor，避免一种机制的 backlog 占满另一种机制的线程。
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

    /**
     * Statement billing job worker pool。
     */
    @Bean(name = "statementJobWorkerExecutor")
    public ThreadPoolTaskExecutor statementJobWorkerExecutor(
            StatementProperties properties
    ) {
        StatementProperties.Jobs jobs = properties.jobs();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("statement-job-worker-");
        // Statement job 内部会按 account 进入小事务，但仍可能同时竞争 credit_accounts row lock，线程数必须有界。
        executor.setCorePoolSize(jobs.workerPoolSize());
        executor.setMaxPoolSize(jobs.workerPoolSize());
        executor.setQueueCapacity(jobs.workerQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
