package com.maogou.stock.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiAnalysisServiceImplHistoricalSnapshotTest {

    @Test
    void decodesFormalAnalysisDetailFromImmutableSampleSnapshot() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        LocalDate tradeDate = LocalDate.of(2026, 7, 24);
        AiSample sample = new AiSample();
        sample.id = 81L;
        sample.featureSnapshot = objectMapper.writeValueAsString(Map.of(
                "quote", new StockQuoteResponse("600519", "贵州茅台", new BigDecimal("1500"),
                        BigDecimal.ONE, new BigDecimal("0.07"), BigDecimal.ONE, "A股", "SINA",
                        tradeDate.atTime(16, 0)),
                "finance", FinanceSnapshotResponse.empty(),
                "intraday", List.of(),
                "kline", List.of(new KlinePointResponse(tradeDate, new BigDecimal("1490"),
                        new BigDecimal("1500"), new BigDecimal("1480"), new BigDecimal("1510"),
                        1000L, new BigDecimal("1500000")) )));

        StockDetailResponse detail = AiAnalysisServiceImpl.formalSnapshotDetail(sample, objectMapper);

        assertThat(detail.quote().code()).isEqualTo("600519");
        assertThat(detail.quote().fetchedAt()).isEqualTo(tradeDate.atTime(16, 0));
        assertThat(detail.kline()).singleElement().extracting(KlinePointResponse::tradeDate)
                .isEqualTo(tradeDate);
        assertThat(detail.finance()).isEqualTo(FinanceSnapshotResponse.empty());
    }
}
