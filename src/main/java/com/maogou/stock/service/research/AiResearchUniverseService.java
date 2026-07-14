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
            LocalDate effectiveFrom
    ) {
    }

    record SnapshotRequest(
            LocalDate tradeDate,
            LocalDateTime asOfTime,
            String calendarVersion,
            List<UniverseCandidate> configuredComponents
    ) {
        public SnapshotRequest {
            configuredComponents = configuredComponents == null ? List.of() : List.copyOf(configuredComponents);
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
