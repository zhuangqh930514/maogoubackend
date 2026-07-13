package com.maogou.stock.service.impl.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.v2.AiFactorValueV2;
import com.maogou.stock.domain.entity.v2.AiModelVersion;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.infrastructure.ml.OnnxPredictionClient;
import com.maogou.stock.mapper.v2.AiModelVersionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiModelInferenceServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsVerifiedContractAndBuildsFeaturesInManifestOrder() throws Exception {
        AiModelVersionMapper mapper = mock(AiModelVersionMapper.class);
        AiModelVersion model = model();
        when(mapper.selectById(11L)).thenReturn(model);
        AtomicReference<float[]> received = new AtomicReference<>();
        AiModelInferenceServiceImpl service = new AiModelInferenceServiceImpl(
                mapper,
                new ObjectMapper().findAndRegisterModules(),
                modelPath -> new AiModelInferenceServiceImpl.RuntimeHandle(
                        (modelKey, features) -> {
                            received.set(features.clone());
                            return new OnnxPredictionClient.InferenceResult(Map.of("score", List.of(1.0f)));
                        },
                        null));

        var result = service.infer(7L, 11L, sample(), List.of(factor()));

        assertThat(received.get()).containsExactly(10.5f, 1.25f);
        assertThat(result.probabilityUp()).isCloseTo(0.880797f, org.assertj.core.data.Offset.offset(0.00001f));
        assertThat(result.featureCount()).isEqualTo(2);
        assertThat(result.matchedFeatureCount()).isEqualTo(2);
        service.close();
    }

    @Test
    void rejectsCandidateModelBeforeOpeningRuntime() throws Exception {
        AiModelVersionMapper mapper = mock(AiModelVersionMapper.class);
        AiModelVersion model = model();
        model.status = "CANDIDATE";
        when(mapper.selectById(11L)).thenReturn(model);
        AiModelInferenceServiceImpl service = new AiModelInferenceServiceImpl(
                mapper,
                new ObjectMapper().findAndRegisterModules(),
                ignored -> {
                    throw new AssertionError("runtime must not be opened");
                });

        assertThatThrownBy(() -> service.infer(7L, 11L, sample(), List.of(factor())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VALIDATED");
    }

    @Test
    void rejectsInferenceWhenImmutableSampleCannotCoverTheModelFeatures() throws Exception {
        AiModelVersionMapper mapper = mock(AiModelVersionMapper.class);
        AiModelVersion model = modelWithManifest(List.of("missing.a", "missing.b", "missing.c"));
        when(mapper.selectById(11L)).thenReturn(model);
        AiModelInferenceServiceImpl service = new AiModelInferenceServiceImpl(
                mapper,
                new ObjectMapper().findAndRegisterModules(),
                ignored -> new AiModelInferenceServiceImpl.RuntimeHandle(
                        (modelKey, features) -> new OnnxPredictionClient.InferenceResult(
                                Map.of("score", List.of(0.0f))), null));

        assertThatThrownBy(() -> service.infer(7L, 11L, sample(), List.of(factor())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("特征覆盖不足");
        service.close();
    }

    private AiModelVersion model() throws Exception {
        return modelWithManifest(List.of("quote.price", "factors.MOMENTUM_RETURN_3D"));
    }

    private AiModelVersion modelWithManifest(List<String> features) throws Exception {
        Path artifact = tempDir.resolve("model-" + features.size() + ".onnx");
        Files.writeString(artifact, "onnx-fixture-" + features);
        Path manifest = tempDir.resolve("manifest-" + features.size() + ".json");
        new ObjectMapper().writeValue(manifest.toFile(), Map.of(
                "features", features,
                "onnxOutput", Map.of("name", "score", "index", 0, "kind", "RAW_SCORE"),
                "calibration", Map.of("method", "sigmoid", "coefficient", 2.0, "intercept", 0.0)));
        AiModelVersion model = new AiModelVersion();
        model.id = 11L;
        model.userId = 7L;
        model.modelKey = "ranker";
        model.versionNo = "v1";
        model.status = "VALIDATED";
        model.artifactUri = artifact.toUri().toString();
        model.artifactChecksum = checksum(artifact);
        model.featureManifestUri = manifest.toUri().toString();
        model.featureManifestChecksum = checksum(manifest);
        model.calibrationJson = "{\"method\":\"sigmoid\",\"fitted\":true,"
                + "\"coefficient\":2.0,\"intercept\":0.0}";
        return model;
    }

    private static AiSampleV2 sample() {
        AiSampleV2 sample = new AiSampleV2();
        sample.id = 21L;
        sample.userId = 7L;
        sample.stockCode = "600519";
        sample.tradeDate = LocalDate.of(2026, 7, 10);
        sample.featureSnapshot = "{\"quote\":{\"price\":10.5},\"ignored\":[1,2]}";
        return sample;
    }

    private static AiFactorValueV2 factor() {
        AiFactorValueV2 factor = new AiFactorValueV2();
        factor.userId = 7L;
        factor.sampleId = 21L;
        factor.stockCode = "600519";
        factor.factorCode = "MOMENTUM_RETURN_3D";
        factor.normalizedValue = new BigDecimal("1.25");
        factor.rawValue = new BigDecimal("0.03");
        factor.missing = 0;
        return factor;
    }

    private static String checksum(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
