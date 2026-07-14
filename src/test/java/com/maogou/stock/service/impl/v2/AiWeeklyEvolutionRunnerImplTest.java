package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiFactorPerformance;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiPortfolioBacktestPosition;
import com.maogou.stock.domain.entity.research.AiPortfolioBacktestRun;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.domain.entity.research.AiWalkForwardFold;
import com.maogou.stock.domain.entity.research.AiWalkForwardRun;
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
import com.maogou.stock.service.research.AiFactorPerformanceService;
import com.maogou.stock.service.research.AiPortfolioBacktestService;
import com.maogou.stock.service.research.AiShadowEvaluationService;
import com.maogou.stock.service.research.AiWalkForwardService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiWeeklyEvolutionRunnerImplTest {

    @Test
    void skipsCleanlyWhenNoChampionOrChallengerExists() {
        Fixture noChampion = fixture();
        when(noChampion.releaseMapper.selectOne(any())).thenReturn(null);

        AiEvolutionAutomationService.CycleResult first = runner(noChampion).run(5L, now());

        assertThat(first.status()).isEqualTo("SKIPPED");
        assertThat(first.message()).contains("Champion");
        Fixture noChallenger = fixture();
        when(noChallenger.releaseMapper.selectOne(any())).thenReturn(champion());
        when(noChallenger.releaseMapper.selectShadowChallengers(5L)).thenReturn(List.of());

        AiEvolutionAutomationService.CycleResult second = runner(noChallenger).run(5L, now());

        assertThat(second.status()).isEqualTo("SKIPPED");
        assertThat(second.message()).contains("SHADOW Challenger");
    }

    @Test
    void evaluatesOnlySameSampleChampionAndChallengerPairsWithoutAutoPromotion() {
        Fixture fixture = fixture();
        AiStrategyRelease champion = champion();
        AiStrategyRelease challenger = challenger();
        when(fixture.releaseMapper.selectOne(any())).thenReturn(champion);
        when(fixture.releaseMapper.selectShadowChallengers(5L)).thenReturn(List.of(challenger));
        AiPrediction championPrediction = prediction(101L, 1001L, 11L, null, "CHAMPION", "0.55");
        AiPrediction challengerPrediction = prediction(
                201L, 1001L, 12L, 81L, "CHALLENGER_SHADOW", "0.68");
        when(fixture.predictionMapper.selectList(any()))
                .thenReturn(List.of(championPrediction), List.of(challengerPrediction));
        AiSampleLabel label = label(championPrediction);
        when(fixture.labelMapper.selectList(any())).thenReturn(List.of(label));
        when(fixture.modelMapper.selectById(81L)).thenReturn(null);

        AiEvolutionAutomationService.CycleResult result = runner(fixture).run(5L, now());

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.successCount()).isEqualTo(1);
        ArgumentCaptor<AiShadowEvaluationService.EvaluationRequest> request =
                ArgumentCaptor.forClass(AiShadowEvaluationService.EvaluationRequest.class);
        verify(fixture.shadowService).evaluate(request.capture());
        assertThat(request.getValue().pairs()).singleElement().satisfies(pair -> {
            assertThat(pair.champion().id).isEqualTo(101L);
            assertThat(pair.challenger().id).isEqualTo(201L);
            assertThat(pair.label().id).isEqualTo(301L);
        });
        assertThat(request.getValue().governance()).isNull();
        verify(fixture.walkForwardService, never()).runAndStore(any());
        verify(fixture.backtestService, never()).runAndStore(any());
    }

    @Test
    void challengerWithoutPairedSamplesIsSkippedRatherThanReportedAsSystemFailure() {
        Fixture fixture = fixture();
        when(fixture.releaseMapper.selectOne(any())).thenReturn(champion());
        when(fixture.releaseMapper.selectShadowChallengers(5L)).thenReturn(List.of(challenger()));
        when(fixture.predictionMapper.selectList(any())).thenReturn(
                List.of(prediction(101L, 1001L, 11L, null, "CHAMPION", "0.55")),
                List.of(prediction(202L, 9999L, 12L, 81L, "CHALLENGER_SHADOW", "0.68")));

        AiEvolutionAutomationService.CycleResult result = runner(fixture).run(5L, now());

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.failedCount()).isZero();
        assertThat(result.message()).contains("尚未形成");
        verify(fixture.shadowService, never()).evaluate(any());
    }

    @Test
    void runsWalkForwardAndRealIndexBacktestWhenThirtyTradingDaysOfEvidenceExist() {
        Fixture fixture = fixture();
        AiStrategyRelease challenger = challenger();
        when(fixture.releaseMapper.selectOne(any())).thenReturn(champion());
        when(fixture.releaseMapper.selectShadowChallengers(5L)).thenReturn(List.of(challenger));
        List<LocalDate> dates = IntStream.range(0, 35)
                .mapToObj(index -> LocalDate.of(2026, 5, 1).plusDays(index))
                .toList();
        List<AiPrediction> championPredictions = new ArrayList<>();
        List<AiPrediction> challengerPredictions = new ArrayList<>();
        List<AiSampleLabel> labels = new ArrayList<>();
        List<AiFactorValue> factors = new ArrayList<>();
        List<AiSample> samples = new ArrayList<>();
        for (int index = 0; index < dates.size(); index++) {
            long sampleId = 1000L + index;
            AiPrediction championPrediction = predictionAt(
                    2000L + index, sampleId, 11L, null, "CHAMPION", "0.55", dates.get(index));
            AiPrediction challengerPrediction = predictionAt(
                    3000L + index, sampleId, 12L, 81L, "CHALLENGER_SHADOW", "0.68", dates.get(index));
            championPredictions.add(championPrediction);
            challengerPredictions.add(challengerPrediction);
            AiSampleLabel label = label(championPrediction);
            label.id = 4000L + index;
            label.exitTradeDate = dates.get(index).plusDays(3);
            label.verifiedAt = label.exitTradeDate.atTime(16, 0);
            label.labelVersion = "LABEL/1.0.0";
            label.inputFingerprint = "label-" + label.id;
            label.executionStatus = "EXECUTED";
            label.executionStatus = "EXECUTED";
            labels.add(label);
            AiFactorValue factor = new AiFactorValue();
            factor.id = 5000L + index;
            factor.userId = 5L;
            factor.sampleId = sampleId;
            factor.stockCode = "600519";
            factor.factorCode = "MOMENTUM_RETURN_5D";
            factor.factorVersion = "2.0.0";
            factor.normalizedValue = new BigDecimal("0.2");
            factor.missing = 0;
            factor.inputFingerprint = "factor-" + sampleId;
            factors.add(factor);
            AiSample sample = new AiSample();
            sample.id = sampleId;
            sample.userId = 5L;
            sample.stockCode = "600519";
            sample.stockName = "贵州茅台";
            sample.tradeDate = dates.get(index);
            sample.marketRegime = "UNKNOWN";
            sample.sourceFingerprint = "sample-" + sampleId;
            samples.add(sample);
        }
        when(fixture.predictionMapper.selectList(any()))
                .thenReturn(championPredictions, challengerPredictions);
        when(fixture.labelMapper.selectList(any())).thenReturn(labels);
        AiModelVersion model = new AiModelVersion();
        model.id = 81L;
        model.trainingDatasetId = 71L;
        when(fixture.modelMapper.selectById(81L)).thenReturn(model);
        when(fixture.factorMapper.selectList(any())).thenReturn(factors);
        when(fixture.sampleMapper.selectList(any())).thenReturn(samples);
        AiFactorPerformance performance = new AiFactorPerformance();
        performance.id = 5501L;
        when(fixture.factorPerformanceService.evaluateAndStore(any())).thenReturn(
                new AiFactorPerformanceService.EvaluationResult(
                        List.of(performance), List.of(), List.of("MOMENTUM_RETURN_5D")));
        KlineSeriesSnapshot stockSeries = series("600519", LocalDate.of(2026, 4, 30), 42);
        KlineSeriesSnapshot benchmarkSeries = series("000300.SH", LocalDate.of(2026, 4, 30), 42);
        when(fixture.marketDataService.klineAt("600519", "day", 240, now())).thenReturn(stockSeries);
        when(fixture.marketDataService.klineAt("000300.SH", "day", 240, now())).thenReturn(benchmarkSeries);
        AiWalkForwardRun walkRun = new AiWalkForwardRun();
        walkRun.id = 601L;
        walkRun.aggregateMetricsJson = "{\"confidenceInterval\":{\"lower95\":0.02}}";
        when(fixture.walkForwardService.runAndStore(any())).thenReturn(
                new AiWalkForwardService.WalkForwardResult(
                        walkRun, List.of(new AiWalkForwardFold(), new AiWalkForwardFold(),
                        new AiWalkForwardFold()), List.of(), List.of()));
        AiPortfolioBacktestRun backtestRun = new AiPortfolioBacktestRun();
        backtestRun.id = 701L;
        backtestRun.tradeCount = 220;
        AiPortfolioBacktestPosition position = new AiPortfolioBacktestPosition();
        position.stockCode = "600519";
        position.returnContribution = new BigDecimal("0.03");
        when(fixture.backtestService.runAndStore(any())).thenReturn(
                new AiPortfolioBacktestService.BacktestResult(
                        backtestRun, List.of(), List.of(), List.of(position)));

        AiEvolutionAutomationService.CycleResult result = runner(fixture).run(5L, now());

        assertThat(result.status()).isEqualTo("SUCCESS");
        ArgumentCaptor<AiFactorPerformanceService.PerformanceBatch> factorBatch =
                ArgumentCaptor.forClass(AiFactorPerformanceService.PerformanceBatch.class);
        verify(fixture.factorPerformanceService).evaluateAndStore(factorBatch.capture());
        assertThat(factorBatch.getValue().factorVersion()).isEqualTo("2.0.0");
        assertThat(factorBatch.getValue().horizonDays()).isEqualTo(3);
        assertThat(factorBatch.getValue().observations()).hasSize(35);
        assertThat(factorBatch.getValue().baselineObservations()).isEmpty();
        assertThat(factorBatch.getValue().windowEndDate()).isEqualTo(LocalDate.of(2026, 6, 4));
        ArgumentCaptor<AiWalkForwardService.WalkForwardRequest> walkRequest =
                ArgumentCaptor.forClass(AiWalkForwardService.WalkForwardRequest.class);
        verify(fixture.walkForwardService).runAndStore(walkRequest.capture());
        assertThat(walkRequest.getValue().observations()).hasSize(35);
        assertThat(walkRequest.getValue().benchmarkCode()).isEqualTo("000300");
        assertThat(walkRequest.getValue().foldCount()).isEqualTo(3);
        assertThat(walkRequest.getValue().benchmark()).first().satisfies(point -> {
            assertThat(point.tradeDate()).isEqualTo(LocalDate.of(2026, 5, 1));
            assertThat(point.dailyReturn()).isEqualByComparingTo(
                    new BigDecimal("102").divide(new BigDecimal("101"), 10, java.math.RoundingMode.HALF_UP)
                            .subtract(BigDecimal.ONE));
        });
        ArgumentCaptor<AiPortfolioBacktestService.BacktestRequest> backtestRequest =
                ArgumentCaptor.forClass(AiPortfolioBacktestService.BacktestRequest.class);
        verify(fixture.backtestService).runAndStore(backtestRequest.capture());
        assertThat(backtestRequest.getValue().benchmarkCode()).isEqualTo("000300");
        assertThat(backtestRequest.getValue().walkForwardRunId()).isEqualTo(601L);
        assertThat(backtestRequest.getValue().bars()).isNotEmpty();
        ArgumentCaptor<AiShadowEvaluationService.EvaluationRequest> evaluationRequest =
                ArgumentCaptor.forClass(AiShadowEvaluationService.EvaluationRequest.class);
        verify(fixture.shadowService).evaluate(evaluationRequest.capture());
        assertThat(evaluationRequest.getValue().governance()).isNotNull();
        assertThat(evaluationRequest.getValue().governance().walkForwardRunId()).isEqualTo(601L);
        assertThat(evaluationRequest.getValue().governance().backtestRunId()).isEqualTo(701L);
    }

    private static AiWeeklyEvolutionRunnerImpl runner(Fixture fixture) {
        return new AiWeeklyEvolutionRunnerImpl(
                fixture.properties, fixture.releaseMapper, fixture.modelMapper,
                fixture.predictionMapper, fixture.labelMapper, fixture.factorMapper,
                fixture.sampleMapper, fixture.marketDataService, fixture.factorPerformanceService,
                fixture.walkForwardService,
                fixture.backtestService, fixture.shadowService,
                new ObjectMapper().findAndRegisterModules());
    }

    private static Fixture fixture() {
        return new Fixture(
                new AppProperties(),
                mock(AiStrategyReleaseMapper.class),
                mock(AiModelVersionMapper.class),
                mock(AiPredictionMapper.class),
                mock(AiSampleLabelMapper.class),
                mock(AiFactorValueMapper.class),
                mock(AiSampleMapper.class),
                mock(MarketDataService.class),
                mock(AiFactorPerformanceService.class),
                mock(AiWalkForwardService.class),
                mock(AiPortfolioBacktestService.class),
                mock(AiShadowEvaluationService.class));
    }

    private static AiStrategyRelease champion() {
        AiStrategyRelease value = new AiStrategyRelease();
        value.id = 11L;
        value.userId = 5L;
        value.releaseRole = "CHAMPION";
        value.status = "ACTIVE";
        return value;
    }

    private static AiStrategyRelease challenger() {
        AiStrategyRelease value = new AiStrategyRelease();
        value.id = 12L;
        value.userId = 5L;
        value.modelVersionId = 81L;
        value.releaseRole = "CHALLENGER";
        value.status = "SHADOW";
        return value;
    }

    private static AiPrediction prediction(
            Long id,
            Long sampleId,
            Long releaseId,
            Long modelId,
            String mode,
            String probability
    ) {
        return predictionAt(id, sampleId, releaseId, modelId, mode, probability,
                LocalDate.of(2026, 7, 10));
    }

    private static AiPrediction predictionAt(
            Long id,
            Long sampleId,
            Long releaseId,
            Long modelId,
            String mode,
            String probability,
            LocalDate tradeDate
    ) {
        AiPrediction value = new AiPrediction();
        value.id = id;
        value.userId = 5L;
        value.sampleId = sampleId;
        value.strategyReleaseId = releaseId;
        value.modelVersionId = modelId;
        value.stockCode = "600519";
        value.tradeDate = tradeDate;
        value.samplePhase = "AFTER_CLOSE";
        value.inferenceMode = mode;
        value.inputFingerprint = "prediction-" + id;
        value.horizonDays = 3;
        value.probabilityUp = new BigDecimal(probability);
        value.calibratedConfidence = new BigDecimal("70");
        value.score = new BigDecimal("68");
        value.action = "BUY";
        value.actionBucket = "RECOMMEND";
        value.targetDirection = "UP";
        value.predictedAt = now();
        return value;
    }

    private static AiSampleLabel label(AiPrediction prediction) {
        AiSampleLabel value = new AiSampleLabel();
        value.id = 301L;
        value.sampleId = prediction.sampleId;
        value.stockCode = prediction.stockCode;
        value.horizonTradingDays = 3;
        value.inputFingerprint = "label-301";
        value.netReturn = new BigDecimal("0.05");
        value.benchmarkReturn = new BigDecimal("0.01");
        value.excessReturn = new BigDecimal("0.04");
        value.labelStatus = "MATURED";
        value.executionStatus = "EXECUTED";
        value.labelVersion = "LABEL/1.0.0";
        value.exitTradeDate = LocalDate.of(2026, 7, 13);
        value.verifiedAt = now();
        return value;
    }

    private static KlineSeriesSnapshot series(String code, LocalDate start, int count) {
        List<KlinePointResponse> points = IntStream.range(0, count).mapToObj(index -> {
            BigDecimal open = BigDecimal.valueOf(100 + index);
            BigDecimal close = open.add(BigDecimal.ONE);
            return new KlinePointResponse(
                    start.plusDays(index), open, close, open.subtract(BigDecimal.ONE),
                    close.add(BigDecimal.ONE), 100000L, new BigDecimal("10000000"));
        }).toList();
        return KlineSeriesSnapshot.create(code, "day", "NONE", "TEST", now(), now(), points);
    }

    private static LocalDateTime now() {
        return LocalDateTime.of(2026, 7, 13, 18, 0);
    }

    private record Fixture(
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
            AiShadowEvaluationService shadowService
    ) {
    }
}
