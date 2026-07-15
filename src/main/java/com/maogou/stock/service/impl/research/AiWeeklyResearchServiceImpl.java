package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.mapper.research.AiFactorValueMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.mapper.research.AiModelVersionMapper;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.research.AiEvolutionAutomationService;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiFactorPerformanceService;
import com.maogou.stock.service.research.AiPortfolioBacktestService;
import com.maogou.stock.service.research.AiShadowEvaluationService;
import com.maogou.stock.service.research.AiStrategyGovernanceService;
import com.maogou.stock.service.research.AiWalkForwardService;
import com.maogou.stock.service.research.AiWeeklyEvolutionRunner;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiWeeklyResearchServiceImpl implements AiWeeklyEvolutionRunner {

    private static final int HORIZON_DAYS = 3;
    private static final long RANDOM_SEED = 930514L;

    private final AppProperties properties;
    private final AiStrategyReleaseMapper releaseMapper;
    private final AiModelVersionMapper modelMapper;
    private final AiPredictionMapper predictionMapper;
    private final AiSampleLabelMapper labelMapper;
    private final AiFactorValueMapper factorMapper;
    private final AiSampleMapper sampleMapper;
    private final MarketDataService marketDataService;
    private final AiFactorPerformanceService factorPerformanceService;
    private final AiWalkForwardService walkForwardService;
    private final AiPortfolioBacktestService backtestService;
    private final AiShadowEvaluationService shadowEvaluationService;
    private final ObjectMapper objectMapper;

    public AiWeeklyResearchServiceImpl(
            AppProperties properties,
            AiStrategyReleaseMapper releaseMapper,
            AiModelVersionMapper modelMapper,
            AiPredictionMapper predictionMapper,
            AiSampleLabelMapper labelMapper,
            AiFactorValueMapper factorMapper,
            AiSampleMapper sampleMapper,
            MarketDataService marketDataService,
            AiFactorPerformanceService factorPerformanceService,
            AiWalkForwardService walkForwardService,
            AiPortfolioBacktestService backtestService,
            AiShadowEvaluationService shadowEvaluationService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.releaseMapper = releaseMapper;
        this.modelMapper = modelMapper;
        this.predictionMapper = predictionMapper;
        this.labelMapper = labelMapper;
        this.factorMapper = factorMapper;
        this.sampleMapper = sampleMapper;
        this.marketDataService = marketDataService;
        this.factorPerformanceService = factorPerformanceService;
        this.walkForwardService = walkForwardService;
        this.backtestService = backtestService;
        this.shadowEvaluationService = shadowEvaluationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiEvolutionAutomationService.CycleResult run(Long ignoredUserId, LocalDateTime triggeredAt) {
        validate(triggeredAt);
        int processed = 0;
        int success = 0;
        int challengerSuccess = 0;
        List<String> errors = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        try {
            int factorCount = evaluateFactorPerformance(triggeredAt);
            processed += factorCount;
            success += factorCount;
            if (factorCount > 0) {
                skipped.add("已更新 " + factorCount + " 条滚动因子表现与漂移证据");
            } else {
                skipped.add("成熟因子样本不足，暂未生成新的因子表现窗口");
            }
        } catch (RuntimeException exception) {
            processed++;
            errors.add("因子表现评估：" + rootMessage(exception));
        }
        AiStrategyRelease champion = releaseMapper.selectGlobalActiveChampionForUpdate(
                AiResearchContract.SYSTEM_UNIVERSE_CODE, AiResearchContract.MODEL_FAMILY);
        if (champion == null) {
            skipped.add("缺少 active Champion，Challenger 评估未执行");
            return cycleResult(processed, success, errors, skipped);
        }
        List<AiStrategyRelease> challengers = releaseMapper.selectShadowChallengers(
                champion.researchUniverseId, champion.modelFamily);
        if (challengers == null || challengers.isEmpty()) {
            skipped.add("当前没有 SHADOW Challenger，影子评估无需执行");
            return cycleResult(processed, success, errors, skipped);
        }
        for (AiStrategyRelease challenger : challengers) {
            processed++;
            try {
                evaluateChallenger(champion, challenger, triggeredAt);
                success++;
                challengerSuccess++;
            } catch (InsufficientEvidenceException exception) {
                skipped.add("Challenger #" + challenger.id + "：" + exception.getMessage());
            } catch (RuntimeException exception) {
                errors.add("Challenger #" + challenger.id + "：" + rootMessage(exception));
            }
        }
        if (challengerSuccess > 0) {
            skipped.add("已完成 Challenger 周度影子评估，证据达标时同步固化实验与回测");
        }
        return cycleResult(processed, success, errors, skipped);
    }

    private int evaluateFactorPerformance(LocalDateTime now) {
        LocalDate historyStart = now.toLocalDate().minusDays(
                Math.max(180, properties.getScheduler().getWeeklyLookbackDays()));
        List<AiSample> samples = sampleMapper.selectList(new QueryWrapper<AiSample>()
                .between("trade_date", historyStart, now.toLocalDate())
                .le("as_of_time", now)
                .orderByAsc("trade_date").orderByAsc("id"));
        if (samples == null || samples.isEmpty()) {
            return 0;
        }
        LocalDate windowEnd = samples.stream().map(item -> item.tradeDate)
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        if (windowEnd == null) {
            return 0;
        }
        LocalDate windowStart = windowEnd.minusDays(59);
        LocalDate baselineStart = windowStart.minusDays(60);
        Map<Long, AiSample> samplesById = samples.stream()
                .filter(item -> item.id != null)
                .collect(Collectors.toMap(item -> item.id, Function.identity(), (left, right) -> left));
        if (samplesById.isEmpty()) {
            return 0;
        }
        List<AiFactorValue> factors = new ArrayList<>();
        List<AiSampleLabel> labels = new ArrayList<>();
        List<Long> orderedSampleIds = samplesById.keySet().stream().sorted().toList();
        for (List<Long> sampleIds : chunks(orderedSampleIds, 500)) {
            factors.addAll(factorMapper.selectBySamples(
                    sampleIds, AiResearchContract.FACTOR_VERSION));
            labels.addAll(labelMapper.selectList(new QueryWrapper<AiSampleLabel>()
                    .eq("label_version", AiResearchContract.LABEL_VERSION)
                    .eq("horizon_trading_days", HORIZON_DAYS)
                    .eq("label_status", "MATURED")
                    .eq("execution_status", "EXECUTED")
                    .le("label_available_at", now)
                    .in("sample_id", sampleIds)
                    .orderByDesc("matured_at").orderByDesc("id")));
        }
        Map<Long, AiSampleLabel> labelBySample = labels.stream()
                .filter(item -> item.sampleId != null)
                .collect(Collectors.toMap(item -> item.sampleId, Function.identity(), (left, right) -> left));
        Map<String, List<AiFactorPerformanceService.Observation>> byRegime = factors.stream()
                .filter(item -> item.sampleId != null)
                .map(factor -> {
                    AiSample sample = samplesById.get(factor.sampleId);
                    AiSampleLabel label = labelBySample.get(factor.sampleId);
                    return sample == null || label == null
                            ? null : new AiFactorPerformanceService.Observation(sample, factor, label);
                })
                .filter(Objects::nonNull)
                .filter(item -> item.sample().tradeDate != null)
                .filter(item -> item.sample().marketRegime != null && !item.sample().marketRegime.isBlank())
                .collect(Collectors.groupingBy(
                        item -> item.sample().marketRegime, LinkedHashMap::new, Collectors.toList()));
        int count = 0;
        for (Map.Entry<String, List<AiFactorPerformanceService.Observation>> entry : byRegime.entrySet()) {
            List<AiFactorPerformanceService.Observation> current = entry.getValue().stream()
                    .filter(item -> !item.sample().tradeDate.isBefore(windowStart))
                    .filter(item -> !item.sample().tradeDate.isAfter(windowEnd))
                    .toList();
            if (current.isEmpty()) {
                continue;
            }
            List<AiFactorPerformanceService.Observation> baseline = entry.getValue().stream()
                    .filter(item -> !item.sample().tradeDate.isBefore(baselineStart))
                    .filter(item -> item.sample().tradeDate.isBefore(windowStart))
                    .toList();
            AiFactorPerformanceService.EvaluationResult result = factorPerformanceService.evaluateAndStore(
                    new AiFactorPerformanceService.PerformanceBatch(
                            AiResearchContract.FACTOR_VERSION, HORIZON_DAYS,
                            entry.getKey(), "ROLLING_60D", windowStart, windowEnd,
                            current, baseline, "FACTOR_DRIFT_V2_1",
                            new AiFactorPerformanceService.DriftThresholds(
                                    new BigDecimal("0.10"), new BigDecimal("0.25"),
                                    new BigDecimal("15"), new BigDecimal("25")),
                            now));
            if (result != null && result.performances() != null) {
                count += result.performances().size();
            }
        }
        return count;
    }

    private void evaluateChallenger(
            AiStrategyRelease champion,
            AiStrategyRelease challenger,
            LocalDateTime now
    ) {
        LocalDate windowEnd = now.toLocalDate();
        LocalDate windowStart = windowEnd.minusDays(Math.max(30, properties.getScheduler().getWeeklyLookbackDays()));
        List<AiPrediction> championPredictions = predictions(
                champion.id, windowStart, windowEnd, List.of("RULE_BASELINE", "CHAMPION"));
        List<AiPrediction> challengerPredictions = predictions(
                challenger.id, windowStart, windowEnd, List.of("CHALLENGER_SHADOW"));
        Map<String, AiPrediction> championBySample = latestBySample(championPredictions);
        Map<String, AiPrediction> challengerBySample = latestBySample(challengerPredictions);
        List<String> pairKeys = championBySample.keySet().stream()
                .filter(challengerBySample::containsKey).sorted().toList();
        if (pairKeys.isEmpty()) {
            throw new InsufficientEvidenceException("同一样本的 Champion/Challenger 影子预测尚未形成");
        }
        List<Long> sampleIds = pairKeys.stream().map(championBySample::get)
                .map(item -> item.sampleId).filter(Objects::nonNull).distinct().toList();
        Map<Long, AiSampleLabel> labels = labels(sampleIds);
        List<AiShadowEvaluationService.PredictionPair> pairs = pairKeys.stream()
                .map(key -> {
                    AiPrediction source = championBySample.get(key);
                    return new AiShadowEvaluationService.PredictionPair(
                            source, challengerBySample.get(key), labels.get(source.sampleId));
                }).toList();
        AiModelVersion model = challenger.modelVersionId == null
                ? null : modelMapper.selectById(challenger.modelVersionId);
        Long datasetId = model == null ? null : model.trainingDatasetId;
        ResearchEvidence evidence = buildResearchEvidence(challenger, model, pairs, now);
        BigDecimal drift = predictionPsi(pairs);
        AiShadowEvaluationService.GovernanceContext governance = governance(evidence, pairs);
        shadowEvaluationService.evaluate(new AiShadowEvaluationService.EvaluationRequest(
                null, datasetId, champion.id, challenger.id,
                pairs.stream().map(pair -> pair.champion().tradeDate).min(LocalDate::compareTo).orElse(windowStart),
                pairs.stream().map(pair -> pair.champion().tradeDate).max(LocalDate::compareTo).orElse(windowEnd),
                "WEEKLY_SHADOW_V2_1:" + now.format(DateTimeFormatter.BASIC_ISO_DATE),
                Math.max(challengerPredictions.size(), pairs.size()), pairs, drift,
                new AiShadowEvaluationService.ShadowThresholds(
                        new BigDecimal("0.80"), new BigDecimal("0.50"), BigDecimal.ZERO,
                        new BigDecimal("-0.25"), new BigDecimal("0.10"),
                        new BigDecimal("0.25"), "PREDICTION_PSI_V1"),
                governance, now));
    }

    private ResearchEvidence buildResearchEvidence(
            AiStrategyRelease challenger,
            AiModelVersion model,
            List<AiShadowEvaluationService.PredictionPair> pairs,
            LocalDateTime now
    ) {
        if (model == null || model.trainingDatasetId == null) {
            return ResearchEvidence.empty();
        }
        List<AiShadowEvaluationService.PredictionPair> labelled = pairs.stream()
                .filter(pair -> pair.label() != null && "MATURED".equals(pair.label().labelStatus)
                        && pair.label().netReturn != null && pair.label().benchmarkReturn != null
                        && pair.label().inputFingerprint != null)
                .toList();
        if (labelled.isEmpty()) {
            return ResearchEvidence.empty();
        }
        AiWalkForwardService.WalkForwardResult walkForward = runWalkForwardIfReady(
                challenger, model, labelled, now);
        AiPortfolioBacktestService.BacktestResult backtest = runBacktestIfReady(
                challenger, model, walkForward, labelled, now);
        return new ResearchEvidence(walkForward, backtest);
    }

    private AiWalkForwardService.WalkForwardResult runWalkForwardIfReady(
            AiStrategyRelease challenger,
            AiModelVersion model,
            List<AiShadowEvaluationService.PredictionPair> pairs,
            LocalDateTime now
    ) {
        List<Long> sampleIds = pairs.stream().map(pair -> pair.champion().sampleId).distinct().toList();
        Map<Long, AiFactorValue> momentum = factorMapper.selectBySamples(
                        sampleIds, AiResearchContract.FACTOR_VERSION).stream()
                .filter(item -> "MOMENTUM_RETURN_5D".equals(item.factorCode))
                .filter(item -> value(item.missing) == 0 && item.normalizedValue != null)
                .sorted(Comparator.comparing((AiFactorValue item) -> item.calculatedAt).reversed())
                .collect(Collectors.toMap(
                        item -> item.sampleId, Function.identity(), (left, right) -> left));
        List<AiWalkForwardService.Observation> observations = pairs.stream()
                .filter(pair -> momentum.containsKey(pair.champion().sampleId))
                .filter(pair -> labelAvailableDate(pair.label()) != null)
                .map(pair -> new AiWalkForwardService.Observation(
                        pair.champion().sampleId,
                        pair.champion().tradeDate,
                        labelAvailableDate(pair.label()),
                        pair.champion().stockCode,
                        pair.label().netReturn,
                        zero(pair.challenger().score),
                        zero(momentum.get(pair.champion().sampleId).normalizedValue),
                        zero(pair.champion().score),
                        sha256(String.join("|", pair.champion().inputFingerprint,
                                pair.challenger().inputFingerprint, pair.label().inputFingerprint,
                                momentum.get(pair.champion().sampleId).inputFingerprint))))
                .filter(item -> !item.labelAvailableDate().isBefore(item.tradeDate()))
                .toList();
        List<LocalDate> dates = observations.stream().map(AiWalkForwardService.Observation::tradeDate)
                .distinct().sorted().toList();
        WalkPlan plan = walkPlan(dates.size());
        if (plan == null) {
            return null;
        }
        Map<LocalDate, AiPortfolioBacktestService.BenchmarkPoint> benchmarkByDate = benchmarkPoints(
                dates.get(0), dates.get(dates.size() - 1), now).stream()
                .collect(Collectors.toMap(
                        AiPortfolioBacktestService.BenchmarkPoint::tradeDate,
                        Function.identity(), (left, right) -> left));
        List<AiWalkForwardService.BenchmarkPoint> benchmark = dates.stream()
                .map(benchmarkByDate::get)
                .filter(Objects::nonNull)
                .map(point -> new AiWalkForwardService.BenchmarkPoint(
                        point.tradeDate(), point.dailyReturn(), point.sourceFingerprint()))
                .toList();
        if (benchmark.size() != dates.size()) {
            return null;
        }
        String suffix = now.format(DateTimeFormatter.BASIC_ISO_DATE);
        return walkForwardService.runAndStore(new AiWalkForwardService.WalkForwardRequest(
                model.trainingDatasetId, challenger.id, model.id,
                "WF:WEEKLY:" + challenger.id + ":" + suffix, "WALK_FORWARD_V2_1",
                "MAXIMIZE_T3_EXCESS_RETURN", HORIZON_DAYS, 5, 5,
                plan.foldCount(), RANDOM_SEED,
                new AiWalkForwardService.WalkForwardConfig(
                        plan.initialTrainDays(), plan.validationDays(), plan.testDays(),
                        plan.stepDays(), 5, 1000),
                observations, AiResearchContract.BENCHMARK_CODE, benchmark, now));
    }

    private AiPortfolioBacktestService.BacktestResult runBacktestIfReady(
            AiStrategyRelease challenger,
            AiModelVersion model,
            AiWalkForwardService.WalkForwardResult walkForward,
            List<AiShadowEvaluationService.PredictionPair> pairs,
            LocalDateTime now
    ) {
        List<AiPortfolioBacktestService.Signal> signals = pairs.stream()
                .map(AiShadowEvaluationService.PredictionPair::challenger)
                .filter(item -> "RECOMMEND".equals(item.actionBucket) && item.id != null
                        && item.score != null && item.inputFingerprint != null)
                .map(item -> new AiPortfolioBacktestService.Signal(
                        item.id, item.tradeDate, item.stockCode, item.score,
                        item.actionBucket, item.inputFingerprint))
                .toList();
        if (signals.isEmpty()) {
            return null;
        }
        LocalDate start = signals.stream().map(AiPortfolioBacktestService.Signal::signalDate)
                .min(LocalDate::compareTo).orElseThrow();
        LocalDate end = pairs.stream().map(AiShadowEvaluationService.PredictionPair::label)
                .filter(Objects::nonNull).map(label -> label.exitTradeDate)
                .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(start.plusDays(10));
        Set<String> codes = signals.stream().map(AiPortfolioBacktestService.Signal::stockCode).collect(Collectors.toSet());
        Map<String, Boolean> stByCode = stFlags(pairs);
        List<AiPortfolioBacktestService.MarketBar> bars = marketBars(
                codes, start, end, now, stByCode);
        if (bars.size() < signals.size()) {
            return null;
        }
        List<AiPortfolioBacktestService.BenchmarkPoint> benchmark = benchmarkPoints(start, end, now);
        if (benchmark.size() < 2) {
            return null;
        }
        String suffix = now.format(DateTimeFormatter.BASIC_ISO_DATE);
        return backtestService.runAndStore(new AiPortfolioBacktestService.BacktestRequest(
                model.trainingDatasetId,
                walkForward == null ? null : walkForward.run().id,
                challenger.id, model.id, "BT:WEEKLY:" + challenger.id + ":" + suffix,
                "PORTFOLIO_BACKTEST_V2_1", RANDOM_SEED,
                benchmark.get(0).tradeDate(), benchmark.get(benchmark.size() - 1).tradeDate(),
                HORIZON_DAYS, 5, "DAILY", new BigDecimal("1000000"),
                new AiPortfolioBacktestService.CostModel(
                        "CN_A_V1", new BigDecimal("0.0003"), new BigDecimal("0.0003"),
                        new BigDecimal("0.0005"), new BigDecimal("0.00001"),
                        new BigDecimal("5"), new BigDecimal("5")),
                signals, bars, AiResearchContract.BENCHMARK_CODE, benchmark, now));
    }

    private AiShadowEvaluationService.GovernanceContext governance(
            ResearchEvidence evidence,
            List<AiShadowEvaluationService.PredictionPair> pairs
    ) {
        if (evidence.walkForward() == null || evidence.backtest() == null) {
            return null;
        }
        BigDecimal lower = confidenceLower(evidence.walkForward().run().aggregateMetricsJson);
        BigDecimal maxContribution = maxSingleStockContribution(evidence.backtest());
        int tradingDays = (int) pairs.stream().map(pair -> pair.champion().tradeDate).distinct().count();
        return new AiShadowEvaluationService.GovernanceContext(
                evidence.walkForward().run().id, evidence.backtest().run().id,
                new AiStrategyGovernanceService.PromotionPolicy(
                        60, 1000, 200, 3, new BigDecimal("-0.25"),
                        new BigDecimal("0.15"), BigDecimal.ZERO),
                tradingDays, value(evidence.backtest().run().tradeCount),
                evidence.walkForward().folds().size(), maxContribution, lower,
                "PROMOTION_POLICY_V2_1");
    }

    private List<AiPrediction> predictions(
            Long releaseId,
            LocalDate start,
            LocalDate end,
            List<String> modes
    ) {
        return predictionMapper.selectList(new QueryWrapper<AiPrediction>()
                .eq("strategy_release_id", releaseId)
                .eq("horizon_trading_days", HORIZON_DAYS).in("inference_mode", modes)
                .between("trade_date", start, end)
                .orderByDesc("predicted_at").orderByDesc("id"));
    }

    private Map<Long, AiSampleLabel> labels(List<Long> sampleIds) {
        if (sampleIds.isEmpty()) {
            return Map.of();
        }
        return labelMapper.selectList(new QueryWrapper<AiSampleLabel>()
                        .eq("label_status", "MATURED")
                        .eq("label_version", AiResearchContract.LABEL_VERSION)
                        .eq("horizon_trading_days", HORIZON_DAYS)
                        .in("sample_id", sampleIds).orderByDesc("verified_at"))
                .stream().collect(Collectors.toMap(
                        item -> item.sampleId, Function.identity(), (left, right) -> left));
    }

    private static Map<String, AiPrediction> latestBySample(List<AiPrediction> values) {
        Map<String, AiPrediction> result = new LinkedHashMap<>();
        for (AiPrediction value : values) {
            result.putIfAbsent(value.sampleId + ":" + value.horizonDays, value);
        }
        return result;
    }

    private Map<String, Boolean> stFlags(
            List<AiShadowEvaluationService.PredictionPair> pairs
    ) {
        List<Long> sampleIds = pairs.stream().map(pair -> pair.champion().sampleId).distinct().toList();
        return sampleMapper.selectList(new QueryWrapper<AiSample>()
                        .in("id", sampleIds))
                .stream().collect(Collectors.toMap(
                        item -> item.stockCode,
                        item -> item.stockName != null && item.stockName.toUpperCase().contains("ST"),
                        Boolean::logicalOr));
    }

    private List<AiPortfolioBacktestService.MarketBar> marketBars(
            Set<String> codes,
            LocalDate start,
            LocalDate end,
            LocalDateTime asOfTime,
            Map<String, Boolean> stByCode
    ) {
        List<AiPortfolioBacktestService.MarketBar> result = new ArrayList<>();
        for (String code : codes.stream().sorted().toList()) {
            KlineSeriesSnapshot series;
            try {
                series = marketDataService.klineAt(code, "day", 240, asOfTime);
            } catch (RuntimeException exception) {
                return List.of();
            }
            if (!usableSeries(series)) {
                return List.of();
            }
            BigDecimal previousClose = null;
            for (KlinePointResponse point : series.points().stream()
                    .filter(Objects::nonNull)
                    .filter(item -> item.tradeDate() != null)
                    .sorted(Comparator.comparing(KlinePointResponse::tradeDate)).toList()) {
                if (point.tradeDate().isAfter(end)) {
                    break;
                }
                if (!validMarketPoint(point)) {
                    return List.of();
                }
                BigDecimal effectivePrevious = previousClose == null ? point.open() : previousClose;
                if (!point.tradeDate().isBefore(start)) {
                    result.add(new AiPortfolioBacktestService.MarketBar(
                            code, point.tradeDate(), point.open(), point.close(), point.high(), point.low(),
                            effectivePrevious, point.volume(), stByCode.getOrDefault(code, false),
                            sha256(String.join("|", series.sourceFingerprint(), code,
                                    String.valueOf(point.tradeDate()), String.valueOf(point.open()),
                                    String.valueOf(point.close()), String.valueOf(point.high()),
                                    String.valueOf(point.low()), String.valueOf(point.volume())))));
                }
                previousClose = point.close();
            }
        }
        return result;
    }

    private List<AiPortfolioBacktestService.BenchmarkPoint> benchmarkPoints(
            LocalDate start,
            LocalDate end,
            LocalDateTime asOfTime
    ) {
        KlineSeriesSnapshot series;
        try {
            series = marketDataService.klineAt(
                    AiResearchContract.BENCHMARK_SYMBOL, "day", 240, asOfTime);
        } catch (RuntimeException exception) {
            return List.of();
        }
        if (!usableSeries(series)) {
            return List.of();
        }
        List<AiPortfolioBacktestService.BenchmarkPoint> result = new ArrayList<>();
        BigDecimal previousClose = null;
        for (KlinePointResponse point : series.points().stream()
                .filter(Objects::nonNull)
                .filter(item -> item.tradeDate() != null)
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate)).toList()) {
            if (point.tradeDate().isAfter(end)) {
                break;
            }
            if (!validMarketPoint(point)) {
                return List.of();
            }
            BigDecimal effectivePrevious = previousClose == null ? point.open() : previousClose;
            if (!point.tradeDate().isBefore(start)
                    && effectivePrevious != null && effectivePrevious.signum() > 0
                    && point.close() != null) {
                BigDecimal dailyReturn = point.close().divide(
                        effectivePrevious, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
                result.add(new AiPortfolioBacktestService.BenchmarkPoint(
                        point.tradeDate(), dailyReturn,
                        sha256(String.join("|", series.sourceFingerprint(),
                                String.valueOf(point.tradeDate()), String.valueOf(point.close()),
                                String.valueOf(effectivePrevious)))));
            }
            previousClose = point.close();
        }
        return result;
    }

    private static boolean usableSeries(KlineSeriesSnapshot series) {
        return series != null && series.points() != null && !series.points().isEmpty()
                && series.fingerprintMatches()
                && "NONE".equalsIgnoreCase(series.adjustmentMode());
    }

    private static boolean validMarketPoint(KlinePointResponse point) {
        return point.open() != null && point.open().signum() > 0
                && point.close() != null && point.close().signum() > 0
                && point.high() != null && point.high().signum() > 0
                && point.low() != null && point.low().signum() > 0;
    }

    private BigDecimal confidenceLower(String metricsJson) {
        try {
            JsonNode metrics = objectMapper.readTree(metricsJson);
            return metrics.path("confidenceInterval").path("lower95").decimalValue();
        } catch (Exception exception) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal maxSingleStockContribution(
            AiPortfolioBacktestService.BacktestResult result
    ) {
        Map<String, BigDecimal> byStock = result.positions().stream()
                .filter(item -> item.returnContribution != null)
                .collect(Collectors.groupingBy(
                        item -> item.stockCode,
                        Collectors.reducing(BigDecimal.ZERO, item -> item.returnContribution, BigDecimal::add)));
        BigDecimal totalPositive = byStock.values().stream().filter(value -> value.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalPositive.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return byStock.values().stream().filter(value -> value.signum() > 0)
                .map(value -> value.divide(totalPositive, 6, RoundingMode.HALF_UP))
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private static BigDecimal predictionPsi(List<AiShadowEvaluationService.PredictionPair> pairs) {
        int buckets = 10;
        double[] champion = new double[buckets];
        double[] challenger = new double[buckets];
        for (AiShadowEvaluationService.PredictionPair pair : pairs) {
            champion[bucket(pair.champion().probabilityUp, buckets)]++;
            challenger[bucket(pair.challenger().probabilityUp, buckets)]++;
        }
        double psi = 0d;
        for (int index = 0; index < buckets; index++) {
            double left = (champion[index] + 0.5d) / (pairs.size() + buckets * 0.5d);
            double right = (challenger[index] + 0.5d) / (pairs.size() + buckets * 0.5d);
            psi += (right - left) * Math.log(right / left);
        }
        return BigDecimal.valueOf(psi).setScale(6, RoundingMode.HALF_UP);
    }

    private static int bucket(BigDecimal probability, int buckets) {
        double value = probability == null ? 0.5d : Math.max(0d, Math.min(1d, probability.doubleValue()));
        return Math.min(buckets - 1, (int) Math.floor(value * buckets));
    }

    private static WalkPlan walkPlan(int tradingDays) {
        if (tradingDays < 30) {
            return null;
        }
        int initial = Math.max(15, (int) Math.floor(tradingDays * 0.45d));
        int validation = Math.max(4, (int) Math.floor(tradingDays * 0.10d));
        int test = Math.max(4, (int) Math.floor(tradingDays * 0.10d));
        int step = Math.max(3, (int) Math.floor(tradingDays * 0.08d));
        int fixed = initial + HORIZON_DAYS + validation + 1 + test;
        int maxFolds = fixed > tradingDays ? 0 : 1 + (tradingDays - fixed) / step;
        return maxFolds <= 0 ? null : new WalkPlan(initial, validation, test, step, Math.min(3, maxFolds));
    }

    private static LocalDate labelAvailableDate(AiSampleLabel label) {
        LocalDateTime available = label.verifiedAt == null ? label.maturedAt : label.verifiedAt;
        return available == null ? label.exitTradeDate : available.toLocalDate();
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static AiEvolutionAutomationService.CycleResult cycleResult(
            int processed,
            int success,
            List<String> errors,
            List<String> notices
    ) {
        int failed = errors.size();
        String status = success == 0 && failed == 0 ? "SKIPPED"
                : failed == 0 ? "SUCCESS" : success == 0 ? "FAILED" : "PARTIAL_SUCCESS";
        List<String> messages = new ArrayList<>(notices);
        messages.addAll(errors);
        String message = messages.isEmpty() ? "没有可执行的周度评估" : String.join("；", messages);
        return new AiEvolutionAutomationService.CycleResult(status, processed, success, failed, message);
    }

    private static <T> List<List<T>> chunks(List<T> values, int chunkSize) {
        List<List<T>> result = new ArrayList<>();
        for (int start = 0; start < values.size(); start += chunkSize) {
            result.add(values.subList(start, Math.min(values.size(), start + chunkSize)));
        }
        return result;
    }

    private static void validate(LocalDateTime triggeredAt) {
        if (triggeredAt == null) {
            throw new IllegalArgumentException("周度研究缺少触发时间");
        }
    }

    private record ResearchEvidence(
            AiWalkForwardService.WalkForwardResult walkForward,
            AiPortfolioBacktestService.BacktestResult backtest
    ) {
        private static ResearchEvidence empty() {
            return new ResearchEvidence(null, null);
        }
    }

    private record WalkPlan(
            int initialTrainDays,
            int validationDays,
            int testDays,
            int stepDays,
            int foldCount
    ) {
    }

    private static final class InsufficientEvidenceException extends IllegalStateException {
        private InsufficientEvidenceException(String message) {
            super(message);
        }
    }
}
