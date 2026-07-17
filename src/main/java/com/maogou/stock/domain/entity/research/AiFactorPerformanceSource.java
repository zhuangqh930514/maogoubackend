package com.maogou.stock.domain.entity.research;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class AiFactorPerformanceSource {
    public Long sampleId;
    public String stockCode;
    public LocalDate tradeDate;
    public String marketRegime;
    public String sampleSourceFingerprint;
    public Long factorValueId;
    public Long factorDefinitionId;
    public String factorCode;
    public String factorVersion;
    public String factorDirection;
    public BigDecimal rawValue;
    public BigDecimal normalizedValue;
    public Integer factorMissing;
    public String factorInputFingerprint;
    public Long labelId;
    public Integer horizonTradingDays;
    public String labelInputFingerprint;
    public BigDecimal excessReturn;
    public BigDecimal maxAdverseReturn;
    public String executionStatus;
    public String labelStatus;
    public LocalDateTime labelAvailableAt;
    public LocalDateTime maturedAt;
    public LocalDateTime verifiedAt;
}
