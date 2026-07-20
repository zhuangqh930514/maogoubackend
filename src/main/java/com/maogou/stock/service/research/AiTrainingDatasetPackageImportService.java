package com.maogou.stock.service.research;

import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Imports a local, immutable dataset only after every row is resolved to production facts.
 */
public interface AiTrainingDatasetPackageImportService {

    PreviewResult preview(MultipartFile packageFile, Long operatorUserId);

    ImportResult importPackage(MultipartFile packageFile, Long operatorUserId);

    record Rejection(Integer lineNumber, String reason) {
    }

    record PreviewResult(
            String datasetKey,
            String versionNo,
            String lineageFingerprint,
            String packageChecksum,
            int declaredRows,
            int matchedRows,
            int rejectedRows,
            boolean compatible,
            boolean alreadyImported,
            List<Rejection> rejections
    ) {
    }

    record ImportResult(
            Long trainingDatasetId,
            String datasetKey,
            String versionNo,
            String lineageFingerprint,
            String status,
            String packageChecksum,
            int rowCount,
            boolean reused
    ) {
    }
}
