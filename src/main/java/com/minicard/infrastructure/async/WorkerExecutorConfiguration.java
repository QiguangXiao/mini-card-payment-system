package com.minicard.infrastructure.async;

import com.minicard.delayjob.DelayJobProperties;
import com.minicard.messaging.outbox.OutboxProperties;
import com.minicard.notification.application.delivery.NotificationDeliveryProperties;
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
     * DelayJob business worker pool（内部 DB 小事务类 job，如授权过期）。
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
     * AUTO_REPAYMENT 专用 worker pool（外部银行调用类 job）。
     */
    // 同一机制（DelayJob）内再按副作用类型分池："不同机制不同 executor"的原则下沉到 job 类型：
    // 自动扣款会同步等银行网关（最长到 Feign read-timeout），银行 brownout 时钉住的只是本池，
    // 授权过期释放额度在 delayJobWorkerExecutor 里不受影响；27 号扣款日的批量爆发也只在本池排队。
    @Bean(name = "autoRepaymentDelayJobWorkerExecutor")
    public ThreadPoolTaskExecutor autoRepaymentDelayJobWorkerExecutor(
            DelayJobProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("auto-repay-worker-");
        executor.setCorePoolSize(properties.autoRepaymentWorkerPoolSize());
        executor.setMaxPoolSize(properties.autoRepaymentWorkerPoolSize());
        executor.setQueueCapacity(properties.autoRepaymentWorkerQueueCapacity());
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

    /**
     * 通知投递 worker pool。
     */
    // 投递 worker 会调外部 provider 并在短事务里 finalize；线程数有界，避免一种机制 backlog 占满其他池。
    @Bean(name = "notificationDeliveryWorkerExecutor")
    public ThreadPoolTaskExecutor notificationDeliveryWorkerExecutor(
            NotificationDeliveryProperties properties
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("notif-delivery-worker-");
        executor.setCorePoolSize(properties.workerPoolSize());
        executor.setMaxPoolSize(properties.workerPoolSize());
        // queue 满会让 poller 捕获 TaskRejectedException，并把投递放回 retry。
        executor.setQueueCapacity(properties.workerQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
