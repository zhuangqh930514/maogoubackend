package com.maogou.stock.service.research;

import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.dto.market.StockDetailResponse;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiSampleSnapshotService {

    AiDataBatch startOrGetBatch(
            Long universeSnapshotId,
            LocalDate tradeDate,
            String samplePhase,
            LocalDateTime asOfTime,
            String idempotencyKey
    );

    AiSample createOrGetSnapshot(SnapshotCommand command);

    AiDataBatch completeBatch(Long batchId, BatchCompletion completion);

    List<AiSample> findBatchSnapshots(Long batchId, LocalDate tradeDate);

    record SnapshotCommand(
            Long dataBatchId,
            Long universeItemId,
            LocalDate tradeDate,
            String samplePhase,
            LocalDateTime asOfTime,
            String marketRegime,
            String sectorCode,
            String sectorName,
            String stockName,
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
