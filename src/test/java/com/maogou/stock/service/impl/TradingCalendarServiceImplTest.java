package com.maogou.stock.service.impl;

import com.maogou.stock.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TradingCalendarServiceImplTest {

    @Test
    void detectsConfiguredHolidayAndWeekdayTradingDay() {
        TradingCalendarServiceImpl service = new TradingCalendarServiceImpl(new AppProperties());

        assertThat(service.isTradingDay(LocalDate.parse("2026-06-19"))).isFalse();
        assertThat(service.isTradingDay(LocalDate.parse("2026-06-22"))).isTrue();
        assertThat(service.isTradingDay(LocalDate.parse("2026-06-20"))).isFalse();
    }

    @Test
    void calculatesExpectedKlineDateByMarketClose() {
        TradingCalendarServiceImpl service = new TradingCalendarServiceImpl(new AppProperties());

        assertThat(service.latestExpectedKlineDate(LocalDateTime.parse("2026-06-10T14:00:00")))
                .isEqualTo(LocalDate.parse("2026-06-09"));
        assertThat(service.latestExpectedKlineDate(LocalDateTime.parse("2026-06-10T16:00:00")))
                .isEqualTo(LocalDate.parse("2026-06-10"));
        assertThat(service.latestExpectedKlineDate(LocalDateTime.parse("2026-06-21T12:00:00")))
                .isEqualTo(LocalDate.parse("2026-06-18"));
    }

    @Test
    void skipsHolidaysForNextScheduledRun() {
        TradingCalendarServiceImpl service = new TradingCalendarServiceImpl(new AppProperties());

        assertThat(service.nextTradingDateTime(LocalDateTime.parse("2026-06-18T17:00:00"), 16, 0))
                .isEqualTo(LocalDateTime.parse("2026-06-22T16:00:00"));
    }
}
