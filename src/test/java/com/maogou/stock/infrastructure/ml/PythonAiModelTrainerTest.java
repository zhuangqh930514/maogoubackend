package com.maogou.stock.infrastructure.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.service.research.AiModelTrainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PythonAiModelTrainerTest {

    @TempDir
    Path temporary;

    @Test
    void rejectsMissingTrainerScriptBeforeStartingAProcess() throws Exception {
        AppProperties properties = properties(temporary.resolve("missing.sh"), 5);
        PythonAiModelTrainer trainer = trainer(properties);

        assertThatThrownBy(() -> trainer.train(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("训练脚本不存在");
    }

    @Test
    void terminatesTrainingAfterConfiguredTimeoutAndKeepsDiagnosticOutput() throws Exception {
        Path script = script("""
                printf 'trainer started\\n'
                sleep 5
                """);
        PythonAiModelTrainer trainer = trainer(properties(script, 1));

        assertThatThrownBy(() -> trainer.train(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模型训练超时")
                .hasMessageContaining("trainer started");
    }

    @Test
    void reportsNonZeroExitCodeAndProcessOutput() throws Exception {
        Path script = script("""
                printf 'invalid training data\\n'
                exit 9
                """);
        PythonAiModelTrainer trainer = trainer(properties(script, 5));

        assertThatThrownBy(() -> trainer.train(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exit=9")
                .hasMessageContaining("invalid training data");
    }

    @Test
    void rejectsSuccessfulProcessThatDidNotExportOnnx() throws Exception {
        Path output = temporary.resolve("artifacts");
        String result = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "algorithm", "LOGISTIC_REGRESSION",
                "model", output.resolve("model.joblib").toString(),
                "featureManifest", output.resolve("feature_manifest.json").toString(),
                "metrics", output.resolve("metrics.json").toString()
        ));
        Path script = script("""
                mkdir -p '%s'
                printf 'model' > '%s'
                printf '{}' > '%s'
                printf '{}' > '%s'
                printf '%%s\\n' '%s'
                """.formatted(
                output,
                output.resolve("model.joblib"),
                output.resolve("feature_manifest.json"),
                output.resolve("metrics.json"),
                result));
        PythonAiModelTrainer trainer = trainer(properties(script, 5));

        assertThatThrownBy(() -> trainer.train(request()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未导出 ONNX");
    }

    private AiModelTrainer.TrainingRequest request() throws Exception {
        Path dataset = temporary.resolve("dataset.jsonl");
        if (!Files.exists(dataset)) {
            Files.writeString(dataset, "{}\n");
        }
        return new AiModelTrainer.TrainingRequest(
                dataset, temporary.resolve("artifacts"), 930514L);
    }

    private Path script(String body) throws Exception {
        Path script = temporary.resolve("trainer-" + Math.abs(body.hashCode()) + ".sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        return script;
    }

    private static AppProperties properties(Path script, long timeoutSeconds) {
        AppProperties properties = new AppProperties();
        properties.getScheduler().setTrainerPythonExecutable("/bin/sh");
        properties.getScheduler().setTrainerScript(script.toString());
        properties.getScheduler().setTrainerTimeoutSeconds(timeoutSeconds);
        return properties;
    }

    private static PythonAiModelTrainer trainer(AppProperties properties) {
        return new PythonAiModelTrainer(properties, new ObjectMapper());
    }
}
