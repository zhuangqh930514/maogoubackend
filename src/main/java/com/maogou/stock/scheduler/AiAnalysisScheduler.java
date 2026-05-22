package com.maogou.stock.scheduler;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.service.AiAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiAnalysisScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisScheduler.class);

    private final AppProperties properties;
    private final AiAnalysisService aiAnalysisService;

    public AiAnalysisScheduler(AppProperties properties, AiAnalysisService aiAnalysisService) {
        this.properties = properties;
        this.aiAnalysisService = aiAnalysisService;
    }

    @Scheduled(fixedRateString = "${maogou.scheduler.intraday-analysis-fixed-rate-ms}")
    public void intradayAnalyze() {
        if (!properties.getScheduler().isEnabled()) {
            return;
        }
        log.info("start intraday AI analysis for watchlist");
        try {
            aiAnalysisService.analyzeWatchlist();
        } catch (Exception ex) {
            log.warn("intraday AI analysis skipped: {}", ex.getMessage());
        }
    }

    @Scheduled(cron = "${maogou.scheduler.close-analysis-cron}")
    public void closeAnalyze() {
        if (!properties.getScheduler().isEnabled()) {
            return;
        }
        log.info("start close AI analysis for watchlist");
        try {
            aiAnalysisService.analyzeWatchlist();
        } catch (Exception ex) {
            log.warn("close AI analysis skipped: {}", ex.getMessage());
        }
    }
}
