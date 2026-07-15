package com.maogou.stock.controller;

import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.dto.home.HomeOverviewResponse;
import com.maogou.stock.service.HomeOverviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/home")
public class HomeController {

    private final HomeOverviewService homeOverviewService;

    public HomeController(HomeOverviewService homeOverviewService) {
        this.homeOverviewService = homeOverviewService;
    }

    @GetMapping("/overview")
    public ApiResponse<HomeOverviewResponse> overview() {
        return ApiResponse.ok(homeOverviewService.overview());
    }
}
