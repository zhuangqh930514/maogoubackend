package com.maogou.stock.service.research;

import com.maogou.stock.dto.research.ResearchOperationsOverviewPayloads;

/** Operator-only read model. It never triggers research, retries, or model inference. */
public interface AiResearchOperationsOverviewService {

    ResearchOperationsOverviewPayloads.Overview overview(Integer requestedWindowDays);
}
