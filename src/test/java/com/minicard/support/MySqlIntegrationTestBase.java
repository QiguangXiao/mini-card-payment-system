package com.minicard.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
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
 * <p>容器用 JVM singleton 手动启动：所有 integration test class 共享同一个 MySQL。
 * 如果用 JUnit {@code @Container} 管理这个 base-class static field，某个测试类结束时容器可能被停掉，
 * 但 Spring context cache 仍复用旧 DataSource，下一类测试就会连到已关闭端口。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("it")
public abstract class MySqlIntegrationTestBase {

    // 锁定具体 minor 版本，保证 CI 与本地跑在同一 InnoDB 上；镜像本地已缓存，启动只需几秒。
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4.8"));

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
