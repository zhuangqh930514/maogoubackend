package com.maogou.stock.domain.entity.research;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class AiTrainingDatasetSource {
    public Long sampleId;
    public Long labelId;
    public Long userId;
    public String stockCode;
    public LocalDate tradeDate;
    public LocalDateTime sampleAsOfTime;
    public LocalDateTime labelAvailableAt;
    public String featureVersion;
    public String labelVersion;
    public String calendarVersion;
    public Integer horizonDays;
    public String featureSnapshot;
    public BigDecimal netReturn;
    public BigDecimal excessReturn;
    public BigDecimal labelScore;
    public Integer hitDirection;
    public String featureFingerprint;
    public String labelFingerprint;
}
