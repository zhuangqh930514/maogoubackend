package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiHistoricalEvidenceImportService {

    int MATURITY_BUFFER_TRADING_DAYS = 5;

    ColdStartPlan plan(LocalDate endDate, int trainingTradingDays, int targetStockCount);

    ImportResult importEvidence(ImportRequest request);

    record ColdStartPlan(
            LocalDate startDate,
            LocalDate endDate,
            int trainingTradingDays,
            int replayTradingDays,
            int targetStockCount,
            List<LocalDate> tradingDates
    ) {
        public ColdStartPlan {
            tradingDates = tradingDates == null ? List.of() : List.copyOf(tradingDates);
        }
    }

    record ImportRequest(
            ColdStartPlan plan,
            String idempotencyKey,
            LocalDateTime requestedAt
    ) {
    }

    record ImportResult(
            int importedTradingDays,
            int reusedTradingDays,
            int preparedStocks,
            String sourceFingerprint,
            List<String> warnings
    ) {
        public ImportResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
