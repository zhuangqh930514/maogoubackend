package com.maogou.stock.infrastructure.search;

public interface WebSearchService {
    WebSearchContext search(String query, int limit);
}
