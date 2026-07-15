package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiPortfolioBacktestDaily;
import com.maogou.stock.domain.entity.research.AiPortfolioBacktestPosition;
import com.maogou.stock.domain.entity.research.AiPortfolioBacktestRun;
import com.maogou.stock.domain.entity.research.AiPortfolioBacktestTrade;
import com.maogou.stock.mapper.research.AiPortfolioBacktestDailyMapper;
import com.maogou.stock.mapper.research.AiPortfolioBacktestPositionMapper;
import com.maogou.stock.mapper.research.AiPortfolioBacktestRunMapper;
import com.maogou.stock.mapper.research.AiPortfolioBacktestTradeMapper;
import com.maogou.stock.service.research.AiPortfolioBacktestService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiPortfolioBacktestServiceImplTest {

    @Test
    void rebalancesFromPreviousCloseSignalsKeepsTopKAndCompoundsDailyNav() {
        Fixture fixture = fixture();
        AiPortfolioBacktestService service = service(fixture);

        AiPortfolioBacktestService.BacktestResult result = service.runAndStore(request());

        assertThat(result.run().status).isEqualTo("COMPLETED");
        assertThat(result.daily()).hasSize(4);
        assertThat(result.positions().stream().filter(item -> item.tradeDate.equals(date(2))))
                .extracting(item -> item.stockCode).containsExactlyInAnyOrder("600001", "600002");
        assertThat(result.daily()).allMatch(item -> item.holdingCount <= 2);
        BigDecimal compounded = result.daily().stream()
                .map(item -> BigDecimal.ONE.add(item.dailyReturn))
                .reduce(BigDecimal.ONE, BigDecimal::multiply)
                .setScale(8, RoundingMode.HALF_UP);
        assertThat(result.run().finalNav).isEqualByComparingTo(
                result.daily().get(result.daily().size() - 1).nav);
        assertThat(result.run().finalNav).isCloseTo(compounded, within(new BigDecimal("0.000001")));
        assertThat(result.run().totalReturn)
                .isEqualByComparingTo(result.run().finalNav.subtract(BigDecimal.ONE)
                        .setScale(6, RoundingMode.HALF_UP));
        assertThat(result.trades()).anyMatch(item -> "600003".equals(item.stockCode)
                && date(3).equals(item.tradeDate) && "BUY".equals(item.side));
    }

    @Test
    void appliesLotSizeCommissionStampDutyTransferFeeAndSlippageWithoutDoubleChargingCash() {
        AiPortfolioBacktestService zeroCostService = service(fixture());
        AiPortfolioBacktestService.BacktestResult zeroCost = zeroCostService.runAndStore(request());
        AiPortfolioBacktestService chargedService = service(fixture());
        AiPortfolioBacktestService.BacktestRequest original = request();
        AiPortfolioBacktestService.CostModel costs = new AiPortfolioBacktestService.CostModel(
                "CN_A_V1", new BigDecimal("0.0003"), new BigDecimal("0.0003"),
                new BigDecimal("0.0005"), new BigDecimal("0.00001"),
                new BigDecimal("5"), new BigDecimal("5"));
        AiPortfolioBacktestService.BacktestRequest chargedRequest = withCost(original, costs);

        AiPortfolioBacktestService.BacktestResult charged = chargedService.runAndStore(chargedRequest);

        assertThat(charged.run().finalNav).isLessThan(zeroCost.run().finalNav);
        assertThat(charged.daily()).anyMatch(item -> item.transactionCost.signum() > 0);
        assertThat(charged.trades().stream().filter(item -> "EXECUTED".equals(item.executionStatus)))
                .allMatch(item -> item.filledQuantity.remainder(new BigDecimal("100")).signum() == 0);
        assertThat(charged.trades().stream().filter(item -> "BUY".equals(item.side)))
                .allMatch(item -> item.stampDutyAmount.signum() == 0);
        assertThat(charged.trades()).anyMatch(item -> "SELL".equals(item.side)
                && item.stampDutyAmount.signum() > 0);
        assertThat(charged.trades().stream().filter(item -> "EXECUTED".equals(item.executionStatus)))
                .allMatch(item -> item.totalCostAmount.compareTo(
                        item.commissionAmount.add(item.stampDutyAmount)
                                .add(item.transferFeeAmount).add(item.slippageAmount)) == 0);
    }

    @Test
    void recordsLimitDownAndSuspensionAsRejectedExecutionsAndKeepsForcedHoldings() {
        Fixture fixture = fixture();
        AiPortfolioBacktestService service = service(fixture);
        AiPortfolioBacktestService.BacktestRequest original = request();
        List<AiPortfolioBacktestService.MarketBar> bars = new ArrayList<>(original.bars());
        replaceBar(bars, new AiPortfolioBacktestService.MarketBar(
                "600002", date(3), new BigDecimal("8.1"), new BigDecimal("8.1"),
                new BigDecimal("8.1"), new BigDecimal("8.1"), new BigDecimal("9"),
                1000L, false, "locked-limit-down"));
        replaceBar(bars, new AiPortfolioBacktestService.MarketBar(
                "600003", date(3), new BigDecimal("10"), new BigDecimal("10"),
                new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("10"),
                0L, false, "suspended"));
        AiPortfolioBacktestService.BacktestRequest blocked = new AiPortfolioBacktestService.BacktestRequest(
                original.trainingDatasetId(), original.walkForwardRunId(),
                original.strategyReleaseId(), original.modelVersionId(), original.runKey(),
                original.engineVersion(), original.randomSeed(), original.startTradeDate(),
                original.endTradeDate(), original.horizonDays(), original.topK(),
                original.rebalanceFrequency(), original.initialCapital(), original.costModel(),
                original.signals(), bars, original.benchmarkCode(), original.benchmark(),
                original.evaluatedAt());

        AiPortfolioBacktestService.BacktestResult result = service.runAndStore(blocked);

        assertThat(result.trades()).anyMatch(item -> date(3).equals(item.tradeDate)
                && "600002".equals(item.stockCode) && "SELL".equals(item.side)
                && "REJECTED".equals(item.executionStatus)
                && "LIMIT_DOWN_EXIT_BLOCKED".equals(item.rejectionReason));
        assertThat(result.trades()).anyMatch(item -> date(3).equals(item.tradeDate)
                && "600003".equals(item.stockCode) && "BUY".equals(item.side)
                && "REJECTED".equals(item.executionStatus)
                && "SUSPENDED_ENTRY".equals(item.rejectionReason));
        assertThat(result.positions().stream().filter(item -> date(3).equals(item.tradeDate)))
                .extracting(item -> item.stockCode).containsExactlyInAnyOrder("600001", "600002");
    }

    @Test
    void rerunningTheSameInputsReturnsTheOriginalDeterministicBacktestArtifacts() {
        Fixture fixture = fixture();
        AiPortfolioBacktestService service = service(fixture);
        AiPortfolioBacktestService.BacktestRequest request = request();

        AiPortfolioBacktestService.BacktestResult first = service.runAndStore(request);
        AiPortfolioBacktestService.BacktestResult repeated = service.runAndStore(request);

        assertThat(repeated.run().id).isEqualTo(first.run().id);
        assertThat(repeated.run().finalNav).isEqualByComparingTo(first.run().finalNav);
        assertThat(repeated.daily()).extracting(item -> item.id)
                .containsExactlyElementsOf(first.daily().stream().map(item -> item.id).toList());
        assertThat(repeated.trades()).extracting(item -> item.id)
                .containsExactlyElementsOf(first.trades().stream().map(item -> item.id).toList());
        assertThat(repeated.positions()).extracting(item -> item.id)
                .containsExactlyElementsOf(first.positions().stream().map(item -> item.id).toList());
    }

    @Test
    void aBlockedSellCannotExpandThePortfolioBeyondTopK() {
        Fixture fixture = fixture();
        AiPortfolioBacktestService service = service(fixture);
        AiPortfolioBacktestService.BacktestRequest original = request();
        List<AiPortfolioBacktestService.MarketBar> bars = new ArrayList<>(original.bars());
        replaceBar(bars, new AiPortfolioBacktestService.MarketBar(
                "600002", date(3), new BigDecimal("8.1"), new BigDecimal("8.1"),
                new BigDecimal("8.1"), new BigDecimal("8.1"), new BigDecimal("9"),
                1000L, false, "locked-limit-down"));
        AiPortfolioBacktestService.BacktestRequest blocked = new AiPortfolioBacktestService.BacktestRequest(
                original.trainingDatasetId(), original.walkForwardRunId(),
                original.strategyReleaseId(), original.modelVersionId(), original.runKey(),
                original.engineVersion(), original.randomSeed(), original.startTradeDate(),
                original.endTradeDate(), original.horizonDays(), original.topK(),
                original.rebalanceFrequency(), new BigDecimal("101000"), original.costModel(),
                original.signals(), bars, original.benchmarkCode(), original.benchmark(),
                original.evaluatedAt());

        AiPortfolioBacktestService.BacktestResult result = service.runAndStore(blocked);

        assertThat(result.daily()).allMatch(item -> item.holdingCount <= 2);
        assertThat(result.trades()).anyMatch(item -> date(3).equals(item.tradeDate)
                && "600003".equals(item.stockCode) && "BUY".equals(item.side)
                && "POSITION_LIMIT_FORCED_HOLD".equals(item.rejectionReason));
    }

    @Test
    void rejectsDuplicateStockSignalsOnTheSameDateInsteadOfConsumingMultipleTopKSlots() {
        Fixture fixture = fixture();
        AiPortfolioBacktestService service = service(fixture);
        AiPortfolioBacktestService.BacktestRequest original = request();
        List<AiPortfolioBacktestService.Signal> duplicated = new ArrayList<>(original.signals());
        duplicated.add(signal(999L, 1, "600001", "99"));
        AiPortfolioBacktestService.BacktestRequest invalid = new AiPortfolioBacktestService.BacktestRequest(
                original.trainingDatasetId(), original.walkForwardRunId(),
                original.strategyReleaseId(), original.modelVersionId(), original.runKey(),
                original.engineVersion(), original.randomSeed(), original.startTradeDate(),
                original.endTradeDate(), original.horizonDays(), original.topK(),
                original.rebalanceFrequency(), original.initialCapital(), original.costModel(),
                duplicated, original.bars(), original.benchmarkCode(), original.benchmark(),
                original.evaluatedAt());

        assertThatThrownBy(() -> service.runAndStore(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同日重复股票信号");
    }

    private static AiPortfolioBacktestService service(Fixture fixture) {
        return new AiPortfolioBacktestServiceImpl(
                fixture.runMapper, fixture.dailyMapper, fixture.tradeMapper, fixture.positionMapper,
                new ObjectMapper().findAndRegisterModules());
    }

    private static AiPortfolioBacktestService.BacktestRequest request() {
        List<AiPortfolioBacktestService.Signal> signals = List.of(
                signal(101L, 1, "600001", "90"), signal(102L, 1, "600002", "80"),
                signal(103L, 1, "600003", "70"), signal(104L, 2, "600001", "95"),
                signal(105L, 2, "600003", "85"), signal(106L, 2, "600002", "60"),
                signal(107L, 3, "600003", "95"), signal(108L, 3, "600001", "90"));
        List<AiPortfolioBacktestService.MarketBar> bars = new ArrayList<>();
        bars.add(bar(1, "600001", "10", "10", "10", "10", "9", 1000));
        bars.add(bar(1, "600002", "10", "10", "10", "10", "9", 1000));
        bars.add(bar(1, "600003", "10", "10", "10", "10", "9", 1000));
        bars.add(bar(2, "600001", "10", "11", "12", "9", "10", 1000));
        bars.add(bar(2, "600002", "10", "9", "11", "8", "10", 1000));
        bars.add(bar(2, "600003", "10", "10", "11", "9", "10", 1000));
        bars.add(bar(3, "600001", "11", "12", "12", "10", "11", 1000));
        bars.add(bar(3, "600002", "9", "9", "10", "8", "9", 1000));
        bars.add(bar(3, "600003", "10", "11", "11", "9", "10", 1000));
        bars.add(bar(4, "600001", "12", "12", "13", "11", "12", 1000));
        bars.add(bar(4, "600002", "9", "9", "10", "8", "9", 1000));
        bars.add(bar(4, "600003", "11", "12", "12", "10", "11", 1000));
        List<AiPortfolioBacktestService.BenchmarkPoint> benchmark = List.of(
                benchmark(1, "0"), benchmark(2, "0.01"),
                benchmark(3, "0.01"), benchmark(4, "0.01"));
        AiPortfolioBacktestService.CostModel zeroCost = new AiPortfolioBacktestService.CostModel(
                "ZERO_COST", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        return new AiPortfolioBacktestService.BacktestRequest(
                11L, 31L, 21L, null, "BT-202607", "BACKTEST_V2_1",
                930514L, date(1), date(4), 3, 2, "DAILY",
                new BigDecimal("100000"), zeroCost, signals, bars,
                "000300", benchmark, LocalDateTime.of(2026, 7, 31, 16, 0));
    }

    private static AiPortfolioBacktestService.BacktestRequest withCost(
            AiPortfolioBacktestService.BacktestRequest original,
            AiPortfolioBacktestService.CostModel costs
    ) {
        return new AiPortfolioBacktestService.BacktestRequest(
                original.trainingDatasetId(), original.walkForwardRunId(),
                original.strategyReleaseId(), original.modelVersionId(), original.runKey(),
                original.engineVersion(), original.randomSeed(), original.startTradeDate(),
                original.endTradeDate(), original.horizonDays(), original.topK(),
                original.rebalanceFrequency(), original.initialCapital(), costs,
                original.signals(), original.bars(), original.benchmarkCode(),
                original.benchmark(), original.evaluatedAt());
    }

    private static void replaceBar(
            List<AiPortfolioBacktestService.MarketBar> bars,
            AiPortfolioBacktestService.MarketBar replacement
    ) {
        bars.removeIf(item -> item.tradeDate().equals(replacement.tradeDate())
                && item.stockCode().equals(replacement.stockCode()));
        bars.add(replacement);
    }

    private static AiPortfolioBacktestService.Signal signal(
            Long predictionId, int day, String stockCode, String score
    ) {
        return new AiPortfolioBacktestService.Signal(
                predictionId, date(day), stockCode, new BigDecimal(score),
                "RECOMMEND", "signal-" + predictionId);
    }

    private static AiPortfolioBacktestService.MarketBar bar(
            int day,
            String stockCode,
            String open,
            String close,
            String high,
            String low,
            String previousClose,
            long volume
    ) {
        return new AiPortfolioBacktestService.MarketBar(
                stockCode, date(day), new BigDecimal(open), new BigDecimal(close),
                new BigDecimal(high), new BigDecimal(low), new BigDecimal(previousClose),
                volume, false, "bar-" + day + "-" + stockCode);
    }

    private static AiPortfolioBacktestService.BenchmarkPoint benchmark(int day, String value) {
        return new AiPortfolioBacktestService.BenchmarkPoint(
                date(day), new BigDecimal(value), "benchmark-" + day);
    }

    private static LocalDate date(int day) {
        return LocalDate.of(2026, 7, day);
    }

    private static Fixture fixture() {
        AiPortfolioBacktestRunMapper runMapper = mock(AiPortfolioBacktestRunMapper.class);
        AiPortfolioBacktestDailyMapper dailyMapper = mock(AiPortfolioBacktestDailyMapper.class);
        AiPortfolioBacktestTradeMapper tradeMapper = mock(AiPortfolioBacktestTradeMapper.class);
        AiPortfolioBacktestPositionMapper positionMapper = mock(AiPortfolioBacktestPositionMapper.class);
        AtomicLong ids = new AtomicLong(2000);
        List<AiPortfolioBacktestRun> runs = new ArrayList<>();
        List<AiPortfolioBacktestDaily> daily = new ArrayList<>();
        List<AiPortfolioBacktestTrade> trades = new ArrayList<>();
        List<AiPortfolioBacktestPosition> positions = new ArrayList<>();
        when(runMapper.insertImmutable(org.mockito.ArgumentMatchers.any(AiPortfolioBacktestRun.class)))
                .thenAnswer(invocation -> {
                    AiPortfolioBacktestRun item = invocation.getArgument(0);
                    boolean exists = runs.stream().anyMatch(existing ->
                            existing.runKey.equals(item.runKey));
                    if (!exists) {
                        item.id = ids.incrementAndGet();
                        runs.add(item);
                    }
                    return 1;
                });
        when(runMapper.selectByRunKeyForShare(anyString()))
                .thenAnswer(invocation -> runs.stream()
                        .filter(item -> item.runKey.equals(invocation.getArgument(0)))
                        .findFirst().orElse(null));
        when(dailyMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiPortfolioBacktestDaily> items = invocation.getArgument(0);
            items.forEach(item -> {
                boolean exists = daily.stream().anyMatch(existing ->
                        existing.backtestRunId.equals(item.backtestRunId)
                                && existing.tradeDate.equals(item.tradeDate));
                if (!exists) { item.id = ids.incrementAndGet(); daily.add(item); }
            });
            return items.size();
        });
        when(dailyMapper.selectByRunIdForShare(anyLong())).thenAnswer(invocation -> List.copyOf(daily));
        when(tradeMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiPortfolioBacktestTrade> items = invocation.getArgument(0);
            items.forEach(item -> {
                boolean exists = trades.stream().anyMatch(existing ->
                        existing.backtestRunId.equals(item.backtestRunId)
                                && existing.tradeKey.equals(item.tradeKey));
                if (!exists) { item.id = ids.incrementAndGet(); trades.add(item); }
            });
            return items.size();
        });
        when(tradeMapper.selectByRunIdForShare(anyLong())).thenAnswer(invocation -> List.copyOf(trades));
        when(positionMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiPortfolioBacktestPosition> items = invocation.getArgument(0);
            items.forEach(item -> {
                boolean exists = positions.stream().anyMatch(existing ->
                        existing.backtestRunId.equals(item.backtestRunId)
                                && existing.tradeDate.equals(item.tradeDate)
                                && existing.stockCode.equals(item.stockCode));
                if (!exists) { item.id = ids.incrementAndGet(); positions.add(item); }
            });
            return items.size();
        });
        when(positionMapper.selectByRunIdForShare(anyLong()))
                .thenAnswer(invocation -> List.copyOf(positions));
        return new Fixture(runMapper, dailyMapper, tradeMapper, positionMapper);
    }

    private record Fixture(
            AiPortfolioBacktestRunMapper runMapper,
            AiPortfolioBacktestDailyMapper dailyMapper,
            AiPortfolioBacktestTradeMapper tradeMapper,
            AiPortfolioBacktestPositionMapper positionMapper
    ) {
    }
}
