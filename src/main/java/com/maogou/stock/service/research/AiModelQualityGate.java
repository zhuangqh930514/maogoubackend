package com.maogou.stock.service.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;

import java.util.List;

/**
 * One quality contract shared by in-process training and imported model packages.
 * A failed check keeps the model candidate-only; it never silently becomes a
 * production release.
 */
public interface AiModelQualityGate {

    Evaluation evaluate(
            AiTrainingDataset dataset,
            int sampleCount,
            JsonNode metrics,
            JsonNode calibration,
            int minimumSamples,
            double minimumTestRocAuc
    );

    record Evaluation(boolean passed, List<Check> checks) {
        public Evaluation {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }

        public String summary() {
            return passed ? "质量门通过" : checks.stream()
                    .filter(check -> !check.passed())
                    .map(Check::message)
                    .reduce((left, right) -> left + "；" + right)
                    .orElse("质量门未通过");
        }
    }

    record Check(String key, boolean passed, String expected, String actual, String message) {
    }
}
