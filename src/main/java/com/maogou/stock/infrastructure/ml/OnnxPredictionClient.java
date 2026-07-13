package com.maogou.stock.infrastructure.ml;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class OnnxPredictionClient {

    private final ModelContract contract;
    private final InferenceRuntime runtime;

    public OnnxPredictionClient(ModelContract contract, InferenceRuntime runtime) {
        this.contract = Objects.requireNonNull(contract, "model contract");
        this.runtime = Objects.requireNonNull(runtime, "inference runtime");
    }

    public float[] prepareFeatures(Map<String, ? extends Number> features) {
        if (features == null) {
            throw new IllegalArgumentException("ONNX 特征不能为空");
        }
        Set<String> expected = contract.features().stream()
                .map(FeatureSpec::name)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> unexpected = new LinkedHashSet<>(features.keySet());
        unexpected.removeAll(expected);
        if (!unexpected.isEmpty()) {
            throw new IllegalArgumentException("ONNX 特征包含清单外字段：" + unexpected);
        }

        float[] ordered = new float[contract.features().size()];
        for (int index = 0; index < contract.features().size(); index++) {
            FeatureSpec spec = contract.features().get(index);
            Number value = features.get(spec.name());
            if (value == null) {
                if (spec.required()) {
                    throw new IllegalArgumentException("ONNX 必填特征缺失：" + spec.name());
                }
                ordered[index] = spec.missingValue();
                continue;
            }
            float numeric = value.floatValue();
            if (!Float.isFinite(numeric)) {
                throw new IllegalArgumentException("ONNX 特征必须是有限数值：" + spec.name());
            }
            ordered[index] = numeric;
        }
        return ordered;
    }

    public Prediction predict(Map<String, ? extends Number> features) {
        InferenceResult result = runtime.run(contract.modelKey(), prepareFeatures(features));
        if (result == null) {
            throw new IllegalStateException("ONNX runtime 返回空结果");
        }
        List<Float> selected = result.outputs().get(contract.outputName());
        if (selected == null) {
            throw new IllegalStateException("ONNX runtime 缺少声明输出：" + contract.outputName());
        }
        if (contract.outputIndex() < 0 || contract.outputIndex() >= selected.size()) {
            throw new IllegalStateException("ONNX 声明输出索引越界：" + contract.outputName()
                    + "[" + contract.outputIndex() + "]");
        }
        float rawOutput = selected.get(contract.outputIndex());
        if (!Float.isFinite(rawOutput)) {
            throw new IllegalStateException("ONNX 声明输出不是有限数值：" + rawOutput);
        }
        float calibrationInput = contract.rawOutputKind() == RawOutputKind.PROBABILITY_UP
                ? logit(rawOutput) : rawOutput;
        float probabilityUp = contract.calibration().apply(calibrationInput);
        return new Prediction(contract.modelKey(), result.outputs(), rawOutput, calibrationInput, probabilityUp);
    }

    private static float logit(float probability) {
        double clipped = Math.max(1.0e-7d, Math.min(1.0d - 1.0e-7d, probability));
        return (float) Math.log(clipped / (1.0d - clipped));
    }

    @FunctionalInterface
    public interface InferenceRuntime {
        InferenceResult run(String modelKey, float[] orderedFeatures);
    }

    public record InferenceResult(Map<String, List<Float>> outputs) {
        public InferenceResult {
            if (outputs == null || outputs.isEmpty()) {
                throw new IllegalArgumentException("ONNX 输出不能为空");
            }
            Map<String, List<Float>> immutable = new LinkedHashMap<>();
            outputs.forEach((name, values) -> {
                if (name == null || name.isBlank() || values == null) {
                    throw new IllegalArgumentException("ONNX 输出名称和值不能为空");
                }
                immutable.put(name, List.copyOf(values));
            });
            outputs = Map.copyOf(immutable);
        }
    }

    public record FeatureSpec(String name, boolean required, Float missingValue) {
        public FeatureSpec {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("ONNX 特征名不能为空");
            }
            if (!required && missingValue == null) {
                throw new IllegalArgumentException("ONNX 可选特征必须配置缺失值：" + name);
            }
            if (!required && Float.isInfinite(missingValue)) {
                throw new IllegalArgumentException("ONNX 缺失值不能是无穷大：" + name);
            }
        }

        public static FeatureSpec required(String name) {
            return new FeatureSpec(name, true, null);
        }

        public static FeatureSpec optional(String name, float missingValue) {
            return new FeatureSpec(name, false, missingValue);
        }
    }

    public enum RawOutputKind {
        RAW_SCORE,
        PROBABILITY_UP
    }

    public record SigmoidCalibration(float coefficient, float intercept) {
        public SigmoidCalibration {
            if (!Float.isFinite(coefficient) || !Float.isFinite(intercept)) {
                throw new IllegalArgumentException("sigmoid 校准参数必须是有限数值");
            }
        }

        public float apply(float rawScore) {
            double value = coefficient * rawScore + intercept;
            double clipped = Math.max(-35.0d, Math.min(35.0d, value));
            return (float) (1.0d / (1.0d + Math.exp(-clipped)));
        }
    }

    public record ModelContract(
            String modelKey,
            List<FeatureSpec> features,
            String outputName,
            int outputIndex,
            RawOutputKind rawOutputKind,
            SigmoidCalibration calibration
    ) {
        public ModelContract(String modelKey, List<FeatureSpec> features) {
            this(modelKey, features, "output", 0, RawOutputKind.RAW_SCORE,
                    new SigmoidCalibration(1.0f, 0.0f));
        }

        public ModelContract {
            if (modelKey == null || modelKey.isBlank()) {
                throw new IllegalArgumentException("ONNX 模型标识不能为空");
            }
            if (features == null || features.isEmpty() || features.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("ONNX 特征清单不能为空");
            }
            features = List.copyOf(features);
            Set<String> names = new HashSet<>();
            for (FeatureSpec feature : features) {
                if (!names.add(feature.name())) {
                    throw new IllegalArgumentException("ONNX 特征清单包含重复字段：" + feature.name());
                }
            }
            if (outputName == null || outputName.isBlank() || outputIndex < 0
                    || rawOutputKind == null || calibration == null) {
                throw new IllegalArgumentException("ONNX 输出与校准契约不能为空");
            }
        }
    }

    public record Prediction(
            String modelKey,
            Map<String, List<Float>> outputs,
            float rawOutput,
            float calibrationInput,
            float probabilityUp
    ) {
        public Prediction {
            outputs = Map.copyOf(outputs);
        }
    }
}
