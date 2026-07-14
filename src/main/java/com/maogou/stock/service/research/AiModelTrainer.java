package com.maogou.stock.service.research;

import java.nio.file.Path;

public interface AiModelTrainer {

    TrainingArtifacts train(TrainingRequest request);

    record TrainingRequest(
            Path datasetPath,
            Path outputDirectory,
            long randomSeed
    ) {
    }

    record TrainingArtifacts(
            String algorithm,
            Path modelPath,
            Path onnxPath,
            Path featureManifestPath,
            Path metricsPath
    ) {
    }
}
