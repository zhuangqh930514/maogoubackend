package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface HistoricalRawEvidenceStore {

    RawArtifact stage(
            Long backfillRunId,
            String providerCode,
            String datasetCode,
            String sourceRevision,
            Object payload,
            LocalDateTime observedAt
    );

    record RawArtifact(
            String objectUri,
            long objectSize,
            String objectChecksum,
            String schemaVersion,
            long rowCount,
            LocalDate rangeStartDate,
            LocalDate rangeEndDate,
            LocalDateTime observedAt,
            String manifestJson
    ) {
    }
}
