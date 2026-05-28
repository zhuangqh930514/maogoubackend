package com.maogou.stock.dto.ai;

public record RunAnalysisRequest(
        String code,
        boolean forceRefresh,
        Long promptTemplateId,
        Long targetReportId
) {
}
