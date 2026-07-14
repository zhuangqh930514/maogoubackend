package com.maogou.stock.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.mapper.AiDailyInsightItemMapper;
import com.maogou.stock.mapper.AiDailyInsightSnapshotMapper;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.research.AiFactorPerformanceMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.mapper.research.AiPortfolioBacktestRunMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.service.research.AiResearchDailyReportSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiResearchDailyReportSourceImplTest {

    @Test
    void projectedFinalStatusAndStepCountsOverrideTheStillRunningDatabaseRun() {
        AiDailyInsightSnapshotMapper snapshotMapper = mock(AiDailyInsightSnapshotMapper.class);
        AiDailyInsightItemMapper itemMapper = mock(AiDailyInsightItemMapper.class);
        TradeRecordMapper tradeMapper = mock(TradeRecordMapper.class);
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        AiPortfolioBacktestRunMapper backtestMapper = mock(AiPortfolioBacktestRunMapper.class);
        AiFactorPerformanceMapper performanceMapper = mock(AiFactorPerformanceMapper.class);
        AiPipelineRunMapper runMapper = mock(AiPipelineRunMapper.class);
        AiPipelineStepMapper stepMapper = mock(AiPipelineStepMapper.class);
        when(snapshotMapper.selectOne(any())).thenReturn(null);
        when(tradeMapper.selectList(any())).thenReturn(List.of());
        AiPipelineRun run = new AiPipelineRun();
        run.id = 81L;
        run.status = "RUNNING";
        run.processedCount = 0;
        run.successCount = 0;
        run.failedCount = 0;
        when(runMapper.selectById(81L)).thenReturn(run);
        AiPipelineStep fetch = step("FETCH_DATA", "SUCCESS", 3, 3);
        AiPipelineStep report = step("BUILD_RESEARCH_DAILY_REPORT", "RUNNING", 0, 0);
        when(stepMapper.selectByRunIdForUpdate(81L)).thenReturn(List.of(fetch, report));
        AiResearchDailyReportSource source = new AiResearchDailyReportSourceImpl(
                snapshotMapper, itemMapper, tradeMapper, releaseMapper, backtestMapper,
                performanceMapper, runMapper, stepMapper, new ObjectMapper());

        AiResearchDailyReportSource.ReportSource result = source.load(
                5L,
                LocalDate.of(2026, 7, 10),
                new AiResearchDailyReportSource.PipelineRequest(
                        81L, null, null, "SUCCESS", null, null));

        assertThat(result.pipeline().status()).isEqualTo("SUCCESS");
        assertThat(result.pipeline().processedCount()).isEqualTo(4);
        assertThat(result.pipeline().successCount()).isEqualTo(4);
        assertThat(result.pipeline().steps()).extracting(item -> item.status())
                .containsExactly("SUCCESS", "SUCCESS");
    }

    private static AiPipelineStep step(String key, String status, int input, int output) {
        AiPipelineStep value = new AiPipelineStep();
        value.stepKey = key;
        value.status = status;
        value.inputCount = input;
        value.outputCount = output;
        return value;
    }
}
