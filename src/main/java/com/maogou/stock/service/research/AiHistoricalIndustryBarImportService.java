package com.maogou.stock.service.research;

import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface AiHistoricalIndustryBarImportService {

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
            int industryCount,
            int insertedRevisions,
            int reusedRows,
            LocalDate earliestTradeDate,
            LocalDate latestTradeDate
    ) {
    }
}
