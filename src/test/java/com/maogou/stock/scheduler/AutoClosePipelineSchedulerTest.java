package com.maogou.stock.scheduler;

import com.maogou.stock.service.AutoClosePipelineService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AutoClosePipelineSchedulerTest {

    @Test
    void schedulerHonorsTheGlobalSchedulerEnabledSwitch() {
        ConditionalOnProperty condition = AutoClosePipelineScheduler.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("maogou.scheduler");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
    }

    @Test
    void waitingSourceRecoveryUsesASeparatePersistentRetrySchedule() throws Exception {
        Method recovery = AutoClosePipelineScheduler.class.getMethod("retryWaitingPipelines");
        Scheduled scheduled = recovery.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${maogou.scheduler.pipeline-recovery-fixed-delay-ms:60000}");

        AutoClosePipelineService service = mock(AutoClosePipelineService.class);
        AutoClosePipelineScheduler scheduler = new AutoClosePipelineScheduler(service);
        scheduler.retryWaitingPipelines();

        verify(service).retryWaitingPipelines();
    }
}
