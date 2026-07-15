package com.maogou.stock.service;

import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.ai.AiAnalysisReportPageResponse;
import com.maogou.stock.dto.ai.AiAnalysisReportSummaryResponse;

import java.time.LocalDate;
import java.util.List;

public interface AiAnalysisService {
    List<AiAnalysisReportSummaryResponse> listReports(String code);

    AiAnalysisReportResponse report(Long reportId);

    AiAnalysisReportResponse latestReport(String code);

    AiAnalysisReportPageResponse pageReports(String code, LocalDate date, int page, int pageSize, String filter);

    void removeReports(List<Long> ids);

    AiAnalysisReportResponse analyzeStock(String code, boolean forceRefresh, Long promptTemplateId, Long targetReportId);

    default AiAnalysisReportResponse analyzeStockForTradeDate(
            String code,
            boolean forceRefresh,
            Long promptTemplateId,
            Long targetReportId,
            LocalDate tradeDate
    ) {
        return analyzeStock(code, forceRefresh, promptTemplateId, targetReportId);
    }

    void analyzeWatchlist(Long promptTemplateId);
}
