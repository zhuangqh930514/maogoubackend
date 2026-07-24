package com.maogou.stock.service;

import com.maogou.stock.dto.ai.WatchlistAnalysisJobResponse;

public interface WatchlistAnalysisJobService {
    WatchlistAnalysisJobResponse submit(Long promptTemplateId);

    WatchlistAnalysisJobResponse current();

    WatchlistAnalysisJobResponse detail(Long jobId);
}
