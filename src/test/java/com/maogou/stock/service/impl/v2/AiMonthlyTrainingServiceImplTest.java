package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.domain.entity.research.AiTrainingSourceSummary;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import com.maogou.stock.service.research.AiResearchCycleResult;
import com.maogou.stock.service.research.AiModelTrainer;
import com.maogou.stock.service.research.AiTrainingDatasetService;
import com.maogou.stock.service.research.AiTrainingReadinessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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

class AiMonthlyTrainingServiceImplTest {

    @TempDir
    Path temporary;

    @Test
    void insufficientMatureSamplesAreSkippedBeforeCreatingArtifacts() {
        Fixture fixture = fixture();
        fixture.properties.getScheduler().setMonthlyMinimumSamples(1000);
        AiTrainingSourceSummary summary = summary(120);
        when(fixture.datasetItemMapper.selectDominantSourceSummary(any(), any(), any()))
                .thenReturn(summary);
        AiMonthlyTrainingServiceImpl runner = runner(fixture);

        AiResearchCycleResult result = runner.run(5L, now());

        assertThat(result.status()).isEqualTo("SKIPPED");
        assertThat(result.message()).contains("120 / 20000");
        verify(fixture.datasetItemMapper).selectDominantSourceSummary(
                "LABEL/1.0.0", 3, now());
        verify(fixture.datasetService, never()).buildDataset(any());
        verify(fixture.modelTrainer, never()).train(any());
    }

    @Test
    void validatedTrainingArtifactsCreateAnIdempotentShadowChallenger() throws Exception {
        Fixture fixture = fixture();
        fixture.properties.getScheduler().setMonthlyMinimumSamples(10);
        fixture.properties.getScheduler().setTrainingArtifactRoot(temporary.toString());
        when(fixture.datasetItemMapper.selectDominantSourceSummary(any(), any(), any()))
                .thenReturn(summary(30_000));
        when(fixture.datasetItemMapper.selectEligibleTradeDates(
                any(), any(), any(), any(), any())).thenReturn(dates(120));
        AiTrainingDataset dataset = new AiTrainingDataset();
        dataset.id = 71L;
        dataset.rowCount = 30_000;
        dataset.status = "READY";
        when(fixture.datasetService.buildDataset(any())).thenReturn(
                new AiTrainingDatasetService.DatasetBuildResult(dataset, List.of()));
        stubTrainingArtifacts(fixture);
        AiModelVersion validated = new AiModelVersion();
        validated.id = 81L;
        validated.userId = 5L;
        validated.versionNo = "20260713";
        validated.featureVersion = "POINT_IN_TIME_V2.1";
        validated.status = "VALIDATED";
        when(fixture.datasetService.registerModel(any())).thenReturn(validated);
        AiStrategyRelease champion = new AiStrategyRelease();
        champion.id = 91L;
        champion.researchUniverseId = 41L;
        champion.modelFamily = "A_SHARE_MULTI_HORIZON";
        champion.releaseRole = "CHAMPION";
        champion.status = "ACTIVE";
        when(fixture.releaseMapper.selectOne(any())).thenReturn(null, champion);
        when(fixture.releaseMapper.insert(any(AiStrategyRelease.class))).thenAnswer(invocation -> {
            ((AiStrategyRelease) invocation.getArgument(0)).id = 92L;
            return 1;
        });
        AiMonthlyTrainingServiceImpl runner = runner(fixture);

        AiResearchCycleResult result = runner.run(5L, now());

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.message()).contains("SHADOW Challenger #92");
        ArgumentCaptor<AiTrainingDatasetService.DatasetBuildRequest> datasetRequest =
                ArgumentCaptor.forClass(AiTrainingDatasetService.DatasetBuildRequest.class);
        verify(fixture.datasetService).buildDataset(datasetRequest.capture());
        assertThat(datasetRequest.getValue().maxHorizonDays()).isEqualTo(3);
        assertThat(datasetRequest.getValue().researchUniverseId()).isEqualTo(41L);
        assertThat(datasetRequest.getValue().versionNo()).isEqualTo("20260713190000");
        assertThat(datasetRequest.getValue().purgeTradingDays()).isEqualTo(5);
        assertThat(datasetRequest.getValue().embargoTradingDays()).isEqualTo(5);
        ArgumentCaptor<AiTrainingDatasetService.ModelRegistration> registration =
                ArgumentCaptor.forClass(AiTrainingDatasetService.ModelRegistration.class);
        verify(fixture.datasetService).registerModel(registration.capture());
        assertThat(registration.getValue().qualityGatePassed()).isTrue();
        assertThat(registration.getValue().artifactUri()).contains("/model/model.onnx");
        assertThat(Path.of(java.net.URI.create(registration.getValue().artifactUri()))).isRegularFile();
        try (var paths = Files.list(temporary.resolve("A_SHARE_MULTI_HORIZON/20260713190000"))) {
            assertThat(paths.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.startsWith(".model.tmp-"));
        }
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
        when(fixture.datasetItemMapper.selectDominantSourceSummary(any(), any(), any()))
                .thenReturn(summary(30_000));
        when(fixture.datasetItemMapper.selectEligibleTradeDates(
                any(), any(), any(), any(), any())).thenReturn(dates(120));
        AiTrainingDataset dataset = new AiTrainingDataset();
        dataset.id = 71L;
        dataset.rowCount = 30_000;
        when(fixture.datasetService.buildDataset(any())).thenReturn(
                new AiTrainingDatasetService.DatasetBuildResult(dataset, List.of()));
        stubTrainingArtifacts(fixture);
        AiModelVersion candidate = new AiModelVersion();
        candidate.id = 82L;
        candidate.status = "CANDIDATE";
        when(fixture.datasetService.registerModel(any())).thenReturn(candidate);

        AiResearchCycleResult result = runner(fixture).run(5L, now());

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.message()).contains("不创建 Challenger");
        verify(fixture.releaseMapper, never()).insert(any(AiStrategyRelease.class));
    }

    private AiMonthlyTrainingServiceImpl runner(Fixture fixture) {
        return new AiMonthlyTrainingServiceImpl(
                fixture.properties, fixture.datasetItemMapper, fixture.readinessService,
                fixture.datasetService,
                fixture.modelTrainer, fixture.releaseMapper,
                new ObjectMapper().findAndRegisterModules());
    }

    private static Fixture fixture() {
        AppProperties properties = new AppProperties();
        AiTrainingReadinessService readinessService = mock(AiTrainingReadinessService.class);
        when(readinessService.assess(any())).thenReturn(ready());
        AiStrategyRelease champion = new AiStrategyRelease();
        champion.id = 91L;
        champion.researchUniverseId = 41L;
        champion.modelFamily = "A_SHARE_MULTI_HORIZON";
        champion.releaseRole = "CHAMPION";
        champion.status = "ACTIVE";
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        when(releaseMapper.selectGlobalActiveChampion(any(), any())).thenReturn(champion);
        return new Fixture(
                properties,
                mock(AiTrainingDatasetItemMapper.class),
                readinessService,
                mock(AiTrainingDatasetService.class),
                mock(AiModelTrainer.class),
                releaseMapper);
    }

    private static AiTrainingSourceSummary summary(int rows) {
        AiTrainingSourceSummary value = new AiTrainingSourceSummary();
        value.featureVersion = "POINT_IN_TIME_V2.1";
        value.labelVersion = "LABEL/1.0.0";
        value.calendarVersion = "CN_A_CALENDAR/1.0.0";
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

    private static void stubTrainingArtifacts(Fixture fixture) throws Exception {
        when(fixture.modelTrainer.train(any())).thenAnswer(invocation -> {
            AiModelTrainer.TrainingRequest request = invocation.getArgument(0);
            Files.createDirectories(request.outputDirectory());
            Path model = Files.writeString(request.outputDirectory().resolve("model.joblib"), "model");
            Path onnx = Files.writeString(request.outputDirectory().resolve("model.onnx"), "onnx");
            Path manifest = Files.writeString(
                    request.outputDirectory().resolve("feature_manifest.json"),
                    "{\"features\":[\"momentum\"]}");
            Path metrics = Files.writeString(request.outputDirectory().resolve("metrics.json"),
                    metricsJson(checksum(onnx), checksum(manifest)));
            return new AiModelTrainer.TrainingArtifacts(
                    "LOGISTIC_REGRESSION", model, onnx, manifest, metrics);
        });
    }

    private static String metricsJson(String onnxChecksum, String manifestChecksum) {
        return """
                {
                  "trainerVersion":"TRAIN_RANKER_V2_1",
                  "algorithm":"LOGISTIC_REGRESSION",
                  "randomSeed":930514,
                  "parameters":{},
                  "calibration":{"method":"sigmoid","fitSplit":"TRAIN","fitted":true,"coefficient":1.0,"intercept":0.0},
                  "splits":{"validation":{"rocAuc":0.70},"test":{"rocAuc":0.68}},
                  "artifacts":{"onnxExported":true,"modelSha256":"%s","onnxSha256":"%s","featureManifestSha256":"%s"}
                }
                """.formatted(onnxChecksum, onnxChecksum, manifestChecksum);
    }

    private static String checksum(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static LocalDateTime now() {
        return LocalDateTime.of(2026, 7, 13, 19, 0);
    }

    private static AiTrainingReadinessGate.Readiness ready() {
        return new AiTrainingReadinessGate().evaluate(new AiTrainingReadinessGate.Evidence(
                120, 200,
                java.util.Map.of(1, 20_000, 2, 20_000, 3, 30_000, 5, 20_000),
                java.util.Map.of("UP", 20, "DOWN", 20, "SIDEWAYS", 20)));
    }

    private record Fixture(
            AppProperties properties,
            AiTrainingDatasetItemMapper datasetItemMapper,
            AiTrainingReadinessService readinessService,
            AiTrainingDatasetService datasetService,
            AiModelTrainer modelTrainer,
            AiStrategyReleaseMapper releaseMapper
    ) {
    }
}
