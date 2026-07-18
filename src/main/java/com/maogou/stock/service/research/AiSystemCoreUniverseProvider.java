package com.maogou.stock.service.research;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiSystemCoreUniverseProvider {

    List<AiResearchUniverseService.UniverseCandidate> baselineCandidates(
            LocalDate tradeDate,
            LocalDateTime asOfTime,
            Integer minimumStockCount
    );
}
