package com.maogou.stock.scheduler;

import com.maogou.stock.service.v2.AiEvolutionAutomationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "maogou.scheduler", name = "enabled", havingValue = "true")
public class AiEvolutionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiEvolutionScheduler.class);

    private final AiEvolutionAutomationService automationService;

    public AiEvolutionScheduler(AiEvolutionAutomationService automationService) {
        this.automationService = automationService;
    }

    @Scheduled(cron = "${maogou.scheduler.weekly-evolution-cron}", zone = "Asia/Shanghai")
    public void runWeeklyEvolution() {
        try {
            automationService.runWeeklyForEnabledUsers();
            log.info("AI V2 weekly evolution finished");
        } catch (Exception ex) {
            log.warn("AI V2 weekly evolution failed: {}", ex.getMessage(), ex);
        }
    }

    @Scheduled(cron = "${maogou.scheduler.monthly-training-cron}", zone = "Asia/Shanghai")
    public void runMonthlyTraining() {
        try {
            automationService.runMonthlyForEnabledUsers();
            log.info("AI V2 monthly training finished");
        } catch (Exception ex) {
            log.warn("AI V2 monthly training failed: {}", ex.getMessage(), ex);
        }
    }
}
