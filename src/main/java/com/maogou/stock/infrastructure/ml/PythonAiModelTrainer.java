package com.maogou.stock.infrastructure.ml;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.service.v2.AiModelTrainer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class PythonAiModelTrainer implements AiModelTrainer {

    private static final int MAX_OUTPUT_LENGTH = 32_000;

    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public PythonAiModelTrainer(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public TrainingArtifacts train(TrainingRequest request) {
        validate(request);
        AppProperties.Scheduler scheduler = properties.getScheduler();
        Path script = Path.of(scheduler.getTrainerScript()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(script)) {
            throw new IllegalStateException("模型训练脚本不存在：" + script);
        }
        if (scheduler.getTrainerTimeoutSeconds() <= 0) {
            throw new IllegalStateException("模型训练超时时间必须大于 0 秒");
        }
        try {
            Path outputDirectory = request.outputDirectory().toAbsolutePath().normalize();
            Files.createDirectories(outputDirectory);
            clearKnownArtifacts(outputDirectory);
            Path processLog = outputDirectory.resolve("trainer-process.log");
            Process process = new ProcessBuilder(List.of(
                    scheduler.getTrainerPythonExecutable(),
                    script.toString(),
                    "--dataset", request.datasetPath().toAbsolutePath().normalize().toString(),
                    "--output-dir", outputDirectory.toString(),
                    "--random-seed", String.valueOf(request.randomSeed())))
                    .redirectErrorStream(true)
                    .redirectOutput(processLog.toFile())
                    .start();
            boolean completed = process.waitFor(scheduler.getTrainerTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                throw new IllegalStateException("模型训练超时：" + Duration.ofSeconds(
                        scheduler.getTrainerTimeoutSeconds()) + outputSuffix(readOutput(processLog)));
            }
            String output = readOutput(processLog);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("模型训练失败（exit=" + process.exitValue() + "）："
                        + abbreviate(output));
            }
            JsonNode result = parseLastJsonLine(output);
            Path model = requiredPath(result, "model", outputDirectory);
            Path manifest = requiredPath(result, "featureManifest", outputDirectory);
            Path metrics = requiredPath(result, "metrics", outputDirectory);
            Path onnx = optionalPath(result, "onnx", outputDirectory);
            if (onnx == null) {
                throw new IllegalStateException("训练器未导出 ONNX，模型不能进入生产验证");
            }
            return new TrainingArtifacts(
                    requiredText(result, "algorithm"), model, onnx, manifest, metrics);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型训练被中断", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("无法启动或读取模型训练进程", exception);
        }
    }

    private JsonNode parseLastJsonLine(String output) {
        String[] lines = output == null ? new String[0] : output.split("\\R");
        for (int index = lines.length - 1; index >= 0; index--) {
            String value = lines[index].trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                return objectMapper.readTree(value);
            } catch (Exception ignored) {
                // Training libraries may log before the final machine-readable result.
            }
        }
        throw new IllegalStateException("模型训练没有返回结构化结果：" + abbreviate(output));
    }

    private static Path requiredPath(JsonNode result, String field, Path outputDirectory) {
        Path path = optionalPath(result, field, outputDirectory);
        if (path == null) {
            throw new IllegalStateException("模型训练结果缺少产物：" + field);
        }
        return path;
    }

    private static Path optionalPath(JsonNode result, String field, Path outputDirectory) {
        JsonNode node = result == null ? null : result.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        Path path = Path.of(node.asText()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("模型训练产物不存在：" + path);
        }
        try {
            Path realOutput = outputDirectory.toRealPath();
            Path realArtifact = path.toRealPath();
            if (!realArtifact.startsWith(realOutput)) {
                throw new IllegalStateException("模型训练产物越出输出目录：" + realArtifact);
            }
            if (Files.size(realArtifact) <= 0) {
                throw new IllegalStateException("模型训练产物为空：" + realArtifact);
            }
            return realArtifact;
        } catch (IOException exception) {
            throw new IllegalStateException("无法校验模型训练产物：" + path, exception);
        }
    }

    private static String requiredText(JsonNode result, String field) {
        String value = result == null ? "" : result.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("模型训练结果缺少字段：" + field);
        }
        return value;
    }

    private static void validate(TrainingRequest request) {
        if (request == null || request.datasetPath() == null || request.outputDirectory() == null
                || !Files.isRegularFile(request.datasetPath()) || request.randomSeed() <= 0) {
            throw new IllegalArgumentException("模型训练缺少数据集、输出目录或随机种子");
        }
    }

    private static void clearKnownArtifacts(Path outputDirectory) throws IOException {
        for (String file : List.of(
                "model.joblib", "model.onnx", "feature_manifest.json", "metrics.json",
                "trainer-process.log")) {
            Files.deleteIfExists(outputDirectory.resolve(file));
        }
    }

    private static String readOutput(Path path) throws IOException {
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
    }

    private static String outputSuffix(String output) {
        String abbreviated = abbreviate(output);
        return abbreviated.isBlank() ? "" : "：" + abbreviated;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= MAX_OUTPUT_LENGTH
                ? trimmed : trimmed.substring(trimmed.length() - MAX_OUTPUT_LENGTH);
    }
}
