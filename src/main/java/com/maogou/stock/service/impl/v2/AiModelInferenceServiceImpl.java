package com.maogou.stock.service.impl.v2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.v2.AiFactorValueV2;
import com.maogou.stock.domain.entity.v2.AiModelVersion;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.infrastructure.ml.OnnxPredictionClient;
import com.maogou.stock.infrastructure.ml.OrtOnnxInferenceRuntime;
import com.maogou.stock.mapper.v2.AiModelVersionMapper;
import com.maogou.stock.service.v2.AiModelInferenceService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AiModelInferenceServiceImpl implements AiModelInferenceService {

    private static final double MIN_FEATURE_COVERAGE = 0.40d;

    private final AiModelVersionMapper modelMapper;
    private final ObjectMapper objectMapper;
    private final RuntimeFactory runtimeFactory;
    private final ConcurrentMap<Long, LoadedModel> cache = new ConcurrentHashMap<>();

    @Autowired
    public AiModelInferenceServiceImpl(AiModelVersionMapper modelMapper, ObjectMapper objectMapper) {
        this(modelMapper, objectMapper, modelPath -> new RuntimeHandle(new OrtOnnxInferenceRuntime(
                Map.of(modelPath.modelKey(), modelPath.path()))));
    }

    AiModelInferenceServiceImpl(
            AiModelVersionMapper modelMapper,
            ObjectMapper objectMapper,
            RuntimeFactory runtimeFactory
    ) {
        this.modelMapper = modelMapper;
        this.objectMapper = objectMapper;
        this.runtimeFactory = runtimeFactory;
    }

    @Override
    public ModelInference infer(
            Long userId,
            Long modelVersionId,
            AiSampleV2 sample,
            List<AiFactorValueV2> factors
    ) {
        validateRequest(userId, modelVersionId, sample);
        AiModelVersion version = modelMapper.selectById(modelVersionId);
        validateVersion(userId, version);
        LoadedModel loaded = cache.compute(modelVersionId, (ignored, existing) -> {
            if (existing != null && Objects.equals(existing.artifactChecksum(), version.artifactChecksum)
                    && Objects.equals(existing.manifestChecksum(), version.featureManifestChecksum)) {
                return existing;
            }
            if (existing != null) {
                existing.close();
            }
            return load(version);
        });

        Map<String, Number> available = availableFeatures(sample, factors);
        Map<String, Number> selected = new LinkedHashMap<>();
        int matched = 0;
        for (String featureName : loaded.featureNames()) {
            Number value = available.get(featureName);
            if (value != null) {
                selected.put(featureName, value);
                matched++;
            }
        }
        int minimumMatched = Math.max(1, (int) Math.ceil(loaded.featureNames().size() * MIN_FEATURE_COVERAGE));
        if (matched < minimumMatched) {
            throw new IllegalStateException("模型特征覆盖不足：" + matched + "/" + loaded.featureNames().size());
        }

        OnnxPredictionClient.Prediction prediction = loaded.client().predict(selected);
        return new ModelInference(
                version.id,
                version.modelKey,
                version.versionNo,
                version.artifactChecksum,
                prediction.rawOutput(),
                prediction.probabilityUp(),
                loaded.featureNames().size(),
                matched);
    }

    private LoadedModel load(AiModelVersion version) {
        Path artifact = localArtifact(version.artifactUri, "ONNX 模型产物");
        Path manifest = localArtifact(version.featureManifestUri, "模型特征清单");
        verifyChecksum(artifact, version.artifactChecksum, "ONNX 模型产物");
        verifyChecksum(manifest, version.featureManifestChecksum, "模型特征清单");
        JsonNode manifestJson = readJsonFile(manifest, "模型特征清单");
        JsonNode calibrationJson = readJson(version.calibrationJson, "模型校准信息");

        List<OnnxPredictionClient.FeatureSpec> featureSpecs = parseFeatures(manifestJson.path("features"));
        JsonNode output = manifestJson.path("onnxOutput");
        if (!output.isObject()) {
            throw new IllegalStateException("模型特征清单缺少 onnxOutput 契约");
        }
        String outputName = requiredText(output.path("name"), "onnxOutput.name");
        int outputIndex = output.path("index").asInt(-1);
        OnnxPredictionClient.RawOutputKind outputKind;
        try {
            outputKind = OnnxPredictionClient.RawOutputKind.valueOf(
                    requiredText(output.path("kind"), "onnxOutput.kind"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("模型 onnxOutput.kind 不受支持", exception);
        }
        String method = requiredText(calibrationJson.path("method"), "calibration.method");
        if (!"sigmoid".equalsIgnoreCase(method)) {
            throw new IllegalStateException("当前仅支持 sigmoid 模型校准：" + method);
        }
        float coefficient = finiteFloat(calibrationJson.path("coefficient"), "calibration.coefficient");
        float intercept = finiteFloat(calibrationJson.path("intercept"), "calibration.intercept");
        JsonNode manifestCalibration = manifestJson.path("calibration");
        if (!manifestCalibration.isObject()
                || Float.compare(coefficient, finiteFloat(
                manifestCalibration.path("coefficient"), "manifest.calibration.coefficient")) != 0
                || Float.compare(intercept, finiteFloat(
                manifestCalibration.path("intercept"), "manifest.calibration.intercept")) != 0) {
            throw new IllegalStateException("模型清单与注册校准参数不一致");
        }

        String runtimeKey = "model-" + version.id + "-" + version.artifactChecksum;
        RuntimeHandle runtime = runtimeFactory.create(new ModelPath(runtimeKey, artifact));
        try {
            OnnxPredictionClient client = new OnnxPredictionClient(
                    new OnnxPredictionClient.ModelContract(
                            runtimeKey,
                            featureSpecs,
                            outputName,
                            outputIndex,
                            outputKind,
                            new OnnxPredictionClient.SigmoidCalibration(coefficient, intercept)),
                    runtime.runtime());
            return new LoadedModel(
                    client,
                    runtime,
                    featureSpecs.stream().map(OnnxPredictionClient.FeatureSpec::name).toList(),
                    version.artifactChecksum,
                    version.featureManifestChecksum);
        } catch (RuntimeException exception) {
            runtime.close();
            throw exception;
        }
    }

    private static List<OnnxPredictionClient.FeatureSpec> parseFeatures(JsonNode node) {
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalStateException("模型特征清单不能为空");
        }
        List<OnnxPredictionClient.FeatureSpec> specs = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isTextual()) {
                specs.add(OnnxPredictionClient.FeatureSpec.optional(item.asText(), Float.NaN));
                continue;
            }
            if (!item.isObject()) {
                throw new IllegalStateException("模型特征声明必须是字符串或对象");
            }
            String name = requiredText(item.path("name"), "feature.name");
            boolean required = item.path("required").asBoolean(false);
            specs.add(required
                    ? OnnxPredictionClient.FeatureSpec.required(name)
                    : OnnxPredictionClient.FeatureSpec.optional(name,
                    item.hasNonNull("missingValue") ? finiteFloat(item.path("missingValue"), name + ".missingValue")
                            : Float.NaN));
        }
        return List.copyOf(specs);
    }

    private Map<String, Number> availableFeatures(AiSampleV2 sample, List<AiFactorValueV2> factors) {
        Map<String, Number> values = new HashMap<>();
        if (sample.featureSnapshot != null && !sample.featureSnapshot.isBlank()) {
            try {
                flattenNumeric(objectMapper.readTree(sample.featureSnapshot), "", values);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("不可变样本特征快照不是有效 JSON：" + sample.id, exception);
            }
        }
        if (factors != null) {
            for (AiFactorValueV2 factor : factors) {
                if (factor == null || factor.factorCode == null || factor.factorCode.isBlank()
                        || factor.missing != null && factor.missing == 1) {
                    continue;
                }
                Number normalized = factor.normalizedValue;
                Number raw = factor.rawValue;
                if (normalized != null) {
                    values.put(factor.factorCode, normalized);
                    values.put("factors." + factor.factorCode, normalized);
                    values.put("normalizedFactors." + factor.factorCode, normalized);
                }
                if (raw != null) {
                    values.put("rawFactors." + factor.factorCode, raw);
                }
            }
        }
        return values;
    }

    private static void flattenNumeric(JsonNode node, String prefix, Map<String, Number> output) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> flattenNumeric(
                    entry.getValue(), prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), output));
            return;
        }
        if (node.isNumber() && !prefix.isEmpty()) {
            output.put(prefix, node.decimalValue());
        }
    }

    private static void validateRequest(Long userId, Long modelVersionId, AiSampleV2 sample) {
        if (userId == null || userId <= 0 || modelVersionId == null || modelVersionId <= 0
                || sample == null || sample.id == null || !Objects.equals(userId, sample.userId)) {
            throw new IllegalArgumentException("模型推理缺少用户、模型版本或不可变样本血缘");
        }
    }

    private static void validateVersion(Long userId, AiModelVersion version) {
        if (version == null || version.id == null || !Objects.equals(userId, version.userId)) {
            throw new IllegalArgumentException("模型版本不存在或不属于当前用户");
        }
        if (!"VALIDATED".equals(version.status)) {
            throw new IllegalStateException("只有 VALIDATED 模型可以进入生产或影子推理");
        }
        if (blank(version.modelKey) || blank(version.versionNo) || blank(version.artifactUri)
                || blank(version.artifactChecksum) || blank(version.featureManifestUri)
                || blank(version.featureManifestChecksum) || blank(version.calibrationJson)) {
            throw new IllegalStateException("模型版本缺少生产推理产物或校准血缘");
        }
    }

    private static Path localArtifact(String uriValue, String label) {
        try {
            URI uri = new URI(uriValue);
            Path path = uri.getScheme() == null ? Path.of(uriValue) : "file".equalsIgnoreCase(uri.getScheme())
                    ? Path.of(uri) : null;
            if (path == null || !Files.isRegularFile(path)) {
                throw new IllegalStateException(label + "不存在或不支持非 file URI：" + uriValue);
            }
            return path.toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IllegalStateException(label + " URI 无效：" + uriValue, exception);
        }
    }

    private static void verifyChecksum(Path path, String expected, String label) {
        String actual = sha256(path);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IllegalStateException(label + " checksum 不匹配：" + path);
        }
    }

    private JsonNode readJsonFile(Path path, String label) {
        try {
            return objectMapper.readTree(path.toFile());
        } catch (IOException exception) {
            throw new IllegalStateException(label + "不是有效 JSON：" + path, exception);
        }
    }

    private JsonNode readJson(String content, String label) {
        try {
            return objectMapper.readTree(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(label + "不是有效 JSON", exception);
        }
    }

    private static String requiredText(JsonNode node, String label) {
        String value = node == null ? null : node.asText();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(label + " 不能为空");
        }
        return value;
    }

    private static float finiteFloat(JsonNode node, String label) {
        double value = node == null ? Double.NaN : node.asDouble(Double.NaN);
        if (!Double.isFinite(value)) {
            throw new IllegalStateException(label + " 必须是有限数值");
        }
        return (float) value;
    }

    private static String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("无法计算模型产物 checksum：" + path, exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @PreDestroy
    public void close() {
        cache.values().forEach(LoadedModel::close);
        cache.clear();
    }

    @FunctionalInterface
    interface RuntimeFactory {
        RuntimeHandle create(ModelPath modelPath);
    }

    record ModelPath(String modelKey, Path path) {
    }

    static final class RuntimeHandle implements AutoCloseable {
        private final OnnxPredictionClient.InferenceRuntime runtime;
        private final AutoCloseable closeable;

        RuntimeHandle(OnnxPredictionClient.InferenceRuntime runtime, AutoCloseable closeable) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.closeable = closeable;
        }

        RuntimeHandle(OrtOnnxInferenceRuntime runtime) {
            this(runtime, runtime);
        }

        OnnxPredictionClient.InferenceRuntime runtime() {
            return runtime;
        }

        @Override
        public void close() {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (Exception exception) {
                throw new IllegalStateException("关闭 ONNX 推理运行时失败", exception);
            }
        }
    }

    private record LoadedModel(
            OnnxPredictionClient client,
            RuntimeHandle runtime,
            List<String> featureNames,
            String artifactChecksum,
            String manifestChecksum
    ) implements AutoCloseable {
        @Override
        public void close() {
            runtime.close();
        }
    }
}
