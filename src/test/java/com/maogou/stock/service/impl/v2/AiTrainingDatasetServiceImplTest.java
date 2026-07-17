package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetItem;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetSource;
import com.maogou.stock.mapper.research.AiModelVersionMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.service.research.AiTrainingDatasetService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTrainingDatasetServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsOnlyPointInTimeFeaturesAndSplitsStrictlyByTradeDate() throws Exception {
        Fixture fixture = fixture();
        fixture.sources.addAll(List.of(
                source(1L, 11L, "2026-01-02", "2026-01-06T15:00:00", "10.1"),
                source(2L, 12L, "2026-01-31", "2026-01-31T18:00:00", "10.2"),
                source(3L, 13L, "2026-02-01", "2026-02-12T15:00:00", "10.3"),
                source(4L, 14L, "2026-02-28", "2026-02-28T18:00:00", "10.4"),
                source(5L, 15L, "2026-03-01", "2026-03-06T15:00:00", "10.5"),
                source(6L, 16L, "2026-03-31", "2026-04-01T15:00:00", "10.6")));
        AiTrainingDatasetService service = service(fixture);

        var result = service.buildDataset(request(tempDir.resolve("dataset.jsonl")));

        assertThat(result.dataset().status).isEqualTo("READY");
        assertThat(result.dataset().rowCount).isEqualTo(6);
        assertThat(result.dataset().lineageFingerprint).hasSize(64);
        assertThat(result.dataset().artifactChecksum).hasSize(64);
        assertThat(result.items()).extracting(item -> item.splitType)
                .containsExactly("TRAIN", "TRAIN", "VALIDATION", "VALIDATION", "TEST", "TEST");
        assertThat(result.items()).allSatisfy(item -> {
            assertThat(item.featureFingerprint).startsWith("sample-");
            assertThat(item.labelFingerprint).startsWith("label-");
            assertThat(item.labelAvailableAt).isBeforeOrEqualTo(request(tempDir.resolve("unused")).asOfTime());
        });

        List<String> lines = Files.readAllLines(tempDir.resolve("dataset.jsonl"));
        assertThat(lines).hasSize(6);
        JsonNode first = new ObjectMapper().readTree(lines.get(0));
        assertThat(first.path("split").asText()).isEqualTo("TRAIN");
        assertThat(first.path("tradeDate").asText()).isEqualTo("2026-01-02");
        assertThat(first.path("sampleAsOfTime").asText()).isEqualTo("2026-01-02T15:00");
        assertThat(first.path("features").path("quote").path("price").decimalValue())
                .isEqualByComparingTo("10.1");
        assertThat(first.path("target").path("excessReturn").decimalValue())
                .isEqualByComparingTo("0.02");
    }

    @Test
    void rejectsFeatureEvidenceDatedAfterTheImmutableSampleAsOfTime() {
        Fixture fixture = fixture();
        AiTrainingDatasetSource source = source(
                1L, 11L, "2026-01-02", "2026-01-06T15:00:00", "10.1");
        source.featureSnapshot = "{\"asOfTime\":\"2026-01-02T15:00:00\","
                + "\"quote\":{\"price\":10.1,\"fetchedAt\":\"2026-01-03T09:30:00\"}}";
        fixture.sources.add(source);

        assertThatThrownBy(() -> service(fixture).buildDataset(request(tempDir.resolve("future.jsonl"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未来特征");
    }

    @Test
    void excludesLabelsThatWereNotAvailableBeforeTheFollowingSplit() {
        Fixture fixture = fixture();
        fixture.sources.add(source(1L, 11L, "2026-01-20", "2026-02-02T15:00:00", "10.1"));

        var result = service(fixture).buildDataset(request(tempDir.resolve("purged.jsonl")));

        assertThat(result.items()).isEmpty();
        assertThat(result.dataset().rowCount).isZero();
    }

    @Test
    void rejectsLabelsFromADifferentHorizonThanTheDatasetTarget() {
        Fixture fixture = fixture();
        AiTrainingDatasetSource source = source(
                1L, 11L, "2026-01-02", "2026-01-06T15:00:00", "10.1");
        source.horizonTradingDays = 3;
        fixture.sources.add(source);

        assertThatThrownBy(() -> service(fixture).buildDataset(request(tempDir.resolve("wrong-horizon.jsonl"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("周期");
    }

    @Test
    void repeatedBuildReturnsTheSameImmutableDatasetAndItems() {
        Fixture fixture = fixture();
        fixture.sources.add(source(1L, 11L, "2026-01-02", "2026-01-06T15:00:00", "10.1"));
        AiTrainingDatasetService service = service(fixture);
        var request = request(tempDir.resolve("stable.jsonl"));

        var first = service.buildDataset(request);
        var repeated = service.buildDataset(request);

        assertThat(repeated.dataset().id).isEqualTo(first.dataset().id);
        assertThat(repeated.dataset().lineageFingerprint).isEqualTo(first.dataset().lineageFingerprint);
        assertThat(repeated.items()).extracting(item -> item.id)
                .containsExactlyElementsOf(first.items().stream().map(item -> item.id).toList());
    }

    @Test
    void conflictingRebuildCannotOverwriteAnExistingImmutableArtifact() throws Exception {
        Fixture fixture = fixture();
        AiTrainingDatasetSource source = source(
                1L, 11L, "2026-01-02", "2026-01-06T15:00:00", "10.1");
        fixture.sources.add(source);
        AiTrainingDatasetService service = service(fixture);
        Path artifact = tempDir.resolve("immutable.jsonl");
        service.buildDataset(request(artifact));
        String original = Files.readString(artifact);
        source.featureSnapshot = source.featureSnapshot.replace("10.1", "99.9");
        source.featureFingerprint = "changed-sample";

        assertThatThrownBy(() -> service.buildDataset(request(artifact)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不可变");
        assertThat(Files.readString(artifact)).isEqualTo(original);
    }

    @Test
    void buildsLargeDatasetUsingBoundedSourceAndInsertPages() {
        Fixture fixture = fixture();
        for (long id = 1; id <= 1_200; id++) {
            fixture.sources.add(source(
                    id, 10_000L + id, "2026-01-02", "2026-01-06T15:00:00",
                    String.valueOf(10 + id / 10_000d)));
        }

        var result = service(fixture).buildDataset(request(tempDir.resolve("paged.jsonl")));

        assertThat(result.dataset().rowCount).isEqualTo(1_200);
        assertThat(result.items()).hasSize(1_200);
        verify(fixture.itemMapper, times(3)).selectEligibleSourcesPage(
                any(), nullable(LocalDate.class), nullable(String.class),
                nullable(Long.class), anyInt());
        verify(fixture.itemMapper, times(3)).insertBatchImmutable(anyList());
    }

    @Test
    void modelRegistrationRequiresDatasetAndKeepsFailedGateAsCandidate() {
        Fixture fixture = fixture();
        AiTrainingDataset dataset = readyDataset(88L);
        fixture.datasets.add(dataset);
        AiTrainingDatasetService service = service(fixture);

        assertThatThrownBy(() -> service.registerModel(modelRegistration(null, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trainingDatasetId");

        AiModelVersion candidate = service.registerModel(modelRegistration(88L, false));
        assertThat(candidate.trainingDatasetId).isEqualTo(88L);
        assertThat(candidate.status).isEqualTo("CANDIDATE");
        assertThat(candidate.featureManifestChecksum).hasSize(64);
    }

    @Test
    void modelPassingTheQualityGateIsOnlyValidatedAndRegistrationIsIdempotent() {
        Fixture fixture = fixture();
        fixture.datasets.add(readyDataset(88L));
        AiTrainingDatasetService service = service(fixture);

        AiModelVersion first = service.registerModel(modelRegistration(88L, true));
        AiModelVersion repeated = service.registerModel(modelRegistration(88L, true));

        assertThat(first.status).isEqualTo("VALIDATED");
        assertThat(repeated.id).isEqualTo(first.id);
    }

    @Test
    void modelFeatureVersionMustMatchItsTrainingDatasetLineage() {
        Fixture fixture = fixture();
        AiTrainingDataset dataset = readyDataset(88L);
        dataset.featureVersion = "SAMPLE_V2_OTHER";
        fixture.datasets.add(dataset);

        assertThatThrownBy(() -> service(fixture).registerModel(modelRegistration(88L, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("featureVersion");
    }

    @Test
    void modelRegistrationRejectsArtifactChecksumThatDoesNotMatchTheFile() {
        Fixture fixture = fixture();
        fixture.datasets.add(readyDataset(88L));
        AiTrainingDatasetService.ModelRegistration valid = modelRegistration(88L, true);
        AiTrainingDatasetService.ModelRegistration forged = new AiTrainingDatasetService.ModelRegistration(
                valid.trainingDatasetId(), valid.modelFamily(), valid.modelKey(), valid.versionNo(),
                valid.modelType(), valid.algorithm(), valid.featureVersion(), valid.trainerVersion(),
                valid.randomSeed(), valid.artifactUri(), "forged-checksum", valid.featureManifestUri(),
                valid.featureManifestChecksum(), valid.parametersJson(), valid.metricsJson(),
                valid.calibrationJson(), valid.sampleCount(), true, valid.registeredAt());

        assertThatThrownBy(() -> service(fixture).registerModel(forged))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
    }

    private AiTrainingDatasetService service(Fixture fixture) {
        return new AiTrainingDatasetServiceImpl(
                fixture.datasetMapper, fixture.itemMapper, fixture.modelMapper,
                new ObjectMapper().findAndRegisterModules());
    }

    private AiTrainingDatasetService.DatasetBuildRequest request(Path artifactPath) {
        return new AiTrainingDatasetService.DatasetBuildRequest(
                7L, "ranker", "2026-07-v1", "A_SHARE_MULTI_HORIZON", "RANKING",
                "SAMPLE_V2_1", "LABEL_V2_1", "CN_A_V1",
                LocalDateTime.of(2026, 4, 10, 18, 0),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31),
                5, 5, 5, artifactPath);
    }

    private AiTrainingDatasetService.ModelRegistration modelRegistration(
            Long trainingDatasetId,
            boolean qualityGatePassed
    ) {
        try {
            Path artifact = tempDir.resolve("ranker-" + trainingDatasetId + ".onnx");
            Files.writeString(artifact, "verified-model-artifact");
            Path manifest = tempDir.resolve("ranker-" + trainingDatasetId + "-features.json");
            Files.writeString(manifest, "{\"schemaVersion\":\"FEATURE_MANIFEST_V2_1\","
                    + "\"trainerVersion\":\"TRAINER_V2_1\",\"randomSeed\":930514,"
                    + "\"features\":[\"MOMENTUM_RETURN_5D\"],\"dtype\":\"float32\","
                    + "\"missingValuePolicy\":\"median_imputation\","
                    + "\"onnxOutput\":{\"name\":\"probabilities\",\"index\":1,"
                    + "\"kind\":\"PROBABILITY_UP\"},"
                    + "\"calibration\":{\"method\":\"sigmoid\",\"fitted\":true,"
                    + "\"coefficient\":1.25,\"intercept\":-0.1}}");
            String artifactChecksum = checksum(artifact);
            String manifestChecksum = checksum(manifest);
            String metrics = "{\"trainerVersion\":\"TRAINER_V2_1\","
                    + "\"algorithm\":\"LOGISTIC_REGRESSION\",\"randomSeed\":930514,"
                    + "\"calibration\":{\"method\":\"sigmoid\",\"fitted\":true,"
                    + "\"coefficient\":1.25,\"intercept\":-0.1},"
                    + "\"splits\":{\"validation\":{\"rocAuc\":0.70},\"test\":{\"rocAuc\":0.68}},"
                    + "\"artifacts\":{\"modelSha256\":\"" + artifactChecksum
                    + "\",\"featureManifestSha256\":\"" + manifestChecksum
                    + "\",\"onnxSha256\":\"" + artifactChecksum
                    + "\",\"onnxExported\":true}}";
        return new AiTrainingDatasetService.ModelRegistration(
                trainingDatasetId, "A_SHARE_MULTI_HORIZON", "ranker", "2026-07-v1", "RANKER",
                "LOGISTIC_REGRESSION", "SAMPLE_V2_1", "TRAINER_V2_1", 930514L,
                artifact.toUri().toString(), artifactChecksum,
                manifest.toUri().toString(), manifestChecksum,
                "{\"C\":1.0}", metrics, "{\"method\":\"sigmoid\",\"fitted\":true,"
                + "\"coefficient\":1.25,\"intercept\":-0.1}",
                120, qualityGatePassed, LocalDateTime.of(2026, 4, 10, 19, 0));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String checksum(Path path) throws java.io.IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AiTrainingDatasetSource source(
            long sampleId,
            long labelId,
            String tradeDate,
            String labelAvailableAt,
            String price
    ) {
        AiTrainingDatasetSource source = new AiTrainingDatasetSource();
        source.sampleId = sampleId;
        source.sampleLabelId = labelId;
        source.stockCode = "60000" + sampleId;
        source.tradeDate = LocalDate.parse(tradeDate);
        source.sampleAsOfTime = source.tradeDate.atTime(15, 0);
        source.labelAvailableAt = LocalDateTime.parse(labelAvailableAt);
        source.featureVersion = "SAMPLE_V2_1";
        source.labelVersion = "LABEL_V2_1";
        source.calendarVersion = "CN_A_V1";
        source.horizonTradingDays = 5;
        source.featureSnapshot = "{\"asOfTime\":\"" + source.sampleAsOfTime + "\","
                + "\"quote\":{\"price\":" + price + ",\"fetchedAt\":\""
                + source.sampleAsOfTime.minusMinutes(1) + "\"}}";
        source.netReturn = new BigDecimal("0.03");
        source.excessReturn = new BigDecimal("0.02");
        source.actualDirection = "UP";
        source.executionStatus = "EXECUTED";
        source.featureFingerprint = "sample-" + sampleId;
        source.labelFingerprint = "label-" + labelId;
        return source;
    }

    private static AiTrainingDataset readyDataset(long id) {
        AiTrainingDataset dataset = new AiTrainingDataset();
        dataset.id = id;
        dataset.researchUniverseId = 7L;
        dataset.datasetKey = "ranker";
        dataset.versionNo = "2026-07-v1";
        dataset.modelFamily = "A_SHARE_MULTI_HORIZON";
        dataset.featureVersion = "SAMPLE_V2_1";
        dataset.trainStartDate = LocalDate.of(2026, 1, 1);
        dataset.trainEndDate = LocalDate.of(2026, 1, 31);
        dataset.validationStartDate = LocalDate.of(2026, 2, 1);
        dataset.validationEndDate = LocalDate.of(2026, 2, 28);
        dataset.testStartDate = LocalDate.of(2026, 3, 1);
        dataset.testEndDate = LocalDate.of(2026, 3, 31);
        dataset.rowCount = 120;
        dataset.status = "READY";
        return dataset;
    }

    private static Fixture fixture() {
        AiTrainingDatasetMapper datasetMapper = mock(AiTrainingDatasetMapper.class);
        AiTrainingDatasetItemMapper itemMapper = mock(AiTrainingDatasetItemMapper.class);
        AiModelVersionMapper modelMapper = mock(AiModelVersionMapper.class);
        List<AiTrainingDatasetSource> sources = new ArrayList<>();
        List<AiTrainingDataset> datasets = new ArrayList<>();
        List<AiTrainingDatasetItem> items = new ArrayList<>();
        List<AiModelVersion> models = new ArrayList<>();
        AtomicLong ids = new AtomicLong(100);

        when(itemMapper.selectEligibleSourcesPage(
                any(), nullable(LocalDate.class), nullable(String.class),
                nullable(Long.class), anyInt())).thenAnswer(invocation -> {
            LocalDate afterTradeDate = invocation.getArgument(1);
            String afterStockCode = invocation.getArgument(2);
            Long afterSampleId = invocation.getArgument(3);
            int limit = invocation.getArgument(4);
            Comparator<AiTrainingDatasetSource> order = Comparator
                    .comparing((AiTrainingDatasetSource item) -> item.tradeDate)
                    .thenComparing(item -> item.stockCode)
                    .thenComparing(item -> item.sampleId)
                    .thenComparing(item -> item.sampleLabelId);
            return sources.stream().sorted(order)
                    .filter(item -> afterTradeDate == null || compareKey(
                            item, afterTradeDate, afterStockCode, afterSampleId) > 0)
                    .limit(limit).toList();
        });
        when(datasetMapper.insertImmutable(any())).thenAnswer(invocation -> {
            AiTrainingDataset candidate = invocation.getArgument(0);
            boolean exists = datasets.stream().anyMatch(item -> item.datasetKey.equals(candidate.datasetKey)
                    && item.versionNo.equals(candidate.versionNo));
            if (!exists) {
                candidate.id = ids.incrementAndGet();
                datasets.add(candidate);
            }
            return 1;
        });
        when(datasetMapper.selectByVersionForShare(any(), any())).thenAnswer(invocation ->
                datasets.stream().filter(item -> item.datasetKey.equals(invocation.getArgument(0))
                                && item.versionNo.equals(invocation.getArgument(1)))
                        .findFirst().orElse(null));
        when(datasetMapper.selectById(any())).thenAnswer(invocation -> datasets.stream()
                .filter(item -> item.id.equals(invocation.getArgument(0))).findFirst().orElse(null));
        when(itemMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            List<AiTrainingDatasetItem> candidates = invocation.getArgument(0);
            candidates.forEach(candidate -> {
                boolean exists = items.stream().anyMatch(item -> item.trainingDatasetId.equals(candidate.trainingDatasetId)
                        && item.sampleId.equals(candidate.sampleId)
                        && item.sampleLabelId.equals(candidate.sampleLabelId));
                if (!exists) {
                    candidate.id = ids.incrementAndGet();
                    items.add(candidate);
                }
            });
            return candidates.size();
        });
        when(itemMapper.selectByDatasetForShare(any())).thenAnswer(invocation -> items.stream()
                .filter(item -> item.trainingDatasetId.equals(invocation.getArgument(0))).toList());
        when(modelMapper.insertImmutable(any())).thenAnswer(invocation -> {
            AiModelVersion candidate = invocation.getArgument(0);
            boolean exists = models.stream().anyMatch(item -> item.modelFamily.equals(candidate.modelFamily)
                    && item.modelKey.equals(candidate.modelKey) && item.versionNo.equals(candidate.versionNo));
            if (!exists) {
                candidate.id = ids.incrementAndGet();
                models.add(candidate);
            }
            return 1;
        });
        when(modelMapper.selectByVersionForShare(any(), any(), any())).thenAnswer(invocation -> models.stream()
                .filter(item -> item.modelFamily.equals(invocation.getArgument(0))
                        && item.modelKey.equals(invocation.getArgument(1))
                        && item.versionNo.equals(invocation.getArgument(2))).findFirst().orElse(null));
        return new Fixture(datasetMapper, itemMapper, modelMapper, sources, datasets);
    }

    private static int compareKey(
            AiTrainingDatasetSource source,
            LocalDate tradeDate,
            String stockCode,
            Long sampleId
    ) {
        int value = source.tradeDate.compareTo(tradeDate);
        if (value == 0) {
            value = source.stockCode.compareTo(stockCode);
        }
        if (value == 0) {
            value = source.sampleId.compareTo(sampleId);
        }
        return value;
    }

    private record Fixture(
            AiTrainingDatasetMapper datasetMapper,
            AiTrainingDatasetItemMapper itemMapper,
            AiModelVersionMapper modelMapper,
            List<AiTrainingDatasetSource> sources,
            List<AiTrainingDataset> datasets
    ) {
    }
}
