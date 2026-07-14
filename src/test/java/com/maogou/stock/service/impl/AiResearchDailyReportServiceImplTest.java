package com.maogou.stock.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItemPrediction;
import com.maogou.stock.domain.entity.research.AiDailyDecisionSnapshot;
import com.maogou.stock.domain.entity.research.AiResearchDailyReport;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.mapper.research.AiDailyDecisionItemMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionItemPredictionMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionSnapshotMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.mapper.research.AiResearchDailyReportMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.TradingCalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiResearchDailyReportServiceImplTest {

    @Test
    void archivesThePersistedDecisionSnapshotWithoutReclassifyingIt() {
        Fixture fixture = fixture();
        AiResearchDailyReportService service = fixture.service;

        AiResearchDailyReportService.ReportView first = service.generate(request("REPORT:41"));
        fixture.items.get(0).category = "AVOID";
        fixture.items.get(0).finalAction = "SELL";
        fixture.items.get(0).systemScore = BigDecimal.ZERO;
        AiResearchDailyReportService.ReportView second = service.generate(request("REPORT:41"));

        assertThat(first.decisionSnapshotId()).isEqualTo(41L);
        assertThat(first.content().recommendations()).extracting(item -> item.stockCode())
                .containsExactly("600519");
        assertThat(first.content().avoids()).isEmpty();
        assertThat(second.content()).isEqualTo(first.content());
        verify(fixture.reportMapper, times(1)).insert(any(AiResearchDailyReport.class));
    }

    @Test
    void keepsDataUnavailableOutsideWatchRecommendationAndHitRateGroups() {
        Fixture fixture = fixture();
        AiDailyDecisionItem unavailable = item("300058", "蓝色光标", "DATA_UNAVAILABLE", null);
        unavailable.systemScore = null;
        unavailable.finalAction = null;
        unavailable.riskScore = null;
        unavailable.riskLevel = null;
        unavailable.unavailableReason = "MISSING_T2_PREDICTION";
        fixture.items.clear();
        fixture.items.add(unavailable);
        fixture.snapshot.snapshotStatus = "DATA_UNAVAILABLE";
        fixture.snapshot.overallHitRate = null;

        AiResearchDailyReportService.ReportView report = fixture.service.generate(request("REPORT:UNAVAILABLE"));

        assertThat(report.reportStatus()).isEqualTo("DATA_UNAVAILABLE");
        assertThat(report.recommendationCount()).isZero();
        assertThat(report.watchCount()).isZero();
        assertThat(report.avoidCount()).isZero();
        assertThat(report.content().unavailable()).hasSize(1);
        assertThat(report.content().unavailable().get(0).unavailableReason())
                .isEqualTo("MISSING_T2_PREDICTION");
    }

    @Test
    void refusesToArchiveAnotherUsersDecisionSnapshot() {
        Fixture fixture = fixture();
        fixture.snapshot.userId = 6L;

        assertThatThrownBy(() -> fixture.service.generate(request("REPORT:FOREIGN")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("其他用户");
    }

    @Test
    void reportVersionSwitchIsTransactional() throws NoSuchMethodException {
        Transactional annotation = AiResearchDailyReportServiceImpl.class
                .getMethod("generate", AiResearchDailyReportService.GenerationRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
    }

    @Test
    void manualRebuildArchivesTheCurrentSnapshotForTheSelectedTradingDate() {
        Fixture fixture = fixture();
        when(fixture.tradingCalendarService.latestExpectedKlineDate(any(LocalDateTime.class)))
                .thenReturn(LocalDate.of(2026, 7, 10));
        when(fixture.snapshotMapper.selectCurrent(5L, LocalDate.of(2026, 7, 9)))
                .thenReturn(fixture.snapshot);
        fixture.snapshot.tradeDate = LocalDate.of(2026, 7, 9);

        java.util.concurrent.atomic.AtomicReference<AiResearchDailyReportService.ReportView> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        AuthContext.runAs(5L, () -> result.set(fixture.service.rebuild(LocalDate.of(2026, 7, 9))));

        assertThat(result.get().tradeDate()).isEqualTo(LocalDate.of(2026, 7, 9));
        assertThat(result.get().decisionSnapshotId()).isEqualTo(41L);
    }

    private static Fixture fixture() {
        AiResearchDailyReportMapper reportMapper = mock(AiResearchDailyReportMapper.class);
        AiDailyDecisionSnapshotMapper snapshotMapper = mock(AiDailyDecisionSnapshotMapper.class);
        AiDailyDecisionItemMapper itemMapper = mock(AiDailyDecisionItemMapper.class);
        AiDailyDecisionItemPredictionMapper linkMapper = mock(AiDailyDecisionItemPredictionMapper.class);
        AiPipelineRunMapper runMapper = mock(AiPipelineRunMapper.class);
        AiPipelineStepMapper stepMapper = mock(AiPipelineStepMapper.class);
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        TradingCalendarService calendar = mock(TradingCalendarService.class);

        AiDailyDecisionSnapshot snapshot = snapshot();
        List<AiDailyDecisionItem> items = new ArrayList<>();
        AiDailyDecisionItem recommendation = item("600519", "贵州茅台", "RECOMMEND", "BUY");
        recommendation.id = 51L;
        items.add(recommendation);
        AiDailyDecisionItemPrediction link = new AiDailyDecisionItemPrediction();
        link.id = 61L;
        link.userId = 5L;
        link.decisionItemId = 51L;
        link.predictionId = 71L;
        link.purpose = "PRIMARY_RANKING";
        link.weight = new BigDecimal("0.500000");

        when(snapshotMapper.selectById(41L)).thenReturn(snapshot);
        when(snapshotMapper.selectCurrent(5L, snapshot.tradeDate)).thenReturn(snapshot);
        when(itemMapper.selectBySnapshot(anyLong(), anyLong())).thenAnswer(invocation -> List.copyOf(items));
        when(linkMapper.selectByItems(anyLong(), any())).thenReturn(List.of(link));
        when(stepMapper.selectByRunIdForUpdate(anyLong())).thenReturn(List.of());
        AiStrategyRelease release = new AiStrategyRelease();
        release.id = 91L;
        release.versionNo = "baseline-1";
        release.title = "统一研究基线";
        release.validationMetricsJson = "{\"status\":\"BASELINE_NOT_VALIDATED\"}";
        when(releaseMapper.selectById(91L)).thenReturn(release);

        List<AiResearchDailyReport> reports = new ArrayList<>();
        AtomicLong ids = new AtomicLong(8000);
        when(reportMapper.lockUser(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reportMapper.selectByIdempotencyForShare(anyLong(), anyString())).thenAnswer(invocation ->
                reports.stream().filter(value -> value.userId.equals(invocation.getArgument(0))
                                && value.idempotencyKey.equals(invocation.getArgument(1)))
                        .findFirst().orElse(null));
        when(reportMapper.selectMaxVersionForUpdate(anyLong(), any(LocalDate.class))).thenReturn(0);
        when(reportMapper.insert(any(AiResearchDailyReport.class))).thenAnswer(invocation -> {
            AiResearchDailyReport report = invocation.getArgument(0);
            report.id = ids.incrementAndGet();
            reports.add(report);
            return 1;
        });
        when(reportMapper.updateById(any(AiResearchDailyReport.class))).thenReturn(1);

        AiResearchDailyReportService service = new AiResearchDailyReportServiceImpl(
                reportMapper, snapshotMapper, itemMapper, linkMapper, runMapper, stepMapper,
                releaseMapper, new ObjectMapper().findAndRegisterModules(), calendar);
        return new Fixture(service, reportMapper, snapshotMapper, calendar, snapshot, items);
    }

    private static AiResearchDailyReportService.GenerationRequest request(String idempotencyKey) {
        return new AiResearchDailyReportService.GenerationRequest(
                5L,
                LocalDate.of(2026, 7, 10),
                41L,
                null,
                91L,
                101L,
                idempotencyKey,
                "READY",
                null,
                "已完成",
                LocalDateTime.of(2026, 7, 10, 16, 30));
    }

    private static AiDailyDecisionSnapshot snapshot() {
        AiDailyDecisionSnapshot value = new AiDailyDecisionSnapshot();
        value.id = 41L;
        value.userId = 5L;
        value.tradeDate = LocalDate.of(2026, 7, 10);
        value.snapshotVersion = 1;
        value.globalPipelineRunId = 81L;
        value.strategyReleaseId = 91L;
        value.modelVersionId = 101L;
        value.snapshotStatus = "READY";
        value.marketRegime = "BALANCED";
        value.overallHitRate = new BigDecimal("61.8");
        value.freshnessStatus = "CURRENT_CLOSE";
        value.dataQualityScore = new BigDecimal("95");
        value.decisionPolicyVersion = "DECISION/1.0.0";
        value.generatedAt = LocalDateTime.of(2026, 7, 10, 16, 20);
        return value;
    }

    private static AiDailyDecisionItem item(String code, String name, String category, String action) {
        AiDailyDecisionItem value = new AiDailyDecisionItem();
        value.userId = 5L;
        value.decisionSnapshotId = 41L;
        value.tradeDate = LocalDate.of(2026, 7, 10);
        value.sampleId = 31L;
        value.stockCode = code;
        value.stockName = name;
        value.category = category;
        value.systemScore = new BigDecimal("76.2");
        value.horizonSignalScore = new BigDecimal("80");
        value.factorReliabilityScore = new BigDecimal("65");
        value.strategyValidationScore = new BigDecimal("70");
        value.dataQualityComponent = new BigDecimal("95");
        value.riskComponent = new BigDecimal("65");
        value.finalAction = action;
        value.riskScore = new BigDecimal("35");
        value.riskLevel = "MEDIUM";
        value.decisionSource = "DETERMINISTIC_POLICY";
        value.freshnessStatus = "CURRENT_CLOSE";
        value.decisionPolicyVersion = "DECISION/1.0.0";
        value.confidenceLevel = "OOS_VALIDATED";
        value.outOfSampleCount = 240;
        value.historicalHitRate = new BigDecimal("61.8");
        value.triggerFactorsJson = "[]";
        value.reasonSummary = "已持久化的确定性结论";
        value.inputFingerprint = "fingerprint";
        return value;
    }

    private record Fixture(
            AiResearchDailyReportService service,
            AiResearchDailyReportMapper reportMapper,
            AiDailyDecisionSnapshotMapper snapshotMapper,
            TradingCalendarService tradingCalendarService,
            AiDailyDecisionSnapshot snapshot,
            List<AiDailyDecisionItem> items
    ) {
    }
}
