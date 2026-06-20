package com.minicard.statement.application;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;

/**
 * 日本の銀行営業日を判断する簡易 calendar。
 *
 * <p>週末に加えて日本の「国民の祝日」、振替休日、国民の休日を休業日として扱う。
 * 祝日法が将来改正される可能性はあるため、実務では外部 holiday master の更新運用も必要。</p>
 */
@Component
public class JapaneseBusinessDayCalendar implements BusinessDayCalendar {

    @Override
    public boolean isBusinessDay(LocalDate date) {
        return !isWeekend(date) && !holidays(date.getYear()).contains(date);
    }

    private Set<LocalDate> holidays(int year) {
        Set<LocalDate> holidays = baseNationalHolidays(year);
        addSubstituteHolidays(holidays);
        addCitizensHolidays(holidays, year);
        return holidays;
    }

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

    private LocalDate nthMonday(int year, Month month, int nth) {
        LocalDate date = LocalDate.of(year, month, 1);
        while (date.getDayOfWeek() != DayOfWeek.MONDAY) {
            date = date.plusDays(1);
        }
        return date.plusWeeks(nth - 1L);
    }

    private int vernalEquinoxDay(int year) {
        return (int) Math.floor(20.8431
                + (0.242194 * (year - 1980))
                - Math.floor((year - 1980) / 4.0));
    }

    private int autumnEquinoxDay(int year) {
        return (int) Math.floor(23.2488
                + (0.242194 * (year - 1980))
                - Math.floor((year - 1980) / 4.0));
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        return dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY;
    }
}
