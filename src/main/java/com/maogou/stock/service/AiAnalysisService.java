package com.maogou.stock.service;

import com.maogou.stock.dto.ai.AiAnalysisReportResponse;

import java.util.List;

public interface AiAnalysisService {
    List<AiAnalysisReportResponse> listReports(String code);

    AiAnalysisReportResponse analyzeStock(String code, boolean forceRefresh);

    void analyzeWatchlist();
}
