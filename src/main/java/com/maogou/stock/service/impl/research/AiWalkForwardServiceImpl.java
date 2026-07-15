package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiWalkForwardBaseline;
import com.maogou.stock.domain.entity.research.AiWalkForwardFold;
import com.maogou.stock.domain.entity.research.AiWalkForwardRun;
import com.maogou.stock.mapper.research.AiWalkForwardBaselineMapper;
import com.maogou.stock.mapper.research.AiWalkForwardFoldMapper;
import com.maogou.stock.mapper.research.AiWalkForwardRunMapper;
import com.maogou.stock.service.research.AiWalkForwardService;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

@Service
public class AiWalkForwardServiceImpl implements AiWalkForwardService {

    private final AiWalkForwardRunMapper runMapper;
    private final AiWalkForwardFoldMapper foldMapper;
    private final AiWalkForwardBaselineMapper baselineMapper;
    private final ObjectMapper objectMapper;

    public AiWalkForwardServiceImpl(
            AiWalkForwardRunMapper runMapper,
            AiWalkForwardFoldMapper foldMapper,
            AiWalkForwardBaselineMapper baselineMapper,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.foldMapper = foldMapper;
        this.baselineMapper = baselineMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public WalkForwardResult runAndStore(WalkForwardRequest request) {
        validate(request);
        List<LocalDate> dates = request.observations().stream().map(Observation::tradeDate)
                .distinct().sorted().toList();
        List<SplitPlan> plans = createPlans(request, dates);
        AiWalkForwardRun expectedRun = buildRun(request, plans);
        runMapper.insertImmutable(expectedRun);
        AiWalkForwardRun run = runMapper.selectByRunKeyForShare(request.runKey());
        if (run == null) {
            throw new IllegalStateException("Walk-forward 运行写入后未读取到记录");
        }
        if (!Objects.equals(expectedRun.inputFingerprint, run.inputFingerprint)) {
            throw new IllegalStateException("不可变 Walk-forward 运行冲突：" + request.runKey());
        }

        List<AiWalkForwardFold> foldCandidates = plans.stream()
                .map(plan -> buildFold(run.id, request, plan))
                .toList();
        foldMapper.insertBatchImmutable(foldCandidates);
        List<AiWalkForwardFold> folds = foldMapper.selectByRunIdForShare(run.id);
        validatePersistedFolds(foldCandidates, folds);
        List<FoldExecution> executions = plans.stream()
                .map(plan -> new FoldExecution(
                        plan.foldNo(), sampleIds(plan.train()), sampleIds(plan.validation()),
                        sampleIds(plan.test())))
                .toList();
        Map<Integer, AiWalkForwardFold> foldsByNo = new LinkedHashMap<>();
        folds.forEach(fold -> foldsByNo.put(fold.foldNo, fold));
        List<AiWalkForwardBaseline> baselineCandidates = new ArrayList<>();
        for (SplitPlan plan : plans) {
            AiWalkForwardFold fold = foldsByNo.get(plan.foldNo());
            if (fold == null) {
                throw new IllegalStateException("Walk-forward 折叠缺少基线父记录：" + plan.foldNo());
            }
            baselineCandidates.addAll(buildBaselines(request, fold, plan));
        }
        if (!baselineCandidates.isEmpty()) {
            baselineMapper.insertBatchImmutable(baselineCandidates);
        }
        List<AiWalkForwardBaseline> baselines = baselineMapper.selectByRunIdForShare(run.id);
        validatePersistedBaselines(baselineCandidates, baselines);
        return new WalkForwardResult(run, List.copyOf(folds), List.copyOf(baselines), executions);
    }

    private List<SplitPlan> createPlans(WalkForwardRequest request, List<LocalDate> dates) {
        List<SplitPlan> plans = new ArrayList<>();
        WalkForwardConfig config = request.config();
        int isolationDays = request.purgeDays() + request.embargoDays();
        for (int foldIndex = 0; foldIndex < request.foldCount(); foldIndex++) {
            int trainEndIndex = config.initialTrainDays() - 1 + foldIndex * config.stepDays();
            int validationStartIndex = trainEndIndex + isolationDays + 1;
            int validationEndIndex = validationStartIndex + config.validationDays() - 1;
            int testStartIndex = validationEndIndex + isolationDays + 1;
            int testEndIndex = testStartIndex + config.testDays() - 1;
            if (testEndIndex >= dates.size()) {
                throw new IllegalArgumentException("交易日数量不足以生成请求的 Walk-forward 窗口");
            }
            LocalDate trainStart = dates.get(0);
            LocalDate trainEnd = dates.get(trainEndIndex);
            LocalDate validationStart = dates.get(validationStartIndex);
            LocalDate validationEnd = dates.get(validationEndIndex);
            LocalDate testStart = dates.get(testStartIndex);
            LocalDate testEnd = dates.get(testEndIndex);

            List<Observation> train = request.observations().stream()
                    .filter(item -> between(item.tradeDate(), trainStart, trainEnd))
                    .filter(item -> item.labelAvailableDate().isBefore(validationStart))
                    .toList();
            List<Observation> validation = request.observations().stream()
                    .filter(item -> between(item.tradeDate(), validationStart, validationEnd))
                    .filter(item -> item.labelAvailableDate().isBefore(testStart))
                    .toList();
            List<Observation> test = request.observations().stream()
                    .filter(item -> between(item.tradeDate(), testStart, testEnd))
                    .filter(item -> !item.labelAvailableDate().isAfter(request.evaluatedAt().toLocalDate()))
                    .toList();
            plans.add(new SplitPlan(foldIndex + 1, trainStart, trainEnd, validationStart,
                    validationEnd, testStart, testEnd, train, validation, test));
        }
        return plans;
    }

    private AiWalkForwardRun buildRun(WalkForwardRequest request, List<SplitPlan> plans) {
        AiWalkForwardRun run = new AiWalkForwardRun();
        run.trainingDatasetId = request.trainingDatasetId();
        run.strategyReleaseId = request.strategyReleaseId();
        run.modelVersionId = request.modelVersionId();
        run.runKey = request.runKey();
        run.engineVersion = request.engineVersion();
        run.purgeTradingDays = request.purgeDays();
        run.embargoTradingDays = request.embargoDays();
        run.randomSeed = request.randomSeed();
        run.inputFingerprint = inputFingerprint(request);
        Map<String, Object> persistedConfig = new LinkedHashMap<>();
        persistedConfig.put("objective", request.objective());
        persistedConfig.put("horizonTradingDays", request.horizonDays());
        persistedConfig.put("foldCount", plans.size());
        persistedConfig.put("benchmarkCode", request.benchmarkCode());
        persistedConfig.put("window", request.config());
        run.configJson = json(persistedConfig);
        List<BigDecimal> foldReturns = plans.stream()
                .map(plan -> compound(strategyDailyReturns(plan.test(), request.config().topK())))
                .toList();
        run.aggregateMetricsJson = json(Map.of(
                "foldCount", plans.size(),
                "meanTestReturn", average(foldReturns),
                "confidenceInterval", confidenceInterval(
                        foldReturns, request.config().bootstrapIterations(), request.randomSeed())));
        run.status = "COMPLETED";
        run.startedAt = request.evaluatedAt();
        run.completedAt = request.evaluatedAt();
        run.createdAt = LocalDateTime.now();
        return run;
    }

    private AiWalkForwardFold buildFold(Long runId, WalkForwardRequest request, SplitPlan plan) {
        AiWalkForwardFold fold = new AiWalkForwardFold();
        fold.walkForwardRunId = runId;
        fold.foldNo = plan.foldNo();
        fold.trainStartDate = plan.trainStart();
        fold.trainEndDate = plan.trainEnd();
        fold.validationStartDate = plan.validationStart();
        fold.validationEndDate = plan.validationEnd();
        fold.testStartDate = plan.testStart();
        fold.testEndDate = plan.testEnd();
        fold.trainSampleCount = plan.train().size();
        fold.validationSampleCount = plan.validation().size();
        fold.testSampleCount = plan.test().size();
        List<BigDecimal> candidateReturns = strategyDailyReturns(plan.test(), request.config().topK());
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("candidateTotalReturn", compound(candidateReturns));
        metrics.put("candidateMeanDailyReturn", average(candidateReturns));
        metrics.put("candidateHitRate", hitRate(candidateReturns));
        metrics.put("testTradingDayCount", candidateReturns.size());
        metrics.put("confidenceInterval", confidenceInterval(
                candidateReturns, request.config().bootstrapIterations(),
                request.randomSeed() + plan.foldNo()));
        fold.metricsJson = json(metrics);
        fold.status = "COMPLETED";
        fold.createdAt = LocalDateTime.now();
        return fold;
    }

    private List<AiWalkForwardBaseline> buildBaselines(
            WalkForwardRequest request,
            AiWalkForwardFold fold,
            SplitPlan plan
    ) {
        Map<LocalDate, BigDecimal> benchmarkByDate = new LinkedHashMap<>();
        request.benchmark().stream().sorted(Comparator.comparing(BenchmarkPoint::tradeDate))
                .forEach(point -> benchmarkByDate.put(point.tradeDate(), point.dailyReturn()));
        List<LocalDate> testDates = plan.test().stream().map(Observation::tradeDate)
                .distinct().sorted().toList();
        List<BigDecimal> indexReturns = testDates.stream().map(date -> {
            BigDecimal value = benchmarkByDate.get(date);
            if (value == null) {
                throw new IllegalArgumentException("指数基线缺少测试交易日：" + date);
            }
            return value;
        }).toList();
        List<BigDecimal> equalWeight = equalWeightDailyReturns(plan.test());
        List<BigDecimal> momentum = rankedDailyReturns(
                plan.test(), request.config().topK(), Observation::momentumScore);
        List<BigDecimal> champion = rankedDailyReturns(
                plan.test(), request.config().topK(), Observation::championScore);

        return List.of(
                baseline(fold.id, null, "F" + fold.foldNo + ":INDEX:" + request.benchmarkCode(),
                        "INDEX", request.benchmarkCode(), indexReturns),
                baseline(fold.id, null, "F" + fold.foldNo + ":EQUAL_WEIGHT_UNIVERSE",
                        "EQUAL_WEIGHT_UNIVERSE", null, equalWeight),
                baseline(fold.id, null, "F" + fold.foldNo + ":MOMENTUM",
                        "MOMENTUM", null, momentum),
                baseline(fold.id, request.strategyReleaseId(),
                        "F" + fold.foldNo + ":CHAMPION:" + request.strategyReleaseId(),
                        "CHAMPION", null, champion));
    }

    private AiWalkForwardBaseline baseline(
            Long foldId,
            Long strategyReleaseId,
            String key,
            String type,
            String benchmarkCode,
            List<BigDecimal> returns
    ) {
        AiWalkForwardBaseline baseline = new AiWalkForwardBaseline();
        baseline.walkForwardFoldId = foldId;
        baseline.strategyReleaseId = strategyReleaseId;
        baseline.baselineKey = key;
        baseline.baselineType = type;
        baseline.benchmarkCode = benchmarkCode;
        baseline.metricsJson = json(Map.of(
                "totalReturn", compound(returns),
                "meanDailyReturn", average(returns),
                "hitRate", hitRate(returns),
                "tradingDayCount", returns.size()));
        baseline.navJson = json(Map.of("nav", nav(returns)));
        baseline.createdAt = LocalDateTime.now();
        return baseline;
    }

    private static List<BigDecimal> strategyDailyReturns(List<Observation> observations, int topK) {
        return rankedDailyReturns(observations, topK, Observation::strategyScore);
    }

    private static List<BigDecimal> equalWeightDailyReturns(List<Observation> observations) {
        Map<LocalDate, List<Observation>> byDate = observationsByDate(observations);
        return byDate.values().stream()
                .map(day -> average(day.stream().map(Observation::realizedNetReturn).toList()))
                .toList();
    }

    private static List<BigDecimal> rankedDailyReturns(
            List<Observation> observations,
            int topK,
            java.util.function.Function<Observation, BigDecimal> score
    ) {
        Map<LocalDate, List<Observation>> byDate = observationsByDate(observations);
        Comparator<Observation> ranking = Comparator.comparing(score, Comparator.reverseOrder())
                .thenComparing(Observation::stockCode)
                .thenComparing(Observation::sampleId);
        return byDate.values().stream().map(day -> {
            List<BigDecimal> selected = day.stream().sorted(ranking).limit(topK)
                    .map(Observation::realizedNetReturn).toList();
            return selected.isEmpty() ? BigDecimal.ZERO : average(selected);
        }).toList();
    }

    private static Map<LocalDate, List<Observation>> observationsByDate(List<Observation> observations) {
        Map<LocalDate, List<Observation>> byDate = new LinkedHashMap<>();
        observations.stream().sorted(Comparator.comparing(Observation::tradeDate)
                        .thenComparing(Observation::stockCode))
                .forEach(observation -> byDate.computeIfAbsent(
                        observation.tradeDate(), ignored -> new ArrayList<>()).add(observation));
        return byDate;
    }

    private static BigDecimal compound(List<BigDecimal> returns) {
        BigDecimal nav = BigDecimal.ONE;
        for (BigDecimal value : returns) {
            nav = nav.multiply(BigDecimal.ONE.add(value));
        }
        return nav.subtract(BigDecimal.ONE).setScale(8, RoundingMode.HALF_UP);
    }

    private static List<BigDecimal> nav(List<BigDecimal> returns) {
        List<BigDecimal> values = new ArrayList<>();
        BigDecimal current = BigDecimal.ONE;
        for (BigDecimal value : returns) {
            current = current.multiply(BigDecimal.ONE.add(value));
            values.add(current.setScale(8, RoundingMode.HALF_UP));
        }
        return values;
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP);
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal hitRate(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        long positive = values.stream().filter(value -> value.signum() > 0).count();
        return BigDecimal.valueOf(positive * 100L)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private static Map<String, BigDecimal> confidenceInterval(
            List<BigDecimal> values,
            int iterations,
            long seed
    ) {
        if (values.isEmpty()) {
            return Map.of("lower95", BigDecimal.ZERO, "upper95", BigDecimal.ZERO);
        }
        Random random = new Random(seed);
        List<BigDecimal> estimates = new ArrayList<>(iterations);
        for (int iteration = 0; iteration < iterations; iteration++) {
            List<BigDecimal> sampled = new ArrayList<>(values.size());
            for (int index = 0; index < values.size(); index++) {
                sampled.add(values.get(random.nextInt(values.size())));
            }
            estimates.add(compound(sampled));
        }
        estimates.sort(BigDecimal::compareTo);
        int lowerIndex = Math.max(0, (int) Math.floor(iterations * 0.025d));
        int upperIndex = Math.min(iterations - 1, (int) Math.ceil(iterations * 0.975d) - 1);
        return Map.of("lower95", estimates.get(lowerIndex), "upper95", estimates.get(upperIndex));
    }

    private static void validatePersistedBaselines(
            List<AiWalkForwardBaseline> expected,
            List<AiWalkForwardBaseline> actual
    ) {
        if (actual == null || actual.size() != expected.size()) {
            throw new IllegalStateException("Walk-forward 基线写入数量不一致");
        }
        Map<String, AiWalkForwardBaseline> byKey = new LinkedHashMap<>();
        actual.forEach(item -> byKey.put(item.walkForwardFoldId + "|" + item.baselineKey, item));
        for (AiWalkForwardBaseline item : expected) {
            AiWalkForwardBaseline persisted = byKey.get(item.walkForwardFoldId + "|" + item.baselineKey);
            if (persisted == null || !Objects.equals(item.metricsJson, persisted.metricsJson)
                    || !Objects.equals(item.navJson, persisted.navJson)) {
                throw new IllegalStateException("不可变 Walk-forward 基线冲突：" + item.baselineKey);
            }
        }
    }

    private static void validatePersistedFolds(
            List<AiWalkForwardFold> expected,
            List<AiWalkForwardFold> actual
    ) {
        if (actual == null || actual.size() != expected.size()) {
            throw new IllegalStateException("Walk-forward 折叠写入数量不一致");
        }
        Map<Integer, AiWalkForwardFold> byNo = new LinkedHashMap<>();
        actual.forEach(fold -> byNo.put(fold.foldNo, fold));
        for (AiWalkForwardFold fold : expected) {
            AiWalkForwardFold persisted = byNo.get(fold.foldNo);
            if (persisted == null
                    || !Objects.equals(fold.trainStartDate, persisted.trainStartDate)
                    || !Objects.equals(fold.trainEndDate, persisted.trainEndDate)
                    || !Objects.equals(fold.validationStartDate, persisted.validationStartDate)
                    || !Objects.equals(fold.validationEndDate, persisted.validationEndDate)
                    || !Objects.equals(fold.testStartDate, persisted.testStartDate)
                    || !Objects.equals(fold.testEndDate, persisted.testEndDate)
                    || !Objects.equals(fold.trainSampleCount, persisted.trainSampleCount)
                    || !Objects.equals(fold.validationSampleCount, persisted.validationSampleCount)
                    || !Objects.equals(fold.testSampleCount, persisted.testSampleCount)) {
                throw new IllegalStateException("不可变 Walk-forward 折叠冲突：" + fold.foldNo);
            }
        }
    }

    private static void validate(WalkForwardRequest request) {
        if (request == null || request.trainingDatasetId() == null || request.trainingDatasetId() <= 0
                || request.strategyReleaseId() == null || request.strategyReleaseId() <= 0
                || request.runKey() == null || request.runKey().isBlank()
                || request.engineVersion() == null || request.engineVersion().isBlank()
                || request.objective() == null || request.objective().isBlank()
                || request.horizonDays() == null || request.horizonDays() <= 0
                || request.purgeDays() == null || request.purgeDays() < 5
                || request.embargoDays() == null || request.embargoDays() < 5
                || request.foldCount() == null || request.foldCount() <= 0
                || request.randomSeed() == null || request.config() == null
                || request.observations() == null || request.observations().isEmpty()
                || request.benchmarkCode() == null || request.benchmarkCode().isBlank()
                || request.benchmark() == null || request.benchmark().isEmpty()
                || request.evaluatedAt() == null) {
            throw new IllegalArgumentException("Walk-forward 请求缺少有效数据集、窗口或版本");
        }
        WalkForwardConfig config = request.config();
        if (config.initialTrainDays() == null || config.initialTrainDays() <= 0
                || config.validationDays() == null || config.validationDays() <= 0
                || config.testDays() == null || config.testDays() <= 0
                || config.stepDays() == null || config.stepDays() <= 0
                || config.topK() == null || config.topK() <= 0
                || config.bootstrapIterations() == null || config.bootstrapIterations() <= 0) {
            throw new IllegalArgumentException("Walk-forward 配置必须为正数");
        }
        Set<Long> sampleIds = new HashSet<>();
        for (Observation observation : request.observations()) {
            if (observation == null || observation.sampleId() == null
                    || observation.tradeDate() == null || observation.labelAvailableDate() == null
                    || observation.stockCode() == null || observation.stockCode().isBlank()
                    || observation.realizedNetReturn() == null || observation.strategyScore() == null
                    || observation.momentumScore() == null || observation.championScore() == null
                    || observation.lineageFingerprint() == null
                    || !sampleIds.add(observation.sampleId())
                    || observation.labelAvailableDate().isBefore(observation.tradeDate())) {
                throw new IllegalArgumentException("Walk-forward 观察值缺少血缘或包含重复样本");
            }
        }
        Set<LocalDate> benchmarkDates = new HashSet<>();
        for (BenchmarkPoint point : request.benchmark()) {
            if (point == null || point.tradeDate() == null || point.dailyReturn() == null
                    || point.sourceFingerprint() == null || point.sourceFingerprint().isBlank()
                    || !benchmarkDates.add(point.tradeDate())) {
                throw new IllegalArgumentException("Walk-forward 指数基线缺少来源或包含重复交易日");
            }
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化 Walk-forward 证据", ex);
        }
    }

    private static String inputFingerprint(WalkForwardRequest request) {
        String observations = request.observations().stream()
                .sorted(Comparator.comparing(Observation::sampleId))
                .map(item -> item.sampleId() + ":" + item.lineageFingerprint())
                .reduce((left, right) -> left + "|" + right).orElse("");
        String benchmark = request.benchmark().stream()
                .sorted(Comparator.comparing(BenchmarkPoint::tradeDate))
                .map(item -> item.tradeDate() + ":" + item.sourceFingerprint())
                .reduce((left, right) -> left + "|" + right).orElse("");
        return sha256(String.join("|", request.engineVersion(), request.objective(),
                String.valueOf(request.horizonDays()), String.valueOf(request.purgeDays()),
                String.valueOf(request.embargoDays()), String.valueOf(request.foldCount()),
                String.valueOf(request.randomSeed()), String.valueOf(request.config()),
                request.benchmarkCode(), observations, benchmark));
    }

    private static List<Long> sampleIds(List<Observation> observations) {
        return observations.stream().map(Observation::sampleId).toList();
    }

    private static boolean between(LocalDate value, LocalDate start, LocalDate end) {
        return !value.isBefore(start) && !value.isAfter(end);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private record SplitPlan(
            Integer foldNo,
            LocalDate trainStart,
            LocalDate trainEnd,
            LocalDate validationStart,
            LocalDate validationEnd,
            LocalDate testStart,
            LocalDate testEnd,
            List<Observation> train,
            List<Observation> validation,
            List<Observation> test
    ) {
    }
}
