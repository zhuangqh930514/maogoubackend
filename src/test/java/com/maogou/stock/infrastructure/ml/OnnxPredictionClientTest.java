package com.maogou.stock.infrastructure.ml;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxPredictionClientTest {

    @Test
    void selectsTheDeclaredNamedOutputAndAppliesTrainingSigmoidCalibration() {
        OnnxPredictionClient client = new OnnxPredictionClient(
                new OnnxPredictionClient.ModelContract(
                        "challenger-v2",
                        List.of(OnnxPredictionClient.FeatureSpec.required("momentum_20")),
                        "probabilities",
                        1,
                        OnnxPredictionClient.RawOutputKind.PROBABILITY_UP,
                        new OnnxPredictionClient.SigmoidCalibration(2.0f, -1.0f)),
                (modelKey, orderedFeatures) -> new OnnxPredictionClient.InferenceResult(Map.of(
                        "label", List.of(1.0f),
                        "probabilities", List.of(0.2f, 0.8f))));

        OnnxPredictionClient.Prediction prediction = client.predict(Map.of("momentum_20", 1.2d));

        assertThat(prediction.rawOutput()).isEqualTo(0.8f);
        assertThat(prediction.probabilityUp()).isCloseTo(0.8548f, org.assertj.core.data.Offset.offset(0.0002f));
        assertThat(prediction.outputs()).containsOnlyKeys("label", "probabilities");
    }

    @Test
    void ordersFeaturesByImmutableModelContractBeforeCallingRuntime() {
        AtomicReference<float[]> captured = new AtomicReference<>();
        OnnxPredictionClient client = new OnnxPredictionClient(
                new OnnxPredictionClient.ModelContract("challenger-v2", List.of(
                        OnnxPredictionClient.FeatureSpec.required("momentum_20"),
                        OnnxPredictionClient.FeatureSpec.required("volatility_20"),
                        OnnxPredictionClient.FeatureSpec.optional("turnover", -999f))),
                (modelKey, orderedFeatures) -> {
                    captured.set(orderedFeatures.clone());
                    return new OnnxPredictionClient.InferenceResult(
                            Map.of("output", List.of(0.72f, 0.28f)));
                });
        Map<String, Number> unordered = new LinkedHashMap<>();
        unordered.put("turnover", 3.5d);
        unordered.put("volatility_20", 0.4d);
        unordered.put("momentum_20", 1.2d);

        OnnxPredictionClient.Prediction result = client.predict(unordered);

        assertThat(captured.get()).containsExactly(1.2f, 0.4f, 3.5f);
        assertThat(result.modelKey()).isEqualTo("challenger-v2");
        assertThat(result.outputs().get("output")).containsExactly(0.72f, 0.28f);
    }

    @Test
    void appliesConfiguredSentinelOnlyToOptionalMissingFeatures() {
        OnnxPredictionClient client = client();

        assertThat(client.prepareFeatures(Map.of("momentum_20", 1.5d)))
                .containsExactly(1.5f, Float.NaN);
        assertThatThrownBy(() -> client.prepareFeatures(Map.of("volatility_20", 0.2d)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("momentum_20");
    }

    @Test
    void rejectsUnexpectedDuplicateOrNonFiniteFeaturesAtTheRuntimeBoundary() {
        OnnxPredictionClient client = client();

        assertThatThrownBy(() -> client.prepareFeatures(Map.of(
                "momentum_20", 1d,
                "unknown", 2d)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
        assertThatThrownBy(() -> client.prepareFeatures(Map.of("momentum_20", Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("有限数值");
        assertThatThrownBy(() -> new OnnxPredictionClient.ModelContract("bad", List.of(
                OnnxPredictionClient.FeatureSpec.required("momentum_20"),
                OnnxPredictionClient.FeatureSpec.required("momentum_20"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复");
    }

    private static OnnxPredictionClient client() {
        return new OnnxPredictionClient(
                new OnnxPredictionClient.ModelContract("challenger-v2", List.of(
                        OnnxPredictionClient.FeatureSpec.required("momentum_20"),
                        OnnxPredictionClient.FeatureSpec.optional("volatility_20", Float.NaN))),
                (modelKey, orderedFeatures) -> new OnnxPredictionClient.InferenceResult(
                        Map.of("output", List.of())));
    }
}
