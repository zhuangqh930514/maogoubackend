package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiSecurityDailyState;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public interface AiSecurityDailyStateService {

    AiSecurityDailyState store(StateCommand command);

    record StateCommand(
            String stockCode,
            LocalDate tradeDate,
            Long sourceBatchId,
            String sourceRevision,
            LocalDate listedOn,
            Integer listedDays,
            String securityStatus,
            String stStatus,
            Integer isSt,
            Integer suspended,
            BigDecimal limitRatio,
            BigDecimal limitUpPrice,
            BigDecimal limitDownPrice,
            Integer isLimitUp,
            Integer isLimitDown,
            Integer buyTradable,
            Integer sellTradable,
            String qualityStatus,
            String missingReason,
            String evidenceJson,
            String sourceFingerprint,
            LocalDateTime observedAt
    ) {
    }
}
