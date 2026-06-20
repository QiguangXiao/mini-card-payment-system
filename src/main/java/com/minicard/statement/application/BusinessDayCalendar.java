package com.minicard.statement.application;

import java.time.LocalDate;

public interface BusinessDayCalendar {

    boolean isBusinessDay(LocalDate date);

    default LocalDate nextBusinessDayOnOrAfter(LocalDate date) {
        LocalDate candidate = date;
        while (!isBusinessDay(candidate)) {
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }
}
