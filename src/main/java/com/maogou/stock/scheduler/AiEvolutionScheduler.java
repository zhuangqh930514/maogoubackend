package com.maogou.stock.scheduler;

import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.service.research.AiResearchOperationsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.WeekFields;

@Component
@ConditionalOnProperty(prefix = "maogou.scheduler", name = "enabled", havingValue = "true")
public class AiEvolutionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiEvolutionScheduler.class);

    private final AiResearchOperationsService operationsService;

    public AiEvolutionScheduler(AiResearchOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @Scheduled(cron = "${maogou.scheduler.weekly-evolution-cron}", zone = "Asia/Shanghai")
    public void runWeeklyEvolution() {
        LocalDate today = LocalDate.now();
        int weekYear = today.get(WeekFields.ISO.weekBasedYear());
        int week = today.get(WeekFields.ISO.weekOfWeekBasedYear());
        try {
            ResearchLabPayloads.ActionAccepted accepted = operationsService.runWeekly(
                    null,
                    request(today, "SCHEDULED:GLOBAL_WEEKLY:%d-W%02d".formatted(weekYear, week)));
            log.info("global weekly research submitted, pipelineRunId={}, status={}",
                    accepted.pipelineRunId(), accepted.status());
        } catch (RuntimeException exception) {
            log.warn("global weekly research submission failed: {}", exception.getMessage(), exception);
        }
    }

    @Scheduled(cron = "${maogou.scheduler.monthly-training-cron}", zone = "Asia/Shanghai")
    public void runMonthlyTraining() {
        LocalDate today = LocalDate.now();
        try {
            ResearchLabPayloads.ActionAccepted accepted = operationsService.runTraining(
                    null,
                    request(today, "SCHEDULED:GLOBAL_MONTHLY:" + YearMonth.from(today)));
            log.info("global monthly training submitted, pipelineRunId={}, status={}",
                    accepted.pipelineRunId(), accepted.status());
        } catch (RuntimeException exception) {
            log.warn("global monthly training submission failed: {}", exception.getMessage(), exception);
        }
    }

    private static ResearchLabPayloads.ActionRequest request(LocalDate tradeDate, String idempotencyKey) {
        return new ResearchLabPayloads.ActionRequest(
                tradeDate, null, null, null, null, null, null, idempotencyKey);
    }
}
