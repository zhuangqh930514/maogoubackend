package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiResearchUniverse;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetSource;
import com.maogou.stock.mapper.research.AiResearchSchemaVersionMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.service.research.AiTrainingDatasetPackageImportService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiTrainingDatasetPackageImportServiceImplTest {

    private static final String FINGERPRINT = "a".repeat(64);
    private static final String LABEL = "b".repeat(64);
    private static final String UNIVERSE = "c".repeat(64);
    private static final String STATE = "d".repeat(64);
    private static final String SECTOR = "e".repeat(64);
    private static final String LINEAGE = "f".repeat(64);

    @TempDir
    Path tempDir;

    @Test
    void previewsOnlyWhenEveryLocalItemResolvesToProductionFacts() throws Exception {
        Fixture fixture = fixture(true);

        AiTrainingDatasetPackageImportService.PreviewResult result = fixture.service.preview(upload(packageFile(false)), 9L);

        assertThat(result.compatible()).isTrue();
        assertThat(result.matchedRows()).isEqualTo(1);
        assertThat(result.rejectedRows()).isZero();
        verify(fixture.datasetMapper, never()).insertImmutable(any());
    }

    @Test
    void rejectsMissingProductionFactWithoutWritingDataset() throws Exception {
        Fixture fixture = fixture(false);

        AiTrainingDatasetPackageImportService.PreviewResult result = fixture.service.preview(upload(packageFile(false)), 9L);

        assertThat(result.compatible()).isFalse();
        assertThat(result.rejectedRows()).isEqualTo(1);
        assertThat(result.rejections()).singleElement().extracting(AiTrainingDatasetPackageImportService.Rejection::reason)
                .asString().contains("缺少匹配");
        assertThatThrownBy(() -> fixture.service.importPackage(upload(packageFile(false)), 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预检未通过");
        verify(fixture.datasetMapper, never()).insertImmutable(any());
    }

    @Test
    void rejectsTamperedPackageBeforeFactLookupOrWrites() throws Exception {
        Fixture fixture = fixture(true);

        assertThatThrownBy(() -> fixture.service.preview(upload(packageFile(true)), 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");

        verify(fixture.itemMapper, never()).selectByImportLineage(any());
        verify(fixture.datasetMapper, never()).insertImmutable(any());
    }

    @Test
    void rejectsManifestRowCountMismatchBeforeFactLookupOrWrites() throws Exception {
        Fixture fixture = fixture(true);

        assertThatThrownBy(() -> fixture.service.preview(upload(packageFile(false, 2)), 9L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("行数");

        verify(fixture.itemMapper, never()).selectByImportLineage(any());
        verify(fixture.datasetMapper, never()).insertImmutable(any());
    }

    @Test
    void existingCompatibleDatasetIsIdempotentlyReusedWithoutNewWrites() throws Exception {
        Fixture fixture = fixture(true);
        AiTrainingDataset existing = persistedDataset();
        when(fixture.datasetMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);
        when(fixture.datasetMapper.selectByVersionForShare("MAOGOU_RANKER_T3", "20260720"))
                .thenReturn(existing);

        AiTrainingDatasetPackageImportService.ImportResult result = fixture.service.importPackage(upload(packageFile(false)), 9L);

        assertThat(result.reused()).isTrue();
        assertThat(result.trainingDatasetId()).isEqualTo(77L);
        verify(fixture.datasetMapper, never()).insertImmutable(any());
        verify(fixture.itemMapper, never()).insertBatchImmutable(anyList());
    }

    @Test
    void importsResolvedDatasetAsReadyAndMapsOnlyProductionIds() throws Exception {
        Fixture fixture = fixture(true);
        AiTrainingDataset persisted = persistedDataset();
        when(fixture.datasetMapper.selectByVersionForShare("MAOGOU_RANKER_T3", "20260720"))
                .thenReturn(null, persisted);

        AiTrainingDatasetPackageImportService.ImportResult result = fixture.service.importPackage(upload(packageFile(false)), 9L);

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.trainingDatasetId()).isEqualTo(77L);
        verify(fixture.datasetMapper).insertImmutable(any());
        verify(fixture.itemMapper).insertBatchImmutable(anyList());
        assertThat(fixture.writtenItems.get()).singleElement().satisfies(item -> {
            assertThat(item.sampleId).isEqualTo(101L);
            assertThat(item.sampleLabelId).isEqualTo(201L);
            assertThat(item.featureFingerprint).isEqualTo(FINGERPRINT);
        });
        assertThat(Files.isRegularFile(tempDir.resolve("artifacts/imported-datasets/A_SHARE_MULTI_HORIZON")
                .resolve("MAOGOU_RANKER_T3/20260720").resolve(result.packageChecksum()).resolve("dataset.jsonl")))
                .isTrue();
    }

    private Fixture fixture(boolean productionFactExists) {
        AppProperties properties = new AppProperties();
        properties.getScheduler().setTrainingArtifactRoot(tempDir.resolve("artifacts").toString());
        properties.getScheduler().setModelPackageMaxBytes(10 * 1024 * 1024L);
        AiTrainingDatasetMapper datasetMapper = mock(AiTrainingDatasetMapper.class);
        AiTrainingDatasetItemMapper itemMapper = mock(AiTrainingDatasetItemMapper.class);
        AiResearchUniverseMapper universeMapper = mock(AiResearchUniverseMapper.class);
        AiResearchSchemaVersionMapper schemaMapper = mock(AiResearchSchemaVersionMapper.class);
        when(schemaMapper.selectStatus("20260714-unified-1.1")).thenReturn("APPLIED");
        when(datasetMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        AiResearchUniverse universe = new AiResearchUniverse();
        universe.id = 31L;
        universe.universeCode = "A_SHARE_CORE";
        universe.enabled = 1;
        when(universeMapper.selectOne(any(QueryWrapper.class))).thenReturn(universe);
        if (productionFactExists) {
            when(itemMapper.selectByImportLineage(any())).thenReturn(List.of(source()));
        } else {
            when(itemMapper.selectByImportLineage(any())).thenReturn(List.of());
        }
        AtomicReference<List<com.maogou.stock.domain.entity.research.AiTrainingDatasetItem>> writtenItems =
                new AtomicReference<>(List.of());
        when(itemMapper.insertBatchImmutable(anyList())).thenAnswer(invocation -> {
            writtenItems.set(List.copyOf(invocation.getArgument(0)));
            return invocation.<List<?>>getArgument(0).size();
        });
        when(itemMapper.selectByDatasetForShare(77L)).thenAnswer(invocation -> writtenItems.get());
        return new Fixture(new AiTrainingDatasetPackageImportServiceImpl(properties, datasetMapper, itemMapper,
                universeMapper, schemaMapper, new ObjectMapper().findAndRegisterModules()), datasetMapper, itemMapper,
                writtenItems);
    }

    private AiTrainingDatasetSource source() {
        AiTrainingDatasetSource value = new AiTrainingDatasetSource();
        value.sampleId = 101L;
        value.sampleLabelId = 201L;
        value.sampleAsOfTime = LocalDateTime.parse("2026-07-01T15:00:00");
        value.labelAvailableAt = LocalDateTime.parse("2026-07-06T15:00:00");
        value.featureFingerprint = FINGERPRINT;
        value.labelFingerprint = LABEL;
        value.universeFingerprint = UNIVERSE;
        value.tradingStateFingerprint = STATE;
        value.sectorMembershipFingerprint = SECTOR;
        return value;
    }

    private AiTrainingDataset persistedDataset() {
        AiTrainingDataset value = new AiTrainingDataset();
        value.id = 77L;
        value.datasetKey = "MAOGOU_RANKER_T3";
        value.versionNo = "20260720";
        value.lineageFingerprint = LINEAGE;
        value.modelFamily = "A_SHARE_MULTI_HORIZON";
        value.featureVersion = "POINT_IN_TIME/1.0.0";
        value.labelVersion = "LABEL/1.1.0";
        value.calendarVersion = "CN_A/1.0.0";
        value.rowCount = 1;
        value.status = "READY";
        return value;
    }

    private Path packageFile(boolean tamper) throws Exception {
        return packageFile(tamper, 1);
    }

    private Path packageFile(boolean tamper, int declaredRows) throws Exception {
        byte[] datasetJsonl = "{\"features\":{},\"target\":{}}\n".getBytes(StandardCharsets.UTF_8);
        byte[] items = ("{\"splitType\":\"TRAIN\",\"sequenceNo\":1,\"sampleAsOfTime\":\"2026-07-01T15:00:00\","
                + "\"labelAvailableAt\":\"2026-07-06T15:00:00\",\"featureFingerprint\":\"" + FINGERPRINT
                + "\",\"labelFingerprint\":\"" + LABEL + "\",\"universeFingerprint\":\"" + UNIVERSE
                + "\",\"tradingStateFingerprint\":\"" + STATE + "\",\"sectorMembershipFingerprint\":\"" + SECTOR
                + "\"}\n").getBytes(StandardCharsets.UTF_8);
        String artifactChecksum = sha256(datasetJsonl);
        byte[] manifest = ("{\"format\":\"MAOGOU_TRAINING_DATASET_MANIFEST_V1\",\"dataset\":{"
                + "\"datasetKey\":\"MAOGOU_RANKER_T3\",\"versionNo\":\"20260720\","
                + "\"modelFamily\":\"A_SHARE_MULTI_HORIZON\",\"purpose\":\"MONTHLY_MODEL_TRAINING\","
                + "\"featureVersion\":\"POINT_IN_TIME/1.0.0\",\"labelVersion\":\"LABEL/1.1.0\","
                + "\"calendarVersion\":\"CN_A/1.0.0\",\"asOfTime\":\"2026-07-20T16:00:00\","
                + "\"trainStartDate\":\"2026-01-01\",\"trainEndDate\":\"2026-04-01\","
                + "\"validationStartDate\":\"2026-04-02\",\"validationEndDate\":\"2026-05-01\","
                + "\"testStartDate\":\"2026-05-02\",\"testEndDate\":\"2026-06-01\","
                + "\"maxHorizonDays\":3,\"purgeTradingDays\":5,\"embargoTradingDays\":5,"
                + "\"lineageFingerprint\":\"" + LINEAGE + "\",\"artifactChecksum\":\"" + artifactChecksum
                + "\",\"rowCount\":" + declaredRows + "},\"researchUniverse\":{\"universeCode\":\"A_SHARE_CORE\"},"
                + "\"sourceSnapshot\":{\"schemaVersion\":\"20260714-unified-1.1\"}}").getBytes(StandardCharsets.UTF_8);
        byte[] packageManifest = ("{\"format\":\"MAOGOU_TRAINING_DATASET_PACKAGE_V1\","
                + "\"sourceSchemaVersion\":\"20260714-unified-1.1\"}").getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("dataset-manifest.json", manifest);
        files.put("dataset-items.jsonl", items);
        files.put("dataset.jsonl", tamper ? "tampered\n".getBytes(StandardCharsets.UTF_8) : datasetJsonl);
        files.put("data-card.json", "{\"format\":\"MAOGOU_TRAINING_DATA_CARD_V1\"}\n".getBytes(StandardCharsets.UTF_8));
        files.put("package-manifest.json", packageManifest);
        StringBuilder checksums = new StringBuilder();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            checksums.append(sha256(file.getValue())).append("  ").append(file.getKey()).append('\n');
        }
        files.put("checksums.sha256", checksums.toString().getBytes(StandardCharsets.US_ASCII));
        Path archive = tempDir.resolve(tamper ? "tampered.tar.gz" : "valid.tar.gz");
        writeTar(archive, files);
        return archive;
    }

    private MockMultipartFile upload(Path archive) throws Exception {
        return new MockMultipartFile("package", archive.getFileName().toString(), "application/gzip", Files.readAllBytes(archive));
    }

    private static void writeTar(Path archive, Map<String, byte[]> files) throws Exception {
        try (OutputStream output = Files.newOutputStream(archive);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry("dataset/" + file.getKey());
                entry.setSize(file.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(file.getValue());
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record Fixture(
            AiTrainingDatasetPackageImportServiceImpl service,
            AiTrainingDatasetMapper datasetMapper,
            AiTrainingDatasetItemMapper itemMapper,
            AtomicReference<List<com.maogou.stock.domain.entity.research.AiTrainingDatasetItem>> writtenItems
    ) {
    }
}
