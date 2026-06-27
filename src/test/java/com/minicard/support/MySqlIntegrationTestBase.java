package com.minicard.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 真 MySQL 集成测试基类：用 Testcontainers 起一个一次性 MySQL，由 Liquibase 建表后跑测试。
 *
 * <p>关键词：集成测试, Testcontainers, 真数据库, integration test, real MySQL,
 * row lock, 行ロック検証, 結合テスト。</p>
 *
 * <p>为什么必须真 MySQL：本仓库的并发正确性靠 {@code SELECT ... FOR UPDATE} 和
 * {@code FOR UPDATE SKIP LOCKED}，这些行锁语义 H2/HSQLDB 要么不支持要么语义不同。
 * 只有在真 InnoDB 上跑，才能把“我用了行锁”从注释声明变成可运行证据。</p>
 *
 * <p>容器用 {@code static} 字段：JVM 内只起一次，被所有子类复用，避免每个测试类都拉一次镜像。
 * {@code @ServiceConnection} 让 Spring Boot 在容器启动后自动注入 {@code spring.datasource.*}，
 * 不需要手写 {@code @DynamicPropertySource}。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
@Testcontainers
public abstract class MySqlIntegrationTestBase {

    // 锁定具体 minor 版本，保证 CI 与本地跑在同一 InnoDB 上；镜像本地已缓存，启动只需几秒。
    // 复用宿主已有的 mysql:8.4.8，避免额外网络拉取。
    @Container
    @ServiceConnection
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4.8"));
}
