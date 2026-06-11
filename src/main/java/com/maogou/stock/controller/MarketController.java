package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.market.IntradayPointResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.MarketBreadthResponse;
import com.maogou.stock.dto.market.MarketIndexResponse;
import com.maogou.stock.dto.market.SectorHeatmapResponse;
import com.maogou.stock.dto.market.SectorHotStocksResponse;
import com.maogou.stock.service.MarketDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketDataService marketDataService;

    public MarketController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/indexes")
    public ApiResponse<List<MarketIndexResponse>> indexes() {
        return ApiResponse.ok(marketDataService.coreIndexes());
    }

    @GetMapping("/breadth")
    public ApiResponse<MarketBreadthResponse> breadth() {
        return ApiResponse.ok(marketDataService.marketBreadth());
    }

    @GetMapping("/sectors/heatmap")
    public ApiResponse<SectorHeatmapResponse> sectorHeatmap() {
        return ApiResponse.ok(marketDataService.sectorHeatmap());
    }

    @GetMapping("/hot-stocks")
    public ApiResponse<SectorHotStocksResponse> marketHotStocks(
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(marketDataService.marketHotStocks(limit));
    }

    @GetMapping("/sectors/{code}/hot-stocks")
    public ApiResponse<SectorHotStocksResponse> sectorHotStocks(
            @PathVariable String code,
            @RequestParam(defaultValue = "10") int limit
    ) {
        return ApiResponse.ok(marketDataService.sectorHotStocks(code, limit));
    }

    @GetMapping("/indexes/{code}/intraday")
    public ApiResponse<List<IntradayPointResponse>> indexIntraday(@PathVariable String code) {
        return ApiResponse.ok(marketDataService.intraday(code));
    }

    @GetMapping("/indexes/{code}/kline")
    public ApiResponse<List<KlinePointResponse>> indexKline(
            @PathVariable String code,
            @RequestParam(defaultValue = "day") String period,
            @RequestParam(defaultValue = "120") int limit
    ) {
        return ApiResponse.ok(marketDataService.kline(code, period, limit));
    }
}
