package com.maogou.stock.service.impl.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.v2.AiFactorValueV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.mapper.v2.AiPredictionV2Mapper;
import com.maogou.stock.service.v2.AiModelInferenceService;
import com.maogou.stock.service.v2.AiPredictionEngineV2;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiPredictionEngineV2ImplTest {

    @Test
    void ranksEachTradingDayIndependentlyAndOnlyTopKCanBeRecommended() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        stubPersistence(mapper);
        AiPredictionEngineV2 engine = engine(mapper);
        LocalDate firstDate = LocalDate.of(2026, 7, 9);
        LocalDate secondDate = LocalDate.of(2026, 7, 10);

        List<AiPredictionV2> predictions = new ArrayList<>();
        predictions.addAll(engine.predictAndStore(batch(List.of(
                input(sample(1L, "000001", firstDate, "READY", "TRADABLE", "92"), "2.2"),
                input(sample(2L, "000002", firstDate, "READY", "TRADABLE", "92"), "0.8")
        ), 1)));
        predictions.addAll(engine.predictAndStore(batch(List.of(
                input(sample(3L, "000003", secondDate, "READY", "TRADABLE", "92"), "1.9"),
                input(sample(4L, "000004", secondDate, "READY", "TRADABLE", "92"), "0.5")
        ), 1)));

        for (LocalDate tradeDate : List.of(firstDate, secondDate)) {
            List<AiPredictionV2> day = predictions.stream()
                    .filter(item -> tradeDate.equals(item.tradeDate))
                    .toList();
            assertThat(day).hasSize(2);
            assertThat(day).extracting(item -> item.rankNo).containsExactlyInAnyOrder(1, 2);
            assertThat(day).filteredOn(item -> "RECOMMEND".equals(item.actionBucket)).hasSize(1);
            assertThat(day).filteredOn(item -> "RECOMMEND".equals(item.actionBucket))
                    .allMatch(item -> item.rankNo != null && item.rankNo == 1);
        }
    }

    @Test
    void lowConfidenceActivelyAbstainsInsteadOfMakingUpADirection() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        stubPersistence(mapper);
        AiPredictionEngineV2 engine = engine(mapper);
        AiSampleV2 sample = sample(8L, "600519", LocalDate.of(2026, 7, 10), "PARTIAL", "TRADABLE", "66");
        List<AiFactorValueV2> missingFactors = List.of(missingFactor(sample.id, "MOMENTUM_RETURN_3D"));

        AiPredictionV2 prediction = engine.predictAndStore(batch(
                List.of(new AiPredictionEngineV2.PredictionInput(sample, missingFactors)), 3)).get(0);

        assertThat(prediction.action).isEqualTo("WATCH");
        assertThat(prediction.actionBucket).isEqualTo("ABSTAIN");
        assertThat(prediction.abstainReason).contains("LOW_CONFIDENCE");
        assertThat(prediction.targetDirection).isEqualTo("SIDEWAYS");
    }

    @Test
    void staleOrUntradableSamplesAreUnavailableAndNeverReceiveARank() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        stubPersistence(mapper);
        AiPredictionEngineV2 engine = engine(mapper);
        AiSampleV2 stale = sample(8L, "600519", LocalDate.of(2026, 7, 10), "UNAVAILABLE", "DATA_UNAVAILABLE", "35");

        AiPredictionV2 prediction = engine.predictAndStore(
                batch(List.of(input(stale, "3.0")), 3)).get(0);

        assertThat(prediction.action).isEqualTo("UNAVAILABLE");
        assertThat(prediction.actionBucket).isEqualTo("UNAVAILABLE");
        assertThat(prediction.rankNo).isNull();
        assertThat(prediction.abstainReason).contains("DATA_UNAVAILABLE");
    }

    @Test
    void ruleBaselineOutputsAuditableProbabilitiesReturnsRiskAndReasons() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        stubPersistence(mapper);
        AiPredictionEngineV2 engine = engine(mapper);
        AiSampleV2 sample = sample(8L, "600519", LocalDate.of(2026, 7, 10), "READY", "TRADABLE", "95");

        AiPredictionV2 prediction = engine.predictAndStore(
                batch(List.of(input(sample, "2.0")), 3)).get(0);

        assertThat(prediction.expectedReturn).isNotNull();
        assertThat(prediction.expectedExcessReturn).isNotNull();
        assertThat(prediction.riskScore).isBetween(BigDecimal.ZERO, new BigDecimal("100"));
        assertThat(prediction.probabilityUp.add(prediction.probabilityDown))
                .isEqualByComparingTo("1.000000");
        assertThat(prediction.reasonJson).contains("MOMENTUM_RETURN_3D");
        assertThat(prediction.inputFingerprint).hasSize(64);
    }

    @Test
    void repeatedBusinessKeyReturnsTheImmutablePersistedPredictionWithoutUpdates() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        stubPersistence(mapper);
        AiPredictionEngineV2 engine = engine(mapper);
        AiSampleV2 sample = sample(8L, "600519", LocalDate.of(2026, 7, 10), "READY", "TRADABLE", "95");
        AiPredictionEngineV2.PredictionBatch batch = batch(List.of(input(sample, "2.0")), 3);

        AiPredictionV2 first = engine.predictAndStore(batch).get(0);
        AiPredictionV2 second = engine.predictAndStore(batch).get(0);

        assertThat(second.id).isEqualTo(first.id);
        assertThat(second.inputFingerprint).isEqualTo(first.inputFingerprint);
        verify(mapper, never()).updateById(org.mockito.ArgumentMatchers.any(AiPredictionV2.class));
    }

    @Test
    void rejectsAnImmutablePredictionCollisionWithDifferentContent() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        when(mapper.insertBatchImmutable(anyList())).thenReturn(1);
        AiPredictionV2 conflicting = new AiPredictionV2();
        conflicting.id = 99L;
        conflicting.userId = 5L;
        conflicting.idempotencyKey = "RULE_BASELINE:10:BASELINE:8:3:K3:POLICY_V2_1";
        conflicting.inputFingerprint = "different";
        when(mapper.selectByIdempotencyKeysForShare(anyLong(), anyList())).thenReturn(List.of(conflicting));
        AiPredictionEngineV2 engine = engine(mapper);
        AiSampleV2 sample = sample(8L, "600519", LocalDate.of(2026, 7, 10), "READY", "TRADABLE", "95");

        assertThatThrownBy(() -> engine.predictAndStore(batch(List.of(input(sample, "2.0")), 3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可变预测冲突");
    }

    @Test
    void topKIsPartOfTheImmutableStrategyIdentity() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        stubPersistence(mapper);
        AiPredictionEngineV2 engine = engine(mapper);
        LocalDate tradeDate = LocalDate.of(2026, 7, 10);
        List<AiPredictionEngineV2.PredictionInput> inputs = List.of(
                input(sample(1L, "000001", tradeDate, "READY", "TRADABLE", "92"), "2.2"),
                input(sample(2L, "000002", tradeDate, "READY", "TRADABLE", "92"), "1.9"));

        List<AiPredictionV2> topOne = engine.predictAndStore(batch(inputs, 1));
        List<AiPredictionV2> topTwo = engine.predictAndStore(batch(inputs, 2));

        assertThat(topOne).filteredOn(item -> "RECOMMEND".equals(item.actionBucket)).hasSize(1);
        assertThat(topTwo).filteredOn(item -> "RECOMMEND".equals(item.actionBucket)).hasSize(2);
        assertThat(topTwo).extracting(item -> item.id)
                .doesNotContainAnyElementsOf(topOne.stream().map(item -> item.id).toList());
    }

    @Test
    void rejectsMixedTradingDatesPhasesOrUniverseVersionsInOneRankingBatch() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        AiPredictionEngineV2 engine = engine(mapper);
        AiSampleV2 first = sample(1L, "000001", LocalDate.of(2026, 7, 9), "READY", "TRADABLE", "92");
        AiSampleV2 second = sample(2L, "000002", LocalDate.of(2026, 7, 10), "READY", "TRADABLE", "92");

        assertThatThrownBy(() -> engine.predictAndStore(batch(List.of(input(first, "2"), input(second, "2")), 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("同一交易日");
    }

    @Test
    void rejectsFactorsThatDoNotBelongToTheInputSample() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        AiPredictionEngineV2 engine = engine(mapper);
        AiSampleV2 sample = sample(8L, "600519", LocalDate.of(2026, 7, 10), "READY", "TRADABLE", "95");
        AiFactorValueV2 wrong = factor(sample.id + 1, sample.stockCode, "MOMENTUM_RETURN_3D", "2");

        assertThatThrownBy(() -> engine.predictAndStore(batch(
                List.of(new AiPredictionEngineV2.PredictionInput(sample, List.of(wrong))), 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("因子血缘");
    }

    @Test
    void ruleBaselineRequiresNullModelAndNeverAcceptsZeroParentIds() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        AiPredictionEngineV2 engine = engine(mapper);
        AiSampleV2 sample = sample(8L, "600519", LocalDate.of(2026, 7, 10), "READY", "TRADABLE", "95");
        AiPredictionEngineV2.PredictionBatch invalid = new AiPredictionEngineV2.PredictionBatch(
                List.of(input(sample, "2")), 10L, 0L, 3, 1, "RULE_BASELINE",
                LocalDateTime.of(2026, 7, 10, 16, 5));

        assertThatThrownBy(() -> engine.predictAndStore(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modelVersionId");
    }

    @Test
    void validatedModelInferenceDrivesScoreInsteadOfReusingRuleScore() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        stubPersistence(mapper);
        AiModelInferenceService inferenceService = mock(AiModelInferenceService.class);
        when(inferenceService.infer(
                org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.any(AiSampleV2.class),
                anyList())).thenReturn(new AiModelInferenceService.ModelInference(
                101L, "ranker", "v1", "artifact-sha", 1.4f, 0.80f, 16, 16));
        AiPredictionEngineV2 engine = engine(mapper, inferenceService);
        AiSampleV2 sample = sample(8L, "600519", LocalDate.of(2026, 7, 10), "READY", "TRADABLE", "95");

        AiPredictionV2 prediction = engine.predictAndStore(modelBatch(List.of(input(sample, "0.2")), 1)).get(0);

        assertThat(prediction.score).isEqualByComparingTo("80.0000");
        assertThat(prediction.probabilityUp).isEqualByComparingTo("0.800000");
        assertThat(prediction.inferenceMode).isEqualTo("CHAMPION");
        assertThat(prediction.reasonJson).contains("artifact-sha").contains("CALIBRATED_DIRECTION_PROBABILITY_PROXY");
    }

    @Test
    void modelFailureBecomesUnavailableAndNeverFallsBackToRuleRecommendation() {
        AiPredictionV2Mapper mapper = mock(AiPredictionV2Mapper.class);
        stubPersistence(mapper);
        AiModelInferenceService inferenceService = mock(AiModelInferenceService.class);
        when(inferenceService.infer(
                org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.any(AiSampleV2.class),
                anyList())).thenThrow(new IllegalStateException("ONNX artifact missing"));
        AiPredictionEngineV2 engine = engine(mapper, inferenceService);
        AiSampleV2 sample = sample(8L, "600519", LocalDate.of(2026, 7, 10), "READY", "TRADABLE", "95");

        AiPredictionV2 prediction = engine.predictAndStore(modelBatch(List.of(input(sample, "3.0")), 1)).get(0);

        assertThat(prediction.action).isEqualTo("UNAVAILABLE");
        assertThat(prediction.actionBucket).isEqualTo("UNAVAILABLE");
        assertThat(prediction.rankNo).isNull();
        assertThat(prediction.abstainReason).isEqualTo("MODEL_INFERENCE_UNAVAILABLE");
        assertThat(prediction.reasonJson).contains("ONNX artifact missing");
    }

    private static AiPredictionEngineV2 engine(AiPredictionV2Mapper mapper) {
        return engine(mapper, mock(AiModelInferenceService.class));
    }

    private static AiPredictionEngineV2 engine(
            AiPredictionV2Mapper mapper,
            AiModelInferenceService inferenceService
    ) {
        return new AiPredictionEngineV2Impl(
                mapper,
                new DefaultAiDecisionPolicy(),
                inferenceService,
                new ObjectMapper().findAndRegisterModules());
    }

    private static AiPredictionEngineV2.PredictionBatch batch(
            List<AiPredictionEngineV2.PredictionInput> inputs,
            int topK
    ) {
        return new AiPredictionEngineV2.PredictionBatch(
                inputs, 10L, null, 3, topK, "RULE_BASELINE", LocalDateTime.of(2026, 7, 10, 16, 5));
    }

    private static AiPredictionEngineV2.PredictionBatch modelBatch(
            List<AiPredictionEngineV2.PredictionInput> inputs,
            int topK
    ) {
        return new AiPredictionEngineV2.PredictionBatch(
                inputs, 10L, 101L, 3, topK, "CHAMPION", LocalDateTime.of(2026, 7, 10, 16, 5));
    }

    private static AiPredictionEngineV2.PredictionInput input(AiSampleV2 sample, String strength) {
        List<String> codes = List.of(
                "MOMENTUM_RETURN_3D", "MOMENTUM_RETURN_5D", "TREND_MA5_DISTANCE",
                "TREND_MA20_DISTANCE", "VOLUME_RATIO_5D", "VOLATILITY_10D",
                "LIQUIDITY_AVG_AMOUNT_5D", "FUNDAMENTAL_PE", "FUNDAMENTAL_PB",
                "FUNDAMENTAL_ROE", "FUNDAMENTAL_REVENUE_GROWTH", "FUNDAMENTAL_PROFIT_GROWTH",
                "FUNDAMENTAL_DEBT_RATIO", "MARKET_RELATIVE_STRENGTH",
                "SECTOR_RELATIVE_STRENGTH", "NEWS_SENTIMENT");
        List<AiFactorValueV2> factors = codes.stream()
                .map(code -> {
                    boolean inverse = code.contains("VOLATILITY") || code.contains("DEBT_RATIO")
                            || code.contains("FUNDAMENTAL_PE") || code.contains("FUNDAMENTAL_PB");
                    AiFactorValueV2 factor = factor(
                            sample.id, sample.stockCode, code, inverse ? "-" + strength : strength);
                    if (code.contains("FUNDAMENTAL_PE") || code.contains("FUNDAMENTAL_PB")) {
                        factor.rawValue = new BigDecimal("10");
                    }
                    return factor;
                })
                .toList();
        return new AiPredictionEngineV2.PredictionInput(sample, factors);
    }

    private static AiSampleV2 sample(
            Long id,
            String stockCode,
            LocalDate tradeDate,
            String qualityStatus,
            String tradableStatus,
            String qualityScore
    ) {
        AiSampleV2 sample = new AiSampleV2();
        sample.id = id;
        sample.userId = 5L;
        sample.dataBatchId = 20L;
        sample.stockCode = stockCode;
        sample.stockName = stockCode;
        sample.tradeDate = tradeDate;
        sample.samplePhase = "AFTER_CLOSE";
        sample.asOfTime = tradeDate.atTime(16, 0);
        sample.universeCode = "WATCHLIST";
        sample.universeVersion = "WATCHLIST-20260710";
        sample.marketRegime = "RANGE";
        sample.dataQualityScore = new BigDecimal(qualityScore);
        sample.qualityStatus = qualityStatus;
        sample.tradableStatus = tradableStatus;
        sample.featureVersion = "POINT_IN_TIME_V2.1";
        sample.sourceFingerprint = "sample-" + id;
        return sample;
    }

    private static AiFactorValueV2 factor(
            Long sampleId,
            String stockCode,
            String code,
            String normalizedValue
    ) {
        AiFactorValueV2 factor = new AiFactorValueV2();
        factor.id = sampleId * 10;
        factor.userId = 5L;
        factor.sampleId = sampleId;
        factor.stockCode = stockCode;
        factor.factorCode = code;
        factor.factorVersion = "2.0.0";
        factor.factorGroup = "MOMENTUM";
        factor.rawValue = new BigDecimal(normalizedValue);
        factor.normalizedValue = new BigDecimal(normalizedValue);
        factor.missing = 0;
        factor.inputFingerprint = "factor-" + sampleId + "-" + code;
        return factor;
    }

    private static AiFactorValueV2 missingFactor(Long sampleId, String code) {
        AiFactorValueV2 factor = factor(sampleId, "600519", code, "0");
        factor.rawValue = null;
        factor.normalizedValue = null;
        factor.missing = 1;
        factor.missingReason = "缺失";
        return factor;
    }

    private static void stubPersistence(AiPredictionV2Mapper mapper) {
        List<AiPredictionV2> database = new ArrayList<>();
        AtomicLong ids = new AtomicLong(200);
        when(mapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiPredictionV2> batch = invocation.getArgument(0);
            for (AiPredictionV2 candidate : batch) {
                AiPredictionV2 existing = database.stream()
                        .filter(item -> item.userId.equals(candidate.userId)
                                && item.idempotencyKey.equals(candidate.idempotencyKey))
                        .findFirst()
                        .orElse(null);
                if (existing == null) {
                    candidate.id = ids.incrementAndGet();
                    database.add(candidate);
                }
            }
            return batch.size();
        });
        when(mapper.selectByIdempotencyKeysForShare(anyLong(), anyList())).thenAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            List<String> keys = invocation.getArgument(1);
            return database.stream()
                    .filter(item -> userId.equals(item.userId) && keys.contains(item.idempotencyKey))
                    .toList();
        });
    }
}
