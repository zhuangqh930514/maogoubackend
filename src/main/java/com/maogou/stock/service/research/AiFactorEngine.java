package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.StockDetailResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AiFactorEngine {

    List<AiFactorValue> compute(AiSample sample, StockDetailResponse detail);

    List<AiFactorValue> compute(FactorContext context);

    List<AiFactorValue> normalizeCrossSection(List<AiFactorValue> values);

    List<AiFactorValue> normalizeAndStoreCrossSection(List<AiFactorValue> values);

    List<AiFactorValue> computeAndStoreCrossSection(List<FactorContext> contexts);

    List<AiFactorValue> findStoredForSamples(List<Long> sampleIds);

    record FactorContext(
            AiSample sample,
            StockDetailResponse detail,
            List<KlinePointResponse> marketKlines,
            List<KlinePointResponse> sectorKlines,
            BigDecimal newsSentiment,
            LocalDateTime newsAsOfTime,
            KlineSeriesSnapshot stockSeries,
            KlineSeriesSnapshot marketSeries,
            KlineSeriesSnapshot sectorSeries
    ) {
        public FactorContext(
                AiSample sample,
                StockDetailResponse detail,
                List<KlinePointResponse> marketKlines,
                List<KlinePointResponse> sectorKlines,
                BigDecimal newsSentiment,
                LocalDateTime newsAsOfTime
        ) {
            this(sample, detail, marketKlines, sectorKlines, newsSentiment, newsAsOfTime, null, null, null);
        }
    }
}
