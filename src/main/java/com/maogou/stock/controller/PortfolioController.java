package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.portfolio.BatchPortfolioPositionRequest;
import com.maogou.stock.dto.portfolio.PortfolioSummaryResponse;
import com.maogou.stock.dto.portfolio.TradeRecordCreateRequest;
import com.maogou.stock.dto.portfolio.TradeRecordResponse;
import com.maogou.stock.service.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/trades")
    public ApiResponse<List<TradeRecordResponse>> trades() {
        return ApiResponse.ok(portfolioService.trades());
    }

    @PostMapping("/trades")
    public ApiResponse<TradeRecordResponse> addBuy(@RequestBody @Valid TradeRecordCreateRequest request) {
        return ApiResponse.ok(portfolioService.addBuyRecord(request));
    }

    @PostMapping("/positions/batch-delete")
    public ApiResponse<Void> removePositions(@RequestBody @Valid BatchPortfolioPositionRequest request) {
        portfolioService.removePositions(request.codes());
        return ApiResponse.ok(null);
    }

    @GetMapping("/positions")
    public ApiResponse<PortfolioSummaryResponse> positions() {
        return ApiResponse.ok(portfolioService.portfolio());
    }
}
