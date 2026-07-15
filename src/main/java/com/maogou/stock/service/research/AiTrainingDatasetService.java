package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetItem;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiTrainingDatasetService {

    DatasetBuildResult buildDataset(DatasetBuildRequest request);

    AiModelVersion registerModel(ModelRegistration registration);

    record DatasetBuildRequest(
            Long researchUniverseId,
            String datasetKey,
            String versionNo,
            String modelFamily,
            String purpose,
            String featureVersion,
            String labelVersion,
            String calendarVersion,
            LocalDateTime asOfTime,
            LocalDate trainStartDate,
            LocalDate trainEndDate,
            LocalDate validationStartDate,
            LocalDate validationEndDate,
            LocalDate testStartDate,
            LocalDate testEndDate,
            Integer maxHorizonDays,
            Integer purgeTradingDays,
            Integer embargoTradingDays,
            Path artifactPath
    ) {
    }

    record DatasetBuildResult(
            AiTrainingDataset dataset,
            List<AiTrainingDatasetItem> items
    ) {
    }

    record ModelRegistration(
            Long trainingDatasetId,
            String modelFamily,
            String modelKey,
            String versionNo,
            String modelType,
            String algorithm,
            String featureVersion,
            String trainerVersion,
            Long randomSeed,
            String artifactUri,
            String artifactChecksum,
            String featureManifestUri,
            String featureManifestChecksum,
            String parametersJson,
            String metricsJson,
            String calibrationJson,
            Integer sampleCount,
            boolean qualityGatePassed,
            LocalDateTime registeredAt
    ) {
    }
}
