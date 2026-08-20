package com.minicard.statement.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 由配置驱动的银行营业日 calendar（営業日カレンダー）。
 *
 * <p>关键词：营业日, 节假日主数据, 顺延, business day, holiday master, roll forward,
 * 営業日(えいぎょうび), 祝日マスター(しゅくじつマスター), 翌営業日(よくえいぎょうび)。</p>
 *
 * <p>非营业日 = weekend + {@code statement.batch.bank-holidays} 里配置的日期。
 * 这里刻意<b>不用代码推导</b>日本祝日（Happy Monday、振替休日、春分/秋分公式）：
 * 祝日是<b>数据</b>，不是算法。祝日法会修改（山の日、天皇誕生日都改过），
 * 春分/秋分由官方每年公布，用近似公式算出来的日期在远年会漂移。
 * 真实发卡/银行系统一律从可更新的祝日マスター（holiday master）读取，
 * 所以这里把它建模成配置数据，换数据源时只替换这一个 bean。</p>
 *
 * <p>反向事实（为什么要有 {@link #warnWhenYearNotCovered}）：如果配置的日历数据过期，
 * {@code isBusinessDay} 会静默退化成"只跳周末"，把自动扣款日排到祝日上——扣款当天银行不受理，
 * 整批口座振替失败，而日志里什么都看不到。所以未覆盖的年份要主动告警，
 * 把"日历数据该续期了"变成可观测事件。这里选择 fail-open（继续按周末顺延）而不是抛异常：
 * 账单批处理宁可把 due date 排错一天由运维补救，也不该因为日历缺一年就整批停摆。</p>
 */
@Component
@Slf4j
public class ConfiguredBusinessDayCalendar implements BusinessDayCalendar {

    /** 配置来的银行休业日（祝日 + 特殊休业日），启动后不可变。 */
    private final Set<LocalDate> bankHolidays;
    /** 配置里出现过的年份；用来判断"这一年到底有没有维护过日历数据"。 */
    private final Set<Integer> coveredYears;
    /** 已告警过的年份，避免批处理循环里对同一年反复打日志。 */
    private final Set<Integer> warnedYears = ConcurrentHashMap.newKeySet();

    public ConfiguredBusinessDayCalendar(StatementProperties properties) {
        this.bankHolidays = properties.batch().bankHolidays();
        Set<Integer> years = new HashSet<>();
        for (LocalDate holiday : this.bankHolidays) {
            years.add(holiday.getYear());
        }
        this.coveredYears = Set.copyOf(years);
    }

    @Override
    public boolean isBusinessDay(LocalDate date) {
        warnWhenYearNotCovered(date);
        // 银行扣款必须避开 weekend + 配置的 bank holidays（祝日/銀行休業日）。
        return !isWeekend(date) && !bankHolidays.contains(date);
    }

    /**
     * 日历数据未覆盖该年份时告警一次。
     *
     * <p>只在"这一年一个假日都没配"时才告警：配置里有该年数据就说明有人维护过，
     * 缺某个具体假日属于数据错误，不是覆盖范围问题，那种情况靠对账而不是这条日志发现。</p>
     */
    private void warnWhenYearNotCovered(LocalDate date) {
        int year = date.getYear();
        if (coveredYears.contains(year) || !warnedYears.add(year)) {
            return;
        }
        log.warn(
                "business_day_calendar_year_not_covered year={} action=weekend_only_fallback"
                        + " hint=extend statement.batch.bank-holidays",
                year
        );
    }

    /**
     * 周末不是银行营业日（銀行休業日）。
     */
    private boolean isWeekend(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }
}
