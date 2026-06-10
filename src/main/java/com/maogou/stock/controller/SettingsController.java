package com.maogou.stock.controller;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.common.ApiResponse;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.dto.settings.ConnectionTestResponse;
import com.maogou.stock.dto.settings.ModelConfigRequest;
import com.maogou.stock.dto.settings.ModelConfigResponse;
import com.maogou.stock.dto.settings.SchedulerStatusResponse;
import com.maogou.stock.dto.settings.SchedulerToggleRequest;
import com.maogou.stock.service.ModelConfigService;
import com.maogou.stock.service.TradingCalendarService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ModelConfigService modelConfigService;
    private final AppProperties properties;
    private final TradingCalendarService tradingCalendarService;

    public SettingsController(
            ModelConfigService modelConfigService,
            AppProperties properties,
            TradingCalendarService tradingCalendarService
    ) {
        this.modelConfigService = modelConfigService;
        this.properties = properties;
        this.tradingCalendarService = tradingCalendarService;
    }

    @GetMapping("/model")
    public ApiResponse<ModelConfigResponse> model() {
        return ApiResponse.ok(modelConfigService.current());
    }

    @PutMapping("/model")
    public ApiResponse<ModelConfigResponse> saveModel(@RequestBody @Valid ModelConfigRequest request) {
        return ApiResponse.ok(modelConfigService.save(request));
    }

    @PostMapping("/model/test")
    public ApiResponse<ConnectionTestResponse> test(@RequestBody @Valid ModelConfigRequest request) {
        return ApiResponse.ok(modelConfigService.testConnection(request));
    }

    @GetMapping("/scheduler/status")
    public ApiResponse<SchedulerStatusResponse> schedulerStatus() {
        AiModelConfig entity = modelConfigService.currentEntity();
        ModelConfigResponse config = modelConfigService.current();
        AppProperties.Scheduler scheduler = properties.getScheduler();
        return ApiResponse.ok(new SchedulerStatusResponse(
                scheduler.isEnabled(),
                scheduler.getNewsFixedRateMs(),
                scheduler.getIntradayAnalysisFixedRateMs(),
                scheduler.getCloseAnalysisCron(),
                scheduler.getEvolutionReviewCron(),
                config.intradayInterval(),
                config.closeTime(),
                config.analysisScope(),
                nextCloseAnalysisTime(config.closeTime()),
                "交易日 16:10",
                entity.autoClosePipelineEnabled != null && entity.autoClosePipelineEnabled == 1,
                "RUNNING".equalsIgnoreCase(nullToEmpty(entity.autoClosePipelineLastStatus)),
                scheduler.getAutoClosePipelineCron(),
                nextAutoClosePipelineTime(),
                formatDateTime(entity.autoClosePipelineLastRunAt),
                formatDateTime(entity.autoClosePipelineLastFinishedAt),
                nullToEmpty(entity.autoClosePipelineLastStatus),
                nullToEmpty(entity.autoClosePipelineLastMessage)
        ));
    }

    @PutMapping("/scheduler/auto-close-pipeline")
    public ApiResponse<SchedulerStatusResponse> toggleAutoClosePipeline(@RequestBody SchedulerToggleRequest request) {
        modelConfigService.setAutoClosePipelineEnabled(request.enabled());
        return schedulerStatus();
    }

    private static String nextCloseAnalysisTime(String closeTime) {
        LocalTime time = LocalTime.parse(closeTime == null || closeTime.isBlank() ? "15:30" : closeTime, DateTimeFormatter.ofPattern("HH:mm"));
        LocalDate today = LocalDate.now();
        LocalDateTime next = LocalDateTime.of(today, time);
        if (!next.isAfter(LocalDateTime.now())) {
            next = next.plusDays(1);
        }
        return next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private String nextAutoClosePipelineTime() {
        LocalDateTime next = tradingCalendarService.nextTradingDateTime(LocalDateTime.now(), 16, 0);
        return next.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String formatDateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
