package com.maogou.stock.dto.ai;

import com.maogou.stock.domain.entity.AiAnalysisReport;

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
        String status
) {
    public static AiAnalysisReportResponse from(AiAnalysisReport entity) {
        return new AiAnalysisReportResponse(
                entity.id,
                entity.stockName,
                entity.stockCode,
                entity.score,
                entity.advice,
                entity.generatedAt,
                entity.technicalAnalysis,
                entity.riskWarning,
                entity.buySellPoints,
                entity.promptSummary,
                entity.status == null ? null : entity.status.name()
        );
    }
}
