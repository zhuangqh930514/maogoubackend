package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.market.IntradayPointResponse;
import com.maogou.stock.dto.market.MarketIndexResponse;
import com.maogou.stock.service.MarketDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
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

    @GetMapping("/indexes/{code}/intraday")
    public ApiResponse<List<IntradayPointResponse>> indexIntraday(@PathVariable String code) {
        return ApiResponse.ok(marketDataService.intraday(code));
    }
}
