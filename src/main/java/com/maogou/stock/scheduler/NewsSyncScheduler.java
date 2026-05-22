package com.maogou.stock.scheduler;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.service.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NewsSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsSyncScheduler.class);

    private final AppProperties properties;
    private final MarketDataService marketDataService;

    public NewsSyncScheduler(AppProperties properties, MarketDataService marketDataService) {
        this.properties = properties;
        this.marketDataService = marketDataService;
    }

    @Scheduled(fixedRateString = "${maogou.scheduler.news-fixed-rate-ms}")
    public void syncNews() {
        if (!properties.getScheduler().isEnabled()) {
            return;
        }
        try {
            int size = marketDataService.latestNews(20).size();
            log.info("news sync completed, fetched={}", size);
        } catch (Exception ex) {
            log.warn("news sync skipped: {}", ex.getMessage());
        }
    }
}
