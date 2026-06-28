package com.minicard.notification.infrastructure.delivery;

import com.minicard.notification.application.NotificationDeliveryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 通知投递的配置绑定入口 + Resilience4j 异步执行池。
 *
 * <p>关键词：投递配置, 异步执行池, TimeLimiter 线程, delivery configuration,
 * sender executor, 設定バインド(せっていバインド)。</p>
 */
@Configuration
// @EnableConfigurationProperties 把 NotificationDeliveryProperties 注册成 bean，供 claimer/worker/executor 注入。
@EnableConfigurationProperties(NotificationDeliveryProperties.class)
public class NotificationDeliveryConfiguration {

    /**
     * provider 调用用的异步执行池。
     *
     * <p>Resilience4j TimeLimiter 需要在<b>另一个线程</b>上跑 provider 调用，才能在超时时放弃等待并取消任务；
     * 如果在 worker 线程内同步阻塞，TimeLimiter 无法实现硬超时。所以这里单独给一个有界池。</p>
     */
    @Bean(name = "notificationSenderExecutor")
    public ThreadPoolTaskExecutor notificationSenderExecutor(NotificationDeliveryProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("notif-sender-");
        // core=max 固定线程数；senderThreads 应 >= worker pool，保证每个 worker 的 send 都有线程可跑。
        executor.setCorePoolSize(properties.senderThreads());
        executor.setMaxPoolSize(properties.senderThreads());
        executor.setQueueCapacity(properties.workerQueueCapacity());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
