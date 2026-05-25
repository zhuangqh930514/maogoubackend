package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockSearchResponse;
import com.maogou.stock.service.MarketDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stocks")
public class StockController {

    private final MarketDataService marketDataService;

    public StockController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/search")
    public ApiResponse<List<StockSearchResponse>> search(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(marketDataService.searchStocks(keyword, limit));
    }

    @GetMapping("/{code}")
    public ApiResponse<StockDetailResponse> detail(@PathVariable String code) {
        return ApiResponse.ok(marketDataService.stockDetail(code));
    }

    @GetMapping("/{code}/kline")
    public ApiResponse<List<KlinePointResponse>> kline(
            @PathVariable String code,
            @RequestParam(defaultValue = "day") String period,
            @RequestParam(defaultValue = "60") int limit
    ) {
        return ApiResponse.ok(marketDataService.kline(code, period, limit));
    }
}
