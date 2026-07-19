package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.mapper.research.AiResearchSchemaVersionMapper;
import com.maogou.stock.service.research.AiTrainingDatasetService;
import com.maogou.stock.service.research.OnnxModelHealthValidator;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelPackageImportServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void importsVerifiedPackageAsCandidateBoundByProductionLineage() throws Exception {
        Fixture fixture = fixture();
        Path archive = packageFile(false);

        var result = fixture.service.importCandidate(upload(archive), 1357L);

        assertThat(result.status()).isEqualTo("CANDIDATE");
        assertThat(result.modelId()).isEqualTo(71L);
        assertThat(result.trainingDatasetId()).isEqualTo(41L);
        assertThat(Files.isRegularFile(tempDir.resolve("artifacts/imported-models/A_SHARE_MULTI_HORIZON")
                .resolve("MAOGOU_RANKER/20260719003353").resolve(result.packageChecksum())
                .resolve("model.onnx"))).isTrue();
        ArgumentCaptor<AiTrainingDatasetService.ModelRegistration> registration = ArgumentCaptor.forClass(
                AiTrainingDatasetService.ModelRegistration.class);
        verify(fixture.datasetService).registerModel(registration.capture());
        assertThat(registration.getValue().trainingDatasetId()).isEqualTo(41L);
        assertThat(registration.getValue().qualityGatePassed()).isFalse();
        assertThat(registration.getValue().artifactUri()).contains("imported-models");
        verify(fixture.onnxValidator).verify(any(Path.class));
    }

    @Test
    void rejectsTamperedPackageBeforeOnnxOrDatabaseRegistration() throws Exception {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.importCandidate(upload(packageFile(true)), 1357L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");

        verify(fixture.onnxValidator, never()).verify(any());
        verify(fixture.datasetService, never()).registerModel(any());
        assertThat(Files.exists(tempDir.resolve("artifacts/imported-models"))).isFalse();
    }

    @Test
    void rejectsPathTraversalArchiveBeforeAnyRegistration() throws Exception {
        Fixture fixture = fixture();
        Path archive = tempDir.resolve("traversal.tar.gz");
        writeTar(archive, Map.of("../model.onnx", "not-a-model".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> fixture.service.importCandidate(upload(archive), 1357L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径穿越");

        verify(fixture.datasetService, never()).registerModel(any());
    }

    @Test
    void rejectsPackageWhenProductionDatasetLineageDoesNotExist() throws Exception {
        Fixture fixture = fixture();
        when(fixture.datasetMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> fixture.service.importCandidate(upload(packageFile(false)), 1357L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("完全匹配");

        verify(fixture.onnxValidator, never()).verify(any());
        verify(fixture.datasetService, never()).registerModel(any());
        assertThat(Files.exists(tempDir.resolve("artifacts/imported-models"))).isFalse();
    }

    @Test
    void rejectsPackageWhenTargetResearchSchemaIsNotApplied() throws Exception {
        Fixture fixture = fixture();
        when(fixture.schemaVersionMapper.selectStatus(any())).thenReturn("APPLYING");

        assertThatThrownBy(() -> fixture.service.importCandidate(upload(packageFile(false)), 1357L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("schema");

        verify(fixture.onnxValidator, never()).verify(any());
        verify(fixture.datasetService, never()).registerModel(any());
    }

    private Fixture fixture() {
        AppProperties properties = new AppProperties();
        properties.getScheduler().setTrainingArtifactRoot(tempDir.resolve("artifacts").toString());
        properties.getScheduler().setModelPackageMaxBytes(10 * 1024 * 1024L);
        AiTrainingDatasetMapper datasetMapper = mock(AiTrainingDatasetMapper.class);
        AiResearchSchemaVersionMapper schemaVersionMapper = mock(AiResearchSchemaVersionMapper.class);
        when(schemaVersionMapper.selectStatus(any())).thenReturn("APPLIED");
        AiTrainingDataset dataset = new AiTrainingDataset();
        dataset.id = 41L;
        dataset.datasetKey = "MAOGOU_RANKER_T3";
        dataset.versionNo = "20260719003353";
        dataset.lineageFingerprint = "647ff3101d4472e21e1c0e06bc97dd3e8fc93264d23094c82730783e81c1d611";
        dataset.modelFamily = "A_SHARE_MULTI_HORIZON";
        dataset.featureVersion = "POINT_IN_TIME/1.0.0";
        dataset.rowCount = 90550;
        dataset.status = "READY";
        when(datasetMapper.selectOne(any(QueryWrapper.class))).thenReturn(dataset);
        AiTrainingDatasetService datasetService = mock(AiTrainingDatasetService.class);
        when(datasetService.registerModel(any())).thenAnswer(invocation -> {
            AiTrainingDatasetService.ModelRegistration registration = invocation.getArgument(0);
            AiModelVersion model = new AiModelVersion();
            model.id = 71L;
            model.modelFamily = registration.modelFamily();
            model.modelKey = registration.modelKey();
            model.versionNo = registration.versionNo();
            model.status = "CANDIDATE";
            return model;
        });
        OnnxModelHealthValidator validator = mock(OnnxModelHealthValidator.class);
        return new Fixture(new AiModelPackageImportServiceImpl(properties, datasetMapper, schemaVersionMapper,
                datasetService, validator, new ObjectMapper().findAndRegisterModules()), datasetMapper,
                schemaVersionMapper, datasetService, validator);
    }

    private Path packageFile(boolean tamperModel) throws Exception {
        byte[] model = "onnx-model-binary".getBytes(StandardCharsets.UTF_8);
        byte[] manifest = ("{\"schemaVersion\":\"FEATURE_MANIFEST_V2_1\",\"trainerVersion\":\"TRAIN_RANKER_V2_1\","
                + "\"randomSeed\":930514,\"features\":[\"quote.price\"],\"onnxOutput\":{\"name\":\"probabilities\","
                + "\"index\":1,\"kind\":\"PROBABILITY_UP\"},\"calibration\":{\"method\":\"sigmoid\","
                + "\"fitted\":true,\"coefficient\":1.0,\"intercept\":0.0}}").getBytes(StandardCharsets.UTF_8);
        String modelChecksum = sha256(model);
        String manifestChecksum = sha256(manifest);
        byte[] metrics = ("{\"trainerVersion\":\"TRAIN_RANKER_V2_1\",\"algorithm\":\"LOGISTIC_REGRESSION\","
                + "\"randomSeed\":930514,\"parameters\":{\"C\":1.0},\"calibration\":{\"method\":\"sigmoid\","
                + "\"fitted\":true},\"artifacts\":{\"onnxExported\":true,\"onnxParity\":{\"verified\":true},"
                + "\"onnxSha256\":\"" + modelChecksum + "\",\"modelSha256\":\"" + modelChecksum
                + "\",\"featureManifestSha256\":\"" + manifestChecksum + "\"}}").getBytes(StandardCharsets.UTF_8);
        byte[] calibration = "{\"method\":\"sigmoid\",\"fitted\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] dataset = ("{\"format\":\"MAOGOU_DATASET_MANIFEST_V1\",\"sourceSnapshot\":{\"schemaVersion\":\"20260714-unified-1.1\"},\"dataset\":{\"datasetKey\":\"MAOGOU_RANKER_T3\","
                + "\"versionNo\":\"20260719003353\",\"lineageFingerprint\":\"647ff3101d4472e21e1c0e06bc97dd3e8fc93264d23094c82730783e81c1d611\","
                + "\"featureVersion\":\"POINT_IN_TIME/1.0.0\",\"rowCount\":90550,\"status\":\"READY\"}}").getBytes(StandardCharsets.UTF_8);
        byte[] packageManifest = ("{\"format\":\"MAOGOU_MODEL_PACKAGE_V1\",\"requiredRegistrationStatus\":\"CANDIDATE\","
                + "\"datasetBusinessKey\":{\"datasetKey\":\"MAOGOU_RANKER_T3\",\"versionNo\":\"20260719003353\","
                + "\"lineageFingerprint\":\"647ff3101d4472e21e1c0e06bc97dd3e8fc93264d23094c82730783e81c1d611\"},"
                + "\"model\":{\"modelFamily\":\"A_SHARE_MULTI_HORIZON\",\"modelKey\":\"MAOGOU_RANKER\","
                + "\"versionNo\":\"20260719003353\",\"modelType\":\"RANKER\",\"algorithm\":\"LOGISTIC_REGRESSION\","
                + "\"featureVersion\":\"POINT_IN_TIME/1.0.0\",\"trainerVersion\":\"TRAIN_RANKER_V2_1\","
                + "\"randomSeed\":930514,\"sampleCount\":90550,\"status\":\"CANDIDATE\"}}").getBytes(StandardCharsets.UTF_8);
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("model.onnx", model);
        files.put("feature-manifest.json", manifest);
        files.put("metrics.json", metrics);
        files.put("calibration.json", calibration);
        files.put("dataset-manifest.json", dataset);
        files.put("runtime-manifest.json", "{\"python\":\"3.12.13\"}".getBytes(StandardCharsets.UTF_8));
        files.put("model-card.md", "candidate".getBytes(StandardCharsets.UTF_8));
        files.put("package-manifest.json", packageManifest);
        StringBuilder checksumLines = new StringBuilder();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            checksumLines.append(sha256(entry.getValue())).append("  ").append(entry.getKey()).append('\n');
        }
        files.put("checksums.sha256", checksumLines.toString().getBytes(StandardCharsets.US_ASCII));
        if (tamperModel) {
            files.put("model.onnx", "changed-model".getBytes(StandardCharsets.UTF_8));
        }
        Path archive = tempDir.resolve(tamperModel ? "tampered.tar.gz" : "valid.tar.gz");
        writeTar(archive, prefix("candidate", files));
        return archive;
    }

    private static Map<String, byte[]> prefix(String directory, Map<String, byte[]> files) {
        Map<String, byte[]> prefixed = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            prefixed.put(directory + "/" + entry.getKey(), entry.getValue());
        }
        return prefixed;
    }

    private static void writeTar(Path archive, Map<String, byte[]> entries) throws Exception {
        try (OutputStream output = Files.newOutputStream(archive);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                TarArchiveEntry tarEntry = new TarArchiveEntry(entry.getKey());
                tarEntry.setSize(entry.getValue().length);
                tar.putArchiveEntry(tarEntry);
                tar.write(entry.getValue());
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
    }

    private static MockMultipartFile upload(Path file) throws Exception {
        return new MockMultipartFile("package", file.getFileName().toString(), "application/gzip", Files.readAllBytes(file));
    }

    private static String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private record Fixture(
            AiModelPackageImportServiceImpl service,
            AiTrainingDatasetMapper datasetMapper,
            AiResearchSchemaVersionMapper schemaVersionMapper,
            AiTrainingDatasetService datasetService,
            OnnxModelHealthValidator onnxValidator
    ) {
    }
}
