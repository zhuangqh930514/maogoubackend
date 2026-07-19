package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiResearchUniverse;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiResearchUniverseService {

    SnapshotResult createSystemCoreSnapshot(SnapshotRequest request);

    record UniverseCandidate(
            String stockCode,
            String stockName,
            String market,
            String sourceType,
            boolean included,
            String excludeReason,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String listedStatus,
            String sourceReference,
            String sourceEvidenceFingerprint,
            String industryCode,
            String industryName,
            String industryStandard,
            String industryEvidenceFingerprint
    ) {
        public UniverseCandidate(
                String stockCode,
                String stockName,
                String market,
                String sourceType,
                boolean included,
                String excludeReason,
                LocalDate effectiveFrom,
                LocalDate effectiveTo,
                String listedStatus,
                String sourceReference,
                String sourceEvidenceFingerprint
        ) {
            this(stockCode, stockName, market, sourceType, included, excludeReason,
                    effectiveFrom, effectiveTo, listedStatus, sourceReference,
                    sourceEvidenceFingerprint, null, null, null, null);
        }

        public UniverseCandidate(
                String stockCode,
                String stockName,
                String market,
                String sourceType,
                boolean included,
                String excludeReason,
                LocalDate effectiveFrom
        ) {
            this(stockCode, stockName, market, sourceType, included, excludeReason,
                    effectiveFrom, null, "LISTED", null, null, null, null, null, null);
        }
    }

    record SnapshotRequest(
            LocalDate tradeDate,
            LocalDateTime asOfTime,
            String calendarVersion,
            List<UniverseCandidate> configuredComponents,
            boolean includeUserInterests,
            String membershipSourceName,
            String membershipSourceRevision,
            LocalDateTime sourceObservedAt,
            String pointInTimeStatus,
            String pointInTimeReason
    ) {
        public SnapshotRequest {
            configuredComponents = configuredComponents == null ? List.of() : List.copyOf(configuredComponents);
        }

        public SnapshotRequest(
                LocalDate tradeDate,
                LocalDateTime asOfTime,
                String calendarVersion,
                List<UniverseCandidate> configuredComponents,
                boolean includeUserInterests
        ) {
            this(tradeDate, asOfTime, calendarVersion, configuredComponents, includeUserInterests,
                    null, null, null, "UNAVAILABLE", "未声明股票池的时点来源");
        }

        public SnapshotRequest(
                LocalDate tradeDate,
                LocalDateTime asOfTime,
                String calendarVersion,
                List<UniverseCandidate> configuredComponents
        ) {
            this(tradeDate, asOfTime, calendarVersion, configuredComponents, true);
        }
    }

    record SnapshotResult(
            AiResearchUniverse universe,
            AiResearchUniverseSnapshot snapshot,
            List<AiResearchUniverseItem> items,
            boolean reused
    ) {
        public SnapshotResult {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
