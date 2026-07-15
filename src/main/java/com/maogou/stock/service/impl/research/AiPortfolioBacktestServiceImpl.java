package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AiPortfolioBacktestServiceImpl implements AiPortfolioBacktestService {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    private static final BigDecimal LOT_SIZE = new BigDecimal("100");

    private final AiPortfolioBacktestRunMapper runMapper;
    private final AiPortfolioBacktestDailyMapper dailyMapper;
    private final AiPortfolioBacktestTradeMapper tradeMapper;
    private final AiPortfolioBacktestPositionMapper positionMapper;
    private final ObjectMapper objectMapper;

    public AiPortfolioBacktestServiceImpl(
            AiPortfolioBacktestRunMapper runMapper,
            AiPortfolioBacktestDailyMapper dailyMapper,
            AiPortfolioBacktestTradeMapper tradeMapper,
            AiPortfolioBacktestPositionMapper positionMapper,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.dailyMapper = dailyMapper;
        this.tradeMapper = tradeMapper;
        this.positionMapper = positionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public BacktestResult runAndStore(BacktestRequest request) {
        validate(request);
        Simulation simulation = simulate(request);
        AiPortfolioBacktestRun expectedRun = buildRun(request, simulation);
        runMapper.insertImmutable(expectedRun);
        AiPortfolioBacktestRun run = runMapper.selectByRunKeyForShare(request.runKey());
        if (run == null) {
            throw new IllegalStateException("组合回测运行写入后未读取到记录");
        }
        if (!Objects.equals(expectedRun.inputFingerprint, run.inputFingerprint)
                || !Objects.equals(expectedRun.configFingerprint, run.configFingerprint)) {
            throw new IllegalStateException("不可变组合回测运行冲突：" + request.runKey());
        }
        simulation.daily().forEach(item -> item.backtestRunId = run.id);
        simulation.trades().forEach(item -> item.backtestRunId = run.id);
        simulation.positions().forEach(item -> item.backtestRunId = run.id);
        if (!simulation.daily().isEmpty()) {
            dailyMapper.insertBatchImmutable(simulation.daily());
        }
        if (!simulation.trades().isEmpty()) {
            tradeMapper.insertBatchImmutable(simulation.trades());
        }
        if (!simulation.positions().isEmpty()) {
            positionMapper.insertBatchImmutable(simulation.positions());
        }
        List<AiPortfolioBacktestDaily> daily = dailyMapper.selectByRunIdForShare(run.id);
        List<AiPortfolioBacktestTrade> trades = tradeMapper.selectByRunIdForShare(run.id);
        List<AiPortfolioBacktestPosition> positions = positionMapper.selectByRunIdForShare(run.id);
        validateArtifacts(simulation, daily, trades, positions);
        return new BacktestResult(run, List.copyOf(daily), List.copyOf(trades), List.copyOf(positions));
    }

    private Simulation simulate(BacktestRequest request) {
        Map<LocalDate, List<Signal>> signalsByDate = new LinkedHashMap<>();
        request.signals().stream().sorted(Comparator.comparing(Signal::signalDate)
                        .thenComparing(Signal::stockCode))
                .forEach(signal -> signalsByDate.computeIfAbsent(
                        signal.signalDate(), ignored -> new ArrayList<>()).add(signal));
        Map<String, MarketBar> bars = new HashMap<>();
        request.bars().forEach(bar -> bars.put(barKey(bar.tradeDate(), bar.stockCode()), bar));
        Map<LocalDate, BenchmarkPoint> benchmark = new HashMap<>();
        request.benchmark().forEach(point -> benchmark.put(point.tradeDate(), point));
        List<LocalDate> dates = request.benchmark().stream().map(BenchmarkPoint::tradeDate)
                .filter(date -> !date.isBefore(request.startTradeDate())
                        && !date.isAfter(request.endTradeDate()))
                .sorted().toList();

        Map<String, Holding> holdings = new LinkedHashMap<>();
        List<AiPortfolioBacktestDaily> dailyRows = new ArrayList<>();
        List<AiPortfolioBacktestTrade> tradeRows = new ArrayList<>();
        List<AiPortfolioBacktestPosition> positionRows = new ArrayList<>();
        List<BigDecimal> dailyReturns = new ArrayList<>();
        BigDecimal cash = request.initialCapital();
        BigDecimal previousEquity = request.initialCapital();
        BigDecimal peakNav = BigDecimal.ONE;
        BigDecimal benchmarkNav = BigDecimal.ONE;

        for (int dateIndex = 0; dateIndex < dates.size(); dateIndex++) {
            LocalDate tradeDate = dates.get(dateIndex);
            LocalDate signalDate = dateIndex == 0 ? null : dates.get(dateIndex - 1);
            List<Signal> targets = topSignals(
                    signalDate == null ? List.of() : signalsByDate.getOrDefault(signalDate, List.of()),
                    request.topK());
            Set<String> targetCodes = targets.stream().map(Signal::stockCode)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            Map<String, Signal> targetByCode = new HashMap<>();
            targets.forEach(signal -> targetByCode.put(signal.stockCode(), signal));
            BigDecimal transactionCost = BigDecimal.ZERO;
            BigDecimal turnoverAmount = BigDecimal.ZERO;

            List<String> sells = holdings.keySet().stream()
                    .filter(code -> !targetCodes.contains(code)).sorted().toList();
            for (String stockCode : sells) {
                Holding holding = holdings.get(stockCode);
                MarketBar bar = bars.get(barKey(tradeDate, stockCode));
                Execution execution = sell(request, tradeDate, holding, bar);
                tradeRows.add(execution.trade());
                if (execution.executed()) {
                    cash = cash.add(execution.cashDelta());
                    transactionCost = transactionCost.add(execution.cost());
                    turnoverAmount = turnoverAmount.add(execution.grossAmount());
                    holdings.remove(stockCode);
                }
            }

            BigDecimal equityAtOpen = cash.add(openMarketValue(holdings, bars, tradeDate));
            BigDecimal targetAllocation = targetCodes.isEmpty() ? BigDecimal.ZERO
                    : equityAtOpen.divide(BigDecimal.valueOf(request.topK()), 12, RoundingMode.HALF_UP);
            for (String stockCode : targetCodes) {
                if (holdings.containsKey(stockCode)) {
                    continue;
                }
                Signal signal = targetByCode.get(stockCode);
                MarketBar bar = bars.get(barKey(tradeDate, stockCode));
                String marketRejection = buyRejection(stockCode, bar);
                if (marketRejection != null) {
                    tradeRows.add(rejectedTrade(
                            tradeDate, signal.predictionId(), stockCode, "BUY", marketRejection).trade());
                    continue;
                }
                if (holdings.size() >= request.topK()) {
                    tradeRows.add(rejectedTrade(
                            tradeDate, signal.predictionId(), stockCode, "BUY",
                            "POSITION_LIMIT_FORCED_HOLD").trade());
                    continue;
                }
                Execution execution = buy(request, tradeDate, signal, bar, targetAllocation, cash);
                tradeRows.add(execution.trade());
                if (execution.executed()) {
                    cash = cash.add(execution.cashDelta());
                    transactionCost = transactionCost.add(execution.cost());
                    turnoverAmount = turnoverAmount.add(execution.grossAmount());
                    BigDecimal averageCost = execution.cashDelta().negate()
                            .divide(execution.trade().filledQuantity, 8, RoundingMode.HALF_UP);
                    holdings.put(stockCode, new Holding(
                            stockCode, execution.trade().filledQuantity, averageCost, tradeDate));
                }
            }

            BigDecimal marketValue = closeMarketValue(holdings, bars, tradeDate);
            BigDecimal totalEquity = cash.add(marketValue);
            BigDecimal nav = totalEquity.divide(request.initialCapital(), 12, RoundingMode.HALF_UP);
            BigDecimal dailyReturn = previousEquity.signum() == 0 ? BigDecimal.ZERO
                    : totalEquity.divide(previousEquity, 12, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);
            BenchmarkPoint benchmarkPoint = benchmark.get(tradeDate);
            if (benchmarkPoint == null) {
                throw new IllegalArgumentException("基准缺少交易日：" + tradeDate);
            }
            benchmarkNav = benchmarkNav.multiply(BigDecimal.ONE.add(benchmarkPoint.dailyReturn()));
            peakNav = peakNav.max(nav);
            BigDecimal drawdown = peakNav.signum() == 0 ? BigDecimal.ZERO
                    : nav.divide(peakNav, 12, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
            BigDecimal turnover = previousEquity.signum() == 0 ? BigDecimal.ZERO
                    : turnoverAmount.divide(previousEquity, 12, RoundingMode.HALF_UP);
            BigDecimal exposure = totalEquity.signum() == 0 ? BigDecimal.ZERO
                    : marketValue.divide(totalEquity, 12, RoundingMode.HALF_UP);

            AiPortfolioBacktestDaily daily = new AiPortfolioBacktestDaily();
            daily.tradeDate = tradeDate;
            daily.cashBalance = amount(cash);
            daily.marketValue = amount(marketValue);
            daily.totalEquity = amount(totalEquity);
            daily.nav = nav.setScale(8, RoundingMode.HALF_UP);
            daily.benchmarkNav = benchmarkNav.setScale(8, RoundingMode.HALF_UP);
            daily.dailyReturn = rate(dailyReturn);
            daily.benchmarkReturn = rate(benchmarkPoint.dailyReturn());
            daily.drawdown = rate(drawdown);
            daily.turnoverRate = rate(turnover);
            daily.grossExposure = rate(exposure);
            daily.netExposure = rate(exposure);
            daily.holdingCount = holdings.size();
            daily.transactionCost = amount(transactionCost);
            daily.createdAt = LocalDateTime.now();
            dailyRows.add(daily);
            dailyReturns.add(daily.dailyReturn);

            for (Holding holding : holdings.values()) {
                MarketBar bar = requiredBar(bars, tradeDate, holding.stockCode);
                AiPortfolioBacktestPosition position = new AiPortfolioBacktestPosition();
                position.tradeDate = tradeDate;
                position.stockCode = holding.stockCode;
                position.quantity = holding.quantity;
                position.averageCost = holding.averageCost.setScale(4, RoundingMode.HALF_UP);
                position.closePrice = bar.close().setScale(4, RoundingMode.HALF_UP);
                position.marketValue = amount(holding.quantity.multiply(bar.close()));
                position.weight = totalEquity.signum() == 0 ? BigDecimal.ZERO
                        : rate(position.marketValue.divide(totalEquity, 12, RoundingMode.HALF_UP));
                position.unrealizedPnl = amount(bar.close().subtract(holding.averageCost)
                        .multiply(holding.quantity));
                BigDecimal pnlBase = holding.buyDate.equals(tradeDate)
                        ? holding.averageCost : bar.previousClose();
                position.dailyPnl = amount(bar.close().subtract(pnlBase).multiply(holding.quantity));
                position.returnContribution = previousEquity.signum() == 0 ? BigDecimal.ZERO
                        : rate(position.dailyPnl.divide(previousEquity, 12, RoundingMode.HALF_UP));
                position.tradableStatus = bar.volume() == null || bar.volume() <= 0
                        ? "SUSPENDED" : "TRADABLE";
                position.createdAt = LocalDateTime.now();
                positionRows.add(position);
            }
            previousEquity = totalEquity;
        }
        return new Simulation(dailyRows, tradeRows, positionRows, dailyReturns);
    }

    private Execution buy(
            BacktestRequest request,
            LocalDate tradeDate,
            Signal signal,
            MarketBar bar,
            BigDecimal targetAllocation,
            BigDecimal cash
    ) {
        String rejection = buyRejection(signal.stockCode(), bar);
        if (rejection != null) {
            return rejectedTrade(tradeDate, signal.predictionId(), signal.stockCode(), "BUY", rejection);
        }
        BigDecimal slippageRate = request.costModel().slippageBps()
                .divide(TEN_THOUSAND, 12, RoundingMode.HALF_UP);
        BigDecimal price = bar.open().multiply(BigDecimal.ONE.add(slippageRate));
        BigDecimal budget = targetAllocation.min(cash);
        BigDecimal quantity = budget.divide(price.multiply(LOT_SIZE), 0, RoundingMode.DOWN)
                .multiply(LOT_SIZE);
        Cost cost = null;
        while (quantity.signum() > 0) {
            cost = buyCost(price, bar.open(), quantity, request.costModel());
            if (cost.grossAmount().add(cost.feeCost()).compareTo(cash) <= 0) {
                break;
            }
            quantity = quantity.subtract(LOT_SIZE);
        }
        if (quantity.signum() <= 0 || cost == null) {
            return rejectedTrade(tradeDate, signal.predictionId(), signal.stockCode(),
                    "BUY", "INSUFFICIENT_CASH_OR_LOT");
        }
        AiPortfolioBacktestTrade trade = trade(
                tradeDate, signal.predictionId(), signal.stockCode(), "BUY", quantity, price, cost);
        return new Execution(trade, cost.grossAmount().add(cost.feeCost()).negate(),
                cost.grossAmount(), cost.totalCost(), true);
    }

    private Execution sell(
            BacktestRequest request,
            LocalDate tradeDate,
            Holding holding,
            MarketBar bar
    ) {
        String rejection = sellRejection(tradeDate, holding, bar);
        if (rejection != null) {
            return rejectedTrade(tradeDate, null, holding.stockCode, "SELL", rejection);
        }
        BigDecimal slippageRate = request.costModel().slippageBps()
                .divide(TEN_THOUSAND, 12, RoundingMode.HALF_UP);
        BigDecimal price = bar.open().multiply(BigDecimal.ONE.subtract(slippageRate));
        Cost cost = sellCost(price, bar.open(), holding.quantity, request.costModel());
        AiPortfolioBacktestTrade trade = trade(
                tradeDate, null, holding.stockCode, "SELL", holding.quantity, price, cost);
        return new Execution(trade, cost.grossAmount().subtract(cost.feeCost()),
                cost.grossAmount(), cost.totalCost(), true);
    }

    private static AiPortfolioBacktestTrade trade(
            LocalDate date,
            Long predictionId,
            String stockCode,
            String side,
            BigDecimal quantity,
            BigDecimal executionPrice,
            Cost cost
    ) {
        AiPortfolioBacktestTrade trade = new AiPortfolioBacktestTrade();
        trade.predictionId = predictionId;
        trade.tradeKey = date + ":" + stockCode + ":" + side;
        trade.tradeDate = date;
        trade.stockCode = stockCode;
        trade.side = side;
        trade.orderQuantity = quantity.setScale(4, RoundingMode.HALF_UP);
        trade.filledQuantity = trade.orderQuantity;
        trade.executionPrice = executionPrice.setScale(4, RoundingMode.HALF_UP);
        trade.grossAmount = amount(cost.grossAmount());
        trade.commissionAmount = amount(cost.commission());
        trade.stampDutyAmount = amount(cost.stampDuty());
        trade.transferFeeAmount = amount(cost.transferFee());
        trade.slippageAmount = amount(cost.slippage());
        trade.totalCostAmount = amount(cost.totalCost());
        trade.executionStatus = "EXECUTED";
        trade.createdAt = LocalDateTime.now();
        return trade;
    }

    private static Execution rejectedTrade(
            LocalDate date,
            Long predictionId,
            String stockCode,
            String side,
            String reason
    ) {
        AiPortfolioBacktestTrade trade = new AiPortfolioBacktestTrade();
        trade.predictionId = predictionId;
        trade.tradeKey = date + ":" + stockCode + ":" + side;
        trade.tradeDate = date;
        trade.stockCode = stockCode;
        trade.side = side;
        trade.orderQuantity = BigDecimal.ZERO.setScale(4);
        trade.filledQuantity = BigDecimal.ZERO.setScale(4);
        trade.commissionAmount = BigDecimal.ZERO.setScale(6);
        trade.stampDutyAmount = BigDecimal.ZERO.setScale(6);
        trade.transferFeeAmount = BigDecimal.ZERO.setScale(6);
        trade.slippageAmount = BigDecimal.ZERO.setScale(6);
        trade.totalCostAmount = BigDecimal.ZERO.setScale(6);
        trade.executionStatus = "REJECTED";
        trade.rejectionReason = reason;
        trade.createdAt = LocalDateTime.now();
        return new Execution(trade, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false);
    }

    private static Cost buyCost(
            BigDecimal price,
            BigDecimal referencePrice,
            BigDecimal quantity,
            CostModel model
    ) {
        BigDecimal gross = price.multiply(quantity);
        BigDecimal commission = commission(gross, model.buyCommissionRate(), model.minimumCommission());
        BigDecimal transfer = gross.multiply(model.transferFeeRate());
        BigDecimal slippage = price.subtract(referencePrice).multiply(quantity).max(BigDecimal.ZERO);
        BigDecimal fees = commission.add(transfer);
        return new Cost(gross, commission, BigDecimal.ZERO, transfer, slippage,
                fees, fees.add(slippage));
    }

    private static Cost sellCost(
            BigDecimal price,
            BigDecimal referencePrice,
            BigDecimal quantity,
            CostModel model
    ) {
        BigDecimal gross = price.multiply(quantity);
        BigDecimal commission = commission(gross, model.sellCommissionRate(), model.minimumCommission());
        BigDecimal stamp = gross.multiply(model.stampDutyRate());
        BigDecimal transfer = gross.multiply(model.transferFeeRate());
        BigDecimal slippage = referencePrice.subtract(price).multiply(quantity).max(BigDecimal.ZERO);
        BigDecimal fees = commission.add(stamp).add(transfer);
        return new Cost(gross, commission, stamp, transfer, slippage,
                fees, fees.add(slippage));
    }

    private static BigDecimal commission(BigDecimal gross, BigDecimal rate, BigDecimal minimum) {
        if (rate.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return gross.multiply(rate).max(minimum);
    }

    private static String buyRejection(String stockCode, MarketBar bar) {
        if (bar == null || bar.open() == null || bar.previousClose() == null) {
            return "MARKET_DATA_UNAVAILABLE";
        }
        if (Boolean.TRUE.equals(bar.st())) {
            return "ST_RESTRICTED";
        }
        if (bar.volume() == null || bar.volume() <= 0) {
            return "SUSPENDED_ENTRY";
        }
        BigDecimal upper = bar.previousClose().multiply(BigDecimal.ONE.add(limitRatio(stockCode, false)));
        if (sealed(bar) && bar.open().compareTo(upper.multiply(new BigDecimal("0.999"))) >= 0) {
            return "LIMIT_UP_ENTRY_BLOCKED";
        }
        return null;
    }

    private static String sellRejection(LocalDate date, Holding holding, MarketBar bar) {
        if (holding.buyDate.equals(date)) {
            return "T_PLUS_ONE_RESTRICTED";
        }
        if (bar == null || bar.open() == null || bar.previousClose() == null) {
            return "MARKET_DATA_UNAVAILABLE";
        }
        if (bar.volume() == null || bar.volume() <= 0) {
            return "SUSPENDED_EXIT";
        }
        BigDecimal lower = bar.previousClose().multiply(
                BigDecimal.ONE.subtract(limitRatio(holding.stockCode, Boolean.TRUE.equals(bar.st()))));
        if (sealed(bar) && bar.open().compareTo(lower.multiply(new BigDecimal("1.001"))) <= 0) {
            return "LIMIT_DOWN_EXIT_BLOCKED";
        }
        return null;
    }

    private static boolean sealed(MarketBar bar) {
        return bar.high() != null && bar.low() != null && bar.open() != null
                && bar.open().compareTo(bar.high()) == 0 && bar.open().compareTo(bar.low()) == 0;
    }

    private static BigDecimal limitRatio(String stockCode, boolean st) {
        if (st) {
            return new BigDecimal("0.05");
        }
        return stockCode.startsWith("300") || stockCode.startsWith("301") || stockCode.startsWith("688")
                ? new BigDecimal("0.20") : new BigDecimal("0.10");
    }

    private AiPortfolioBacktestRun buildRun(BacktestRequest request, Simulation simulation) {
        AiPortfolioBacktestDaily last = simulation.daily().get(simulation.daily().size() - 1);
        BigDecimal totalReturn = last.nav.subtract(BigDecimal.ONE);
        BigDecimal benchmarkReturn = last.benchmarkNav.subtract(BigDecimal.ONE);
        BigDecimal annualized = annualized(last.nav, simulation.daily().size());
        BigDecimal maxDrawdown = simulation.daily().stream().map(item -> item.drawdown)
                .min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal sharpe = sharpe(simulation.dailyReturns());
        BigDecimal calmar = maxDrawdown.signum() == 0 ? BigDecimal.ZERO
                : annualized.divide(maxDrawdown.abs(), 6, RoundingMode.HALF_UP);
        BigDecimal turnover = simulation.daily().stream().map(item -> item.turnoverRate)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        AiPortfolioBacktestRun run = new AiPortfolioBacktestRun();
        run.trainingDatasetId = request.trainingDatasetId();
        run.walkForwardRunId = request.walkForwardRunId();
        run.strategyReleaseId = request.strategyReleaseId();
        run.modelVersionId = request.modelVersionId();
        run.runKey = request.runKey();
        run.engineVersion = request.engineVersion();
        run.configFingerprint = configFingerprint(request);
        run.inputFingerprint = inputFingerprint(request);
        run.randomSeed = request.randomSeed();
        run.startTradeDate = request.startTradeDate();
        run.endTradeDate = request.endTradeDate();
        run.horizonDays = request.horizonDays();
        run.topK = request.topK();
        run.rebalanceFrequency = request.rebalanceFrequency();
        run.initialCapital = amount(request.initialCapital());
        run.finalNav = last.nav;
        run.benchmarkFinalNav = last.benchmarkNav;
        run.totalReturn = rate(totalReturn);
        run.benchmarkReturn = rate(benchmarkReturn);
        run.alpha = rate(totalReturn.subtract(benchmarkReturn));
        run.annualizedReturn = rate(annualized);
        run.sharpeRatio = sharpe;
        run.calmarRatio = calmar;
        run.maxDrawdown = rate(maxDrawdown);
        run.turnoverRate = rate(turnover);
        run.tradeCount = (int) simulation.trades().stream()
                .filter(item -> "EXECUTED".equals(item.executionStatus)).count();
        run.metricsJson = json(Map.of(
                "benchmarkCode", request.benchmarkCode(),
                "tradingDayCount", simulation.daily().size(),
                "rejectedTradeCount", simulation.trades().size() - run.tradeCount));
        run.status = "COMPLETED";
        run.startedAt = request.evaluatedAt();
        run.completedAt = request.evaluatedAt();
        run.createdAt = LocalDateTime.now();
        return run;
    }

    private static BigDecimal annualized(BigDecimal finalNav, int tradingDays) {
        if (tradingDays <= 0 || finalNav.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        double value = Math.pow(finalNav.doubleValue(), 252d / tradingDays) - 1d;
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal sharpe(List<BigDecimal> returns) {
        if (returns.size() < 2) {
            return BigDecimal.ZERO.setScale(6);
        }
        double mean = returns.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0d);
        double variance = returns.stream().mapToDouble(value -> Math.pow(value.doubleValue() - mean, 2))
                .sum() / (returns.size() - 1d);
        if (variance == 0d) {
            return BigDecimal.ZERO.setScale(6);
        }
        return BigDecimal.valueOf(mean / Math.sqrt(variance) * Math.sqrt(252d))
                .setScale(6, RoundingMode.HALF_UP);
    }

    private static List<Signal> topSignals(List<Signal> signals, int topK) {
        return signals.stream().filter(signal -> "RECOMMEND".equals(signal.actionBucket()))
                .sorted(Comparator.comparing(Signal::score, Comparator.reverseOrder())
                        .thenComparing(Signal::stockCode))
                .limit(topK).toList();
    }

    private static BigDecimal openMarketValue(
            Map<String, Holding> holdings,
            Map<String, MarketBar> bars,
            LocalDate date
    ) {
        return holdings.values().stream().map(holding -> {
            MarketBar bar = requiredBar(bars, date, holding.stockCode);
            return holding.quantity.multiply(bar.open());
        }).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal closeMarketValue(
            Map<String, Holding> holdings,
            Map<String, MarketBar> bars,
            LocalDate date
    ) {
        return holdings.values().stream().map(holding -> {
            MarketBar bar = requiredBar(bars, date, holding.stockCode);
            return holding.quantity.multiply(bar.close());
        }).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static MarketBar requiredBar(Map<String, MarketBar> bars, LocalDate date, String code) {
        MarketBar bar = bars.get(barKey(date, code));
        if (bar == null || bar.open() == null || bar.close() == null || bar.previousClose() == null) {
            throw new IllegalArgumentException("组合回测缺少持仓行情：" + date + " " + code);
        }
        return bar;
    }

    private static void validateArtifacts(
            Simulation expected,
            List<AiPortfolioBacktestDaily> daily,
            List<AiPortfolioBacktestTrade> trades,
            List<AiPortfolioBacktestPosition> positions
    ) {
        if (daily == null || daily.size() != expected.daily().size()
                || trades == null || trades.size() != expected.trades().size()
                || positions == null || positions.size() != expected.positions().size()) {
            throw new IllegalStateException("组合回测持久化产物数量不一致");
        }
        for (int index = 0; index < daily.size(); index++) {
            if (!Objects.equals(daily.get(index).tradeDate, expected.daily().get(index).tradeDate)
                    || !Objects.equals(daily.get(index).nav, expected.daily().get(index).nav)) {
                throw new IllegalStateException("不可变组合回测日净值冲突");
            }
        }
    }

    private static void validate(BacktestRequest request) {
        if (request == null || request.trainingDatasetId() == null || request.trainingDatasetId() <= 0
                || request.strategyReleaseId() == null || request.strategyReleaseId() <= 0
                || request.runKey() == null || request.runKey().isBlank()
                || request.engineVersion() == null || request.engineVersion().isBlank()
                || request.randomSeed() == null || request.startTradeDate() == null
                || request.endTradeDate() == null || request.startTradeDate().isAfter(request.endTradeDate())
                || request.horizonDays() == null || request.horizonDays() <= 0
                || request.topK() == null || request.topK() <= 0
                || !"DAILY".equals(request.rebalanceFrequency())
                || request.initialCapital() == null || request.initialCapital().signum() <= 0
                || request.costModel() == null || request.signals() == null || request.bars() == null
                || request.benchmarkCode() == null || request.benchmarkCode().isBlank()
                || request.benchmark() == null || request.benchmark().isEmpty()
                || request.evaluatedAt() == null) {
            throw new IllegalArgumentException("组合回测请求缺少有效配置、血缘或行情");
        }
        validateCosts(request.costModel());
        Set<Long> predictionIds = new HashSet<>();
        Set<String> signalKeys = new HashSet<>();
        for (Signal signal : request.signals()) {
            if (signal == null || signal.predictionId() == null || signal.predictionId() <= 0
                    || signal.signalDate() == null || signal.stockCode() == null
                    || signal.score() == null || signal.actionBucket() == null
                    || signal.lineageFingerprint() == null || signal.lineageFingerprint().isBlank()
                    || !predictionIds.add(signal.predictionId())) {
                throw new IllegalArgumentException("组合回测信号缺少预测血缘或包含重复预测");
            }
            if (!signalKeys.add(signal.signalDate() + "|" + signal.stockCode())) {
                throw new IllegalArgumentException("组合回测包含同日重复股票信号");
            }
        }
        Set<String> barKeys = new HashSet<>();
        for (MarketBar bar : request.bars()) {
            if (bar == null || bar.stockCode() == null || bar.tradeDate() == null
                    || bar.open() == null || bar.close() == null || bar.high() == null
                    || bar.low() == null || bar.previousClose() == null || bar.volume() == null
                    || bar.sourceFingerprint() == null || bar.sourceFingerprint().isBlank()
                    || !barKeys.add(barKey(bar.tradeDate(), bar.stockCode()))) {
                throw new IllegalArgumentException("组合回测行情缺少证据或包含重复股票日线");
            }
        }
        Set<LocalDate> benchmarkDates = new HashSet<>();
        for (BenchmarkPoint point : request.benchmark()) {
            if (point == null || point.tradeDate() == null || point.dailyReturn() == null
                    || point.sourceFingerprint() == null || point.sourceFingerprint().isBlank()
                    || !benchmarkDates.add(point.tradeDate())) {
                throw new IllegalArgumentException("组合回测基准缺少证据或包含重复交易日");
            }
        }
    }

    private static void validateCosts(CostModel model) {
        List<BigDecimal> values = List.of(model.buyCommissionRate(), model.sellCommissionRate(),
                model.stampDutyRate(), model.transferFeeRate(), model.slippageBps(),
                model.minimumCommission());
        if (model.version() == null || model.version().isBlank()
                || values.stream().anyMatch(Objects::isNull)
                || values.stream().anyMatch(value -> value.signum() < 0)) {
            throw new IllegalArgumentException("组合回测成本模型无效");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化组合回测证据", ex);
        }
    }

    private static String configFingerprint(BacktestRequest request) {
        return sha256(String.join("|", request.engineVersion(), String.valueOf(request.horizonDays()),
                String.valueOf(request.topK()), request.rebalanceFrequency(),
                String.valueOf(request.initialCapital()), String.valueOf(request.costModel()),
                String.valueOf(request.randomSeed())));
    }

    private static String inputFingerprint(BacktestRequest request) {
        String signals = request.signals().stream().sorted(Comparator.comparing(Signal::predictionId))
                .map(signal -> signal.predictionId() + ":" + signal.lineageFingerprint())
                .reduce((left, right) -> left + "|" + right).orElse("");
        String bars = request.bars().stream().sorted(Comparator.comparing(MarketBar::tradeDate)
                        .thenComparing(MarketBar::stockCode))
                .map(bar -> bar.tradeDate() + ":" + bar.stockCode() + ":" + bar.sourceFingerprint())
                .reduce((left, right) -> left + "|" + right).orElse("");
        String benchmark = request.benchmark().stream().sorted(Comparator.comparing(BenchmarkPoint::tradeDate))
                .map(point -> point.tradeDate() + ":" + point.sourceFingerprint())
                .reduce((left, right) -> left + "|" + right).orElse("");
        return sha256(String.join("|", String.valueOf(request.trainingDatasetId()),
                String.valueOf(request.walkForwardRunId()), String.valueOf(request.strategyReleaseId()),
                String.valueOf(request.modelVersionId()), configFingerprint(request), signals, bars,
                request.benchmarkCode(), benchmark));
    }

    private static String barKey(LocalDate date, String code) {
        return date + "|" + code;
    }

    private static BigDecimal amount(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static final class Holding {
        private final String stockCode;
        private final BigDecimal quantity;
        private final BigDecimal averageCost;
        private final LocalDate buyDate;

        private Holding(String stockCode, BigDecimal quantity, BigDecimal averageCost, LocalDate buyDate) {
            this.stockCode = stockCode;
            this.quantity = quantity;
            this.averageCost = averageCost;
            this.buyDate = buyDate;
        }
    }

    private record Cost(
            BigDecimal grossAmount,
            BigDecimal commission,
            BigDecimal stampDuty,
            BigDecimal transferFee,
            BigDecimal slippage,
            BigDecimal feeCost,
            BigDecimal totalCost
    ) {
    }

    private record Execution(
            AiPortfolioBacktestTrade trade,
            BigDecimal cashDelta,
            BigDecimal grossAmount,
            BigDecimal cost,
            boolean executed
    ) {
    }

    private record Simulation(
            List<AiPortfolioBacktestDaily> daily,
            List<AiPortfolioBacktestTrade> trades,
            List<AiPortfolioBacktestPosition> positions,
            List<BigDecimal> dailyReturns
    ) {
    }
}
