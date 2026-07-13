package com.maogou.stock.domain.entity.v2;

import java.time.LocalDate;

public class AiTrainingSourceSummary {
    public String featureVersion;
    public String labelVersion;
    public String calendarVersion;
    public LocalDate firstTradeDate;
    public LocalDate lastTradeDate;
    public Integer rowCount;
    public Integer tradingDayCount;
}
