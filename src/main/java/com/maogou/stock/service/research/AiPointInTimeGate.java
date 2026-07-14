package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiPointInTimeGate {

    GateResult evaluate(GateInput input);

    ObservationResult evaluateObservation(ObservationInput input);

    enum GateStatus {
        READY,
        PARTIAL,
        WAITING_SOURCE,
        UNAVAILABLE
    }

    enum RunMode {
        LIVE_ANALYSIS,
        AFTER_CLOSE_RESEARCH
    }

    enum ObservationStatus {
        ELIGIBLE,
        FUTURE_DATA,
        AFTER_CUTOFF,
        INVALID_TIMELINE
    }

    record GateInput(
            LocalDateTime asOfTime,
            RunMode runMode,
            LocalDate latestKlineDate,
            LocalDate benchmarkCloseDate,
            LocalDate sectorCloseDate,
            boolean financeAvailable,
            boolean newsAvailable
    ) {
    }

    record GateResult(
            GateStatus status,
            LocalDate expectedCompleteTradeDate,
            List<String> reasons
    ) {
        public GateResult {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    record ObservationInput(
            String sourceType,
            LocalDateTime eventTime,
            LocalDateTime publishedAt,
            LocalDateTime firstSeenAt,
            LocalDateTime fetchedAt,
            LocalDateTime asOfTime
    ) {
    }

    record ObservationResult(
            ObservationStatus status,
            boolean eligibleForSample,
            LocalDateTime effectivePublishedAt,
            String reason
    ) {
    }
}
