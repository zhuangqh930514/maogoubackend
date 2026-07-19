package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.math.BigDecimal;
import java.util.Map;
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
            KlineSeriesSnapshot sectorSeries,
            String sectorMembershipFingerprint,
            Map<LocalDate, TradingState> tradingStates,
            boolean requiresVerifiedState
    ) {
        public SampleInput(
                Long sampleId,
                String stockCode,
                LocalDate signalTradeDate,
                String tradableStatus,
                String sampleFingerprint,
                KlineSeriesSnapshot stockSeries,
                KlineSeriesSnapshot benchmarkSeries,
                KlineSeriesSnapshot sectorSeries
        ) {
            this(sampleId, stockCode, signalTradeDate, tradableStatus, sampleFingerprint,
                    stockSeries, benchmarkSeries, sectorSeries, null, Map.of(), false);
        }

        public SampleInput {
            tradingStates = tradingStates == null ? Map.of() : Map.copyOf(tradingStates);
        }
    }

    record TradingState(
            LocalDate tradeDate,
            String sourceFingerprint,
            String qualityStatus,
            Integer buyTradable,
            Integer sellTradable,
            Integer suspended,
            Integer isSt,
            BigDecimal limitRatio,
            Integer isLimitUp,
            Integer isLimitDown
    ) {
        public boolean readyForBuy() {
            return "READY".equals(qualityStatus) && Integer.valueOf(1).equals(buyTradable);
        }

        public boolean readyForSell() {
            return "READY".equals(qualityStatus) && Integer.valueOf(1).equals(sellTradable);
        }
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
