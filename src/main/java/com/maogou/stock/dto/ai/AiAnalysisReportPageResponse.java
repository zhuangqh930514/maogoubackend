package com.maogou.stock.dto.ai;

import java.time.LocalDate;
import java.util.List;

public record AiAnalysisReportPageResponse(
        List<AiAnalysisReportResponse> items,
        long total,
        int page,
        int pageSize,
        int totalPages,
        LocalDate selectedDate,
        LocalDate latestAvailableDate
) {
    public static AiAnalysisReportPageResponse empty(int pageSize) {
        return new AiAnalysisReportPageResponse(List.of(), 0, 1, pageSize, 0, null, null);
    }
}
