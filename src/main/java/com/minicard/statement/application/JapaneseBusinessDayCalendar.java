package com.minicard.statement.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

/**
 * 日本银行营业日 calendar（営業日カレンダー）。
 *
 * <p>关键词：日本营业日, 节假日, 顺延, Japanese business day, public holiday,
 * substitute holiday, 営業日(えいぎょうび), 国民の祝日(こくみんのしゅくじつ),
 * 振替休日(ふりかえきゅうじつ), 国民の休日(こくみんのきゅうじつ)。</p>
 *
 * <p>周末之外，日本的「国民の祝日」、振替休日（substitute holiday）、
 * 国民の休日（citizens' holiday）也不能作为信用卡自动扣款日。这里用代码规则模拟
 * holiday master，适合学习和interview说明；真实银行/カード会社通常会维护可更新的
 * 祝日マスター，因为祝日法将来可能变化。</p>
 */
@Component
public class JapaneseBusinessDayCalendar implements BusinessDayCalendar {

    @Override
    public boolean isBusinessDay(LocalDate date) {
        // 银行扣款必须避开 weekend + Japanese public holidays（祝日）。
        return !isWeekend(date) && !holidays(date.getYear()).contains(date);
    }

    /**
     * 汇总某一年的日本非营业日。
     *
     * <p>先计算基础祝日，再追加「振替休日」和「国民の休日」；顺序很重要，
     * 因为后两者依赖已有 holiday set。</p>
     */
    private Set<LocalDate> holidays(int year) {
        Set<LocalDate> holidays = baseNationalHolidays(year);
        addSubstituteHolidays(holidays);
        addCitizensHolidays(holidays, year);
        return holidays;
    }

    /**
     * 日本固定/Happy Monday 祝日规则。
     *
     * <p>这里只覆盖当前学习项目需要的现代日本规则；interview可以说明这属于
     * business calendar rule，不应该散落在 StatementBatchService 里。</p>
     */
    private Set<LocalDate> baseNationalHolidays(int year) {
        Set<LocalDate> holidays = new TreeSet<>();
        holidays.add(LocalDate.of(year, Month.JANUARY, 1));
        holidays.add(nthMonday(year, Month.JANUARY, 2));
        holidays.add(LocalDate.of(year, Month.FEBRUARY, 11));
        holidays.add(LocalDate.of(year, Month.FEBRUARY, 23));
        holidays.add(LocalDate.of(year, Month.MARCH, vernalEquinoxDay(year)));
        holidays.add(LocalDate.of(year, Month.APRIL, 29));
        holidays.add(LocalDate.of(year, Month.MAY, 3));
        holidays.add(LocalDate.of(year, Month.MAY, 4));
        holidays.add(LocalDate.of(year, Month.MAY, 5));
        holidays.add(nthMonday(year, Month.JULY, 3));
        holidays.add(LocalDate.of(year, Month.AUGUST, 11));
        holidays.add(nthMonday(year, Month.SEPTEMBER, 3));
        holidays.add(LocalDate.of(year, Month.SEPTEMBER, autumnEquinoxDay(year)));
        holidays.add(nthMonday(year, Month.OCTOBER, 2));
        holidays.add(LocalDate.of(year, Month.NOVEMBER, 3));
        holidays.add(LocalDate.of(year, Month.NOVEMBER, 23));
        return holidays;
    }

    /**
     * 追加振替休日（substitute holiday / 振替休日）。
     *
     * <p>如果国民の祝日落在周日，则之后第一个非祝日也休息；使用 Set.copyOf 避免遍历时修改集合。</p>
     */
    private void addSubstituteHolidays(Set<LocalDate> holidays) {
        for (LocalDate holiday : Set.copyOf(holidays)) {
            if (holiday.getDayOfWeek() != DayOfWeek.SUNDAY) {
                continue;
            }
            LocalDate substitute = holiday.plusDays(1);
            while (holidays.contains(substitute)) {
                substitute = substitute.plusDays(1);
            }
            holidays.add(substitute);
        }
    }

    /**
     * 追加国民の休日（citizens' holiday）。
     *
     * <p>夹在两个祝日之间的普通日也会成为休息日；典型例子是 9 月大型连休。</p>
     */
    private void addCitizensHolidays(Set<LocalDate> holidays, int year) {
        LocalDate date = LocalDate.of(year, Month.JANUARY, 2);
        LocalDate end = LocalDate.of(year, Month.DECEMBER, 30);
        while (!date.isAfter(end)) {
            if (!holidays.contains(date)
                    && holidays.contains(date.minusDays(1))
                    && holidays.contains(date.plusDays(1))) {
                holidays.add(date);
            }
            date = date.plusDays(1);
        }
    }

    /**
     * 计算第 nth 个星期一，用于 Happy Monday 制度（ハッピーマンデー制度）。
     */
    private LocalDate nthMonday(int year, Month month, int nth) {
        LocalDate date = LocalDate.of(year, month, 1);
        while (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            date = date.plusDays(1);
        }
        return date.plusWeeks(nth - 1L);
    }

    /**
     * 春分日近似公式。
     *
     * <p>日本春分/秋分最终由官方公布；这里使用常见公式让测试可预测，不把外部数据下载纳入项目。</p>
     */
    private int vernalEquinoxDay(int year) {
        return (int) Math.floor(20.8431
                + (0.242194 * (year - 1980))
                - Math.floor((year - 1980) / 4.0));
    }

    /**
     * 秋分日近似公式，业务含义同 vernalEquinoxDay。
     */
    private int autumnEquinoxDay(int year) {
        return (int) Math.floor(23.2488
                + (0.242194 * (year - 1980))
                - Math.floor((year - 1980) / 4.0));
    }

    /**
     * 周末不是银行营业日（銀行休業日）。
     */
    private boolean isWeekend(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }
}
