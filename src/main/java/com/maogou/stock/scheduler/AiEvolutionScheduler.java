package com.maogou.stock.scheduler;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.service.AiEvolutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AiEvolutionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiEvolutionScheduler.class);

    private final AppProperties properties;
    private final AiEvolutionService aiEvolutionService;

    public AiEvolutionScheduler(AppProperties properties, AiEvolutionService aiEvolutionService) {
        this.properties = properties;
        this.aiEvolutionService = aiEvolutionService;
    }

    @Scheduled(cron = "${maogou.scheduler.evolution-review-cron}")
    public void reviewAndLearn() {
        if (!properties.getScheduler().isEnabled()) {
            return;
        }
        try {
            aiEvolutionService.verifyReviews();
            aiEvolutionService.refreshFactors();
            log.info("AI evolution scheduled review finished");
        } catch (Exception ex) {
            log.warn("AI evolution scheduled review failed: {}", ex.getMessage(), ex);
        }
    }
}
