package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiTrainingReadinessMetric;
import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiTrainingReadinessServiceImplTest {

    @Test
    void exposesTradabilityCoverageAndBlocksTrainingBelowThreshold() {
        AiTrainingDatasetItemMapper mapper = mock(AiTrainingDatasetItemMapper.class);
        when(mapper.selectTrainingReadinessMetrics(any(), any())).thenReturn(List.of(
                metric("TRADING_DAYS", "ALL", 120),
                metric("STOCKS", "ALL", 200),
                metric("HORIZON", "1", 20_000), metric("HORIZON", "2", 20_000),
                metric("HORIZON", "3", 20_000), metric("HORIZON", "5", 20_000),
                metric("REGIME", "UP", 20), metric("REGIME", "DOWN", 20), metric("REGIME", "SIDEWAYS", 20),
                metric("TRADABILITY_STATE", "ELIGIBLE", 80_000),
                metric("TRADABILITY_STATE", "READY", 78_000),
                metric("UNIVERSE_MEMBERSHIP", "ELIGIBLE", 80_000),
                metric("UNIVERSE_MEMBERSHIP", "READY", 80_000),
                metric("SECTOR_EVIDENCE", "ELIGIBLE", 80_000),
                metric("SECTOR_EVIDENCE", "READY", 76_000)));

        var readiness = new AiTrainingReadinessServiceImpl(mapper).assess(LocalDateTime.of(2026, 7, 17, 18, 0));

        assertThat(readiness.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(readiness.tradabilityEligibleLabels()).isEqualTo(80_000);
        assertThat(readiness.readyTradabilityLabels()).isEqualTo(78_000);
        assertThat(readiness.tradabilityCoverage()).isEqualTo(0.975d);
        assertThat(readiness.universeCoverage()).isEqualTo(1d);
        assertThat(readiness.sectorEvidenceCoverage()).isEqualTo(0.95d);
    }

    private static AiTrainingReadinessMetric metric(String type, String key, int count) {
        AiTrainingReadinessMetric metric = new AiTrainingReadinessMetric();
        metric.dimensionType = type;
        metric.dimensionKey = key;
        metric.metricCount = count;
        return metric;
    }
}
