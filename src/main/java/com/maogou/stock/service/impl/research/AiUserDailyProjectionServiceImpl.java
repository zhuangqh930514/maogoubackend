package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItemPrediction;
import com.maogou.stock.domain.entity.research.AiDailyDecisionSnapshot;
import com.maogou.stock.domain.entity.research.AiFactorPerformance;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.enums.TradeSide;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
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
import com.maogou.stock.service.AiDecisionPolicyShadowService;
import com.maogou.stock.service.research.AiDailyDecisionPlanService;
import com.maogou.stock.service.research.AiDailyDecisionPolicy;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiFactorSignalPolicy;
import org.springframework.beans.factory.annotation.Autowired;
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
            "GENERATE_STOCK_REPORTS", "BUILD_DAILY_DECISION", "BUILD_DECISION_PLANS", "ARCHIVE_RESEARCH_REPORT");

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
    private final AiAnalysisReportMapper analysisReportMapper;
    private final AiDailyDecisionPolicy decisionPolicy;
    private final AiResearchDailyReportService dailyReportService;
    private final AiDailyDecisionPlanService dailyDecisionPlanService;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private AiDecisionPolicyShadowService decisionShadowService;

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
            AiAnalysisReportMapper analysisReportMapper,
            AiDailyDecisionPolicy decisionPolicy,
            AiResearchDailyReportService dailyReportService,
            AiDailyDecisionPlanService dailyDecisionPlanService,
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
        this.analysisReportMapper = analysisReportMapper;
        this.decisionPolicy = decisionPolicy;
        this.dailyReportService = dailyReportService;
        this.dailyDecisionPlanService = dailyDecisionPlanService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ProjectionResult project(ProjectionRequest request) {
        validate(request);
        AiPipelineRun globalRun = requireGlobalRun(request);
        AiDailyDecisionSnapshot existing = snapshotMapper.selectByIdempotencyForShare(
                request.userId(), request.idempotencyKey());
        if (existing != null && !request.rebuildCurrentSnapshot()) {
            assertOwned(existing, request.userId());
            ProjectionResult result = stored(existing);
            dailyDecisionPlanService.initializeDeterministicPlans(request.userId(), request.tradeDate(), result.items());
            archiveReport(request, globalRun, existing);
            return result;
        }

        if (snapshotMapper.lockUser(request.userId()) == null) {
            throw new IllegalArgumentException("用户不存在，无法生成每日决策");
        }
        existing = snapshotMapper.selectByIdempotencyForShare(request.userId(), request.idempotencyKey());
        if (existing != null && !request.rebuildCurrentSnapshot()) {
            assertOwned(existing, request.userId());
            ProjectionResult result = stored(existing);
            dailyDecisionPlanService.initializeDeterministicPlans(request.userId(), request.tradeDate(), result.items());
            archiveReport(request, globalRun, existing);
            return result;
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

        EvaluationEvidence evidence = loadEvaluationEvidence(globalRun.strategyReleaseId, request.tradeDate());
        List<AiFactorValue> factorValues = sampleIds.isEmpty()
                ? List.of() : safeList(factorValueMapper.selectBySamples(sampleIds, FACTOR_VERSION));
        Map<Long, List<AiFactorValue>> factorsBySample = factorValues.stream()
                .collect(Collectors.groupingBy(value -> value.sampleId, LinkedHashMap::new, Collectors.toList()));
        List<AiFactorPerformance> factorPerformance = sampleIds.isEmpty()
                ? List.of() : safeList(factorPerformanceMapper.selectForSamplesBefore(sampleIds, request.tradeDate()));
        Map<FactorPerformanceKey, AiFactorPerformance> factorPerformanceByDefinition = factorPerformance.stream()
                .filter(performance -> performance != null && performance.factorDefinitionId != null
                        && CORE_HORIZONS.contains(performance.horizonDays))
                .collect(Collectors.toMap(performance -> new FactorPerformanceKey(
                                performance.factorDefinitionId, performance.horizonDays), Function.identity(),
                        AiUserDailyProjectionServiceImpl::latestPerformance, LinkedHashMap::new));
        Map<String, AiAnalysisReport> reportsByStock = loadCurrentReports(
                request.userId(), request.tradeDate(), universe.stockCodes());

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
            AiAnalysisReport report = reportsByStock.get(stockCode);
            AiDailyDecisionItem item = buildItem(
                    request, snapshot, stockCode, universe.stockNames().get(stockCode), sample,
                    corePredictions, evidence,
                    factorsBySample.getOrDefault(sample == null ? null : sample.id, List.of()), holding, report,
                    globalRun.strategyReleaseId, factorPerformanceByDefinition);
            itemMapper.insert(item);
            if (item.id == null) {
                throw new IllegalStateException("每日决策明细写入后缺少主键：" + stockCode);
            }
            items.add(item);
            if (decisionShadowService != null) {
                decisionShadowService.record(request.userId(), request.tradeDate(), sample, corePredictions,
                        shadowInput(stockCode, sample, corePredictions, evidence, factorsBySample.getOrDefault(
                                sample == null ? null : sample.id, List.of()), holding, primaryAction(corePredictions),
                                factorPerformanceByDefinition), item);
            }
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
        AiDailyDecisionPlanService.PlanBuildResult planResult = dailyDecisionPlanService
                .initializeDeterministicPlans(request.userId(), request.tradeDate(), items);
        if (planResult.failedCount() > 0) {
            throw new IllegalStateException("日报条件计划初始化失败：" + String.join("；", planResult.errors()));
        }
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
                "USER_PROJECTION_REPORT:" + fingerprint(request.idempotencyKey(), snapshot.snapshotVersion),
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
            List<AiFactorValue> factors,
            boolean holding,
            AiAnalysisReport report,
            Long strategyReleaseId,
            Map<FactorPerformanceKey, AiFactorPerformance> factorPerformanceByDefinition
    ) {
        String unavailable = availabilityReason(sample, predictions);
        AiPrediction primary = predictions.get(3);
        boolean reportAligned = alignedReport(report, sample, strategyReleaseId, request.tradeDate());
        BigDecimal reportRisk = reportAligned ? report.riskScore : null;
        BigDecimal risk = max(primary == null ? null : primary.riskScore, reportRisk);
        StockEvaluationEvidence stockEvidence = evidence.forStock(stockCode, 3);
        BigDecimal t1FactorReliability = factorReliability(factors, factorPerformanceByDefinition, 1);
        BigDecimal t2FactorReliability = factorReliability(factors, factorPerformanceByDefinition, 2);
        BigDecimal t3FactorReliability = factorReliability(factors, factorPerformanceByDefinition, 3);
        BigDecimal factorReliability = weighted(t1FactorReliability, t2FactorReliability, t3FactorReliability);
        boolean hardStop = primary != null && ("SELL".equals(primary.action)
                || containsIgnoreCase(primary.reasonJson, "HARD_STOP"));
        AiDailyDecisionPolicy.Decision decision = decisionPolicy.decide(new AiDailyDecisionPolicy.Input(
                calibratedSignal(signal(predictions.get(1)), evidence.forStock(stockCode, 1), t1FactorReliability),
                calibratedSignal(signal(predictions.get(2)), evidence.forStock(stockCode, 2), t2FactorReliability),
                calibratedSignal(signal(primary), evidence.forStock(stockCode, 3), t3FactorReliability),
                factorReliability, evidence.strategyValidation(),
                sample == null || sample.dataQualityScore == null
                        ? null : sample.dataQualityScore.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP),
                risk,
                evidence.minimumOutOfSampleCount(stockCode),
                hardStop,
                primary == null ? null : primary.action,
                holding,
                unavailable,
                reportAligned ? report.calibratedConfidence : BigDecimal.ZERO,
                reportAligned ? report.finalAction : null));

        AiDailyDecisionItem item = new AiDailyDecisionItem();
        item.userId = request.userId();
        item.decisionSnapshotId = snapshot.id;
        item.tradeDate = request.tradeDate();
        item.sampleId = sample == null ? null : sample.id;
        item.reportId = reportAligned ? report.id : null;
        item.stockCode = stockCode;
        item.stockName = resolveStockName(
                stockCode,
                sample == null ? null : sample.stockName,
                fallbackName);
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
        item.decisionSource = reportAligned ? "RECONCILED_AI_REPORT" : "DETERMINISTIC_POLICY";
        item.freshnessStatus = unavailable == null ? "CURRENT_CLOSE" : "UNAVAILABLE";
        item.decisionPolicyVersion = decisionPolicy.version();
        item.confidenceLevel = decision.confidenceLevel();
        item.outOfSampleCount = stockEvidence.outOfSampleCount();
        item.historicalHitRate = stockEvidence.hitRate();
        item.evidenceScope = stockEvidence.scope();
        item.triggerFactorsJson = triggerFactorsJson(factors);
        item.reasonSummary = reasonSummary(decision, stockEvidence, report, reportAligned);
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

    private static com.maogou.stock.service.impl.research.DecisionPolicyShadow.Input shadowInput(
            String stockCode, AiSample sample, Map<Integer, AiPrediction> predictions,
            EvaluationEvidence evidence, List<AiFactorValue> factors, boolean holding,
            String predictionAction, Map<FactorPerformanceKey, AiFactorPerformance> performance
    ) {
        return new com.maogou.stock.service.impl.research.DecisionPolicyShadow.Input(
                shadowEvidence(predictions.get(1), evidence.forStock(stockCode, 1)),
                shadowEvidence(predictions.get(2), evidence.forStock(stockCode, 2)),
                shadowEvidence(predictions.get(3), evidence.forStock(stockCode, 3)),
                factorSupportSignal(factors, performance, 3),
                evidence.strategyValidation(),
                sample == null ? null : sample.dataQualityScore,
                predictions.get(3) == null ? null : predictions.get(3).riskScore,
                predictions.get(3) != null && containsIgnoreCase(predictions.get(3).reasonJson, "HARD_STOP"),
                predictionAction, holding, availabilityReason(sample, predictions));
    }

    private static com.maogou.stock.service.research.HorizonDecisionEvidence shadowEvidence(
            AiPrediction prediction, StockEvaluationEvidence evidence
    ) {
        return new com.maogou.stock.service.research.HorizonDecisionEvidence(
                prediction == null || prediction.horizonDays == null ? 3 : prediction.horizonDays,
                signal(prediction), evidence == null ? 0 : evidence.outOfSampleCount(),
                evidence == null ? null : evidence.hitRate(),
                wilsonLower(evidence), evidence == null ? "NONE" : evidence.scope());
    }

    private static BigDecimal wilsonLower(StockEvaluationEvidence evidence) {
        if (evidence == null || evidence.outOfSampleCount() <= 0 || evidence.hitRate() == null) return null;
        double n = evidence.outOfSampleCount();
        double p = normalizeRate(evidence.hitRate()).doubleValue();
        double z = 1.96;
        double denominator = 1 + z * z / n;
        double centre = p + z * z / (2 * n);
        double margin = z * Math.sqrt((p * (1 - p) + z * z / (4 * n)) / n);
        return BigDecimal.valueOf((centre - margin) / denominator).setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal factorSupportSignal(List<AiFactorValue> values,
                                                  Map<FactorPerformanceKey, AiFactorPerformance> performance,
                                                  int horizonDays) {
        BigDecimal numerator = BigDecimal.ZERO;
        BigDecimal denominator = BigDecimal.ZERO;
        for (AiFactorValue value : values) {
            if (value == null || value.missing != null && value.missing == 1 || value.normalizedValue == null) continue;
            AiFactorPerformance factor = value.factorDefinitionId == null ? null
                    : performance.get(new FactorPerformanceKey(value.factorDefinitionId, horizonDays));
            BigDecimal oriented = AiFactorSignalPolicy.orient(value, value.normalizedValue);
            if (oriented == null || factor == null || factor.sampleCount == null
                    || factor.sampleCount < 30 || factor.wilsonLowerBound == null) {
                continue;
            }
            BigDecimal sampleStrength = clampRatio(BigDecimal.valueOf(factor.sampleCount - 30)
                    .divide(BigDecimal.valueOf(200 - 30), 8, RoundingMode.HALF_UP));
            BigDecimal qualityStrength = clampRatio(normalizeRate(factor.wilsonLowerBound)
                    .subtract(new BigDecimal("0.50"))
                    .divide(new BigDecimal("0.15"), 8, RoundingMode.HALF_UP));
            BigDecimal evidenceStrength = sampleStrength.multiply(qualityStrength);
            if (evidenceStrength.signum() <= 0) {
                continue;
            }
            BigDecimal orientedZ = oriented.max(new BigDecimal("-3"))
                    .min(new BigDecimal("3"))
                    .divide(new BigDecimal("3"), 8, RoundingMode.HALF_UP);
            BigDecimal defaultWeight = value.defaultWeight == null
                    ? BigDecimal.ONE : value.defaultWeight.abs();
            BigDecimal weight = defaultWeight.multiply(evidenceStrength);
            numerator = numerator.add(orientedZ.multiply(weight));
            denominator = denominator.add(weight.abs());
        }
        if (denominator.signum() == 0) return new BigDecimal("0.50");
        return new BigDecimal("0.50").add(numerator.divide(denominator, 8, RoundingMode.HALF_UP)
                .max(BigDecimal.valueOf(-1)).min(BigDecimal.ONE).multiply(new BigDecimal("0.50")));
    }

    private static BigDecimal clampRatio(BigDecimal value) {
        return value.max(BigDecimal.ZERO).min(BigDecimal.ONE);
    }

    private static String primaryAction(Map<Integer, AiPrediction> predictions) {
        AiPrediction primary = predictions.get(3);
        return primary == null ? "WATCH" : primary.action;
    }

    private Map<String, AiAnalysisReport> loadCurrentReports(
            Long userId,
            LocalDate tradeDate,
            List<String> stockCodes
    ) {
        if (stockCodes == null || stockCodes.isEmpty()) {
            return Map.of();
        }
        return safeList(analysisReportMapper.selectLatestSuccessfulForDailyDecision(userId, tradeDate, stockCodes))
                .stream()
                .filter(report -> report != null && report.stockCode != null)
                .collect(Collectors.toMap(report -> report.stockCode, Function.identity(),
                        (left, right) -> right, LinkedHashMap::new));
    }

    private static boolean alignedReport(
            AiAnalysisReport report,
            AiSample sample,
            Long strategyReleaseId,
            LocalDate tradeDate
    ) {
        return report != null && report.id != null && sample != null && sample.id != null
                && Objects.equals(report.sampleId, sample.id)
                && Objects.equals(report.strategyReleaseId, strategyReleaseId)
                && Objects.equals(report.reportDate, tradeDate)
                && report.status == com.maogou.stock.domain.enums.AnalysisStatus.SUCCESS;
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
        // A report retry rebuilds the decision after fresh AI reports are available. Keep the
        // original snapshot immutable and give the successor a distinct database idempotency key.
        snapshot.idempotencyKey = snapshotIdempotencyKey(request, version);
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

    private static String snapshotIdempotencyKey(ProjectionRequest request, int version) {
        if (!request.rebuildCurrentSnapshot()) {
            return request.idempotencyKey();
        }
        return request.idempotencyKey() + ":REBUILD:" + version;
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

    private static String resolveStockName(String stockCode, String sampleName, String fallbackName) {
        if (isUsableStockName(sampleName, stockCode)) {
            return sampleName.trim();
        }
        if (isUsableStockName(fallbackName, stockCode)) {
            return fallbackName.trim();
        }
        return stockCode;
    }

    private static boolean isUsableStockName(String name, String stockCode) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = name.trim();
        return !normalized.equalsIgnoreCase(stockCode)
                && !"未知股票".equals(normalized)
                && !normalized.matches("(?i)\\d{6}(?:\\.(?:SH|SZ|BJ))?");
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

    private EvaluationEvidence loadEvaluationEvidence(Long strategyReleaseId, LocalDate tradeDate) {
        Map<Integer, HorizonEvaluationEvidence> byHorizon = new LinkedHashMap<>();
        Map<Integer, AiPredictionEvaluationMapper.HorizonStrategyEvaluationSummary> summaries = safeList(
                evaluationMapper.selectDecisionEvidenceSummaryByHorizon(strategyReleaseId, tradeDate)).stream()
                .filter(value -> value != null && CORE_HORIZONS.contains(value.horizonDays))
                .collect(Collectors.toMap(value -> value.horizonDays, Function.identity(), (left, right) -> right));
        Map<Integer, List<AiPredictionEvaluationMapper.HorizonStockEvaluationSummary>> stockSummaries = safeList(
                evaluationMapper.selectDecisionEvidenceByStockAndHorizon(strategyReleaseId, tradeDate)).stream()
                .filter(value -> value != null && CORE_HORIZONS.contains(value.horizonDays))
                .collect(Collectors.groupingBy(value -> value.horizonDays));
        // Compatibility only for reports created while the horizon summaries are being backfilled.
        // New production rows always take the independent-horizon path below.
        if (summaries.isEmpty() && stockSummaries.isEmpty()) {
            AiPredictionEvaluationMapper.StrategyEvaluationSummary legacy =
                    evaluationMapper.selectDecisionEvidenceSummary(strategyReleaseId, tradeDate);
            List<AiPredictionEvaluationMapper.StockEvaluationSummary> legacyStocks = safeList(
                    evaluationMapper.selectDecisionEvidenceByStock(strategyReleaseId, tradeDate));
            long assessed = legacy == null || legacy.assessedCount == null ? 0L : legacy.assessedCount;
            long correct = legacy == null || legacy.correctCount == null ? 0L : legacy.correctCount;
            BigDecimal overall = assessed == 0 ? null : percentage(correct, assessed);
            Map<String, StockEvaluationEvidence> fallbackStocks = legacyStocks.stream()
                    .filter(value -> value != null && value.stockCode != null
                            && value.totalCount != null && value.totalCount > 0)
                    .collect(Collectors.toMap(value -> value.stockCode, value -> new StockEvaluationEvidence(
                            Math.toIntExact(value.totalCount), percentage(
                            value.correctCount == null ? 0L : value.correctCount, value.totalCount), "TRANSITION_STRATEGY_FALLBACK"),
                            (left, right) -> right, LinkedHashMap::new));
            for (Integer horizon : CORE_HORIZONS) {
                byHorizon.put(horizon, new HorizonEvaluationEvidence(Math.toIntExact(assessed), overall, fallbackStocks));
            }
            return new EvaluationEvidence(byHorizon);
        }
        for (Integer horizon : CORE_HORIZONS) {
            AiPredictionEvaluationMapper.HorizonStrategyEvaluationSummary summary = summaries.get(horizon);
            long assessed = summary == null || summary.assessedCount == null ? 0L : summary.assessedCount;
            long correct = summary == null || summary.correctCount == null ? 0L : summary.correctCount;
            BigDecimal overall = assessed == 0 ? null : percentage(correct, assessed);
            Map<String, StockEvaluationEvidence> byStock = safeList(stockSummaries.get(horizon)).stream()
                    .filter(value -> value.stockCode != null && value.totalCount != null && value.totalCount > 0)
                    .collect(Collectors.toMap(value -> value.stockCode, value -> new StockEvaluationEvidence(
                            Math.toIntExact(value.totalCount), percentage(
                            value.correctCount == null ? 0L : value.correctCount, value.totalCount), "STOCK_T" + horizon),
                            (left, right) -> right, LinkedHashMap::new));
            byHorizon.put(horizon, new HorizonEvaluationEvidence(Math.toIntExact(assessed), overall, byStock));
        }
        return new EvaluationEvidence(byHorizon);
    }

    private static BigDecimal factorReliability(
            List<AiFactorValue> values,
            Map<FactorPerformanceKey, AiFactorPerformance> performanceByDefinition,
            int horizonDays
    ) {
        return values.stream()
                .filter(value -> value != null && value.factorDefinitionId != null)
                .filter(value -> value.hit != null && value.hit == 1)
                .filter(value -> value.missing == null || value.missing == 0)
                .map(value -> performanceByDefinition.get(new FactorPerformanceKey(value.factorDefinitionId, horizonDays)))
                .filter(Objects::nonNull)
                .map(item -> item.wilsonLowerBound).filter(Objects::nonNull)
                .map(AiUserDailyProjectionServiceImpl::normalizeRate)
                .reduce(BigDecimal::add)
                .map(sum -> sum.divide(BigDecimal.valueOf(values.stream()
                        .filter(value -> value != null && value.factorDefinitionId != null)
                        .filter(value -> value.hit != null && value.hit == 1)
                        .filter(value -> value.missing == null || value.missing == 0)
                        .map(value -> performanceByDefinition.get(new FactorPerformanceKey(value.factorDefinitionId, horizonDays)))
                        .filter(Objects::nonNull)
                        .map(item -> item.wilsonLowerBound).filter(Objects::nonNull).count()),
                        8, RoundingMode.HALF_UP))
                .orElse(new BigDecimal("0.50"));
    }

    private static AiFactorPerformance latestPerformance(
            AiFactorPerformance left,
            AiFactorPerformance right
    ) {
        if (left.evaluatedAt == null) {
            return right;
        }
        if (right.evaluatedAt == null) {
            return left;
        }
        return right.evaluatedAt.isAfter(left.evaluatedAt) ? right : left;
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

    /** Applies only evidence known before today's close; missing evidence stays neutral. */
    private static BigDecimal calibratedSignal(
            BigDecimal predictionSignal,
            StockEvaluationEvidence evidence,
            BigDecimal factorReliability
    ) {
        if (predictionSignal == null) {
            return null;
        }
        BigDecimal historical = evidence == null || evidence.hitRate() == null
                ? new BigDecimal("0.50") : normalizeRate(evidence.hitRate());
        BigDecimal factor = factorReliability == null ? new BigDecimal("0.50") : factorReliability;
        return predictionSignal.multiply(historical).multiply(factor).setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal weighted(BigDecimal t1, BigDecimal t2, BigDecimal t3) {
        return t1.multiply(HORIZON_WEIGHTS.get(1))
                .add(t2.multiply(HORIZON_WEIGHTS.get(2)))
                .add(t3.multiply(HORIZON_WEIGHTS.get(3)));
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

    private static String reasonSummary(
            AiDailyDecisionPolicy.Decision decision,
            StockEvaluationEvidence evidence,
            AiAnalysisReport report,
            boolean reportAligned
    ) {
        if ("DATA_UNAVAILABLE".equals(decision.category())) {
            return "核心研究数据不可用：" + decision.unavailableReason();
        }
        if ("LOW_SAMPLE".equals(decision.confidenceLevel())) {
            return evidenceDescription(evidence) + "当前仅有 " + evidence.outOfSampleCount()
                    + " 条已评价样本，结论最高限制为谨慎观察";
        }
        if (report == null) {
            return evidenceDescription(evidence) + "当日未生成结构化 AI 报告，结论由 " + DecisionPolicyV1.VERSION
                    + " 基于三周期预测、因子与样本外证据确定";
        }
        if (!reportAligned) {
            return evidenceDescription(evidence) + "当日 AI 报告未与当前正式样本或策略版本对齐，已按 " + DecisionPolicyV1.VERSION
                    + " 降级为规则决策";
        }
        if (Objects.equals(report.finalAction, decision.finalAction())) {
            return evidenceDescription(evidence) + "AI 报告动作“" + report.finalAction + "”与 " + DecisionPolicyV1.VERSION + " 证据一致";
        }
        return evidenceDescription(evidence) + "AI 报告动作建议“" + report.finalAction + "”，但综合分 " + decision.systemScore()
                + " 未满足正式动作门槛或触发风险约束，最终裁决为“" + decision.finalAction() + "”";
    }

    private static String evidenceDescription(StockEvaluationEvidence evidence) {
        if (evidence == null || evidence.outOfSampleCount() <= 0) {
            return "没有可用的已评价历史证据；";
        }
        return "STRATEGY_FALLBACK".equals(evidence.scope())
                ? "该股票历史样本不足，已使用策略级已评价证据并保守降级；"
                : "已使用该股票的已评价历史证据；";
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

    private static BigDecimal max(BigDecimal left, BigDecimal right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.max(right);
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

    private record EvaluationEvidence(Map<Integer, HorizonEvaluationEvidence> byHorizon) {
        private HorizonEvaluationEvidence horizon(int horizonDays) {
            return byHorizon.getOrDefault(horizonDays, HorizonEvaluationEvidence.empty());
        }

        private StockEvaluationEvidence forStock(String stockCode, int horizonDays) {
            return horizon(horizonDays).forStock(stockCode, horizonDays);
        }

        private int minimumOutOfSampleCount(String stockCode) {
            return CORE_HORIZONS.stream().mapToInt(horizon -> forStock(stockCode, horizon).outOfSampleCount())
                    .min().orElse(0);
        }

        private int outOfSampleCount() {
            return CORE_HORIZONS.stream().mapToInt(horizon -> horizon(horizon).outOfSampleCount()).sum();
        }

        private BigDecimal overallHitRate() {
            BigDecimal weightedTotal = BigDecimal.ZERO;
            BigDecimal weight = BigDecimal.ZERO;
            for (Integer horizon : CORE_HORIZONS) {
                HorizonEvaluationEvidence value = horizon(horizon);
                if (value.overallHitRate() != null && value.outOfSampleCount() > 0) {
                    weightedTotal = weightedTotal.add(value.overallHitRate()
                            .multiply(HORIZON_WEIGHTS.get(horizon)));
                    weight = weight.add(HORIZON_WEIGHTS.get(horizon));
                }
            }
            return weight.signum() == 0 ? null : weightedTotal.divide(weight, 4, RoundingMode.HALF_UP);
        }

        private BigDecimal strategyValidation() {
            BigDecimal hitRate = overallHitRate();
            return hitRate == null ? new BigDecimal("0.50") : normalizeRate(hitRate);
        }
    }

    private record HorizonEvaluationEvidence(
            int outOfSampleCount,
            BigDecimal overallHitRate,
            Map<String, StockEvaluationEvidence> byStock
    ) {
        private static HorizonEvaluationEvidence empty() {
            return new HorizonEvaluationEvidence(0, null, Map.of());
        }

        private StockEvaluationEvidence forStock(String stockCode, int horizonDays) {
            StockEvaluationEvidence stock = byStock.get(stockCode);
            if (stock != null) {
                return stock;
            }
            if (outOfSampleCount > 0 && overallHitRate != null) {
                return new StockEvaluationEvidence(outOfSampleCount, overallHitRate,
                        "STRATEGY_T" + horizonDays + "_FALLBACK");
            }
            return StockEvaluationEvidence.empty();
        }
    }

    private record FactorPerformanceKey(Long factorDefinitionId, Integer horizonDays) {
    }

    private record StockEvaluationEvidence(int outOfSampleCount, BigDecimal hitRate, String scope) {
        private static StockEvaluationEvidence empty() {
            return new StockEvaluationEvidence(0, null, "NONE");
        }
    }
}
