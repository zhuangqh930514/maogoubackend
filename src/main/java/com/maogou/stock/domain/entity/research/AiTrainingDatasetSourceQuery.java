package com.maogou.stock.domain.entity.research;

import java.time.LocalDate;
import java.time.LocalDateTime;

public class AiTrainingDatasetSourceQuery {
    public Long userId;
    public String featureVersion;
    public String labelVersion;
    public String calendarVersion;
    public LocalDate startDate;
    public LocalDate endDate;
    public Integer maxHorizonDays;
    public LocalDateTime asOfTime;
}
