package com.maogou.stock.dto.ai;

import com.maogou.stock.domain.entity.AiAnalysisReport;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AiAnalysisReportResponse(
        Long id,
        String stock,
        String code,
        Integer score,
        String advice,
        LocalDateTime generatedAt,
        String technicalAnalysis,
        String riskWarning,
        String buySellPoints,
        String promptSummary,
        String sourceModel,
        String status,
        String errorMessage,
        Long sampleId,
        Long predictionId,
        Long strategyVersionId,
        BigDecimal dataQualityScore,
        BigDecimal calibratedConfidence
) {
    public static AiAnalysisReportResponse from(AiAnalysisReport entity) {
        String stockName = entity.stockName;
        if (stockName == null || stockName.isBlank() || "未知股票".equals(stockName.trim())) {
            stockName = entity.stockCode;
        }
        return new AiAnalysisReportResponse(
                entity.id,
                stockName,
                entity.stockCode,
                entity.score,
                entity.advice,
                entity.generatedAt,
                entity.technicalAnalysis,
                entity.riskWarning,
                entity.buySellPoints,
                entity.promptSummary,
                entity.sourceModel,
                entity.status == null ? null : entity.status.name(),
                entity.errorMessage,
                entity.sampleId,
                entity.predictionId,
                entity.strategyVersionId,
                entity.dataQualityScore,
                entity.calibratedConfidence
        );
    }
}
