package com.minicard.statement.application;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JapaneseBusinessDayCalendarTest {

    private final JapaneseBusinessDayCalendar calendar = new JapaneseBusinessDayCalendar();

    @Test
    void treatsJapaneseSubstituteHolidayAsNonBusinessDay() {
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-05-06"))).isFalse();
    }

    @Test
    void treatsJapaneseCitizensHolidayAsNonBusinessDay() {
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-09-22"))).isFalse();
        assertThat(calendar.isBusinessDay(LocalDate.parse("2026-09-23"))).isFalse();
        assertThat(calendar.nextBusinessDayOnOrAfter(LocalDate.parse("2026-09-22")))
                .isEqualTo(LocalDate.parse("2026-09-24"));
    }
}
