package com.minicard.statement.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置驱动业务日历测试。
 *
 * <p>关键词：营业日, 节假日主数据, 顺延, business day, holiday master, roll forward,
 * 営業日(えいぎょうび), 祝日マスター(しゅくじつマスター)。</p>
 *
 * <p>这里钉住三件事：weekend 与配置祝日都不是营业日、顺延会跳过连续休业日、
 * 日历数据没覆盖的年份 fail-open 成"只跳周末"而不是抛异常让批处理停摆。</p>
 */
class ConfiguredBusinessDayCalendarTest {

    // 2026 GW：5/3(日) 憲法記念日、5/4、5/5、5/6 振替休日，5/7 才是营业日。
    private static final Set<LocalDate> HOLIDAYS_2026 = Set.of(
            LocalDate.parse("2026-05-03"),
            LocalDate.parse("2026-05-04"),
            LocalDate.parse("2026-05-05"),
            LocalDate.parse("2026-05-06"),
            LocalDate.parse("2026-09-21"),
            LocalDate.parse("2026-09-22"),
            LocalDate.parse("2026-09-23")
    );

    private final ConfiguredBusinessDayCalendar calendar =
            new ConfiguredBusinessDayCalendar(properties(HOLIDAYS_2026));

    @Test
    // 测试目的：配置里的祝日和周末都不能作为扣款日。
    void treatsConfiguredHolidayAndWeekendAsNonBusinessDay() {
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-05-06"))).isFalse();
        // 2026-05-09 是周六。
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-05-09"))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-05-07"))).isTrue();
    }

    @Test
    // 测试目的：顺延要跨过"祝日 + 国民の休日 + 秋分"这种连续休业段。
    void rollsForwardAcrossConsecutiveHolidays() {
        assertThat(calendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-09-21")))
                .isEqualTo(LocalDate.parse("2026-09-24"));
    }

    @Test
    // 测试目的：日历数据未覆盖的年份 fail-open（只跳周末），不抛异常也不把批处理钉死。
    // 反向事实：如果这里改成 fail-closed 抛异常，日历过期会让整期账单规划失败，而不是排错一天由运维补救。
    void fallsBackToWeekendOnlyWhenYearIsNotCovered() {
        // 2030-05-06 是周一：2030 年没有配置任何祝日，所以被当成营业日。
        assertThat(calendar.isBusinessDay(LocalDate.parse("2030-05-06"))).isTrue();
        // 2030-05-04 是周六，周末规则仍然生效。
        assertThat(calendar.nextBusinessDayOnOrAfter(LocalDate.parse("2030-05-04")))
                .isEqualTo(LocalDate.parse("2030-05-06"));
    }

    private StatementProperties properties(Set<LocalDate> holidays) {
        return new StatementProperties(
                new StatementProperties.Batch(
                        true, "0 0 1 * * *", "Asia/Tokyo", 31, 27, 2, 1000, holidays
                ),
                new StatementProperties.Jobs(true, 1000, 10000, 10, 3, 300, 4, 20),
                new StatementProperties.Policy(
                        new BigDecimal("0.10"),
                        Map.of("JPY", new BigDecimal("1000.00"))
                )
        );
    }
}
