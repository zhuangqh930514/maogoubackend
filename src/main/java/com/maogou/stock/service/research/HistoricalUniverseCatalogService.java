package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Reads the immutable, point-in-time universe snapshots produced by the
 * historical security-state import. It is deliberately separate from an
 * external market provider so a current listing endpoint cannot be used to
 * backfill an old date.
 */
public interface HistoricalUniverseCatalogService {

    CatalogPlan load(List<LocalDate> tradeDates, LocalDateTime asOfTime, int targetStockCount);

    record CatalogPlan(
            Map<LocalDate, DayCatalog> byDate,
            List<HistoricalMarketDataProvider.Security> unionSecurities,
            String sourceFingerprint
    ) {
        public CatalogPlan {
            byDate = byDate == null ? Map.of() : Map.copyOf(byDate);
            unionSecurities = unionSecurities == null ? List.of() : List.copyOf(unionSecurities);
        }
    }

    record DayCatalog(
            LocalDate tradeDate,
            Long snapshotId,
            String sourceName,
            String sourceRevision,
            LocalDateTime sourceObservedAt,
            String sourceFingerprint,
            List<AiResearchUniverseItem> items,
            List<HistoricalMarketDataProvider.Security> securities
    ) {
        public DayCatalog {
            items = items == null ? List.of() : List.copyOf(items);
            securities = securities == null ? List.of() : List.copyOf(securities);
        }

        public HistoricalMarketDataProvider.UniverseCatalog asEvidenceCatalog() {
            return new HistoricalMarketDataProvider.UniverseCatalog(
                    sourceName,
                    sourceObservedAt,
                    "db:ai_research_universe_snapshot/" + snapshotId,
                    sourceFingerprint,
                    securities);
        }
    }
}
