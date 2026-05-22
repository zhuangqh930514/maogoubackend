package com.maogou.stock.service;

import com.maogou.stock.dto.watchlist.AddWatchStockRequest;
import com.maogou.stock.dto.watchlist.WatchStockResponse;

import java.util.List;

public interface WatchlistService {
    List<WatchStockResponse> list(String groupName);

    WatchStockResponse add(AddWatchStockRequest request);

    void remove(String code);
}
