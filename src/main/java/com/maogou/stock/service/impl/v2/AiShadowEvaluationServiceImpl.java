package com.maogou.stock.service.impl.v2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.v2.AiDriftEvent;
import com.maogou.stock.domain.entity.v2.AiLabelV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiShadowEvaluation;
import com.maogou.stock.domain.entity.v2.AiShadowEvaluationItem;
import com.maogou.stock.domain.entity.v2.AiStrategyRelease;
import com.maogou.stock.mapper.v2.AiDriftEventMapper;
import com.maogou.stock.mapper.v2.AiLabelV2Mapper;
import com.maogou.stock.mapper.v2.AiPredictionV2Mapper;
import com.maogou.stock.mapper.v2.AiShadowEvaluationItemMapper;
import com.maogou.stock.mapper.v2.AiShadowEvaluationMapper;
import com.maogou.stock.mapper.v2.AiStrategyReleaseMapper;
import com.maogou.stock.service.v2.AiShadowEvaluationService;
import com.maogou.stock.service.v2.AiStrategyGovernanceService;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiShadowEvaluationServiceImpl implements AiShadowEvaluationService {

    private static final int SCALE = 6;

    private final AiShadowEvaluationMapper evaluationMapper;
    private final AiShadowEvaluationItemMapper itemMapper;
    private final AiStrategyReleaseMapper releaseMapper;
    private final AiPredictionV2Mapper predictionMapper;
    private final AiLabelV2Mapper labelMapper;
    private final AiDriftEventMapper driftMapper;
    private final AiStrategyGovernanceService governanceService;
    private final ObjectMapper objectMapper;

    public AiShadowEvaluationServiceImpl(
            AiShadowEvaluationMapper evaluationMapper,
            AiShadowEvaluationItemMapper itemMapper,
            AiStrategyReleaseMapper releaseMapper,
            AiPredictionV2Mapper predictionMapper,
            AiLabelV2Mapper labelMapper,
            AiDriftEventMapper driftMapper,
            AiStrategyGovernanceService governanceService,
            ObjectMapper objectMapper
    ) {
        this.evaluationMapper = evaluationMapper;
        this.itemMapper = itemMapper;
        this.releaseMapper = releaseMapper;
        this.predictionMapper = predictionMapper;
        this.labelMapper = labelMapper;
        this.driftMapper = driftMapper;
        this.governanceService = governanceService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public EvaluationResult evaluate(EvaluationRequest request) {
        validateRequest(request);
        ReleasePair releases = validateReleases(request);
        List<PairSnapshot> pairs = snapshotAndValidatePairs(request, releases);
        Metrics metrics = computeMetrics(request, pairs);
        if (metrics.shadowThresholdsPassed() && request.governance() == null) {
            throw new IllegalArgumentException("晋级候选缺少治理证据上下文");
        }
        AiShadowEvaluation evaluation = persistEvaluation(request, releases, pairs, metrics);
        List<AiShadowEvaluationItem> items = persistItems(evaluation.id, pairs);
        List<AiDriftEvent> driftEvents = persistDriftEvent(request, evaluation, metrics.driftStatus());
        AiStrategyGovernanceService.Assessment assessment = assessPromotion(
                request, evaluation, metrics, driftEvents);
        return new EvaluationResult(evaluation, items, driftEvents, assessment);
    }

    private ReleasePair validateReleases(EvaluationRequest request) {
        AiStrategyRelease champion = releaseMapper.selectById(request.championReleaseId());
        AiStrategyRelease challenger = releaseMapper.selectById(request.challengerReleaseId());
        if (champion == null || !Objects.equals(champion.userId, request.userId())
                || !"CHAMPION".equals(champion.releaseRole) || !"ACTIVE".equals(champion.status)) {
            throw new IllegalArgumentException("影子评估要求当前用户的 ACTIVE Champion");
        }
        if (challenger == null || !Objects.equals(challenger.userId, request.userId())
                || !"CHALLENGER".equals(challenger.releaseRole) || !"SHADOW".equals(challenger.status)) {
            throw new IllegalArgumentException("影子评估要求当前用户的 SHADOW Challenger");
        }
        return new ReleasePair(champion, challenger);
    }

    private List<PairSnapshot> snapshotAndValidatePairs(
            EvaluationRequest request,
            ReleasePair releases
    ) {
        List<Long> predictionIds = request.pairs().stream()
                .flatMap(pair -> pair == null ? java.util.stream.Stream.empty()
                        : java.util.stream.Stream.of(pair.champion(), pair.challenger()))
                .filter(Objects::nonNull)
                .map(item -> item.id)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, AiPredictionV2> databasePredictions = predictionIds.isEmpty()
                ? Map.of()
                : predictionMapper.selectBatchIds(predictionIds).stream()
                .collect(Collectors.toMap(item -> item.id, Function.identity(), (left, right) -> left));
        List<Long> labelIds = request.pairs().stream()
                .map(pair -> pair == null ? null : pair.label())
                .filter(Objects::nonNull)
                .map(item -> item.id)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, AiLabelV2> databaseLabels = labelIds.isEmpty()
                ? Map.of()
                : labelMapper.selectBatchIds(labelIds).stream()
                .collect(Collectors.toMap(item -> item.id, Function.identity(), (left, right) -> left));
        List<PairSnapshot> snapshots = new ArrayList<>();
        Set<String> sampleHorizons = new HashSet<>();
        Set<Long> championPredictionIds = new HashSet<>();
        Set<Long> challengerPredictionIds = new HashSet<>();
        for (PredictionPair pair : request.pairs()) {
            if (pair == null || pair.champion() == null || pair.challenger() == null) {
                throw new IllegalArgumentException("影子评估预测配对不能为空");
            }
            if (pair.champion().id == null || pair.challenger().id == null) {
                throw new IllegalArgumentException("影子评估预测 ID 不能为空");
            }
            AiPredictionV2 champion = databasePredictions.get(pair.champion().id);
            AiPredictionV2 challenger = databasePredictions.get(pair.challenger().id);
            if (champion == null || challenger == null) {
                throw new IllegalArgumentException("影子评估预测必须来自不可变数据库记录");
            }
            validatePredictionFields(champion, "Champion");
            validatePredictionFields(challenger, "Challenger");
            if (Objects.equals(champion.id, challenger.id)) {
                throw new IllegalArgumentException("Champion 与 Challenger 预测 ID 不能相同");
            }
            if (!Objects.equals(champion.userId, request.userId())
                    || !Objects.equals(challenger.userId, request.userId())) {
                throw new IllegalArgumentException("影子预测必须属于同一用户");
            }
            if (!Objects.equals(champion.strategyReleaseId, request.championReleaseId())
                    || !Objects.equals(challenger.strategyReleaseId, request.challengerReleaseId())
                    || !Objects.equals(champion.modelVersionId, releases.champion().modelVersionId)
                    || !Objects.equals(challenger.modelVersionId, releases.challenger().modelVersionId)) {
                throw new IllegalArgumentException("影子预测与 Champion/Challenger 角色不一致");
            }
            if (!sameImmutableSample(champion, challenger)) {
                throw new IllegalArgumentException("Champion 与 Challenger 必须基于同一不可变样本和周期");
            }
            if (champion.tradeDate.isBefore(request.windowStartDate())
                    || champion.tradeDate.isAfter(request.windowEndDate())) {
                throw new IllegalArgumentException("影子预测样本不在评估窗口内");
            }
            String sampleHorizon = champion.sampleId + ":" + champion.horizonDays;
            if (!sampleHorizons.add(sampleHorizon)
                    || !championPredictionIds.add(champion.id)
                    || !challengerPredictionIds.add(challenger.id)) {
                throw new IllegalArgumentException("影子评估包含重复样本周期或预测 ID");
            }
            AiLabelV2 label = pair.label() == null ? null : databaseLabels.get(pair.label().id);
            if (pair.label() != null && label == null) {
                throw new IllegalArgumentException("影子评估标签必须来自不可变数据库记录");
            }
            if (label != null && (!Objects.equals(label.userId, request.userId())
                    || !Objects.equals(label.predictionId, champion.id)
                    || !Objects.equals(label.sampleId, champion.sampleId)
                    || !Objects.equals(label.stockCode, champion.stockCode)
                    || !Objects.equals(label.horizonDays, champion.horizonDays))) {
                throw new IllegalArgumentException("影子评估标签与不可变样本或 Champion 预测不一致");
            }
            snapshots.add(PairSnapshot.from(champion, challenger, label));
        }
        snapshots.sort(Comparator.comparing(PairSnapshot::tradeDate)
                .thenComparing(PairSnapshot::sampleId)
                .thenComparing(PairSnapshot::horizonDays));
        return List.copyOf(snapshots);
    }

    private static void validatePredictionFields(AiPredictionV2 prediction, String role) {
        if (prediction.id == null || prediction.userId == null || prediction.sampleId == null
                || prediction.strategyReleaseId == null || prediction.stockCode == null
                || prediction.tradeDate == null || prediction.samplePhase == null
                || prediction.horizonDays == null || prediction.horizonDays <= 0
                || prediction.targetDirection == null || prediction.targetDirection.isBlank()
                || prediction.inputFingerprint == null || prediction.inputFingerprint.isBlank()) {
            throw new IllegalArgumentException(role + " 预测缺少不可变配对字段");
        }
        if (prediction.probabilityUp != null
                && (prediction.probabilityUp.signum() < 0
                || prediction.probabilityUp.compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException(role + " 上涨概率不在 [0, 1]");
        }
    }

    private static boolean sameImmutableSample(AiPredictionV2 left, AiPredictionV2 right) {
        return Objects.equals(left.sampleId, right.sampleId)
                && Objects.equals(left.stockCode, right.stockCode)
                && Objects.equals(left.tradeDate, right.tradeDate)
                && Objects.equals(left.samplePhase, right.samplePhase)
                && Objects.equals(left.horizonDays, right.horizonDays);
    }

    private Metrics computeMetrics(EvaluationRequest request, List<PairSnapshot> pairs) {
        int agreementCount = (int) pairs.stream().filter(PairSnapshot::directionAgreement).count();
        BigDecimal coverage = ratio(pairs.size(), request.windowSampleCount());
        BigDecimal agreement = ratio(agreementCount, pairs.size());
        List<PairSnapshot> labelled = pairs.stream().filter(PairSnapshot::evaluated).toList();
        List<BigDecimal> championReturns = labelled.stream()
                .map(pair -> directionalReturn(pair.championDirection(), pair.excessReturn()))
                .filter(Objects::nonNull).toList();
        List<BigDecimal> challengerReturns = labelled.stream()
                .map(pair -> directionalReturn(pair.challengerDirection(), pair.excessReturn()))
                .filter(Objects::nonNull).toList();
        BigDecimal championReturn = average(championReturns);
        BigDecimal challengerReturn = average(challengerReturns);
        BigDecimal advantage = championReturn == null || challengerReturn == null
                ? null : decimal(challengerReturn.subtract(championReturn));
        BigDecimal championDrawdown = maxDrawdown(championReturns);
        BigDecimal challengerDrawdown = maxDrawdown(challengerReturns);
        BigDecimal championCalibration = calibrationError(labelled, true);
        BigDecimal challengerCalibration = calibrationError(labelled, false);
        BigDecimal championHitRate = hitRate(championReturns);
        BigDecimal challengerHitRate = hitRate(challengerReturns);
        String driftStatus = driftStatus(request.featureDriftScore(), request.thresholds());
        boolean shadowThresholdsPassed = "STABLE".equals(driftStatus)
                && coverage.compareTo(request.thresholds().minimumCoverageRate()) >= 0
                && agreement.compareTo(request.thresholds().minimumDirectionAgreementRate()) >= 0
                && advantage != null
                && advantage.compareTo(request.thresholds().minimumExcessReturnAdvantage()) >= 0
                && challengerDrawdown != null
                && challengerDrawdown.compareTo(request.thresholds().maximumDrawdown()) >= 0;
        boolean promotionCandidate = shadowThresholdsPassed && governanceThresholdsMet(
                request.governance(), pairs.size(), challengerDrawdown);

        Map<String, Object> driftSummary = new LinkedHashMap<>();
        driftSummary.put("status", driftStatus);
        driftSummary.put("score", request.featureDriftScore());
        driftSummary.put("warningThreshold", request.thresholds().featureDriftWarning());
        driftSummary.put("criticalThreshold", request.thresholds().featureDriftCritical());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sampleCount", request.windowSampleCount());
        values.put("eligibleSampleCount", pairs.size());
        values.put("labelledSampleCount", labelled.size());
        values.put("coverageRate", coverage);
        values.put("directionAgreementRate", agreement);
        values.put("championHitRate", championHitRate);
        values.put("challengerHitRate", challengerHitRate);
        values.put("championExcessReturn", championReturn);
        values.put("challengerExcessReturn", challengerReturn);
        values.put("excessReturnAdvantage", advantage);
        values.put("championMaxDrawdown", championDrawdown);
        values.put("challengerMaxDrawdown", challengerDrawdown);
        values.put("championCalibrationError", championCalibration);
        values.put("challengerCalibrationError", challengerCalibration);
        values.put("driftSummary", driftSummary);
        return new Metrics(coverage, agreement, championCalibration, challengerCalibration,
                championReturn, challengerReturn, championDrawdown, challengerDrawdown,
                championHitRate, challengerHitRate, advantage, driftStatus,
                shadowThresholdsPassed, promotionCandidate, json(values));
    }

    private AiShadowEvaluation persistEvaluation(
            EvaluationRequest request,
            ReleasePair releases,
            List<PairSnapshot> pairs,
            Metrics metrics
    ) {
        AiShadowEvaluation expected = new AiShadowEvaluation();
        expected.userId = request.userId();
        expected.pipelineRunId = request.pipelineRunId();
        expected.trainingDatasetId = request.trainingDatasetId();
        expected.championReleaseId = request.championReleaseId();
        expected.challengerReleaseId = request.challengerReleaseId();
        expected.championModelVersionId = releases.champion().modelVersionId;
        expected.challengerModelVersionId = releases.challenger().modelVersionId;
        expected.windowStartDate = request.windowStartDate();
        expected.windowEndDate = request.windowEndDate();
        expected.evaluationVersion = request.evaluationVersion();
        expected.inputFingerprint = evaluationFingerprint(request, pairs);
        expected.sampleCount = request.windowSampleCount();
        expected.eligibleSampleCount = pairs.size();
        expected.coverageRate = metrics.coverageRate();
        expected.actionAgreementRate = metrics.agreementRate();
        expected.championCalibrationError = metrics.championCalibrationError();
        expected.challengerCalibrationError = metrics.challengerCalibrationError();
        expected.championExcessReturn = metrics.championExcessReturn();
        expected.challengerExcessReturn = metrics.challengerExcessReturn();
        expected.championMaxDrawdown = metrics.championMaxDrawdown();
        expected.challengerMaxDrawdown = metrics.challengerMaxDrawdown();
        expected.featureDriftScore = decimal(request.featureDriftScore());
        expected.metricsJson = metrics.metricsJson();
        expected.decisionStatus = metrics.promotionCandidate() ? "PROMOTION_CANDIDATE" : "OBSERVING";
        expected.evaluatedAt = request.evaluatedAt();
        expected.createdAt = LocalDateTime.now();
        evaluationMapper.insertImmutable(expected);
        AiShadowEvaluation actual = evaluationMapper.selectWindowForShare(
                request.userId(), request.championReleaseId(), request.challengerReleaseId(),
                request.windowStartDate(), request.windowEndDate(), request.evaluationVersion());
        if (actual == null) {
            throw new IllegalStateException("影子评估窗口写入后未读取到记录");
        }
        if (!Objects.equals(expected.inputFingerprint, actual.inputFingerprint)
                || !Objects.equals(expected.metricsJson, actual.metricsJson)
                || !Objects.equals(expected.decisionStatus, actual.decisionStatus)
                || !Objects.equals(expected.sampleCount, actual.sampleCount)
                || !Objects.equals(expected.eligibleSampleCount, actual.eligibleSampleCount)) {
            throw new IllegalStateException("不可变影子评估冲突：" + expected.inputFingerprint);
        }
        return actual;
    }

    private List<AiShadowEvaluationItem> persistItems(
            Long evaluationId,
            List<PairSnapshot> pairs
    ) {
        List<AiShadowEvaluationItem> expected = pairs.stream()
                .map(pair -> evaluationItem(evaluationId, pair)).toList();
        if (expected.isEmpty()) {
            return List.of();
        }
        itemMapper.insertBatchImmutable(expected);
        List<AiShadowEvaluationItem> persisted = itemMapper.selectByEvaluationForShare(evaluationId);
        Map<String, AiShadowEvaluationItem> byKey = new HashMap<>();
        persisted.forEach(item -> byKey.put(item.sampleId + ":" + item.horizonDays, item));
        List<AiShadowEvaluationItem> result = new ArrayList<>();
        for (AiShadowEvaluationItem candidate : expected) {
            AiShadowEvaluationItem actual = byKey.get(candidate.sampleId + ":" + candidate.horizonDays);
            if (actual == null) {
                throw new IllegalStateException("影子评估明细写入后未读取到记录：" + candidate.sampleId);
            }
            if (!Objects.equals(candidate.championPredictionId, actual.championPredictionId)
                    || !Objects.equals(candidate.challengerPredictionId, actual.challengerPredictionId)
                    || !Objects.equals(candidate.labelId, actual.labelId)
                    || !sameDecimal(candidate.scoreDelta, actual.scoreDelta)
                    || !sameDecimal(candidate.confidenceDelta, actual.confidenceDelta)
                    || !Objects.equals(candidate.evaluationStatus, actual.evaluationStatus)) {
                throw new IllegalStateException("不可变影子评估明细冲突：" + candidate.sampleId);
            }
            result.add(actual);
        }
        return List.copyOf(result);
    }

    private static AiShadowEvaluationItem evaluationItem(Long evaluationId, PairSnapshot pair) {
        AiShadowEvaluationItem item = new AiShadowEvaluationItem();
        item.shadowEvaluationId = evaluationId;
        item.sampleId = pair.sampleId();
        item.championPredictionId = pair.championPredictionId();
        item.challengerPredictionId = pair.challengerPredictionId();
        item.labelId = pair.labelId();
        item.horizonDays = pair.horizonDays();
        item.actionAgreement = pair.directionAgreement() ? 1 : 0;
        item.scoreDelta = subtract(pair.challengerScore(), pair.championScore());
        item.confidenceDelta = subtract(pair.challengerConfidence(), pair.championConfidence());
        item.challengerExcessReturn = pair.evaluated()
                ? directionalReturn(pair.challengerDirection(), pair.excessReturn()) : null;
        item.evaluationStatus = pair.evaluated() ? "EVALUATED" : "PENDING_LABEL";
        item.createdAt = LocalDateTime.now();
        return item;
    }

    private List<AiDriftEvent> persistDriftEvent(
            EvaluationRequest request,
            AiShadowEvaluation evaluation,
            String driftStatus
    ) {
        if ("STABLE".equals(driftStatus)) {
            return List.of();
        }
        String severity = driftStatus;
        BigDecimal threshold = "CRITICAL".equals(severity)
                ? request.thresholds().featureDriftCritical()
                : request.thresholds().featureDriftWarning();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evaluationFingerprint", evaluation.inputFingerprint);
        evidence.put("championReleaseId", evaluation.championReleaseId);
        evidence.put("challengerReleaseId", evaluation.challengerReleaseId);
        evidence.put("coverageRate", evaluation.coverageRate);
        evidence.put("actionAgreementRate", evaluation.actionAgreementRate);
        AiDriftEvent expected = new AiDriftEvent();
        expected.userId = request.userId();
        expected.modelVersionId = evaluation.challengerModelVersionId;
        expected.strategyReleaseId = evaluation.challengerReleaseId;
        expected.shadowEvaluationId = evaluation.id;
        expected.eventType = "SHADOW_DRIFT";
        expected.subjectType = "SHADOW_EVALUATION";
        expected.subjectKey = evaluation.championReleaseId + ":" + evaluation.challengerReleaseId;
        expected.detectorVersion = request.thresholds().detectorVersion();
        expected.windowStartDate = request.windowStartDate();
        expected.windowEndDate = request.windowEndDate();
        expected.metricName = "FEATURE_DRIFT_SCORE";
        expected.observedValue = decimal(request.featureDriftScore());
        expected.thresholdValue = decimal(threshold);
        expected.severity = severity;
        expected.status = "OPEN";
        expected.evidenceJson = json(evidence);
        expected.detectedAt = request.evaluatedAt();
        expected.createdAt = LocalDateTime.now();
        expected.eventFingerprint = sha256(String.join("|",
                evaluation.inputFingerprint, expected.detectorVersion, expected.metricName,
                severity, threshold.toPlainString()));
        driftMapper.insertBatchImmutable(List.of(expected));
        List<AiDriftEvent> persisted = driftMapper.selectByFingerprintsForShare(
                request.userId(), List.of(expected.eventFingerprint));
        if (persisted.size() != 1) {
            throw new IllegalStateException("影子漂移事件写入后未读取到唯一记录");
        }
        AiDriftEvent actual = persisted.get(0);
        if (!Objects.equals(expected.metricName, actual.metricName)
                || !Objects.equals(expected.subjectKey, actual.subjectKey)
                || !Objects.equals(expected.severity, actual.severity)
                || !Objects.equals(expected.evidenceJson, actual.evidenceJson)) {
            throw new IllegalStateException("不可变影子漂移事件冲突：" + expected.eventFingerprint);
        }
        return List.of(actual);
    }

    private AiStrategyGovernanceService.Assessment assessPromotion(
            EvaluationRequest request,
            AiShadowEvaluation evaluation,
            Metrics metrics,
            List<AiDriftEvent> driftEvents
    ) {
        if (!metrics.shadowThresholdsPassed()) {
            return null;
        }
        GovernanceContext context = request.governance();
        int criticalDriftCount = (int) driftEvents.stream()
                .filter(event -> "CRITICAL".equals(event.severity)).count();
        AiStrategyGovernanceService.PromotionEvidence evidence =
                new AiStrategyGovernanceService.PromotionEvidence(
                        context.shadowTradingDays(), evaluation.eligibleSampleCount,
                        context.tradeCount(), context.foldCount(),
                        evaluation.challengerExcessReturn, evaluation.challengerMaxDrawdown,
                        context.maxSingleStockContribution(),
                        context.confidenceIntervalLowerExcessReturn(), criticalDriftCount,
                        evaluation.inputFingerprint);
        return governanceService.assess(new AiStrategyGovernanceService.AssessmentRequest(
                request.userId(), request.challengerReleaseId(), request.championReleaseId(),
                context.walkForwardRunId(), context.backtestRunId(), evaluation.id,
                context.policyVersion(), context.policy(), evidence, request.evaluatedAt()));
    }

    private String evaluationFingerprint(EvaluationRequest request, List<PairSnapshot> pairs) {
        StringBuilder canonical = new StringBuilder(String.join("|",
                String.valueOf(request.userId()), String.valueOf(request.championReleaseId()),
                String.valueOf(request.challengerReleaseId()), String.valueOf(request.windowStartDate()),
                String.valueOf(request.windowEndDate()), request.evaluationVersion(),
                String.valueOf(request.windowSampleCount()), request.featureDriftScore().toPlainString(),
                String.valueOf(request.pipelineRunId()), String.valueOf(request.trainingDatasetId()),
                String.valueOf(request.thresholds()), String.valueOf(request.governance()),
                String.valueOf(request.evaluatedAt())));
        for (PairSnapshot pair : pairs) {
            canonical.append('|').append(pair.sampleId()).append(':').append(pair.horizonDays())
                    .append(':').append(pair.championPredictionId())
                    .append(':').append(pair.challengerPredictionId())
                    .append(':').append(pair.championInputFingerprint())
                    .append(':').append(pair.challengerInputFingerprint())
                    .append(':').append(pair.labelId())
                    .append(':').append(pair.excessReturn());
        }
        return sha256(canonical.toString());
    }

    private static BigDecimal calibrationError(List<PairSnapshot> pairs, boolean champion) {
        List<BigDecimal> errors = new ArrayList<>();
        for (PairSnapshot pair : pairs) {
            BigDecimal probability = champion ? pair.championProbabilityUp()
                    : pair.challengerProbabilityUp();
            if (probability != null) {
                BigDecimal actual = pair.excessReturn().signum() > 0
                        ? BigDecimal.ONE : BigDecimal.ZERO;
                errors.add(probability.subtract(actual).abs());
            }
        }
        return average(errors);
    }

    private static BigDecimal directionalReturn(String direction, BigDecimal excessReturn) {
        if (excessReturn == null) {
            return null;
        }
        return switch (normalize(direction)) {
            case "UP" -> decimal(excessReturn);
            case "DOWN" -> decimal(excessReturn.negate());
            default -> null;
        };
    }

    private static BigDecimal hitRate(List<BigDecimal> returns) {
        if (returns.isEmpty()) {
            return null;
        }
        int hits = (int) returns.stream().filter(value -> value.signum() > 0).count();
        return ratio(hits, returns.size());
    }

    private static BigDecimal maxDrawdown(List<BigDecimal> returns) {
        if (returns.isEmpty()) {
            return null;
        }
        BigDecimal equity = BigDecimal.ONE;
        BigDecimal peak = BigDecimal.ONE;
        BigDecimal worst = BigDecimal.ZERO;
        for (BigDecimal value : returns) {
            equity = equity.multiply(BigDecimal.ONE.add(value));
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            }
            BigDecimal drawdown = equity.divide(peak, 12, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE);
            if (drawdown.compareTo(worst) < 0) {
                worst = drawdown;
            }
        }
        return decimal(worst);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : decimal(left.subtract(right));
    }

    private static BigDecimal decimal(BigDecimal value) {
        return value == null ? null : value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static String driftStatus(BigDecimal score, ShadowThresholds thresholds) {
        if (score.compareTo(thresholds.featureDriftCritical()) >= 0) {
            return "CRITICAL";
        }
        if (score.compareTo(thresholds.featureDriftWarning()) >= 0) {
            return "WARNING";
        }
        return "STABLE";
    }

    private static boolean governanceThresholdsMet(
            GovernanceContext context,
            int sampleCount,
            BigDecimal challengerDrawdown
    ) {
        if (context == null || challengerDrawdown == null) {
            return false;
        }
        AiStrategyGovernanceService.PromotionPolicy policy = context.policy();
        return context.shadowTradingDays() >= policy.minimumShadowDays()
                && sampleCount >= policy.minimumSamples()
                && context.tradeCount() >= policy.minimumTrades()
                && context.foldCount() >= policy.minimumFolds()
                && challengerDrawdown.compareTo(policy.maximumDrawdown()) >= 0
                && context.maxSingleStockContribution()
                .compareTo(policy.maximumSingleStockContribution()) <= 0
                && context.confidenceIntervalLowerExcessReturn()
                .compareTo(policy.minimumConfidenceIntervalExcessReturn()) >= 0;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化影子评估证据", ex);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static void validateRequest(EvaluationRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0
                || request.pipelineRunId() != null && request.pipelineRunId() <= 0
                || request.trainingDatasetId() != null && request.trainingDatasetId() <= 0
                || request.championReleaseId() == null || request.championReleaseId() <= 0
                || request.challengerReleaseId() == null || request.challengerReleaseId() <= 0
                || Objects.equals(request.championReleaseId(), request.challengerReleaseId())
                || request.windowStartDate() == null || request.windowEndDate() == null
                || request.windowStartDate().isAfter(request.windowEndDate())
                || request.evaluationVersion() == null || request.evaluationVersion().isBlank()
                || request.windowSampleCount() == null || request.windowSampleCount() <= 0
                || request.pairs() == null || request.pairs().size() > request.windowSampleCount()
                || request.featureDriftScore() == null || request.featureDriftScore().signum() < 0
                || request.thresholds() == null
                || request.evaluatedAt() == null) {
            throw new IllegalArgumentException("影子评估请求缺少有效窗口、发布、样本或证据");
        }
        ShadowThresholds thresholds = request.thresholds();
        if (!validRate(thresholds.minimumCoverageRate())
                || !validRate(thresholds.minimumDirectionAgreementRate())
                || thresholds.minimumExcessReturnAdvantage() == null
                || thresholds.maximumDrawdown() == null || thresholds.maximumDrawdown().signum() > 0
                || thresholds.featureDriftWarning() == null
                || thresholds.featureDriftWarning().signum() < 0
                || thresholds.featureDriftCritical() == null
                || thresholds.featureDriftCritical().compareTo(thresholds.featureDriftWarning()) < 0
                || thresholds.detectorVersion() == null || thresholds.detectorVersion().isBlank()) {
            throw new IllegalArgumentException("影子评估阈值无效");
        }
        GovernanceContext governance = request.governance();
        if (governance != null && (governance.walkForwardRunId() == null
                || governance.walkForwardRunId() <= 0
                || governance.backtestRunId() == null || governance.backtestRunId() <= 0
                || governance.policy() == null
                || governance.policy().minimumShadowDays() == null
                || governance.policy().minimumShadowDays() <= 0
                || governance.policy().minimumSamples() == null
                || governance.policy().minimumSamples() <= 0
                || governance.policy().minimumTrades() == null
                || governance.policy().minimumTrades() <= 0
                || governance.policy().minimumFolds() == null
                || governance.policy().minimumFolds() <= 0
                || governance.policy().maximumDrawdown() == null
                || governance.policy().maximumDrawdown().signum() > 0
                || governance.policy().maximumSingleStockContribution() == null
                || governance.policy().maximumSingleStockContribution().signum() < 0
                || governance.policy().minimumConfidenceIntervalExcessReturn() == null
                || governance.shadowTradingDays() == null || governance.shadowTradingDays() < 0
                || governance.tradeCount() == null || governance.tradeCount() < 0
                || governance.foldCount() == null || governance.foldCount() < 0
                || governance.maxSingleStockContribution() == null
                || governance.confidenceIntervalLowerExcessReturn() == null
                || governance.policyVersion() == null || governance.policyVersion().isBlank())) {
            throw new IllegalArgumentException("影子评估治理证据无效");
        }
    }

    private static boolean validRate(BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
    }

    private record ReleasePair(AiStrategyRelease champion, AiStrategyRelease challenger) {
    }

    private record Metrics(
            BigDecimal coverageRate,
            BigDecimal agreementRate,
            BigDecimal championCalibrationError,
            BigDecimal challengerCalibrationError,
            BigDecimal championExcessReturn,
            BigDecimal challengerExcessReturn,
            BigDecimal championMaxDrawdown,
            BigDecimal challengerMaxDrawdown,
            BigDecimal championHitRate,
            BigDecimal challengerHitRate,
            BigDecimal excessReturnAdvantage,
            String driftStatus,
            boolean shadowThresholdsPassed,
            boolean promotionCandidate,
            String metricsJson
    ) {
    }

    private record PairSnapshot(
            Long sampleId,
            LocalDate tradeDate,
            Integer horizonDays,
            Long championPredictionId,
            Long challengerPredictionId,
            String championInputFingerprint,
            String challengerInputFingerprint,
            String championDirection,
            String challengerDirection,
            BigDecimal championProbabilityUp,
            BigDecimal challengerProbabilityUp,
            BigDecimal championScore,
            BigDecimal challengerScore,
            BigDecimal championConfidence,
            BigDecimal challengerConfidence,
            Long labelId,
            String labelStatus,
            BigDecimal excessReturn
    ) {
        static PairSnapshot from(
                AiPredictionV2 champion,
                AiPredictionV2 challenger,
                AiLabelV2 label
        ) {
            return new PairSnapshot(champion.sampleId, champion.tradeDate, champion.horizonDays,
                    champion.id, challenger.id, champion.inputFingerprint, challenger.inputFingerprint,
                    champion.targetDirection, challenger.targetDirection, champion.probabilityUp,
                    challenger.probabilityUp, champion.score, challenger.score,
                    champion.calibratedConfidence, challenger.calibratedConfidence,
                    label == null ? null : label.id, label == null ? null : label.labelStatus,
                    label == null ? null : label.excessReturn);
        }

        boolean directionAgreement() {
            return normalize(championDirection).equals(normalize(challengerDirection));
        }

        boolean evaluated() {
            return "VERIFIED".equals(labelStatus) && excessReturn != null;
        }
    }
}
