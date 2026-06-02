package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.watchlist.AddWatchStockRequest;
import com.maogou.stock.dto.watchlist.BatchWatchStockRequest;
import com.maogou.stock.dto.watchlist.ReorderWatchStockRequest;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.service.WatchlistService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    @GetMapping
    public ApiResponse<List<WatchStockResponse>> list(@RequestParam(required = false) String groupName) {
        return ApiResponse.ok(watchlistService.list(groupName));
    }

    @GetMapping("/codes")
    public ApiResponse<List<String>> codes(@RequestParam(required = false) String groupName) {
        return ApiResponse.ok(watchlistService.codes(groupName));
    }

    @PostMapping
    public ApiResponse<WatchStockResponse> add(@RequestBody @Valid AddWatchStockRequest request) {
        return ApiResponse.ok(watchlistService.add(request));
    }

    @DeleteMapping("/{code}")
    public ApiResponse<Void> remove(@PathVariable String code) {
        watchlistService.remove(code);
        return ApiResponse.ok(null);
    }

    @PostMapping("/batch-delete")
    public ApiResponse<Void> removeBatch(@RequestBody @Valid BatchWatchStockRequest request) {
        watchlistService.removeBatch(request.codes());
        return ApiResponse.ok(null);
    }

    @PutMapping("/reorder")
    public ApiResponse<Void> reorder(@RequestBody @Valid ReorderWatchStockRequest request) {
        watchlistService.reorder(request.codes());
        return ApiResponse.ok(null);
    }
}
