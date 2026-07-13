package com.maogou.stock.service.v2;

import com.maogou.stock.domain.entity.v2.AiDataBatch;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.dto.market.StockDetailResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiSampleSnapshotService {

    AiDataBatch startOrGetBatch(
            Long userId,
            LocalDate tradeDate,
            String samplePhase,
            LocalDateTime asOfTime,
            String idempotencyKey
    );

    AiSampleV2 createOrGetSnapshot(SnapshotCommand command);

    AiDataBatch completeBatch(Long batchId, BatchCompletion completion);

    List<AiSampleV2> findBatchSnapshots(Long userId, Long batchId, LocalDate tradeDate);

    record SnapshotCommand(
            Long userId,
            Long dataBatchId,
            LocalDate tradeDate,
            String universeCode,
            String universeVersion,
            String samplePhase,
            LocalDateTime asOfTime,
            String marketRegime,
            String sectorCode,
            String sectorName,
            StockDetailResponse detail
    ) {
    }

    record BatchCompletion(
            String sourceStatus,
            java.math.BigDecimal qualityScore,
            String qualityStatus,
            int itemCount,
            int successCount,
            int failedCount,
            String errorMessage,
            LocalDateTime completedAt
    ) {
    }
}
