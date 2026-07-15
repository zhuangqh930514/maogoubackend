package com.maogou.stock.dto.ai;

import com.maogou.stock.domain.entity.AiAnalysisReport;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AiAnalysisReportSummaryResponse(
        Long id,
        String stock,
        String code,
        Integer score,
        String advice,
        LocalDateTime generatedAt,
        String sourceModel,
        String status,
        String errorMessage,
        Long sampleId,
        Long strategyReleaseId,
        Integer reportVersion,
        Long supersedesReportId,
        BigDecimal dataQualityScore,
        BigDecimal calibratedConfidence,
        String finalAction,
        BigDecimal riskScore,
        String riskLevel
) {
    public static AiAnalysisReportSummaryResponse from(AiAnalysisReport entity) {
        String stockName = entity.stockName;
        if (stockName == null || stockName.isBlank() || "未知股票".equals(stockName.trim())) {
            stockName = entity.stockCode;
        }
        return new AiAnalysisReportSummaryResponse(
                entity.id,
                stockName,
                entity.stockCode,
                entity.systemScore == null ? null : entity.systemScore.intValue(),
                entity.advice,
                entity.generatedAt,
                entity.sourceModel,
                entity.status == null ? null : entity.status.name(),
                entity.errorMessage,
                entity.sampleId,
                entity.strategyReleaseId,
                entity.reportVersion,
                entity.supersedesReportId,
                entity.dataQualityScore,
                entity.calibratedConfidence,
                entity.finalAction,
                entity.riskScore,
                entity.riskLevel
        );
    }
}
