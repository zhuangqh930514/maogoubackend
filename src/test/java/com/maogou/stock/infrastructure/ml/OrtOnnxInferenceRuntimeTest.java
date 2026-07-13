package com.maogou.stock.infrastructure.ml;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrtOnnxInferenceRuntimeTest {

    @Test
    void rejectsMissingModelArtifactsBeforeOpeningNativeSessions() {
        assertThatThrownBy(() -> new OrtOnnxInferenceRuntime(Map.of(
                "challenger-v1", Path.of("target/missing-challenger.onnx"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型文件不存在");
    }
}
