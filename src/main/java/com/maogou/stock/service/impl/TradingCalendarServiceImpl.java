package com.maogou.stock.service.impl;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.service.TradingCalendarService;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TradingCalendarServiceImpl implements TradingCalendarService {

    private final AppProperties properties;

    public TradingCalendarServiceImpl(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isTradingDay(LocalDate date) {
        if (date == null) {
            return false;
        }
        Set<LocalDate> workdays = parseDates(properties.getScheduler().getTradingWorkdays());
        if (workdays.contains(date)) {
            return true;
        }
        Set<LocalDate> holidays = parseDates(properties.getScheduler().getTradingHolidays());
        if (holidays.contains(date)) {
            return false;
        }
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    @Override
    public LocalDate previousTradingDay(LocalDate date) {
        LocalDate cursor = date == null ? LocalDate.now() : date.minusDays(1);
        for (int i = 0; i < 20; i++) {
            if (isTradingDay(cursor)) {
                return cursor;
            }
            cursor = cursor.minusDays(1);
        }
        return cursor;
    }

    @Override
    public LocalDate latestExpectedKlineDate(LocalDateTime now) {
        LocalDateTime current = now == null ? LocalDateTime.now() : now;
        LocalDate date = current.toLocalDate();
        if (isTradingDay(date) && !current.toLocalTime().isBefore(LocalTime.of(15, 15))) {
            return date;
        }
        if (isTradingDay(date)) {
            return previousTradingDay(date);
        }
        LocalDate cursor = date;
        for (int i = 0; i < 20; i++) {
            cursor = cursor.minusDays(1);
            if (isTradingDay(cursor)) {
                return cursor;
            }
        }
        return cursor;
    }

    @Override
    public LocalDate minimumRequiredAnalysisKlineDate(LocalDateTime now) {
        LocalDateTime current = now == null ? LocalDateTime.now() : now;
        LocalDate date = current.toLocalDate();
        if (isTradingDay(date)) {
            return previousTradingDay(date);
        }
        return latestExpectedKlineDate(current);
    }

    @Override
    public LocalDateTime nextTradingDateTime(LocalDateTime now, int hour, int minute) {
        LocalDateTime current = now == null ? LocalDateTime.now() : now;
        LocalTime time = LocalTime.of(hour, minute);
        LocalDate date = current.toLocalDate();
        LocalDateTime next = LocalDateTime.of(date, time);
        while (!isTradingDay(next.toLocalDate()) || !next.isAfter(current)) {
            next = LocalDateTime.of(next.toLocalDate().plusDays(1), time);
        }
        return next;
    }

    private static Set<LocalDate> parseDates(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(LocalDate::parse)
                .collect(Collectors.toSet());
    }
}
