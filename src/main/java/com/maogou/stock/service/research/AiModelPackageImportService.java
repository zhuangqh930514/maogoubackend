package com.maogou.stock.service.research;

import org.springframework.web.multipart.MultipartFile;

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
            String packageChecksum
    ) {
    }
}
