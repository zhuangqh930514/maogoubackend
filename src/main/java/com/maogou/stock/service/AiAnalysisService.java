package com.maogou.stock.service;

import com.maogou.stock.dto.ai.AiAnalysisReportResponse;

import java.util.List;

public interface AiAnalysisService {
    List<AiAnalysisReportResponse> listReports(String code);

    void removeReports(List<Long> ids);

    AiAnalysisReportResponse analyzeStock(String code, boolean forceRefresh, Long promptTemplateId, Long targetReportId);

    void analyzeWatchlist(Long promptTemplateId);
}
