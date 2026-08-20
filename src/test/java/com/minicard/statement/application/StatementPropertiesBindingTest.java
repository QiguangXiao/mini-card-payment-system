package com.minicard.statement.application;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真 application.yml 到 StatementProperties 的绑定测试。
 *
 * <p>关键词：配置绑定, 节假日主数据, 缓存广播开关, configuration binding, holiday master,
 * cache invalidation broadcast, 祝日マスター(しゅくじつマスター)。</p>
 *
 * <p>为什么需要它：祝日从代码规则改成配置数据后，一个写错的日期字符串或错误缩进不会在编译期报错，
 * 只会在应用启动绑定时炸；而 bank-holidays 直接决定口座振替的 due date。
 * 这里用轻量 Environment 绑定真 yaml，把"日历数据能被正确读进来"变成一条快测试。</p>
 */
class StatementPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            // 只加载真实 application.yml 到轻量 Environment，不启动 DataSource、Kafka 或 Redis。
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    // 测试目的：bank-holidays（祝日マスター）能从真 yaml 绑定成 Set<LocalDate>，且覆盖已配置的年份。
    void bindsBankHolidayMasterFromApplicationYaml() {
        contextRunner.run(context -> {
            StatementProperties properties = Binder.get(context.getEnvironment())
                    .bind("statement", Bindable.of(StatementProperties.class))
                    .orElseThrow(() -> new AssertionError("statement properties are not bound"));

            assertThat(properties.batch().bankHolidays())
                    // 2026-05-03 憲法記念日は日曜 → 5/6 が振替休日。
                    .contains(LocalDate.parse("2026-05-06"))
                    // 2026-09-22 は祝日に挟まれた国民の休日。
                    .contains(LocalDate.parse("2026-09-22"))
                    // 2027-03-21 春分の日は日曜 → 3/22 が振替休日。
                    .contains(LocalDate.parse("2027-03-22"));
            // 默认扣款基准日 27 日必须是被日历数据覆盖年份里的普通日；这里顺手确认没被误配成休业日。
            assertThat(properties.batch().bankHolidays()).doesNotContain(LocalDate.parse("2026-07-27"));
            assertThat(properties.batch().paymentBaseDayOfMonth()).isEqualTo(27);
        });
    }

    @Test
    // 测试目的：钉住跨 pod L1 失效广播默认开启。
    // 反向事实：默认关闭时多 pod 部署下还款后最多 local-ttl(30s) 还能读到旧账单，
    // 这条断言让"默认值被悄悄改回 false"在测试里可见。
    void enablesCrossPodL1InvalidationBroadcastByDefault() {
        contextRunner.run(context -> assertThat(context.getEnvironment()
                .getProperty("statement.read-cache.broadcast.enabled", Boolean.class))
                .isTrue());
    }
}
