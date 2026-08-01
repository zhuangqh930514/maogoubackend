package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.service.research.AiModelQualityGate;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelQualityGateImplTest {

    private final AiModelQualityGateImpl gate = new AiModelQualityGateImpl();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rejectsCandidateWhenTimeSplitOrOnnxEvidenceIsMissing() throws Exception {
        AiModelQualityGate.Evaluation evaluation = evaluate(10, false);
        assertFalse(evaluation.passed());
        assertTrue(evaluation.checks().stream().anyMatch(check -> "SAMPLE_COUNT".equals(check.key())));
        assertTrue(evaluation.checks().stream().anyMatch(check -> "ONNX_EXPORTED".equals(check.key())));
    }

    @Test
    void acceptsDatasetOnlyWhenAllQualityChecksPass() throws Exception {
        AiModelQualityGate.Evaluation evaluation = evaluate(1000, true);
        assertTrue(evaluation.passed());
    }

    private AiModelQualityGate.Evaluation evaluate(int samples, boolean artifacts) throws Exception {
        AiTrainingDataset dataset = dataset();
        var metrics = objectMapper.readTree("""
                {
                  "artifacts":{"onnxExported":%s,"onnxParity":{"verified":%s}},
                  "splits":{"validation":{"rocAuc":0.61},"test":{"rocAuc":0.58}},
                  "calibration":{"fitted":true}
                }
                """.formatted(artifacts, artifacts));
        var calibration = objectMapper.readTree("{\"fitted\":true}");
        return gate.evaluate(dataset, samples, metrics, calibration, 1000, 0.55);
    }

    private static AiTrainingDataset dataset() {
        AiTrainingDataset dataset = new AiTrainingDataset();
        dataset.status = "READY";
        dataset.rowCount = 1000;
        dataset.trainStartDate = LocalDate.of(2024, 1, 1);
        dataset.trainEndDate = LocalDate.of(2024, 12, 31);
        dataset.validationStartDate = LocalDate.of(2025, 1, 8);
        dataset.validationEndDate = LocalDate.of(2025, 3, 31);
        dataset.testStartDate = LocalDate.of(2025, 4, 8);
        dataset.testEndDate = LocalDate.of(2025, 6, 30);
        dataset.purgeTradingDays = 5;
        dataset.embargoTradingDays = 5;
        return dataset;
    }
}
