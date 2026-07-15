package com.maogou.stock.domain.entity.research;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class AiTrainingDatasetSourceQuery {
    public String featureVersion;
    public String labelVersion;
    public String calendarVersion;
    public LocalDate startDate;
    public LocalDate endDate;
    public Integer horizonTradingDays;
    public LocalDateTime asOfTime;
}
