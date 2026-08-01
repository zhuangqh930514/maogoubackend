package com.maogou.stock.service;

import com.maogou.stock.domain.entity.UserPositionSnapshot;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.dto.portfolio.PositionSnapshotSummary;

import java.util.List;

public interface UserPositionSnapshotService {

    void rebuild(Long userId, String stockCode, StockQuoteResponse quote);

    void refreshForQuote(StockQuoteResponse quote);

    Page page(Long userId, int page, int pageSize);

    record Page(
            List<UserPositionSnapshot> items,
            long total,
            PositionSnapshotSummary summary,
            int page,
            int pageSize,
            int totalPages
    ) {
    }
}
