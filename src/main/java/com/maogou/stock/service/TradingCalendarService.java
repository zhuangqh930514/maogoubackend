package com.maogou.stock.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface TradingCalendarService {
    boolean isTradingDay(LocalDate date);

    LocalDate previousTradingDay(LocalDate date);

    LocalDate latestExpectedKlineDate(LocalDateTime now);

    LocalDateTime nextTradingDateTime(LocalDateTime now, int hour, int minute);
}
