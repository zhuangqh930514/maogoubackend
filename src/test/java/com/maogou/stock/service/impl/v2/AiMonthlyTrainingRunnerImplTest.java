package com.maogou.stock.service.impl.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.v2.AiModelVersion;
import com.maogou.stock.domain.entity.v2.AiStrategyRelease;
import com.maogou.stock.domain.entity.v2.AiTrainingDataset;
import com.maogou.stock.domain.entity.v2.AiTrainingSourceSummary;
import com.maogou.stock.mapper.v2.AiStrategyReleaseMapper;
import com.maogou.stock.mapper.v2.AiTrainingDatasetItemMapper;
import com.maogou.stock.service.v2.AiEvolutionAutomationService;
import com.maogou.stock.service.v2.AiModelTrainer;
import com.maogou.stock.service.v2.AiTrainingDatasetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiMonthlyTrainingRunnerImplTest {

    @TempDir
    Path temporary;

    @Test
    void insufficientMatureSamplesAreSkippedBeforeCreatingArtifacts() {
        Fixture fixture = fixture();
        fixture.properties.getScheduler().setMonthlyMinimumSamples(1000);
        AiTrainingSourceSummary summary = summary(120);
        when(fixture.datasetItemMapper.selectDominantSourceSummary(anyLong(), any(), any(), any()))
                .thenReturn(summary);
        AiMonthlyTrainingRunnerImpl runner = runner(fixture);

        AiEvolutionAutomationService.CycleResult result = runner.run(5L, now());

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("120 / 1000");
        verify(fixture.datasetItemMapper).selectDominantSourceSummary(
                5L, "LABEL_V2.2", 3, now());
        verify(fixture.datasetService, never()).buildDataset(any());
        verify(fixture.modelTrainer, never()).train(any());
    }

    @Test
    void validatedTrainingArtifactsCreateAnIdempotentShadowChallenger() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.getScheduler().setMonthlyMinimumSamples(10);
        fixture.properties.getScheduler().setTrainingArtifactRoot(temporary.toString());
        when(fixture.datasetItemMapper.selectDominantSourceSummary(anyLong(), any(), any(), any()))
                .thenReturn(summary(30));
        when(fixture.datasetItemMapper.selectEligibleTradeDates(
                anyLong(), any(), any(), any(), any(), any())).thenReturn(dates(20));
        AiTrainingDataset dataset = new AiTrainingDataset();
        dataset.id = 71L;
        dataset.rowCount = 30;
        dataset.status = "READY";
        when(fixture.datasetService.buildDataset(any())).thenReturn(
                new AiTrainingDatasetService.DatasetBuildResult(dataset, List.of()));
        Path model = Files.writeString(temporary.resolve("model.joblib"), "model");
        Path onnx = Files.writeString(temporary.resolve("model.onnx"), "onnx");
        Path manifest = Files.writeString(temporary.resolve("manifest.json"), "{}");
        Path metrics = Files.writeString(temporary.resolve("metrics.json"), metricsJson());
        when(fixture.modelTrainer.train(any())).thenReturn(new AiModelTrainer.TrainingArtifacts(
                "LOGISTIC_REGRESSION", model, onnx, manifest, metrics));
        AiModelVersion validated = new AiModelVersion();
        validated.id = 81L;
        validated.userId = 5L;
        validated.versionNo = "20260713";
        validated.featureVersion = "POINT_IN_TIME_V2.1";
        validated.status = "VALIDATED";
        when(fixture.datasetService.registerModel(any())).thenReturn(validated);
        AiStrategyRelease champion = new AiStrategyRelease();
        champion.id = 91L;
        champion.userId = 5L;
        champion.releaseRole = "CHAMPION";
        champion.status = "ACTIVE";
        when(fixture.releaseMapper.selectOne(any())).thenReturn(null, champion);
        when(fixture.releaseMapper.insert(any(AiStrategyRelease.class))).thenAnswer(invocation -> {
            ((AiStrategyRelease) invocation.getArgument(0)).id = 92L;
            return 1;
        });
        AiMonthlyTrainingRunnerImpl runner = runner(fixture);

        AiEvolutionAutomationService.CycleResult result = runner.run(5L, now());

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.message()).contains("SHADOW Challenger #92");
        ArgumentCaptor<AiTrainingDatasetService.DatasetBuildRequest> datasetRequest =
                ArgumentCaptor.forClass(AiTrainingDatasetService.DatasetBuildRequest.class);
        verify(fixture.datasetService).buildDataset(datasetRequest.capture());
        assertThat(datasetRequest.getValue().maxHorizonDays()).isEqualTo(3);
        ArgumentCaptor<AiTrainingDatasetService.ModelRegistration> registration =
                ArgumentCaptor.forClass(AiTrainingDatasetService.ModelRegistration.class);
        verify(fixture.datasetService).registerModel(registration.capture());
        assertThat(registration.getValue().qualityGatePassed()).isTrue();
        assertThat(registration.getValue().artifactUri()).startsWith("file:");
        ArgumentCaptor<AiStrategyRelease> release = ArgumentCaptor.forClass(AiStrategyRelease.class);
        verify(fixture.releaseMapper).insert(release.capture());
        assertThat(release.getValue().releaseRole).isEqualTo("CHALLENGER");
        assertThat(release.getValue().status).isEqualTo("SHADOW");
        assertThat(release.getValue().modelVersionId).isEqualTo(81L);
        assertThat(release.getValue().promotionReason).contains("禁止自动晋级");
    }

    @Test
    void candidateModelNeverCreatesAChallenger() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.getScheduler().setMonthlyMinimumSamples(10);
        fixture.properties.getScheduler().setTrainingArtifactRoot(temporary.toString());
        when(fixture.datasetItemMapper.selectDominantSourceSummary(anyLong(), any(), any(), any()))
                .thenReturn(summary(30));
        when(fixture.datasetItemMapper.selectEligibleTradeDates(
                anyLong(), any(), any(), any(), any(), any())).thenReturn(dates(20));
        AiTrainingDataset dataset = new AiTrainingDataset();
        dataset.id = 71L;
        dataset.rowCount = 30;
        when(fixture.datasetService.buildDataset(any())).thenReturn(
                new AiTrainingDatasetService.DatasetBuildResult(dataset, List.of()));
        Path model = Files.writeString(temporary.resolve("candidate.joblib"), "model");
        Path onnx = Files.writeString(temporary.resolve("candidate.onnx"), "onnx");
        Path manifest = Files.writeString(temporary.resolve("candidate-manifest.json"), "{}");
        Path metrics = Files.writeString(temporary.resolve("candidate-metrics.json"), metricsJson());
        when(fixture.modelTrainer.train(any())).thenReturn(new AiModelTrainer.TrainingArtifacts(
                "LOGISTIC_REGRESSION", model, onnx, manifest, metrics));
        AiModelVersion candidate = new AiModelVersion();
        candidate.id = 82L;
        candidate.status = "CANDIDATE";
        when(fixture.datasetService.registerModel(any())).thenReturn(candidate);

        AiEvolutionAutomationService.CycleResult result = runner(fixture).run(5L, now());

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.message()).contains("不创建 Challenger");
        verify(fixture.releaseMapper, never()).insert(any(AiStrategyRelease.class));
    }

    private AiMonthlyTrainingRunnerImpl runner(Fixture fixture) {
        return new AiMonthlyTrainingRunnerImpl(
                fixture.properties, fixture.datasetItemMapper, fixture.datasetService,
                fixture.modelTrainer, fixture.releaseMapper,
                new ObjectMapper().findAndRegisterModules());
    }

    private static Fixture fixture() {
        AppProperties properties = new AppProperties();
        return new Fixture(
                properties,
                mock(AiTrainingDatasetItemMapper.class),
                mock(AiTrainingDatasetService.class),
                mock(AiModelTrainer.class),
                mock(AiStrategyReleaseMapper.class));
    }

    private static AiTrainingSourceSummary summary(int rows) {
        AiTrainingSourceSummary value = new AiTrainingSourceSummary();
        value.featureVersion = "POINT_IN_TIME_V2.1";
        value.labelVersion = "LABEL_V2.2";
        value.calendarVersion = "CN_A_SHARE_V1";
        value.rowCount = rows;
        value.tradingDayCount = 20;
        value.firstTradeDate = LocalDate.of(2026, 1, 1);
        value.lastTradeDate = LocalDate.of(2026, 7, 10);
        return value;
    }

    private static List<LocalDate> dates(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> LocalDate.of(2026, 1, 1).plusDays(index))
                .toList();
    }

    private static String metricsJson() {
        return """
                {
                  "trainerVersion":"TRAIN_RANKER_V2_1",
                  "algorithm":"LOGISTIC_REGRESSION",
                  "randomSeed":930514,
                  "parameters":{},
                  "calibration":{"method":"sigmoid","fitted":true,"coefficient":1.0,"intercept":0.0},
                  "splits":{"validation":{"rocAuc":0.70},"test":{"rocAuc":0.68}},
                  "artifacts":{"onnxExported":true}
                }
                """;
    }

    private static LocalDateTime now() {
        return LocalDateTime.of(2026, 7, 13, 19, 0);
    }

    private record Fixture(
            AppProperties properties,
            AiTrainingDatasetItemMapper datasetItemMapper,
            AiTrainingDatasetService datasetService,
            AiModelTrainer modelTrainer,
            AiStrategyReleaseMapper releaseMapper
    ) {
    }
}
