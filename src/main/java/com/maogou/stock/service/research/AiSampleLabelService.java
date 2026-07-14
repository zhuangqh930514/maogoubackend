package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

public interface AiSampleLabelService {

    List<AiSampleLabel> matureAndStore(LabelBatch batch);

    record SampleInput(
            Long sampleId,
            String stockCode,
            LocalDate signalTradeDate,
            String tradableStatus,
            String sampleFingerprint,
            KlineSeriesSnapshot stockSeries,
            KlineSeriesSnapshot benchmarkSeries,
            KlineSeriesSnapshot sectorSeries
    ) {
    }

    record TradingDay(
            Long id,
            LocalDate tradeDate,
            boolean tradingDay,
            LocalTime sessionCloseTime,
            String calendarVersion,
            String sourceFingerprint
    ) {
    }

    record LabelBatch(
            List<SampleInput> samples,
            List<TradingDay> calendars,
            String calendarVersion,
            String labelVersion,
            List<Integer> horizons,
            LocalDateTime verifiedAt
    ) {
        public LabelBatch {
            samples = samples == null ? List.of() : List.copyOf(samples);
            calendars = calendars == null ? List.of() : List.copyOf(calendars);
            horizons = horizons == null ? List.of() : List.copyOf(horizons);
        }
    }
}
