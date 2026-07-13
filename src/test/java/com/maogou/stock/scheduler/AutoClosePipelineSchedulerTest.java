package com.maogou.stock.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.assertj.core.api.Assertions.assertThat;

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
}
