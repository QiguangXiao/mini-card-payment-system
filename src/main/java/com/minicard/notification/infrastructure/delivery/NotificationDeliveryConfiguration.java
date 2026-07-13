package com.minicard.notification.infrastructure.delivery;

import com.minicard.notification.application.delivery.NotificationDeliveryProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Notification delivery 配置绑定入口。
 *
 * <p>关键词：通知投递, 配置绑定, notification delivery,
 * configuration properties, 配信設定(はいしんせってい)。</p>
 *
 * <p>本类负责把 {@code notification.delivery.*} 注册为强类型配置 Bean；worker executor、
 * poller、recoverer 和模拟 provider 都只是使用者。反向事实：如果把注册注解放在全局
 * WorkerExecutorConfiguration，替换线程池配置会意外让整个通知投递模块失去 properties Bean，
 * 形成与线程池无关的隐藏启动依赖。</p>
 */
@Configuration
@EnableConfigurationProperties(NotificationDeliveryProperties.class)
public class NotificationDeliveryConfiguration {
}
