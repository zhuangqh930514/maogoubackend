package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItemPrediction;
import com.maogou.stock.domain.entity.research.AiDailyDecisionSnapshot;
import com.maogou.stock.domain.entity.research.AiFactorPerformance;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.enums.TradeSide;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionItemMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionItemPredictionMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionSnapshotMapper;
import com.maogou.stock.mapper.research.AiFactorPerformanceMapper;
import com.maogou.stock.mapper.research.AiFactorValueMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPredictionEvaluationMapper;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.research.AiDailyDecisionPolicy;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiUserDailyProjectionService;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiUserDailyProjectionServiceImpl implements AiUserDailyProjectionService {

    private static final String FACTOR_VERSION = AiResearchContract.FACTOR_VERSION;
    private static final List<Integer> CORE_HORIZONS = List.of(1, 2, 3);
    private static final Map<Integer, BigDecimal> HORIZON_WEIGHTS = Map.of(
            1, new BigDecimal("0.200000"),
            2, new BigDecimal("0.300000"),
            3, new BigDecimal("0.500000"));
    private static final List<String> PROJECTION_STEPS = List.of(
            "GENERATE_STOCK_REPORTS", "BUILD_DAILY_DECISION", "ARCHIVE_RESEARCH_REPORT");

    private final AiDailyDecisionSnapshotMapper snapshotMapper;
    private final AiDailyDecisionItemMapper itemMapper;
    private final AiDailyDecisionItemPredictionMapper itemPredictionMapper;
    private final WatchStockMapper watchStockMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final AiPipelineRunMapper pipelineRunMapper;
    private final AiSampleMapper sampleMapper;
    private final AiPredictionMapper predictionMapper;
    private final AiPredictionEvaluationMapper evaluationMapper;
    private final AiFactorValueMapper factorValueMapper;
    private final AiFactorPerformanceMapper factorPerformanceMapper;
    private final AiDailyDecisionPolicy decisionPolicy;
    private final AiResearchDailyReportService dailyReportService;
    private final ObjectMapper objectMapper;

    public AiUserDailyProjectionServiceImpl(
            AiDailyDecisionSnapshotMapper snapshotMapper,
            AiDailyDecisionItemMapper itemMapper,
            AiDailyDecisionItemPredictionMapper itemPredictionMapper,
            WatchStockMapper watchStockMapper,
            TradeRecordMapper tradeRecordMapper,
            AiPipelineRunMapper pipelineRunMapper,
            AiSampleMapper sampleMapper,
            AiPredictionMapper predictionMapper,
            AiPredictionEvaluationMapper evaluationMapper,
            AiFactorValueMapper factorValueMapper,
            AiFactorPerformanceMapper factorPerformanceMapper,
            AiDailyDecisionPolicy decisionPolicy,
            AiResearchDailyReportService dailyReportService,
            ObjectMapper objectMapper
    ) {
        this.snapshotMapper = snapshotMapper;
        this.itemMapper = itemMapper;
        this.itemPredictionMapper = itemPredictionMapper;
        this.watchStockMapper = watchStockMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.pipelineRunMapper = pipelineRunMapper;
        this.sampleMapper = sampleMapper;
        this.predictionMapper = predictionMapper;
        this.evaluationMapper = evaluationMapper;
        this.factorValueMapper = factorValueMapper;
        this.factorPerformanceMapper = factorPerformanceMapper;
        this.decisionPolicy = decisionPolicy;
        this.dailyReportService = dailyReportService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ProjectionResult project(ProjectionRequest request) {
        validate(request);
        AiPipelineRun globalRun = requireGlobalRun(request);
        AiDailyDecisionSnapshot existing = snapshotMapper.selectByIdempotencyForShare(
                request.userId(), request.idempotencyKey());
        if (existing != null) {
            assertOwned(existing, request.userId());
            archiveReport(request, globalRun, existing);
            return stored(existing);
        }

        if (snapshotMapper.lockUser(request.userId()) == null) {
            throw new IllegalArgumentException("用户不存在，无法生成每日决策");
        }
        existing = snapshotMapper.selectByIdempotencyForShare(request.userId(), request.idempotencyKey());
        if (existing != null) {
            assertOwned(existing, request.userId());
            archiveReport(request, globalRun, existing);
            return stored(existing);
        }

        UserUniverse universe = loadUserUniverse(request.userId());
        List<AiSample> samples = universe.stockCodes().isEmpty() || globalRun.dataBatchId == null
                ? List.of()
                : safeList(sampleMapper.selectLatestForDecision(
                        globalRun.dataBatchId, request.tradeDate(), universe.stockCodes()));
        Map<String, AiSample> samplesByStock = samples.stream()
                .collect(Collectors.toMap(sample -> sample.stockCode, Function.identity(),
                        (left, right) -> left.asOfTime.isAfter(right.asOfTime) ? left : right,
                        LinkedHashMap::new));
        List<Long> sampleIds = samples.stream().map(sample -> sample.id).filter(Objects::nonNull).toList();
        List<AiPrediction> predictions = sampleIds.isEmpty()
                ? List.of()
                : safeList(predictionMapper.selectForDailyDecision(sampleIds, globalRun.strategyReleaseId));
        Map<Long, Map<Integer, AiPrediction>> predictionsBySample = predictions.stream()
                .filter(prediction -> prediction.sampleId != null && prediction.horizonDays != null)
                .collect(Collectors.groupingBy(
                        prediction -> prediction.sampleId,
                        LinkedHashMap::new,
                        Collectors.toMap(prediction -> prediction.horizonDays, Function.identity(),
                                AiUserDailyProjectionServiceImpl::latestPrediction,
                                LinkedHashMap::new)));

        List<AiPredictionEvaluation> evaluations = safeList(evaluationMapper.selectForDecisionEvidence(
                globalRun.strategyReleaseId, request.tradeDate()));
        EvaluationEvidence evidence = evaluationEvidence(evaluations);
        List<AiFactorValue> factorValues = sampleIds.isEmpty()
                ? List.of() : safeList(factorValueMapper.selectBySamples(sampleIds, FACTOR_VERSION));
        Map<Long, List<AiFactorValue>> factorsBySample = factorValues.stream()
                .collect(Collectors.groupingBy(value -> value.sampleId, LinkedHashMap::new, Collectors.toList()));
        List<AiFactorPerformance> factorPerformance = sampleIds.isEmpty()
                ? List.of() : safeList(factorPerformanceMapper.selectForSamplesBefore(sampleIds, request.tradeDate()));
        BigDecimal factorReliability = factorReliability(factorPerformance);

        AiDailyDecisionSnapshot current = snapshotMapper.selectCurrentForUpdate(
                request.userId(), request.tradeDate());
        int nextVersion = value(snapshotMapper.selectMaxVersionForUpdate(
                request.userId(), request.tradeDate())) + 1;
        AiDailyDecisionSnapshot snapshot = newSnapshot(
                request, globalRun, current, nextVersion, samples, evidence);
        if (current != null) {
            assertOwned(current, request.userId());
            snapshotMapper.retireCurrent(current.id, request.userId(), request.generatedAt());
        }
        snapshotMapper.insert(snapshot);
        if (snapshot.id == null) {
            throw new IllegalStateException("每日决策快照写入后缺少主键");
        }

        List<AiDailyDecisionItem> items = new ArrayList<>();
        List<AiDailyDecisionItemPrediction> links = new ArrayList<>();
        for (String stockCode : universe.stockCodes()) {
            AiSample sample = samplesByStock.get(stockCode);
            Map<Integer, AiPrediction> corePredictions = sample == null
                    ? Map.of() : predictionsBySample.getOrDefault(sample.id, Map.of());
            boolean holding = universe.holdingCodes().contains(stockCode);
            AiDailyDecisionItem item = buildItem(
                    request, snapshot, stockCode, universe.stockNames().get(stockCode), sample,
                    corePredictions, evidence, factorReliability,
                    factorsBySample.getOrDefault(sample == null ? null : sample.id, List.of()), holding);
            itemMapper.insert(item);
            if (item.id == null) {
                throw new IllegalStateException("每日决策明细写入后缺少主键：" + stockCode);
            }
            items.add(item);
            for (Integer horizon : CORE_HORIZONS) {
                AiPrediction prediction = corePredictions.get(horizon);
                if (prediction == null) {
                    continue;
                }
                AiDailyDecisionItemPrediction link = new AiDailyDecisionItemPrediction();
                link.userId = request.userId();
                link.decisionItemId = item.id;
                link.predictionId = prediction.id;
                link.purpose = horizon == 3 ? "PRIMARY_RANKING" : "T" + horizon + "_SIGNAL";
                link.weight = HORIZON_WEIGHTS.get(horizon);
                link.createdAt = request.generatedAt();
                itemPredictionMapper.insert(link);
                links.add(link);
            }
        }
        applySnapshotMetrics(snapshot, items, samples, evidence);
        snapshotMapper.updateById(snapshot);
        archiveReport(request, globalRun, snapshot);
        return new ProjectionResult(snapshot, items, links, PROJECTION_STEPS);
    }

    private void archiveReport(
            ProjectionRequest request,
            AiPipelineRun globalRun,
            AiDailyDecisionSnapshot snapshot
    ) {
        dailyReportService.generate(new AiResearchDailyReportService.GenerationRequest(
                request.userId(),
                request.tradeDate(),
                snapshot.id,
                request.userPipelineRunId(),
                globalRun.strategyReleaseId,
                globalRun.modelVersionId,
                "USER_PROJECTION_REPORT:" + fingerprint(request.idempotencyKey()),
                globalRun.status,
                null,
                "用户每日决策投影已完成",
                request.generatedAt()));
    }

    @Override
    public AiDailyDecisionSnapshot current(Long userId, LocalDate tradeDate) {
        if (userId == null || userId <= 0 || tradeDate == null) {
            return null;
        }
        return snapshotMapper.selectCurrent(userId, tradeDate);
    }

    private AiDailyDecisionItem buildItem(
            ProjectionRequest request,
            AiDailyDecisionSnapshot snapshot,
            String stockCode,
            String fallbackName,
            AiSample sample,
            Map<Integer, AiPrediction> predictions,
            EvaluationEvidence evidence,
            BigDecimal factorReliability,
            List<AiFactorValue> factors,
            boolean holding
    ) {
        String unavailable = availabilityReason(sample, predictions);
        AiPrediction primary = predictions.get(3);
        BigDecimal risk = primary == null ? null : primary.riskScore;
        boolean hardStop = primary != null && ("SELL".equals(primary.action)
                || containsIgnoreCase(primary.reasonJson, "HARD_STOP"));
        AiDailyDecisionPolicy.Decision decision = decisionPolicy.decide(new AiDailyDecisionPolicy.Input(
                signal(predictions.get(1)), signal(predictions.get(2)), signal(primary),
                factorReliability, evidence.strategyValidation(),
                sample == null || sample.dataQualityScore == null
                        ? null : sample.dataQualityScore.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP),
                risk,
                evidence.outOfSampleCount(),
                hardStop,
                primary == null ? null : primary.action,
                holding,
                unavailable,
                BigDecimal.ZERO));

        AiDailyDecisionItem item = new AiDailyDecisionItem();
        item.userId = request.userId();
        item.decisionSnapshotId = snapshot.id;
        item.tradeDate = request.tradeDate();
        item.sampleId = sample == null ? null : sample.id;
        item.reportId = null;
        item.stockCode = stockCode;
        item.stockName = sample != null && sample.stockName != null ? sample.stockName : fallbackName;
        item.category = decision.category();
        item.systemScore = decision.systemScore();
        item.horizonSignalScore = decision.horizonSignalScore();
        item.factorReliabilityScore = decision.factorReliabilityScore();
        item.strategyValidationScore = decision.strategyValidationScore();
        item.dataQualityComponent = decision.dataQualityComponent();
        item.riskComponent = decision.riskComponent();
        item.finalAction = decision.finalAction();
        item.riskScore = decision.riskScore();
        item.riskLevel = decision.riskLevel();
        item.decisionSource = "DETERMINISTIC_POLICY";
        item.freshnessStatus = unavailable == null ? "CURRENT_CLOSE" : "UNAVAILABLE";
        item.decisionPolicyVersion = decisionPolicy.version();
        item.confidenceLevel = decision.confidenceLevel();
        item.outOfSampleCount = evidence.outOfSampleCount();
        item.historicalHitRate = evidence.hitRateByStock().get(stockCode);
        item.triggerFactorsJson = triggerFactorsJson(factors);
        item.reasonSummary = reasonSummary(decision, evidence.outOfSampleCount());
        item.unavailableReason = decision.unavailableReason();
        item.inputFingerprint = fingerprint(
                decisionPolicy.version(), request.userId(), request.tradeDate(), stockCode,
                sample == null ? "NO_SAMPLE" : sample.sourceFingerprint,
                CORE_HORIZONS.stream().map(predictions::get)
                        .filter(Objects::nonNull).map(value -> value.inputFingerprint)
                        .sorted().collect(Collectors.joining(",")));
        item.createdAt = request.generatedAt();
        return item;
    }

    private AiDailyDecisionSnapshot newSnapshot(
            ProjectionRequest request,
            AiPipelineRun run,
            AiDailyDecisionSnapshot current,
            int version,
            List<AiSample> samples,
            EvaluationEvidence evidence
    ) {
        AiDailyDecisionSnapshot snapshot = new AiDailyDecisionSnapshot();
        snapshot.userId = request.userId();
        snapshot.tradeDate = request.tradeDate();
        snapshot.snapshotVersion = version;
        snapshot.pipelineRunId = request.userPipelineRunId();
        snapshot.globalPipelineRunId = run.id;
        snapshot.strategyReleaseId = run.strategyReleaseId;
        snapshot.modelVersionId = run.modelVersionId;
        snapshot.supersedesSnapshotId = current == null ? null : current.id;
        snapshot.idempotencyKey = request.idempotencyKey();
        snapshot.isCurrent = 1;
        snapshot.snapshotStatus = "BUILDING";
        snapshot.marketRegime = dominantMarketRegime(samples);
        snapshot.recommendationCount = 0;
        snapshot.cautiousCount = 0;
        snapshot.avoidCount = 0;
        snapshot.holdingRiskCount = 0;
        snapshot.unavailableCount = 0;
        snapshot.overallHitRate = evidence.overallHitRate();
        snapshot.freshnessStatus = samples.isEmpty() ? "UNAVAILABLE" : "CURRENT_CLOSE";
        snapshot.dataQualityScore = averageQuality(samples);
        snapshot.decisionPolicyVersion = decisionPolicy.version();
        snapshot.summaryJson = "{}";
        snapshot.generatedAt = request.generatedAt();
        snapshot.createdAt = request.generatedAt();
        snapshot.updatedAt = request.generatedAt();
        return snapshot;
    }

    private void applySnapshotMetrics(
            AiDailyDecisionSnapshot snapshot,
            List<AiDailyDecisionItem> items,
            List<AiSample> samples,
            EvaluationEvidence evidence
    ) {
        snapshot.recommendationCount = count(items, "RECOMMEND");
        snapshot.cautiousCount = count(items, "CAUTIOUS");
        snapshot.avoidCount = count(items, "AVOID");
        snapshot.holdingRiskCount = count(items, "HOLDING_RISK");
        snapshot.unavailableCount = count(items, "DATA_UNAVAILABLE");
        int usable = items.size() - snapshot.unavailableCount;
        snapshot.snapshotStatus = items.isEmpty() ? "EMPTY"
                : usable == 0 ? "DATA_UNAVAILABLE"
                : snapshot.unavailableCount > 0 ? "PARTIAL" : "READY";
        snapshot.freshnessStatus = usable == 0 ? "UNAVAILABLE" : "CURRENT_CLOSE";
        snapshot.dataQualityScore = averageQuality(samples);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("itemCount", items.size());
        summary.put("usableCount", usable);
        summary.put("outOfSampleCount", evidence.outOfSampleCount());
        summary.put("coreHorizons", CORE_HORIZONS);
        summary.put("primaryHorizon", 3);
        summary.put("predictionWeights", HORIZON_WEIGHTS);
        summary.put("llmConfidenceWeight", 0);
        snapshot.summaryJson = json(summary);
        snapshot.updatedAt = snapshot.generatedAt;
    }

    private UserUniverse loadUserUniverse(Long userId) {
        List<WatchStock> watches = safeList(watchStockMapper.selectList(new QueryWrapper<WatchStock>()
                .eq("user_id", userId).eq("deleted", 0).orderByAsc("priority").orderByAsc("stock_code")));
        List<TradeRecord> trades = safeList(tradeRecordMapper.selectList(new QueryWrapper<TradeRecord>()
                .eq("user_id", userId).eq("deleted", 0).orderByAsc("traded_at").orderByAsc("id")));
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (WatchStock watch : watches) {
            if (watch.stockCode != null && !watch.stockCode.isBlank()) {
                names.put(watch.stockCode, watch.stockName == null ? watch.stockCode : watch.stockName);
            }
        }
        Map<String, Integer> netPositions = new LinkedHashMap<>();
        for (TradeRecord trade : trades) {
            if (trade.stockCode == null || trade.quantity == null) {
                continue;
            }
            int signed = trade.side == TradeSide.SELL ? -trade.quantity : trade.quantity;
            netPositions.merge(trade.stockCode, signed, Integer::sum);
            if (signed > 0) {
                names.putIfAbsent(trade.stockCode,
                        trade.stockName == null ? trade.stockCode : trade.stockName);
            }
        }
        Set<String> holdingCodes = netPositions.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        holdingCodes.forEach(code -> names.putIfAbsent(code, code));
        return new UserUniverse(List.copyOf(names.keySet()), Map.copyOf(names), Set.copyOf(holdingCodes));
    }

    private AiPipelineRun requireGlobalRun(ProjectionRequest request) {
        AiPipelineRun run = pipelineRunMapper.selectById(request.globalPipelineRunId());
        if (run == null || !"GLOBAL".equals(run.scopeType)
                || !request.tradeDate().equals(run.tradeDate)
                || !Set.of("SUCCESS", "PARTIAL_SUCCESS").contains(run.status)) {
            throw new IllegalStateException("每日用户投影只能读取同交易日成功或部分成功的全局研究流水线");
        }
        if (run.strategyReleaseId == null || run.strategyReleaseId <= 0) {
            throw new IllegalStateException("全局研究流水线未绑定正式策略版本");
        }
        return run;
    }

    private ProjectionResult stored(AiDailyDecisionSnapshot snapshot) {
        List<AiDailyDecisionItem> items = safeList(itemMapper.selectBySnapshot(snapshot.userId, snapshot.id));
        List<Long> itemIds = items.stream().map(item -> item.id).filter(Objects::nonNull).toList();
        List<AiDailyDecisionItemPrediction> links = itemIds.isEmpty()
                ? List.of() : safeList(itemPredictionMapper.selectByItems(snapshot.userId, itemIds));
        return new ProjectionResult(snapshot, items, links, PROJECTION_STEPS);
    }

    private static EvaluationEvidence evaluationEvidence(List<AiPredictionEvaluation> evaluations) {
        int total = evaluations.size();
        long correct = evaluations.stream().filter(value -> value.directionCorrect != null)
                .filter(value -> value.directionCorrect == 1).count();
        long assessed = evaluations.stream().filter(value -> value.directionCorrect != null).count();
        BigDecimal overall = assessed == 0 ? null : percentage(correct, assessed);
        Map<String, BigDecimal> byStock = evaluations.stream()
                .filter(value -> value.stockCode != null && value.directionCorrect != null)
                .collect(Collectors.groupingBy(value -> value.stockCode, LinkedHashMap::new,
                        Collectors.collectingAndThen(Collectors.toList(), values -> percentage(
                                values.stream().filter(value -> value.directionCorrect == 1).count(), values.size()))));
        BigDecimal strategy = overall == null ? new BigDecimal("0.50")
                : overall.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
        return new EvaluationEvidence(total, overall, byStock, strategy);
    }

    private static BigDecimal factorReliability(List<AiFactorPerformance> performance) {
        return performance.stream().map(item -> item.wilsonLowerBound).filter(Objects::nonNull)
                .map(AiUserDailyProjectionServiceImpl::normalizeRate)
                .reduce(BigDecimal::add)
                .map(sum -> sum.divide(BigDecimal.valueOf(performance.stream()
                        .map(item -> item.wilsonLowerBound).filter(Objects::nonNull).count()),
                        8, RoundingMode.HALF_UP))
                .orElse(new BigDecimal("0.50"));
    }

    private static BigDecimal signal(AiPrediction prediction) {
        if (prediction == null || prediction.probabilityUp == null || prediction.expectedExcessReturn == null) {
            return null;
        }
        BigDecimal probability = prediction.probabilityUp.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        BigDecimal returnSignal = new BigDecimal("0.5")
                .add(prediction.expectedExcessReturn.divide(new BigDecimal("0.10"), 8, RoundingMode.HALF_UP))
                .max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return probability.multiply(new BigDecimal("0.75"))
                .add(returnSignal.multiply(new BigDecimal("0.25")))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private static String availabilityReason(AiSample sample, Map<Integer, AiPrediction> predictions) {
        if (sample == null) {
            return "MISSING_CURRENT_SAMPLE";
        }
        if (!"READY".equals(sample.qualityStatus) || !"TRADABLE".equals(sample.tradableStatus)) {
            return "SAMPLE_" + normalize(sample.qualityStatus) + "_" + normalize(sample.tradableStatus);
        }
        for (Integer horizon : CORE_HORIZONS) {
            AiPrediction prediction = predictions.get(horizon);
            if (prediction == null) {
                return "MISSING_T" + horizon + "_PREDICTION";
            }
            if (prediction.id == null || prediction.inputFingerprint == null
                    || "UNAVAILABLE".equals(prediction.action)) {
                return "INVALID_T" + horizon + "_PREDICTION";
            }
        }
        return null;
    }

    private String triggerFactorsJson(List<AiFactorValue> factors) {
        List<Map<String, Object>> values = factors.stream()
                .filter(value -> value.missing == null || value.missing == 0)
                .filter(value -> value.hit != null && value.hit == 1)
                .sorted(Comparator.comparing(
                        (AiFactorValue value) -> value.normalizedValue == null
                                ? BigDecimal.ZERO : value.normalizedValue.abs(), Comparator.reverseOrder()))
                .limit(6)
                .map(value -> {
                    Map<String, Object> factor = new LinkedHashMap<>();
                    factor.put("factorCode", value.factorCode);
                    factor.put("factorName", value.factorName == null ? value.factorCode : value.factorName);
                    factor.put("direction", value.direction);
                    factor.put("contribution", value.normalizedValue);
                    factor.put("evidence", value.evidenceJson);
                    return factor;
                }).toList();
        return json(values);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("每日决策证据序列化失败", exception);
        }
    }

    private static String reasonSummary(AiDailyDecisionPolicy.Decision decision, int oosCount) {
        if ("DATA_UNAVAILABLE".equals(decision.category())) {
            return "核心研究数据不可用：" + decision.unavailableReason();
        }
        if ("LOW_SAMPLE".equals(decision.confidenceLevel())) {
            return "当前仅有 " + oosCount + " 条样本外评价，结论最高限制为谨慎观察";
        }
        return "结论由 " + DecisionPolicyV1.VERSION + " 基于三周期预测和样本外证据确定";
    }

    private static String dominantMarketRegime(List<AiSample> samples) {
        return samples.stream().map(sample -> sample.marketRegime)
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).orElse("UNCLASSIFIED");
    }

    private static BigDecimal averageQuality(List<AiSample> samples) {
        List<BigDecimal> values = samples.stream().map(sample -> sample.dataQualityScore)
                .filter(Objects::nonNull).toList();
        if (values.isEmpty()) {
            return BigDecimal.ZERO.setScale(4);
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private static int count(List<AiDailyDecisionItem> items, String category) {
        return (int) items.stream().filter(item -> category.equals(item.category)).count();
    }

    private static AiPrediction latestPrediction(AiPrediction left, AiPrediction right) {
        if (left.predictedAt == null) {
            return right;
        }
        if (right.predictedAt == null) {
            return left;
        }
        return right.predictedAt.isAfter(left.predictedAt) ? right : left;
    }

    private static BigDecimal normalizeRate(BigDecimal value) {
        BigDecimal normalized = value.compareTo(BigDecimal.ONE) > 0
                ? value.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP) : value;
        return normalized.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private static BigDecimal percentage(long numerator, long denominator) {
        if (denominator == 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        return BigDecimal.valueOf(numerator).multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNAVAILABLE" : value.trim().toUpperCase();
    }

    private static boolean containsIgnoreCase(String source, String candidate) {
        return source != null && source.toUpperCase().contains(candidate);
    }

    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '|');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static void assertOwned(AiDailyDecisionSnapshot snapshot, Long userId) {
        if (!Objects.equals(snapshot.userId, userId)) {
            throw new IllegalStateException("每日决策快照违反用户隔离约束");
        }
    }

    private static void validate(ProjectionRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0
                || request.tradeDate() == null || request.globalPipelineRunId() == null
                || request.globalPipelineRunId() <= 0 || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank() || request.generatedAt() == null) {
            throw new IllegalArgumentException("每日用户投影请求不完整");
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record UserUniverse(
            List<String> stockCodes,
            Map<String, String> stockNames,
            Set<String> holdingCodes
    ) {
    }

    private record EvaluationEvidence(
            int outOfSampleCount,
            BigDecimal overallHitRate,
            Map<String, BigDecimal> hitRateByStock,
            BigDecimal strategyValidation
    ) {
    }
}
