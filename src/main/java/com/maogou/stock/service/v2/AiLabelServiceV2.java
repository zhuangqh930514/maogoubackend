package com.maogou.stock.service.v2;

import com.maogou.stock.domain.entity.v2.AiLabelV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.domain.entity.v2.AiTradingCalendar;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface AiLabelServiceV2 {

    List<AiLabelV2> verifyAndStore(LabelBatch batch);

    record LabelInput(
            AiPredictionV2 prediction,
            AiSampleV2 sample,
            KlineSeriesSnapshot stockSeries,
            KlineSeriesSnapshot benchmarkSeries,
            KlineSeriesSnapshot sectorSeries
    ) {
    }

    record CostModel(
            String version,
            BigDecimal buyCommissionRate,
            BigDecimal sellCommissionRate,
            BigDecimal stampDutyRate,
            BigDecimal transferFeeRate,
            BigDecimal slippageBps,
            BigDecimal quantity
    ) {
    }

    record LabelBatch(
            List<LabelInput> inputs,
            List<AiTradingCalendar> calendars,
            String calendarVersion,
            String labelVersion,
            List<Integer> horizons,
            CostModel costModel,
            LocalDateTime verifiedAt
    ) {
    }
}
