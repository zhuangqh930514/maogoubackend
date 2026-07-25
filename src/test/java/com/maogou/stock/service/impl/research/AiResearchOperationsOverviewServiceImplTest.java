package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiSourceHealth;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.dto.research.ResearchOperationsOverviewPayloads;
import com.maogou.stock.mapper.research.AiDataBatchMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiResearchOperationsOverviewMapper;
import com.maogou.stock.mapper.research.AiSourceHealthMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiResearchOperationsOverviewServiceImplTest {

    @Test
    void aggregatesOnlyPersistedEvidenceAndKeepsFailureContextVisible() {
        AiResearchOperationsOverviewMapper overviewMapper = mock(AiResearchOperationsOverviewMapper.class);
        AiPipelineRunMapper runMapper = mock(AiPipelineRunMapper.class);
        AiSourceHealthMapper sourceHealthMapper = mock(AiSourceHealthMapper.class);
        AiDataBatchMapper dataBatchMapper = mock(AiDataBatchMapper.class);
        AiResearchOperationsOverviewServiceImpl service = new AiResearchOperationsOverviewServiceImpl(
                overviewMapper, runMapper, sourceHealthMapper, dataBatchMapper);
        LocalDateTime now = LocalDateTime.now().minusMinutes(2);
        LocalDate tradeDate = LocalDate.of(2026, 7, 24);

        AiPipelineRun latest = run(301L, "SUCCESS", tradeDate, now.minusMinutes(8), now.minusMinutes(3));
        latest.dataBatchId = 61L;
        when(overviewMapper.selectLatestGlobalRun()).thenReturn(latest);
        when(overviewMapper.selectRunStatusCounts(any())).thenReturn(List.of(
                count("SUCCESS", 6), count("PARTIAL_SUCCESS", 1), count("FAILED_RECOVERABLE", 1)));
        when(overviewMapper.selectCompletedRuns(any(), anyInt())).thenReturn(List.of(
                run(1L, "SUCCESS", tradeDate, now.minusSeconds(5), now.minusSeconds(4)),
                run(2L, "SUCCESS", tradeDate, now.minusSeconds(7), now.minusSeconds(4)),
                run(3L, "SUCCESS", tradeDate, now.minusSeconds(10), now.minusSeconds(4))));
        AiPipelineRun attention = run(302L, "FAILED_RECOVERABLE", tradeDate, now.minusMinutes(4), now.minusMinutes(2));
        attention.currentStep = "BUILD_SAMPLES";
        attention.retryCount = 2;
        attention.nextRetryAt = now.plusMinutes(5);
        attention.errorMessage = "股票代码=600519；数据提供方=sina；read timed out";
        attention.failedCount = 1;
        when(overviewMapper.selectAttentionRuns(any(), anyInt())).thenReturn(List.of(attention));
        AiPipelineRun stale = run(303L, "RUNNING", tradeDate, now.minusHours(2), null);
        stale.currentStep = "GENERATE_STOCK_REPORTS";
        stale.retryCount = 1;
        when(runMapper.selectStaleRunning(any(), any(), anyInt())).thenReturn(List.of(stale));

        AiSourceHealth source = new AiSourceHealth();
        source.id = 71L;
        source.providerCode = "eastmoney";
        source.endpointType = "KLINE";
        source.sourceStatus = "UNAVAILABLE";
        source.consecutiveFailureCount = 3;
        source.lastErrorMessage = "远端主动断开";
        when(sourceHealthMapper.selectList(any())).thenReturn(List.of(source));
        when(overviewMapper.selectSampleCoverage(61L)).thenReturn(List.of(count("READY", 302), count("UNAVAILABLE", 2)));

        AiAnalysisReport rateLimited = failedReport(901L, "600519", "qwen3.6", "HTTP 429 rate limit");
        AiAnalysisReport unauthorized = failedReport(902L, "300058", "deepseek", "HTTP 401 API key invalid");
        when(overviewMapper.selectRecentModelFailures(any(), anyInt())).thenReturn(List.of(rateLimited, unauthorized));
        when(overviewMapper.selectModelFailureCount(any())).thenReturn(2L);

        when(overviewMapper.selectEligibleUserCount()).thenReturn(3L);
        when(overviewMapper.selectMissingDailyReportUserCount(tradeDate)).thenReturn(1L);
        AiResearchOperationsOverviewMapper.UserReportGapRow userGap = new AiResearchOperationsOverviewMapper.UserReportGapRow();
        userGap.userId = 8L;
        userGap.displayName = "测试用户";
        userGap.hasWatchlist = 1;
        when(overviewMapper.selectUsersMissingDailyReport(tradeDate, 100)).thenReturn(List.of(userGap));
        AiResearchOperationsOverviewMapper.ConsecutiveReportGapRow consecutiveGap =
                new AiResearchOperationsOverviewMapper.ConsecutiveReportGapRow();
        consecutiveGap.userId = 8L;
        consecutiveGap.displayName = "测试用户";
        consecutiveGap.missingTradeDates = "2026-07-24,2026-07-23";
        when(overviewMapper.selectUsersMissingTwoLatestDailyReports(100)).thenReturn(List.of(consecutiveGap));

        when(overviewMapper.selectActiveHoldingCount()).thenReturn(2L);
        when(overviewMapper.selectHoldingWithoutDailyConclusionCount(tradeDate)).thenReturn(1L);
        AiResearchOperationsOverviewMapper.HoldingGapRow holdingGap = new AiResearchOperationsOverviewMapper.HoldingGapRow();
        holdingGap.userId = 8L;
        holdingGap.stockCode = "300058";
        holdingGap.stockName = "蓝色光标";
        holdingGap.netQuantity = 100L;
        when(overviewMapper.selectHoldingsWithoutDailyConclusion(tradeDate, 100)).thenReturn(List.of(holdingGap));

        when(overviewMapper.selectDecisionConflictCount(tradeDate)).thenReturn(1L);
        when(overviewMapper.selectDailyDecisionWithoutReportCount(tradeDate)).thenReturn(4L);
        AiResearchOperationsOverviewMapper.DecisionConflictRow conflict = new AiResearchOperationsOverviewMapper.DecisionConflictRow();
        conflict.userId = 8L;
        conflict.decisionItemId = 41L;
        conflict.reportId = 901L;
        conflict.stockCode = "600519";
        conflict.stockName = "贵州茅台";
        conflict.decisionAction = "WATCH";
        conflict.reportAction = "BUY";
        when(overviewMapper.selectDecisionConflicts(tradeDate, 100)).thenReturn(List.of(conflict));

        AiDataBatch batch = new AiDataBatch();
        batch.id = 61L;
        batch.universeSnapshotId = 23L;
        when(dataBatchMapper.selectById(61L)).thenReturn(batch);
        when(overviewMapper.selectUniversePollutionCount(23L, 61L)).thenReturn(1L);
        AiResearchOperationsOverviewMapper.UniversePollutionRow pollution = new AiResearchOperationsOverviewMapper.UniversePollutionRow();
        pollution.universeItemId = 61L;
        pollution.stockCode = "600734";
        pollution.stockName = "*ST实达";
        pollution.sourceType = "USER_WATCHLIST";
        pollution.listedStatus = "LISTED";
        pollution.qualityStatus = "READY";
        pollution.tradableStatus = "TRADABLE";
        pollution.issueType = "ST_SECURITY";
        pollution.cause = "名称命中 ST 风险标识";
        when(overviewMapper.selectUniversePollutionItems(23L, 61L, 100)).thenReturn(List.of(pollution));
        when(overviewMapper.selectUniverseLineageCount(23L)).thenReturn(2L);
        when(overviewMapper.selectInvalidUniverseLineageCount(23L)).thenReturn(1L);
        AiResearchOperationsOverviewMapper.UniverseLineageRow invalidLineage = new AiResearchOperationsOverviewMapper.UniverseLineageRow();
        invalidLineage.universeItemId = 61L;
        invalidLineage.stockCode = "600734";
        invalidLineage.stockName = "*ST实达";
        invalidLineage.sourceType = "USER_WATCHLIST";
        invalidLineage.ownerUserId = 8L;
        invalidLineage.sourceRecordId = 22L;
        invalidLineage.activeAtSnapshot = 0;
        invalidLineage.cause = "快照来源记录未标记为当时有效，禁止作为正式用户兴趣来源";
        when(overviewMapper.selectInvalidUniverseLineages(23L, 100)).thenReturn(List.of(invalidLineage));

        ResearchOperationsOverviewPayloads.Overview result = service.overview(14);

        assertThat(result.tradeDate()).isEqualTo(tradeDate);
        assertThat(result.tasks().totalRuns()).isEqualTo(8);
        assertThat(result.tasks().statusCounts()).containsEntry("SUCCESS", 6L);
        assertThat(result.tasks().latencyP50Millis()).isEqualTo(3000L);
        assertThat(result.tasks().latencyP95Millis()).isEqualTo(6000L);
        assertThat(result.tasks().staleRunningCount()).isEqualTo(1);
        assertThat(result.sources().coverage()).contains(
                new ResearchOperationsOverviewPayloads.Coverage("READY", 302),
                new ResearchOperationsOverviewPayloads.Coverage("UNAVAILABLE", 2));
        assertThat(result.modelFailures().groupedCounts()).containsEntry("RATE_LIMIT", 1L)
                .containsEntry("AUTHORIZATION", 1L);
        assertThat(result.dailyReports().reportReadyUserCount()).isEqualTo(2);
        assertThat(result.dailyReports().consecutiveMissingUsers()).singleElement()
                .extracting(ResearchOperationsOverviewPayloads.ConsecutiveReportGap::missingTradeDates)
                .isEqualTo("2026-07-24,2026-07-23");
        assertThat(result.holdings().withoutDailyConclusionCount()).isEqualTo(1);
        assertThat(result.decisionConflicts().conflictCount()).isEqualTo(1);
        assertThat(result.decisionConflicts().withoutReportCount()).isEqualTo(4);
        assertThat(result.universePollution().issueCount()).isEqualTo(1);
        assertThat(result.universeLineage().recordedCount()).isEqualTo(2);
        assertThat(result.universeLineage().invalidCount()).isEqualTo(1);
        assertThat(result.alerts()).anySatisfy(alert -> {
            assertThat(alert.category()).isEqualTo("PIPELINE_ATTENTION");
            assertThat(alert.pipelineRunId()).isEqualTo(302L);
            assertThat(alert.step()).isEqualTo("BUILD_SAMPLES");
            assertThat(alert.stockCode()).isEqualTo("600519");
            assertThat(alert.providerCode()).isEqualTo("sina");
            assertThat(alert.retryCount()).isEqualTo(2);
            assertThat(alert.nextRetryAt()).isEqualTo(attention.nextRetryAt);
        });
        assertThat(result.alerts()).extracting(ResearchOperationsOverviewPayloads.Alert::category)
                .contains("STALE_RUNNING", "SOURCE_HEALTH", "HOLDING_CONCLUSION_MISSING", "UNIVERSE_POLLUTION",
                        "UNIVERSE_LINEAGE_INVALID", "DAILY_REPORT_MISSING_CONSECUTIVE");
    }

    @Test
    void classifiesModelFailuresWithoutCallingThemRecoverableByGuesswork() {
        assertThat(AiResearchOperationsOverviewServiceImpl.classifyModelFailure("socket timeout"))
                .isEqualTo("TIMEOUT");
        assertThat(AiResearchOperationsOverviewServiceImpl.classifyModelFailure("malformed JSON response"))
                .isEqualTo("STRUCTURE");
        assertThat(AiResearchOperationsOverviewServiceImpl.classifyModelFailure("unexpected provider message"))
                .isEqualTo("UNKNOWN");
    }

    private static AiPipelineRun run(
            Long id, String status, LocalDate tradeDate, LocalDateTime startedAt, LocalDateTime finishedAt) {
        AiPipelineRun run = new AiPipelineRun();
        run.id = id;
        run.scopeType = "GLOBAL";
        run.tradeDate = tradeDate;
        run.pipelineType = "GLOBAL_DAILY_RESEARCH";
        run.status = status;
        run.startedAt = startedAt;
        run.finishedAt = finishedAt;
        run.updatedAt = finishedAt == null ? startedAt : finishedAt;
        run.processedCount = 1;
        run.successCount = "SUCCESS".equals(status) ? 1 : 0;
        run.failedCount = 0;
        run.retryCount = 0;
        return run;
    }

    private static AiResearchOperationsOverviewMapper.StatusCountRow count(String status, long value) {
        AiResearchOperationsOverviewMapper.StatusCountRow row = new AiResearchOperationsOverviewMapper.StatusCountRow();
        row.status = status;
        row.recordCount = value;
        return row;
    }

    private static AiAnalysisReport failedReport(Long id, String stockCode, String model, String cause) {
        AiAnalysisReport report = new AiAnalysisReport();
        report.id = id;
        report.userId = 8L;
        report.stockCode = stockCode;
        report.sourceModel = model;
        report.status = AnalysisStatus.FAILED;
        report.errorMessage = cause;
        report.generatedAt = LocalDateTime.now();
        return report;
    }
}
