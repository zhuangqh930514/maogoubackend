package com.maogou.stock.service.v2;

import com.maogou.stock.domain.entity.v2.AiPortfolioBacktestDaily;
import com.maogou.stock.domain.entity.v2.AiPortfolioBacktestPosition;
import com.maogou.stock.domain.entity.v2.AiPortfolioBacktestRun;
import com.maogou.stock.domain.entity.v2.AiPortfolioBacktestTrade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiPortfolioBacktestService {

    BacktestResult runAndStore(BacktestRequest request);

    record Signal(
            Long predictionId,
            LocalDate signalDate,
            String stockCode,
            BigDecimal score,
            String actionBucket,
            String lineageFingerprint
    ) {
    }

    record MarketBar(
            String stockCode,
            LocalDate tradeDate,
            BigDecimal open,
            BigDecimal close,
            BigDecimal high,
            BigDecimal low,
            BigDecimal previousClose,
            Long volume,
            Boolean st,
            String sourceFingerprint
    ) {
    }

    record BenchmarkPoint(LocalDate tradeDate, BigDecimal dailyReturn, String sourceFingerprint) {
    }

    record CostModel(
            String version,
            BigDecimal buyCommissionRate,
            BigDecimal sellCommissionRate,
            BigDecimal stampDutyRate,
            BigDecimal transferFeeRate,
            BigDecimal slippageBps,
            BigDecimal minimumCommission
    ) {
    }

    record BacktestRequest(
            Long userId,
            Long trainingDatasetId,
            Long walkForwardRunId,
            Long strategyReleaseId,
            Long modelVersionId,
            String runKey,
            String engineVersion,
            Long randomSeed,
            LocalDate startTradeDate,
            LocalDate endTradeDate,
            Integer horizonDays,
            Integer topK,
            String rebalanceFrequency,
            BigDecimal initialCapital,
            CostModel costModel,
            List<Signal> signals,
            List<MarketBar> bars,
            String benchmarkCode,
            List<BenchmarkPoint> benchmark,
            LocalDateTime evaluatedAt
    ) {
    }

    record BacktestResult(
            AiPortfolioBacktestRun run,
            List<AiPortfolioBacktestDaily> daily,
            List<AiPortfolioBacktestTrade> trades,
            List<AiPortfolioBacktestPosition> positions
    ) {
    }
}
