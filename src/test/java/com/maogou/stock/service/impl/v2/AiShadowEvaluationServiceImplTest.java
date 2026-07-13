package com.maogou.stock.service.impl.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.v2.AiDriftEvent;
import com.maogou.stock.domain.entity.v2.AiLabelV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiShadowEvaluation;
import com.maogou.stock.domain.entity.v2.AiShadowEvaluationItem;
import com.maogou.stock.domain.entity.v2.AiStrategyGovernanceEvent;
import com.maogou.stock.domain.entity.v2.AiStrategyRelease;
import com.maogou.stock.mapper.v2.AiDriftEventMapper;
import com.maogou.stock.mapper.v2.AiLabelV2Mapper;
import com.maogou.stock.mapper.v2.AiPredictionV2Mapper;
import com.maogou.stock.mapper.v2.AiShadowEvaluationItemMapper;
import com.maogou.stock.mapper.v2.AiShadowEvaluationMapper;
import com.maogou.stock.mapper.v2.AiStrategyReleaseMapper;
import com.maogou.stock.service.v2.AiShadowEvaluationService;
import com.maogou.stock.service.v2.AiStrategyGovernanceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiShadowEvaluationServiceImplTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Map<Long, AiPredictionV2> PREDICTION_DB = new ConcurrentHashMap<>();
    private static final Map<Long, AiLabelV2> LABEL_DB = new ConcurrentHashMap<>();

    @Test
    void pairsImmutableSamplesAndComputesComparableWindowMetricsWithoutChangingUserResults() throws Exception {
        Fixture fixture = fixture();
        AiShadowEvaluationService service = service(fixture);
        AiShadowEvaluationService.EvaluationRequest request = passingRequest(new BigDecimal("0.04"));
        AiPredictionV2 userPrediction = request.pairs().get(0).champion();
        String originalAction = userPrediction.action;
        String originalDirection = userPrediction.targetDirection;

        AiShadowEvaluationService.EvaluationResult result = service.evaluate(request);

        assertThat(result.evaluation().sampleCount).isEqualTo(3);
        assertThat(result.evaluation().eligibleSampleCount).isEqualTo(2);
        assertThat(result.evaluation().coverageRate).isEqualByComparingTo("0.666667");
        assertThat(result.evaluation().actionAgreementRate).isEqualByComparingTo("0.500000");
        assertThat(result.evaluation().championCalibrationError).isEqualByComparingTo("0.450000");
        assertThat(result.evaluation().challengerCalibrationError).isEqualByComparingTo("0.200000");
        assertThat(result.evaluation().championExcessReturn).isEqualByComparingTo("0.025000");
        assertThat(result.evaluation().challengerExcessReturn).isEqualByComparingTo("0.075000");
        assertThat(result.evaluation().championMaxDrawdown).isEqualByComparingTo("-0.050000");
        assertThat(result.evaluation().challengerMaxDrawdown).isEqualByComparingTo("0.000000");
        assertThat(result.evaluation().decisionStatus).isEqualTo("PROMOTION_CANDIDATE");
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(item -> item.sampleId).containsExactly(101L, 102L);
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.championPredictionId).isNotEqualTo(item.challengerPredictionId);
            assertThat(item.evaluationStatus).isEqualTo("EVALUATED");
        });
        JsonNode metrics = JSON.readTree(result.evaluation().metricsJson);
        assertThat(metrics.path("championHitRate").decimalValue()).isEqualByComparingTo("0.500000");
        assertThat(metrics.path("challengerHitRate").decimalValue()).isEqualByComparingTo("1.000000");
        assertThat(metrics.path("excessReturnAdvantage").decimalValue()).isEqualByComparingTo("0.050000");
        assertThat(metrics.path("driftSummary").path("status").asText()).isEqualTo("STABLE");
        assertThat(result.governanceAssessment()).isNotNull();
        assertThat(userPrediction.action).isEqualTo(originalAction);
        assertThat(userPrediction.targetDirection).isEqualTo(originalDirection);
        assertThat(fixture.champion.status).isEqualTo("ACTIVE");
        assertThat(fixture.challenger.status).isEqualTo("SHADOW");
        verify(fixture.releaseMapper, never()).updateById(any(AiStrategyRelease.class));
        verify(fixture.governanceService).assess(any(AiStrategyGovernanceService.AssessmentRequest.class));
    }

    @Test
    void repeatedWindowWriteReturnsTheSameSummaryAndItemsButRejectsChangedImmutableInput() {
        Fixture fixture = fixture();
        AiShadowEvaluationService service = service(fixture);
        AiShadowEvaluationService.EvaluationRequest request = passingRequest(new BigDecimal("0.04"));

        AiShadowEvaluationService.EvaluationResult first = service.evaluate(request);
        AiShadowEvaluationService.EvaluationResult second = service.evaluate(request);

        assertThat(second.evaluation().id).isEqualTo(first.evaluation().id);
        assertThat(second.items()).extracting(item -> item.id)
                .containsExactlyElementsOf(first.items().stream().map(item -> item.id).toList());
        assertThat(fixture.evaluations).hasSize(1);
        assertThat(fixture.items).hasSize(2);

        AiPredictionV2 changed = prediction(299L, 102L, 2L, "UP", "0.75", "0.65");
        AiShadowEvaluationService.EvaluationRequest conflicting = copyWithPairs(request, List.of(
                request.pairs().get(0),
                new AiShadowEvaluationService.PredictionPair(
                        request.pairs().get(1).champion(), changed, request.pairs().get(1).label())));
        assertThatThrownBy(() -> service.evaluate(conflicting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可变影子评估冲突");
    }

    @Test
    void sameWindowRejectsDifferentLineageOrGovernancePolicy() {
        Fixture fixture = fixture();
        AiShadowEvaluationService service = service(fixture);
        AiShadowEvaluationService.EvaluationRequest source = passingRequest(new BigDecimal("0.04"));
        service.evaluate(source);
        AiShadowEvaluationService.GovernanceContext changedGovernance =
                new AiShadowEvaluationService.GovernanceContext(
                        source.governance().walkForwardRunId(), source.governance().backtestRunId(),
                        new AiStrategyGovernanceService.PromotionPolicy(
                                20, 2, 2, 3, new BigDecimal("-0.15"),
                                new BigDecimal("0.20"), new BigDecimal("0.01")),
                        source.governance().shadowTradingDays(), source.governance().tradeCount(),
                        source.governance().foldCount(),
                        source.governance().maxSingleStockContribution(),
                        source.governance().confidenceIntervalLowerExcessReturn(),
                        source.governance().policyVersion());
        AiShadowEvaluationService.EvaluationRequest changed =
                new AiShadowEvaluationService.EvaluationRequest(
                        source.userId(), 72L, 82L, source.championReleaseId(),
                        source.challengerReleaseId(), source.windowStartDate(), source.windowEndDate(),
                        source.evaluationVersion(), source.windowSampleCount(), source.pairs(),
                        source.featureDriftScore(), source.thresholds(), changedGovernance,
                        source.evaluatedAt());

        assertThatThrownBy(() -> service.evaluate(changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可变影子评估冲突");
    }

    @Test
    void rejectsSamePredictionIdAndDifferentImmutableSamples() {
        Fixture fixture = fixture();
        AiShadowEvaluationService service = service(fixture);
        AiShadowEvaluationService.EvaluationRequest request = passingRequest(new BigDecimal("0.04"));
        AiPredictionV2 champion = request.pairs().get(0).champion();
        AiPredictionV2 sameId = prediction(champion.id, champion.sampleId, 2L, "UP", "0.80", "0.70");

        assertThatThrownBy(() -> service.evaluate(copyWithPairs(request, List.of(
                new AiShadowEvaluationService.PredictionPair(
                        champion, sameId, request.pairs().get(0).label())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能相同");

        AiPredictionV2 differentSample = prediction(999L, 999L, 2L, "UP", "0.80", "0.70");
        assertThatThrownBy(() -> service.evaluate(copyWithPairs(request, List.of(
                new AiShadowEvaluationService.PredictionPair(
                        champion, differentSample, request.pairs().get(0).label())))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一不可变样本");
    }

    @Test
    void rejectsWrongReleaseRolesOrPredictionsAssignedToTheWrongRole() {
        Fixture fixture = fixture();
        fixture.challenger.releaseRole = "CHAMPION";
        AiShadowEvaluationService service = service(fixture);

        assertThatThrownBy(() -> service.evaluate(passingRequest(new BigDecimal("0.04"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Challenger");

        fixture.challenger.releaseRole = "CHALLENGER";
        AiShadowEvaluationService.EvaluationRequest request = passingRequest(new BigDecimal("0.04"));
        request.pairs().get(0).challenger().strategyReleaseId = 1L;
        AiShadowEvaluationService.EvaluationResult result = service.evaluate(request);
        assertThat(result.items().get(0).challengerPredictionId).isEqualTo(301L);
    }

    @Test
    void requestCannotForgePredictionProbabilityBecauseMetricsUseDatabaseRows() {
        Fixture fixture = fixture();
        AiShadowEvaluationService service = service(fixture);
        AiShadowEvaluationService.EvaluationRequest request = passingRequest(new BigDecimal("0.04"));
        request.pairs().get(0).challenger().probabilityUp = BigDecimal.ZERO;

        AiShadowEvaluationService.EvaluationResult result = service.evaluate(request);

        assertThat(result.evaluation().challengerCalibrationError).isEqualByComparingTo("0.200000");
    }

    @Test
    void writesCriticalDriftAlertIdempotentlyAndDoesNotCreatePromotionEvidence() {
        Fixture fixture = fixture();
        AiShadowEvaluationService service = service(fixture);
        AiShadowEvaluationService.EvaluationRequest request = passingRequest(new BigDecimal("0.30"));

        AiShadowEvaluationService.EvaluationResult first = service.evaluate(request);
        AiShadowEvaluationService.EvaluationResult second = service.evaluate(request);

        assertThat(first.evaluation().decisionStatus).isEqualTo("OBSERVING");
        assertThat(first.driftEvents()).singleElement().satisfies(event -> {
            assertThat(event.eventType).isEqualTo("SHADOW_DRIFT");
            assertThat(event.metricName).isEqualTo("FEATURE_DRIFT_SCORE");
            assertThat(event.severity).isEqualTo("CRITICAL");
            assertThat(event.shadowEvaluationId).isEqualTo(first.evaluation().id);
        });
        assertThat(second.driftEvents()).extracting(event -> event.id)
                .containsExactly(first.driftEvents().get(0).id);
        assertThat(fixture.driftEvents).hasSize(1);
        verify(fixture.governanceService, never())
                .assess(any(AiStrategyGovernanceService.AssessmentRequest.class));
    }

    @Test
    void observingWindowAllowsNullablePipelineDatasetAndGovernanceLinks() {
        Fixture fixture = fixture();
        AiShadowEvaluationService service = service(fixture);
        AiShadowEvaluationService.EvaluationRequest source = passingRequest(new BigDecimal("0.30"));
        AiShadowEvaluationService.EvaluationRequest request = new AiShadowEvaluationService.EvaluationRequest(
                source.userId(), null, null, source.championReleaseId(), source.challengerReleaseId(),
                source.windowStartDate(), source.windowEndDate(), source.evaluationVersion(),
                source.windowSampleCount(), source.pairs(), source.featureDriftScore(),
                source.thresholds(), null, source.evaluatedAt());

        AiShadowEvaluationService.EvaluationResult result = service.evaluate(request);

        assertThat(result.evaluation().pipelineRunId).isNull();
        assertThat(result.evaluation().trainingDatasetId).isNull();
        assertThat(result.evaluation().decisionStatus).isEqualTo("OBSERVING");
        assertThat(result.governanceAssessment()).isNull();
    }

    @Test
    void promotionEvidenceUsesComputedMetricsAndNeverConfirmsPromotion() {
        Fixture fixture = fixture();
        AiShadowEvaluationService service = service(fixture);
        ArgumentCaptor<AiStrategyGovernanceService.AssessmentRequest> captor =
                ArgumentCaptor.forClass(AiStrategyGovernanceService.AssessmentRequest.class);

        AiShadowEvaluationService.EvaluationResult result = service.evaluate(
                passingRequest(new BigDecimal("0.04")));

        verify(fixture.governanceService).assess(captor.capture());
        AiStrategyGovernanceService.AssessmentRequest evidence = captor.getValue();
        assertThat(evidence.shadowEvaluationId()).isEqualTo(result.evaluation().id);
        assertThat(evidence.currentChampionReleaseId()).isEqualTo(1L);
        assertThat(evidence.challengerReleaseId()).isEqualTo(2L);
        assertThat(evidence.evidence().sampleCount()).isEqualTo(2);
        assertThat(evidence.evidence().challengerExcessReturn()).isEqualByComparingTo("0.075000");
        assertThat(evidence.evidence().maxDrawdown()).isEqualByComparingTo("0.000000");
        assertThat(evidence.evidence().criticalDriftCount()).isZero();
        verify(fixture.governanceService, never())
                .confirmPromotion(any(AiStrategyGovernanceService.ConfirmationRequest.class));
    }

    @Test
    void shadowMetricsCannotCreateCandidateWhenGovernanceThresholdsAreNotMet() {
        Fixture fixture = fixture();
        AiShadowEvaluationService service = service(fixture);
        AiShadowEvaluationService.EvaluationRequest source = passingRequest(new BigDecimal("0.04"));
        AiShadowEvaluationService.GovernanceContext governance =
                new AiShadowEvaluationService.GovernanceContext(
                        source.governance().walkForwardRunId(), source.governance().backtestRunId(),
                        new AiStrategyGovernanceService.PromotionPolicy(
                                20, 2, 2, 6, new BigDecimal("-0.15"),
                                new BigDecimal("0.20"), new BigDecimal("0.01")),
                        source.governance().shadowTradingDays(), source.governance().tradeCount(),
                        5, source.governance().maxSingleStockContribution(),
                        source.governance().confidenceIntervalLowerExcessReturn(),
                        source.governance().policyVersion());
        AiShadowEvaluationService.EvaluationRequest request = new AiShadowEvaluationService.EvaluationRequest(
                source.userId(), source.pipelineRunId(), source.trainingDatasetId(),
                source.championReleaseId(), source.challengerReleaseId(), source.windowStartDate(),
                source.windowEndDate(), source.evaluationVersion(), source.windowSampleCount(),
                source.pairs(), source.featureDriftScore(), source.thresholds(), governance,
                source.evaluatedAt());

        AiShadowEvaluationService.EvaluationResult result = service.evaluate(request);

        assertThat(result.evaluation().decisionStatus).isEqualTo("OBSERVING");
        verify(fixture.governanceService).assess(any(AiStrategyGovernanceService.AssessmentRequest.class));
        verify(fixture.governanceService, never())
                .confirmPromotion(any(AiStrategyGovernanceService.ConfirmationRequest.class));
    }

    private static AiShadowEvaluationService service(Fixture fixture) {
        return new AiShadowEvaluationServiceImpl(
                fixture.evaluationMapper,
                fixture.itemMapper,
                fixture.releaseMapper,
                fixture.predictionMapper,
                fixture.labelMapper,
                fixture.driftMapper,
                fixture.governanceService,
                JSON);
    }

    private static AiShadowEvaluationService.EvaluationRequest passingRequest(BigDecimal driftScore) {
        AiPredictionV2 championOne = prediction(201L, 101L, 1L, "UP", "0.80", "0.70");
        AiPredictionV2 challengerOne = prediction(301L, 101L, 2L, "UP", "0.90", "0.80");
        AiPredictionV2 championTwo = prediction(202L, 102L, 1L, "DOWN", "0.30", "0.65");
        AiPredictionV2 challengerTwo = prediction(302L, 102L, 2L, "UP", "0.70", "0.75");
        List<AiShadowEvaluationService.PredictionPair> pairs = List.of(
                new AiShadowEvaluationService.PredictionPair(
                        championOne, challengerOne, label(401L, championOne, "0.10")),
                new AiShadowEvaluationService.PredictionPair(
                        championTwo, challengerTwo, label(402L, championTwo, "0.05")));
        return new AiShadowEvaluationService.EvaluationRequest(
                5L, 71L, 81L, 1L, 2L,
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                "SHADOW_V2_1", 3, pairs, driftScore,
                new AiShadowEvaluationService.ShadowThresholds(
                        new BigDecimal("0.60"), new BigDecimal("0.50"),
                        new BigDecimal("0.02"), new BigDecimal("-0.15"),
                        new BigDecimal("0.10"), new BigDecimal("0.25"), "SHADOW_DRIFT_V2_1"),
                new AiShadowEvaluationService.GovernanceContext(
                        91L, 92L,
                        new AiStrategyGovernanceService.PromotionPolicy(
                                20, 2, 2, 4, new BigDecimal("-0.15"),
                                new BigDecimal("0.20"), new BigDecimal("0.01")),
                        22, 2, 5, new BigDecimal("0.12"), new BigDecimal("0.02"),
                        "GOVERNANCE_V2_1"),
                LocalDateTime.of(2026, 7, 31, 16, 0));
    }

    private static AiShadowEvaluationService.EvaluationRequest copyWithPairs(
            AiShadowEvaluationService.EvaluationRequest source,
            List<AiShadowEvaluationService.PredictionPair> pairs
    ) {
        return new AiShadowEvaluationService.EvaluationRequest(
                source.userId(), source.pipelineRunId(), source.trainingDatasetId(),
                source.championReleaseId(), source.challengerReleaseId(),
                source.windowStartDate(), source.windowEndDate(), source.evaluationVersion(),
                source.windowSampleCount(), pairs, source.featureDriftScore(), source.thresholds(),
                source.governance(), source.evaluatedAt());
    }

    private static AiPredictionV2 prediction(
            Long id,
            Long sampleId,
            Long releaseId,
            String direction,
            String probabilityUp,
            String score
    ) {
        AiPredictionV2 value = new AiPredictionV2();
        value.id = id;
        value.userId = 5L;
        value.sampleId = sampleId;
        value.strategyReleaseId = releaseId;
        value.modelVersionId = releaseId == 1L ? 11L : 12L;
        value.stockCode = sampleId == 101L ? "600001" : "600002";
        value.tradeDate = sampleId == 101L
                ? LocalDate.of(2026, 7, 10) : LocalDate.of(2026, 7, 11);
        value.samplePhase = "AFTER_CLOSE";
        value.inferenceMode = releaseId == 1L ? "LIVE" : "SHADOW";
        value.inputFingerprint = "prediction-" + id;
        value.horizonDays = 3;
        value.probabilityUp = new BigDecimal(probabilityUp);
        value.calibratedConfidence = new BigDecimal("80");
        value.score = new BigDecimal(score).multiply(new BigDecimal("100"));
        value.action = "UP".equals(direction) ? "BUY" : "AVOID";
        value.actionBucket = value.action;
        value.targetDirection = direction;
        value.predictedAt = LocalDateTime.of(2026, 7, 10, 16, 0);
        PREDICTION_DB.putIfAbsent(value.id, JSON.convertValue(value, AiPredictionV2.class));
        return value;
    }

    private static AiLabelV2 label(Long id, AiPredictionV2 prediction, String excessReturn) {
        AiLabelV2 value = new AiLabelV2();
        value.id = id;
        value.userId = prediction.userId;
        value.predictionId = prediction.id;
        value.sampleId = prediction.sampleId;
        value.stockCode = prediction.stockCode;
        value.horizonDays = prediction.horizonDays;
        value.excessReturn = new BigDecimal(excessReturn);
        value.labelStatus = "VERIFIED";
        value.hitDirection = 1;
        LABEL_DB.putIfAbsent(value.id, JSON.convertValue(value, AiLabelV2.class));
        return value;
    }

    private static Fixture fixture() {
        PREDICTION_DB.clear();
        LABEL_DB.clear();
        AiShadowEvaluationMapper evaluationMapper = mock(AiShadowEvaluationMapper.class);
        AiShadowEvaluationItemMapper itemMapper = mock(AiShadowEvaluationItemMapper.class);
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        AiPredictionV2Mapper predictionMapper = mock(AiPredictionV2Mapper.class);
        AiLabelV2Mapper labelMapper = mock(AiLabelV2Mapper.class);
        AiDriftEventMapper driftMapper = mock(AiDriftEventMapper.class);
        AiStrategyGovernanceService governanceService = mock(AiStrategyGovernanceService.class);
        AiStrategyRelease champion = release(1L, "CHAMPION", "ACTIVE", 11L);
        AiStrategyRelease challenger = release(2L, "CHALLENGER", "SHADOW", 12L);
        List<AiShadowEvaluation> evaluations = new ArrayList<>();
        List<AiShadowEvaluationItem> items = new ArrayList<>();
        List<AiDriftEvent> driftEvents = new ArrayList<>();
        AtomicLong ids = new AtomicLong(1000L);

        when(releaseMapper.selectById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return id.equals(champion.id) ? champion : id.equals(challenger.id) ? challenger : null;
        });
        when(predictionMapper.selectBatchIds(anyList())).thenAnswer(invocation -> {
            List<Long> idsToLoad = invocation.getArgument(0);
            return idsToLoad.stream().map(PREDICTION_DB::get).filter(java.util.Objects::nonNull).toList();
        });
        when(labelMapper.selectBatchIds(anyList())).thenAnswer(invocation -> {
            List<Long> idsToLoad = invocation.getArgument(0);
            return idsToLoad.stream().map(LABEL_DB::get).filter(java.util.Objects::nonNull).toList();
        });
        when(evaluationMapper.insertImmutable(any(AiShadowEvaluation.class))).thenAnswer(invocation -> {
            AiShadowEvaluation candidate = invocation.getArgument(0);
            boolean exists = evaluations.stream().anyMatch(item -> sameWindow(item, candidate));
            if (!exists) {
                candidate.id = ids.incrementAndGet();
                evaluations.add(candidate);
                return 1;
            }
            return 0;
        });
        when(evaluationMapper.selectWindowForShare(
                anyLong(), anyLong(), anyLong(), any(LocalDate.class), any(LocalDate.class),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> evaluations.stream()
                .filter(item -> item.userId.equals(invocation.getArgument(0))
                        && item.championReleaseId.equals(invocation.getArgument(1))
                        && item.challengerReleaseId.equals(invocation.getArgument(2))
                        && item.windowStartDate.equals(invocation.getArgument(3))
                        && item.windowEndDate.equals(invocation.getArgument(4))
                        && item.evaluationVersion.equals(invocation.getArgument(5)))
                .findFirst().orElse(null));
        when(itemMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiShadowEvaluationItem> candidates = invocation.getArgument(0);
            for (AiShadowEvaluationItem candidate : candidates) {
                boolean exists = items.stream().anyMatch(item ->
                        item.shadowEvaluationId.equals(candidate.shadowEvaluationId)
                                && item.sampleId.equals(candidate.sampleId)
                                && item.horizonDays.equals(candidate.horizonDays));
                if (!exists) {
                    candidate.id = ids.incrementAndGet();
                    items.add(candidate);
                }
            }
            return candidates.size();
        });
        when(itemMapper.selectByEvaluationForShare(anyLong())).thenAnswer(invocation -> items.stream()
                .filter(item -> item.shadowEvaluationId.equals(invocation.getArgument(0))).toList());
        when(driftMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiDriftEvent> candidates = invocation.getArgument(0);
            for (AiDriftEvent candidate : candidates) {
                if (driftEvents.stream().noneMatch(item ->
                        item.eventFingerprint.equals(candidate.eventFingerprint))) {
                    candidate.id = ids.incrementAndGet();
                    driftEvents.add(candidate);
                }
            }
            return candidates.size();
        });
        when(driftMapper.selectByFingerprintsForShare(anyLong(), anyList())).thenAnswer(invocation -> {
            List<String> fingerprints = invocation.getArgument(1);
            return driftEvents.stream().filter(item -> fingerprints.contains(item.eventFingerprint)).toList();
        });
        when(governanceService.assess(any(AiStrategyGovernanceService.AssessmentRequest.class)))
                .thenAnswer(invocation -> {
                    AiStrategyGovernanceService.AssessmentRequest request = invocation.getArgument(0);
                    AiStrategyGovernanceEvent event = new AiStrategyGovernanceEvent();
                    event.id = ids.incrementAndGet();
                    event.shadowEvaluationId = request.shadowEvaluationId();
                    event.eventType = "PROMOTION_CANDIDATE_READY";
                    event.decisionStatus = "AWAITING_HUMAN_CONFIRMATION";
                    return new AiStrategyGovernanceService.Assessment(
                            event.decisionStatus, List.of(), challenger, event);
                });
        return new Fixture(evaluationMapper, itemMapper, releaseMapper, predictionMapper, labelMapper, driftMapper,
                governanceService, champion, challenger, evaluations, items, driftEvents);
    }

    private static boolean sameWindow(AiShadowEvaluation left, AiShadowEvaluation right) {
        return left.userId.equals(right.userId)
                && left.championReleaseId.equals(right.championReleaseId)
                && left.challengerReleaseId.equals(right.challengerReleaseId)
                && left.windowStartDate.equals(right.windowStartDate)
                && left.windowEndDate.equals(right.windowEndDate)
                && left.evaluationVersion.equals(right.evaluationVersion);
    }

    private static AiStrategyRelease release(
            Long id,
            String role,
            String status,
            Long modelVersionId
    ) {
        AiStrategyRelease value = new AiStrategyRelease();
        value.id = id;
        value.userId = 5L;
        value.modelVersionId = modelVersionId;
        value.releaseRole = role;
        value.status = status;
        return value;
    }

    private record Fixture(
            AiShadowEvaluationMapper evaluationMapper,
            AiShadowEvaluationItemMapper itemMapper,
            AiStrategyReleaseMapper releaseMapper,
            AiPredictionV2Mapper predictionMapper,
            AiLabelV2Mapper labelMapper,
            AiDriftEventMapper driftMapper,
            AiStrategyGovernanceService governanceService,
            AiStrategyRelease champion,
            AiStrategyRelease challenger,
            List<AiShadowEvaluation> evaluations,
            List<AiShadowEvaluationItem> items,
            List<AiDriftEvent> driftEvents
    ) {
    }
}
