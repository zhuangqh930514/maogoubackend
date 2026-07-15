package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface HistoricalUniverseSourceService {

    HistoricalDayEvidence load(LocalDate tradeDate, LocalDateTime asOfTime);

    record HistoricalDayEvidence(
            String status,
            LocalDate tradeDate,
            LocalDateTime asOfTime,
            Long universeSnapshotId,
            Long dataBatchId,
            Integer stockCount,
            String sourceFingerprint,
            List<String> missingEvidence
    ) {
        public HistoricalDayEvidence {
            missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        }

        public boolean ready() {
            return "READY".equals(status);
        }
    }
}
