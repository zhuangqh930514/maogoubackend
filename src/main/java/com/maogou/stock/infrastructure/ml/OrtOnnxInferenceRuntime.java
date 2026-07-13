package com.maogou.stock.infrastructure.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.lang.reflect.Array;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class OrtOnnxInferenceRuntime implements OnnxPredictionClient.InferenceRuntime, AutoCloseable {

    private final Map<String, Path> modelPaths;
    private final OrtEnvironment environment;
    private final ConcurrentMap<String, OrtSession> sessions = new ConcurrentHashMap<>();

    public OrtOnnxInferenceRuntime(Map<String, Path> modelPaths) {
        if (modelPaths == null || modelPaths.isEmpty()) {
            throw new IllegalArgumentException("ONNX 模型路径不能为空");
        }
        Map<String, Path> normalized = new LinkedHashMap<>();
        modelPaths.forEach((key, path) -> {
            if (key == null || key.isBlank() || path == null) {
                throw new IllegalArgumentException("ONNX 模型标识和路径不能为空");
            }
            Path absolute = path.toAbsolutePath().normalize();
            if (!Files.isRegularFile(absolute)) {
                throw new IllegalArgumentException("ONNX 模型文件不存在：" + absolute);
            }
            normalized.put(key, absolute);
        });
        this.modelPaths = Map.copyOf(normalized);
        this.environment = OrtEnvironment.getEnvironment("maogou-onnx");
    }

    @Override
    public OnnxPredictionClient.InferenceResult run(String modelKey, float[] orderedFeatures) {
        if (modelKey == null || modelKey.isBlank() || !modelPaths.containsKey(modelKey)) {
            throw new IllegalArgumentException("未注册 ONNX 模型：" + modelKey);
        }
        if (orderedFeatures == null || orderedFeatures.length == 0) {
            throw new IllegalArgumentException("ONNX 推理特征不能为空");
        }
        OrtSession session = sessions.computeIfAbsent(modelKey, this::openSession);
        if (session.getInputNames().size() != 1) {
            throw new IllegalStateException("当前仅支持单输入 ONNX 模型：" + modelKey);
        }
        String inputName = session.getInputNames().iterator().next();
        long[] shape = new long[]{1, orderedFeatures.length};
        try (OnnxTensor tensor = OnnxTensor.createTensor(
                environment, FloatBuffer.wrap(orderedFeatures), shape);
             OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
            Map<String, List<Float>> outputs = new LinkedHashMap<>();
            for (Map.Entry<String, OnnxValue> entry : result) {
                List<Float> values = new ArrayList<>();
                OnnxValue value = entry.getValue();
                flattenNumbers(value == null ? null : value.getValue(), values);
                outputs.put(entry.getKey(), List.copyOf(values));
            }
            if (outputs.values().stream().allMatch(List::isEmpty)) {
                throw new IllegalStateException("ONNX 模型没有返回数值输出：" + modelKey);
            }
            return new OnnxPredictionClient.InferenceResult(outputs);
        } catch (OrtException exception) {
            throw new IllegalStateException("ONNX 推理失败：" + modelKey, exception);
        }
    }

    private OrtSession openSession(String modelKey) {
        Path path = Objects.requireNonNull(modelPaths.get(modelKey), "model path");
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            return environment.createSession(path.toString(), options);
        } catch (OrtException exception) {
            throw new IllegalStateException("ONNX 模型加载失败：" + path, exception);
        }
    }

    private static void flattenNumbers(Object value, List<Float> output) {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            float numeric = number.floatValue();
            if (Float.isFinite(numeric)) {
                output.add(numeric);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> flattenNumbers(item, output));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> flattenNumbers(item, output));
            return;
        }
        if (value.getClass().isArray()) {
            for (int index = 0; index < Array.getLength(value); index++) {
                flattenNumbers(Array.get(value, index), output);
            }
        }
    }

    @Override
    public void close() {
        sessions.values().forEach(session -> {
            try {
                session.close();
            } catch (OrtException exception) {
                throw new IllegalStateException("关闭 ONNX 会话失败", exception);
            }
        });
        sessions.clear();
    }
}
