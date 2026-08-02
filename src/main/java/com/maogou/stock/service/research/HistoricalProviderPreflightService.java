package com.maogou.stock.service.research;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface HistoricalProviderPreflightService {

    PreflightResult check(LocalDateTime asOfTime, String benchmarkSymbol);

    record PreflightResult(
            List<Map<String, Object>> capabilities,
            List<Map<String, Object>> blockingIssues,
            boolean ready
    ) {
        public PreflightResult {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            blockingIssues = blockingIssues == null ? List.of() : List.copyOf(blockingIssues);
        }
    }

    static HistoricalProviderPreflightService noop() {
        return (asOfTime, benchmarkSymbol) -> new PreflightResult(
                List.of(), List.of(), true);
    }
}
