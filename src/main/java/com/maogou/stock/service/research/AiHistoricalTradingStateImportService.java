package com.maogou.stock.service.research;

import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.LocalDate;

/**
 * Imports vendor-provided historical trading constraints as immutable daily-state revisions.
 * This intentionally does not infer historical ST status from a current security name.
 */
public interface AiHistoricalTradingStateImportService {

    ImportResult importCsv(ImportRequest request);

    record ImportRequest(
            MultipartFile file,
            String sourceName,
            String sourceRevision,
            LocalDateTime sourceObservedAt,
            Long operatorUserId
    ) {
    }

    record ImportResult(
            String fileChecksum,
            int parsedRows,
            int insertedRevisions,
            int reusedRows,
            int insertedUniverseSnapshots,
            int reusedUniverseSnapshots,
            LocalDate earliestTradeDate,
            LocalDate latestTradeDate
    ) {
    }
}
