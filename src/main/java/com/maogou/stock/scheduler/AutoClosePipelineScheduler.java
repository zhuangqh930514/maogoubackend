package com.maogou.stock.scheduler;

import com.maogou.stock.service.AutoClosePipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "maogou.scheduler", name = "enabled", havingValue = "true")
public class AutoClosePipelineScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoClosePipelineScheduler.class);

    private final AutoClosePipelineService autoClosePipelineService;

    public AutoClosePipelineScheduler(AutoClosePipelineService autoClosePipelineService) {
        this.autoClosePipelineService = autoClosePipelineService;
    }

    @Scheduled(cron = "${maogou.scheduler.auto-close-pipeline-cron}", zone = "Asia/Shanghai")
    public void runAutoClosePipeline() {
        log.info("auto close pipeline scheduler triggered");
        autoClosePipelineService.runEnabledPipelines();
    }

    @Scheduled(fixedDelayString = "${maogou.scheduler.pipeline-recovery-fixed-delay-ms:60000}")
    public void retryWaitingPipelines() {
        autoClosePipelineService.retryWaitingPipelines();
    }
}
