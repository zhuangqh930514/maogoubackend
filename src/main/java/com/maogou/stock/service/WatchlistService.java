package com.maogou.stock.service;

import com.maogou.stock.dto.watchlist.AddWatchStockRequest;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.dto.common.PageResponse;

import java.util.List;

public interface WatchlistService {
    List<WatchStockResponse> list(String groupName);

    PageResponse<WatchStockResponse> page(String view, int page, int pageSize);

    List<String> codes(String groupName);

    WatchStockResponse add(AddWatchStockRequest request);

    void remove(String code);

    void removeBatch(List<String> codes);

    void reorder(List<String> codes);
}
