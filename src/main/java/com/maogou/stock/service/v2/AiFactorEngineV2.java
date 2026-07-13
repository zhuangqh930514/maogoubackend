package com.maogou.stock.service.v2;

import com.maogou.stock.domain.entity.v2.AiFactorValueV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.StockDetailResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AiFactorEngineV2 {

    List<AiFactorValueV2> compute(AiSampleV2 sample, StockDetailResponse detail);

    List<AiFactorValueV2> compute(FactorContext context);

    List<AiFactorValueV2> normalizeCrossSection(List<AiFactorValueV2> values);

    List<AiFactorValueV2> normalizeAndStoreCrossSection(List<AiFactorValueV2> values);

    List<AiFactorValueV2> computeAndStoreCrossSection(List<FactorContext> contexts);

    List<AiFactorValueV2> findStoredForSamples(List<Long> sampleIds);

    record FactorContext(
            AiSampleV2 sample,
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
                AiSampleV2 sample,
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
