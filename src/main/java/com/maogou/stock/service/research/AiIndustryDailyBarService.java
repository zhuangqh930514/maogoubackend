package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiIndustryDailyBar;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public interface AiIndustryDailyBarService {

    AiIndustryDailyBar store(BarCommand command);

    record BarCommand(
            String industryCode,
            String industryName,
            String classificationStandard,
            LocalDate tradeDate,
            BigDecimal openPrice,
            BigDecimal highPrice,
            BigDecimal lowPrice,
            BigDecimal closePrice,
            BigDecimal volume,
            BigDecimal amount,
            String sourceName,
            String sourceRevision,
            String qualityStatus,
            String sourceRef,
            String evidenceJson,
            String sourceFingerprint,
            LocalDateTime observedAt
    ) {
    }
}
