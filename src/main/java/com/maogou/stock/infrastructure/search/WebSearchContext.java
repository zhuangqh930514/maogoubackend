package com.maogou.stock.infrastructure.search;

import java.util.List;

public record WebSearchContext(
        boolean requested,
        boolean success,
        String errorMessage,
        List<WebSearchItem> results
) {
    public static WebSearchContext notRequested() {
        return new WebSearchContext(false, false, null, List.of());
    }

    public static WebSearchContext failure(String errorMessage) {
        return new WebSearchContext(true, false, errorMessage, List.of());
    }

    public static WebSearchContext success(List<WebSearchItem> results) {
        return new WebSearchContext(true, true, null, results == null ? List.of() : results);
    }

    public boolean hasResults() {
        return results != null && !results.isEmpty();
    }
}
