package com.maogou.stock.service.research;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Imports a locally trained model package after validating its immutable research lineage.
 */
public interface AiModelPackageImportService {

    ImportResult importCandidate(MultipartFile packageFile, Long operatorUserId);

    record ImportResult(
            Long modelId,
            Long trainingDatasetId,
            String modelFamily,
            String modelKey,
            String versionNo,
            String status,
            String packageChecksum,
            QualityGateSummary qualityGate,
            Long challengerId,
            String message
    ) {
        public ImportResult(
                Long modelId,
                Long trainingDatasetId,
                String modelFamily,
                String modelKey,
                String versionNo,
                String status,
                String packageChecksum
        ) {
            this(modelId, trainingDatasetId, modelFamily, modelKey, versionNo, status, packageChecksum,
                    new QualityGateSummary(false, List.of(), "未执行统一质量门"), null, null);
        }
    }

    record QualityGateSummary(
            boolean passed,
            List<AiModelQualityGate.Check> checks,
            String summary
    ) {
        public QualityGateSummary {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }
}
