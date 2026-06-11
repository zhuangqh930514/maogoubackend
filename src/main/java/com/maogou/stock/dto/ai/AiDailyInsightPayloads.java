package com.maogou.stock.dto.ai;

import com.maogou.stock.domain.entity.AiDailyInsightItem;
import com.maogou.stock.domain.entity.AiDailyInsightSnapshot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class AiDailyInsightPayloads {
    private AiDailyInsightPayloads() {
    }

    public record DailyInsightResponse(
            boolean schemaReady,
            boolean snapshotReady,
            String message,
            SnapshotSummary summary,
            List<InsightItem> recommendations,
            List<InsightItem> watches,
            List<InsightItem> avoids,
            List<JobLogItem> latestJobLogs
    ) {
    }

    public record SnapshotSummary(
            Long snapshotId,
            LocalDate tradeDate,
            LocalDateTime generatedAt,
            String pipelineStatus,
            String pipelineMessage,
            String freshnessStatus,
            BigDecimal dataQualityScore,
            Integer recommendationCount,
            Integer avoidCount,
            Integer watchCount,
            Integer itemCount,
            Integer lowSampleCount,
            BigDecimal overallHitRate,
            LocalDateTime latestReportAt,
            LocalDateTime latestSampleAt,
            Long latestJobLogId
    ) {
        public static SnapshotSummary empty(LocalDate tradeDate, String message) {
            return new SnapshotSummary(
                    null,
                    tradeDate,
                    null,
                    "EMPTY",
                    message,
                    "EMPTY",
                    BigDecimal.ZERO,
                    0,
                    0,
                    0,
                    0,
                    0,
                    BigDecimal.ZERO,
                    null,
                    null,
                    null
            );
        }

        public static SnapshotSummary from(AiDailyInsightSnapshot entity) {
            return new SnapshotSummary(
                    entity.id,
                    entity.tradeDate,
                    entity.generatedAt,
                    entity.pipelineStatus,
                    entity.pipelineMessage,
                    entity.freshnessStatus,
                    entity.dataQualityScore,
                    entity.recommendationCount,
                    entity.avoidCount,
                    entity.watchCount,
                    entity.itemCount,
                    entity.lowSampleCount,
                    entity.overallHitRate,
                    entity.latestReportAt,
                    entity.latestSampleAt,
                    entity.latestJobLogId
            );
        }
    }

    public record InsightItem(
            Long id,
            String stockCode,
            String stockName,
            String finalAction,
            String actionBucket,
            BigDecimal compositeScore,
            BigDecimal systemScore,
            String aiDecision,
            BigDecimal aiConfidence,
            String targetDirection,
            String riskLevel,
            BigDecimal riskScore,
            BigDecimal dataQualityScore,
            BigDecimal freshnessScore,
            String freshnessStatus,
            String freshnessMessage,
            BigDecimal historicalHitRate,
            Integer historicalSampleCount,
            String confidenceLevel,
            String triggerFactorsJson,
            String reasonSummary,
            Long reportId,
            Long predictionId,
            Long sampleId,
            LocalDateTime reportGeneratedAt,
            LocalDateTime sampleTime
    ) {
        public static InsightItem from(AiDailyInsightItem entity) {
            return new InsightItem(
                    entity.id,
                    entity.stockCode,
                    entity.stockName,
                    entity.finalAction,
                    entity.actionBucket,
                    entity.compositeScore,
                    entity.systemScore,
                    entity.aiDecision,
                    entity.aiConfidence,
                    entity.targetDirection,
                    entity.riskLevel,
                    entity.riskScore,
                    entity.dataQualityScore,
                    entity.freshnessScore,
                    entity.freshnessStatus,
                    entity.freshnessMessage,
                    entity.historicalHitRate,
                    entity.historicalSampleCount,
                    entity.confidenceLevel,
                    entity.triggerFactorsJson,
                    entity.reasonSummary,
                    entity.reportId,
                    entity.predictionId,
                    entity.sampleId,
                    entity.reportGeneratedAt,
                    entity.sampleTime
            );
        }
    }

    public record JobLogItem(
            Long id,
            String jobName,
            String jobType,
            String status,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            Integer processedCount,
            Integer successCount,
            Integer failedCount,
            String errorMessage
    ) {
    }
}
