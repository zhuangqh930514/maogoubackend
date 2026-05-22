package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.market.NewsFlashResponse;
import com.maogou.stock.service.MarketDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final MarketDataService marketDataService;

    public NewsController(MarketDataService marketDataService) {
        this.marketDataService = marketDataService;
    }

    @GetMapping("/latest")
    public ApiResponse<List<NewsFlashResponse>> latest(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(marketDataService.latestNews(limit));
    }
}
