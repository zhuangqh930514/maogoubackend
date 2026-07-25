package com.maogou.stock.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.dto.ai.AiConditionalStrategyPayload;
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
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.dto.portfolio.TradePositionAggregate;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.AiUserNotificationService;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiDailyDecisionPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    void exposesEvidenceScopeAndAConfidenceIntervalWithoutCallingFallbackEvidenceStockSpecific() {
        Fixture fixture = fixture();
        fixture.items.get(0).evidenceScope = "STRATEGY_FALLBACK";
        fixture.items.get(0).outOfSampleCount = 240;
        fixture.items.get(0).historicalHitRate = new BigDecimal("61.8");

        AiResearchDailyReportService.ReportView report = fixture.service.generate(request("REPORT:EVIDENCE-SCOPE"));
        var card = report.content().recommendations().get(0);

        assertThat(card.evidenceScope()).isEqualTo("STRATEGY_FALLBACK");
        assertThat(card.historicalHitRateLower()).isNotNull();
        assertThat(card.historicalHitRateUpper()).isNotNull();
        assertThat(card.historicalHitRateLower()).isLessThan(card.historicalHitRate());
        assertThat(card.historicalHitRateUpper()).isGreaterThan(card.historicalHitRate());
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
    void includesConditionalProtectionPlanForAnActualHolding() throws Exception {
        Fixture fixture = fixture();
        AiDailyDecisionItem holding = fixture.items.get(0);
        holding.category = "HOLDING_RISK";
        holding.finalAction = "REDUCE";
        holding.reportId = 501L;

        AiAnalysisReport report = new AiAnalysisReport();
        report.id = 501L;
        report.userId = 5L;
        report.stockCode = holding.stockCode;
        report.conditionalStrategy = new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(holdingStrategy());
        when(fixture.analysisReportMapper.selectOwnedByIds(anyLong(), any())).thenReturn(List.of(report));

        AiResearchDailyReportService.ReportView dailyReport = fixture.service.generate(request("REPORT:HOLDING"));

        var plan = dailyReport.content().holdingRisks().get(0).positionPlan();
        assertThat(plan).isNotNull();
        assertThat(plan.averageCost()).isEqualByComparingTo("10.00");
        assertThat(plan.currentPrice()).isEqualByComparingTo("10.80");
        assertThat(plan.reduceCondition()).contains("减仓30%");
        assertThat(plan.protectionCondition()).contains("止损");
        assertThat(plan.takeProfitCondition()).contains("止盈");
        assertThat(plan.invalidationCondition()).contains("重新评估");
    }

    @Test
    void includesConservativeHoldingPlanWhenTheAiReportIsTemporarilyUnavailable() {
        Fixture fixture = fixture();
        AiDailyDecisionItem holding = fixture.items.get(0);
        holding.category = "HOLDING_RISK";
        holding.finalAction = "REDUCE";
        TradePositionAggregate position = new TradePositionAggregate();
        position.stockCode = holding.stockCode;
        position.totalCost = new BigDecimal("1000.00");
        position.quantity = 100;
        when(fixture.tradeRecordMapper.selectActivePositions(5L)).thenReturn(List.of(position));

        AiResearchDailyReportService.ReportView dailyReport = fixture.service.generate(request("REPORT:HOLDING-FALLBACK"));

        var plan = dailyReport.content().holdingRisks().get(0).positionPlan();
        assertThat(plan).isNotNull();
        assertThat(plan.averageCost()).isEqualByComparingTo("10.00");
        assertThat(plan.currentPrice()).isNull();
        assertThat(plan.riskAdvice()).contains("AI 持仓报告暂未就绪");
        assertThat(plan.protectionCondition()).contains("92%");
    }

    @Test
    void overviewComputesActionChangesOnTheServer() {
        Fixture fixture = fixture();
        AiResearchDailyReport current = archivedReport(901L, LocalDate.of(2026, 7, 10), "BUY", "RECOMMEND");
        AiResearchDailyReport previous = archivedReport(900L, LocalDate.of(2026, 7, 9), "WATCH", "CAUTIOUS");
        when(fixture.tradingCalendarService.latestExpectedKlineDate(any(LocalDateTime.class)))
                .thenReturn(LocalDate.of(2026, 7, 10));
        when(fixture.reportMapper.selectLatestCurrent(5L, LocalDate.of(2026, 7, 10))).thenReturn(current);
        when(fixture.reportMapper.selectPreviousCurrent(5L, LocalDate.of(2026, 7, 10))).thenReturn(previous);
        when(fixture.reportMapper.selectRecent(5L, 20)).thenReturn(List.of(current, previous));

        AiResearchDailyReportService.DailyOverview overview = AuthContext.callAs(
                5L, () -> fixture.service.overview(20));

        assertThat(overview.report().id()).isEqualTo(901L);
        assertThat(overview.history()).extracting(item -> item.id()).containsExactly(901L, 900L);
        assertThat(overview.dailyChanges()).singleElement().satisfies(change -> {
            assertThat(change.stockCode()).isEqualTo("600519");
            assertThat(change.changeType()).isEqualTo("ACTION_CHANGED");
            assertThat(change.previousAction()).isEqualTo("WATCH");
            assertThat(change.currentAction()).isEqualTo("BUY");
        });
    }

    @Test
    void overviewSurfacesRiskAndFactorChangesWithoutLoadingTheClientHistoryPool() {
        Fixture fixture = fixture();
        AiResearchDailyReport current = archivedReport(901L, LocalDate.of(2026, 7, 10), "WATCH", "CAUTIOUS");
        AiResearchDailyReport previous = archivedReport(900L, LocalDate.of(2026, 7, 9), "WATCH", "CAUTIOUS");
        current.contentJson = current.contentJson.replace(
                "\"freshnessStatus\":\"CURRENT_CLOSE\"",
                "\"riskLevel\":\"HIGH\",\"triggerFactors\":[{\"factorCode\":\"MOMENTUM\",\"direction\":\"UP\",\"contribution\":80}],\"freshnessStatus\":\"CURRENT_CLOSE\"");
        previous.contentJson = previous.contentJson.replace(
                "\"freshnessStatus\":\"CURRENT_CLOSE\"",
                "\"riskLevel\":\"LOW\",\"triggerFactors\":[{\"factorCode\":\"VOLUME\",\"direction\":\"UP\",\"contribution\":65}],\"freshnessStatus\":\"CURRENT_CLOSE\"");
        when(fixture.tradingCalendarService.latestExpectedKlineDate(any(LocalDateTime.class)))
                .thenReturn(LocalDate.of(2026, 7, 10));
        when(fixture.reportMapper.selectLatestCurrent(5L, LocalDate.of(2026, 7, 10))).thenReturn(current);
        when(fixture.reportMapper.selectPreviousCurrent(5L, LocalDate.of(2026, 7, 10))).thenReturn(previous);
        when(fixture.reportMapper.selectRecent(5L, 20)).thenReturn(List.of(current, previous));

        AiResearchDailyReportService.DailyOverview overview = AuthContext.callAs(
                5L, () -> fixture.service.overview(20));

        assertThat(overview.dailyChanges()).singleElement().satisfies(change -> {
            assertThat(change.changeType()).isEqualTo("RISK_CHANGED");
            assertThat(change.message()).isEqualTo("风险等级已变化");
        });
    }

    @Test
    void trimmedDailyReportKeepsActualHoldingsOutOfTheGeneralWatchPool() {
        Fixture fixture = fixture();
        AiDailyDecisionItem holding = fixture.items.get(0);
        holding.category = "CAUTIOUS";
        holding.finalAction = "WATCH";
        TradePositionAggregate position = new TradePositionAggregate();
        position.stockCode = holding.stockCode;
        position.totalCost = new BigDecimal("1000.00");
        position.quantity = 100;
        when(fixture.tradeRecordMapper.selectActivePositions(5L)).thenReturn(List.of(position));

        AiResearchDailyReportService.ReportView generated = fixture.service.generate(request("REPORT:HOLDING-WATCH"));
        var entityCaptor = org.mockito.ArgumentCaptor.forClass(AiResearchDailyReport.class);
        verify(fixture.reportMapper).insert(entityCaptor.capture());
        when(fixture.reportMapper.selectById(generated.id())).thenReturn(entityCaptor.getValue());

        AiResearchDailyReportService.ReportView detail = AuthContext.callAs(
                5L, () -> fixture.service.detail(generated.id()));

        assertThat(detail.content().watches()).singleElement().satisfies(card -> {
            assertThat(card.stockCode()).isEqualTo("600519");
            assertThat(card.positionPlan()).isNotNull();
        });
    }

    @Test
    void pagesCurrentHistoricalReportsByTradeDate() {
        Fixture fixture = fixture();
        AiResearchDailyReport report = archivedReport(910L, LocalDate.of(2026, 7, 9), "WATCH", "CAUTIOUS");
        when(fixture.reportMapper.selectCount(any())).thenReturn(11L);
        when(fixture.reportMapper.selectList(any())).thenReturn(List.of(report));

        AiResearchDailyReportService.ReportListPage page = AuthContext.callAs(5L,
                () -> fixture.service.pageHistory(new AiResearchDailyReportService.ReportListQuery(
                        LocalDate.of(2026, 7, 9), 99, 5)));

        assertThat(page.total()).isEqualTo(11);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.items()).extracting(item -> item.id()).containsExactly(910L);
        var queryCaptor = org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        verify(fixture.reportMapper).selectCount(queryCaptor.capture());
        queryCaptor.getValue().getSqlSegment();
        assertThat(queryCaptor.getValue().getParamNameValuePairs().values())
                .contains(5L, 1, LocalDate.of(2026, 7, 9));
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

    @Test
    void hydratesLegacyCodeOnlyStockNamesFromThePersistedDecisionSnapshot() {
        Fixture fixture = fixture();
        AiResearchDailyReportService.ReportView generated = fixture.service.generate(request("REPORT:LEGACY-NAME"));
        var reportCaptor = org.mockito.ArgumentCaptor.forClass(AiResearchDailyReport.class);
        verify(fixture.reportMapper).insert(reportCaptor.capture());
        AiResearchDailyReport archived = reportCaptor.getValue();
        archived.contentJson = archived.contentJson.replace(
                "\"stockName\":\"贵州茅台\"", "\"stockName\":\"600519\"");
        when(fixture.reportMapper.selectById(archived.id)).thenReturn(archived);

        AiResearchDailyReportService.ReportView detail = AuthContext.callAs(5L,
                () -> fixture.service.detail(archived.id));

        assertThat(generated.content().recommendations()).hasSize(1);
        assertThat(detail.content().recommendations().get(0).stockName()).isEqualTo("贵州茅台");
    }

    @Test
    void surfacesTheLinkedAiReportActionInsteadOfRepeatingTheFinalDailyAction() {
        Fixture fixture = fixture();
        fixture.items.get(0).reportId = 701L;
        fixture.items.get(0).finalAction = "WATCH";
        AiAnalysisReport report = new AiAnalysisReport();
        report.id = 701L;
        report.finalAction = "BUY";
        report.calibratedConfidence = new BigDecimal("0.82");
        report.generatedAt = LocalDateTime.of(2026, 7, 10, 16, 5);
        when(fixture.analysisReportMapper.selectOwnedByIds(5L, List.of(701L))).thenReturn(List.of(report));

        AiResearchDailyReportService.ReportView generated = fixture.service.generate(request("REPORT:LINKED-AI"));

        assertThat(generated.content().recommendations()).singleElement().satisfies(card -> {
            assertThat(card.action()).isEqualTo("WATCH");
            assertThat(card.aiDecision()).isEqualTo("BUY");
            assertThat(card.aiConfidence()).isEqualByComparingTo("0.82");
            assertThat(card.reportGeneratedAt()).isEqualTo(report.generatedAt);
        });
    }

    @Test
    void pagesOnlyOwnedDecisionItemsWithValidatedFiltersAndClampedPage() {
        Fixture fixture = fixture();
        AiResearchDailyReport report = archivedReport(903L, LocalDate.of(2026, 7, 10), "WATCH", "CAUTIOUS");
        report.decisionSnapshotId = 41L;
        when(fixture.reportMapper.selectById(903L)).thenReturn(report);
        when(fixture.itemMapper.selectCount(any())).thenReturn(11L);
        AiDailyDecisionItem watch = item("300750", "宁德时代", "CAUTIOUS", "WATCH");
        watch.id = 52L;
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(watch));

        AiResearchDailyReportService.DecisionItemPage page = AuthContext.callAs(5L,
                () -> fixture.service.pageItems(903L,
                        new AiResearchDailyReportService.DecisionItemQuery(
                                "CAUTIOUS", "WATCH", "AVAILABLE", "宁德",
                                "SYSTEM_SCORE_DESC", 99, 5)));

        assertThat(page.total()).isEqualTo(11);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.items()).extracting(card -> card.stockCode()).containsExactly("300750");

        var countCaptor = org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        verify(fixture.itemMapper).selectCount(countCaptor.capture());
        countCaptor.getValue().getSqlSegment();
        assertThat(countCaptor.getValue().getParamNameValuePairs().values())
                .contains(5L, 41L, "CAUTIOUS", "WATCH", "UNAVAILABLE", "%宁德%");
    }

    @Test
    void refusesDecisionItemPageOutsideTheCurrentUsersReport() {
        Fixture fixture = fixture();
        AiResearchDailyReport foreign = archivedReport(904L, LocalDate.of(2026, 7, 10), "WATCH", "CAUTIOUS");
        foreign.userId = 6L;
        foreign.decisionSnapshotId = 41L;
        when(fixture.reportMapper.selectById(904L)).thenReturn(foreign);

        assertThatThrownBy(() -> AuthContext.callAs(5L,
                () -> fixture.service.pageItems(904L,
                        new AiResearchDailyReportService.DecisionItemQuery(
                                "ALL", null, "ALL", null, "SYSTEM_SCORE_DESC", 1, 8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("日报不存在");
        verify(fixture.itemMapper, never()).selectCount(any());
    }

    @Test
    void rejectsUnsupportedDecisionItemFilterBeforeQuerying() {
        Fixture fixture = fixture();
        AiResearchDailyReport report = archivedReport(905L, LocalDate.of(2026, 7, 10), "WATCH", "CAUTIOUS");
        report.decisionSnapshotId = 41L;
        when(fixture.reportMapper.selectById(905L)).thenReturn(report);

        assertThatThrownBy(() -> AuthContext.callAs(5L,
                () -> fixture.service.pageItems(905L,
                        new AiResearchDailyReportService.DecisionItemQuery(
                                "DELETE", null, "ALL", null, "SYSTEM_SCORE_DESC", 1, 8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的日报分类");
        verify(fixture.itemMapper, never()).selectCount(any());
    }

    private static Fixture fixture() {
        AiResearchDailyReportMapper reportMapper = mock(AiResearchDailyReportMapper.class);
        AiDailyDecisionSnapshotMapper snapshotMapper = mock(AiDailyDecisionSnapshotMapper.class);
        AiDailyDecisionItemMapper itemMapper = mock(AiDailyDecisionItemMapper.class);
        AiDailyDecisionItemPredictionMapper linkMapper = mock(AiDailyDecisionItemPredictionMapper.class);
        AiPipelineRunMapper runMapper = mock(AiPipelineRunMapper.class);
        AiPipelineStepMapper stepMapper = mock(AiPipelineStepMapper.class);
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        WatchStockMapper watchStockMapper = mock(WatchStockMapper.class);
        AiAnalysisReportMapper analysisReportMapper = mock(AiAnalysisReportMapper.class);
        TradeRecordMapper tradeRecordMapper = mock(TradeRecordMapper.class);
        TradingCalendarService calendar = mock(TradingCalendarService.class);
        AiUserNotificationService notificationService = mock(AiUserNotificationService.class);
        AiDailyDecisionPlanService dailyDecisionPlanService = mock(AiDailyDecisionPlanService.class);

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
        WatchStock watchStock = new WatchStock();
        watchStock.stockCode = "600519";
        watchStock.stockName = "贵州茅台";
        when(watchStockMapper.selectList(any())).thenReturn(List.of(watchStock));
        when(dailyDecisionPlanService.plansByDecisionItemIds(anyLong(), anyList())).thenReturn(Map.of());

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
                releaseMapper, watchStockMapper, analysisReportMapper, tradeRecordMapper,
                new ObjectMapper().findAndRegisterModules(), calendar, notificationService, dailyDecisionPlanService);
        return new Fixture(service, reportMapper, snapshotMapper, itemMapper, calendar, analysisReportMapper,
                tradeRecordMapper, snapshot, items);
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
        value.evidenceScope = "STOCK";
        value.triggerFactorsJson = "[]";
        value.reasonSummary = "已持久化的确定性结论";
        value.inputFingerprint = "fingerprint";
        return value;
    }

    private static AiConditionalStrategyPayload holdingStrategy() {
        AiConditionalStrategyPayload.ConditionalRule weak = new AiConditionalStrategyPayload.ConditionalRule(
                "T1_WEAK", "弱势下跌", "如果跌破第一支撑，则减仓30%", List.of(), false,
                "REDUCE", "减仓30%", "继续破位时扩大减仓", new BigDecimal("80"), List.of());
        AiConditionalStrategyPayload.SignalModel target = new AiConditionalStrategyPayload.SignalModel(
                "SELL_TARGET_PROFIT", "目标止盈", "如果收益达到8%，则分批止盈", List.of(), false,
                "11.50", new BigDecimal("70"), "减仓30%", "TAKE_PROFIT", "剩余仓位继续观察", List.of());
        AiConditionalStrategyPayload.SignalModel stop = new AiConditionalStrategyPayload.SignalModel(
                "SELL_TECHNICAL_STOP", "技术止损", "如果跌破成本-8%或MA20，则执行止损", List.of(), false,
                "9.20", new BigDecimal("90"), "退出", "STOP_LOSS", "止损后不得等待解套", List.of());
        AiConditionalStrategyPayload.SignalModel logic = new AiConditionalStrategyPayload.SignalModel(
                "SELL_LOGIC_STOP", "逻辑止损", "如果行业逻辑变化或资金持续流出，则重新评估", List.of(), false,
                null, new BigDecimal("65"), "冻结加仓", "REASSESS", "数据缺失不自动判定", List.of());
        return new AiConditionalStrategyPayload(
                "CONDITIONAL_TRADE_STRATEGY_V1",
                LocalDate.of(2026, 7, 10),
                LocalDateTime.of(2026, 7, 10, 16, 0),
                null,
                new AiConditionalStrategyPayload.PositionContext(
                        true, 100, new BigDecimal("10.00"), new BigDecimal("10.80"), new BigDecimal("8.00")),
                null,
                List.of(new AiConditionalStrategyPayload.HorizonPlan(
                        1, "T+1", "验证", "待验证", "观察", List.of(weak))),
                List.of(),
                List.of(target, stop, logic),
                new AiConditionalStrategyPayload.RiskScore(new BigDecimal("68"), "HIGH", List.of(), "风险较高，优先控制仓位"),
                null,
                List.of());
    }

    private static AiResearchDailyReport archivedReport(
            Long id,
            LocalDate tradeDate,
            String action,
            String category
    ) {
        AiResearchDailyReport report = new AiResearchDailyReport();
        report.id = id;
        report.userId = 5L;
        report.tradeDate = tradeDate;
        report.reportVersion = 1;
        report.isCurrent = 1;
        report.reportStatus = "READY";
        report.title = tradeDate + " 猫狗智投投研日报";
        report.executiveSummary = "已归档";
        report.freshnessStatus = "CURRENT_CLOSE";
        report.dataQualityScore = new BigDecimal("90");
        report.generatedAt = tradeDate.atTime(16, 10);
        report.contentJson = """
                {
                  "freshness":{"status":"CURRENT_CLOSE","dataQualityScore":90},
                  "pipeline":{},
                  "strategyPerformance":{},
                  "recommendations":[{"stockCode":"600519","stockName":"贵州茅台","action":"%s","actionBucket":"%s","freshnessStatus":"CURRENT_CLOSE"}],
                  "watches":[],
                  "avoids":[],
                  "holdingRisks":[],
                  "unavailable":[],
                  "keyFactors":[],
                  "insightSummary":{"marketRegime":"BALANCED"}
                }
                """.formatted(action, category);
        return report;
    }

    private record Fixture(
            AiResearchDailyReportService service,
            AiResearchDailyReportMapper reportMapper,
            AiDailyDecisionSnapshotMapper snapshotMapper,
            AiDailyDecisionItemMapper itemMapper,
            TradingCalendarService tradingCalendarService,
            AiAnalysisReportMapper analysisReportMapper,
            TradeRecordMapper tradeRecordMapper,
            AiDailyDecisionSnapshot snapshot,
            List<AiDailyDecisionItem> items
    ) {
    }
}
