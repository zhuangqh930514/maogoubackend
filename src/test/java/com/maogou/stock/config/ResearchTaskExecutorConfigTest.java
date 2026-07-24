package com.maogou.stock.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchTaskExecutorConfigTest {

    @Test
    void serializesResearchWritesToAvoidCrossUserDeadlocks() {
        ThreadPoolTaskExecutor executor = new ResearchTaskExecutorConfig().researchTaskExecutor();
        try {
            executor.initialize();

            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(50);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void serializesWatchlistModelCallsAndBoundsTheQueue() {
        ThreadPoolTaskExecutor executor = new ResearchTaskExecutorConfig().watchlistAnalysisTaskExecutor();
        try {
            executor.initialize();

            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(20);
        } finally {
            executor.shutdown();
        }
    }
}
