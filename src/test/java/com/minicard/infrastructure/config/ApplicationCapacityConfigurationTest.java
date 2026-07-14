package com.minicard.infrastructure.config;

import java.time.Duration;

import com.minicard.delayjob.DelayJobProperties;
import com.minicard.messaging.kafka.KafkaConsumersProperties;
import com.minicard.notification.application.delivery.NotificationDeliveryProperties;
import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationCapacityConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            // 只加载真实 application.yml 到轻量 Environment，不启动 DataSource、Kafka、Redis 或完整应用。
            // 如果属性路径写成已被 Boot 删除的 server.connection-timeout，下面 Tomcat 断言会直接失败。
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void bindsSmallProjectCapacitySettingsFromApplicationYaml() {
        contextRunner.run(context -> {
            Binder binder = Binder.get(context.getEnvironment());

            ServerProperties server = bind(binder, "server", ServerProperties.class);
            assertThat(server.getTomcat().getThreads().getMax()).isEqualTo(80);
            assertThat(server.getTomcat().getThreads().getMinSpare()).isEqualTo(10);
            assertThat(server.getTomcat().getAcceptCount()).isEqualTo(50);
            assertThat(server.getTomcat().getMaxConnections()).isEqualTo(2048);
            assertThat(server.getTomcat().getConnectionTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(server.getTomcat().getKeepAliveTimeout()).isEqualTo(Duration.ofSeconds(30));

            HikariConfig hikari = bind(binder, "spring.datasource.hikari", HikariConfig.class);
            assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
            assertThat(hikari.getMinimumIdle()).isEqualTo(5);
            assertThat(hikari.getConnectionTimeout()).isEqualTo(1000);
            assertThat(hikari.getValidationTimeout()).isEqualTo(500);
            assertThat(hikari.getIdleTimeout()).isEqualTo(600_000);
            assertThat(hikari.getMaxLifetime()).isEqualTo(1_800_000);

            DelayJobProperties delayJobs = bind(binder, "delay-jobs.scheduler", DelayJobProperties.class);
            assertThat(delayJobs.maxPerRun()).isEqualTo(16);

            NotificationDeliveryProperties notification = bind(
                    binder,
                    "notification.delivery",
                    NotificationDeliveryProperties.class
            );
            assertThat(notification.batchSize()).isEqualTo(40);

            // group-id 是 DLT 路由表的 key（KafkaConsumerConfiguration）；这里钉住 relaxed binding
            // （group-id -> groupId）没被路径改名破坏，否则路由表在启动期就缺 key。
            KafkaConsumersProperties consumers = bind(
                    binder,
                    "messaging.consumers",
                    KafkaConsumersProperties.class
            );
            assertThat(consumers.notification().groupId()).isEqualTo("mini-card-notification-v1");
            assertThat(consumers.notification().concurrency()).isEqualTo(2);
        });
    }

    private <T> T bind(Binder binder, String prefix, Class<T> type) {
        return binder.bind(prefix, Bindable.of(type))
                .orElseThrow(() -> new AssertionError("configuration did not bind: " + prefix));
    }
}
