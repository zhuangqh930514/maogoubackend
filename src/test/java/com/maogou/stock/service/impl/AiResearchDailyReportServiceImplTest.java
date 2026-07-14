package com.maogou.stock.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.maogou.stock.domain.entity.AiDailyInsightItem;
import com.maogou.stock.domain.entity.AiDailyInsightSnapshot;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.v2.AiResearchDailyReport;
import com.maogou.stock.domain.enums.TradeSide;
import com.maogou.stock.dto.ai.AiResearchDailyReportPayloads;
import com.maogou.stock.mapper.v2.AiResearchDailyReportMapper;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.v2.AiResearchDailyReportSource;
import com.maogou.stock.security.AuthContext;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiResearchDailyReportServiceImplTest {

    @Test
    void generatesCompleteDeterministicReportWithoutAnyLlmDependency() {
        Fixture fixture = fixture(source("SUCCESS", null));
        AiResearchDailyReportService service = service(fixture);

        AiResearchDailyReportService.ReportView report = service.generate(request(
                "REPORT:81:SUCCESS", "SUCCESS", null, null));

        assertThat(report.reportStatus()).isEqualTo("READY");
        assertThat(report.recommendationCount()).isEqualTo(1);
        assertThat(report.watchCount()).isEqualTo(1);
        assertThat(report.avoidCount()).isEqualTo(1);
        assertThat(report.holdingRiskCount()).isEqualTo(1);
        assertThat(report.content().recommendations()).extracting(item -> item.stockCode())
                .containsExactly("600519");
        assertThat(report.content().watches()).extracting(item -> item.stockCode())
                .containsExactly("000001");
        assertThat(report.content().avoids()).extracting(item -> item.stockCode())
                .containsExactly("300058");
        assertThat(report.content().holdingRisks()).extracting(item -> item.stockCode())
                .containsExactly("300058");
        assertThat(report.content().keyFactors()).extracting(item -> item.factorCode())
                .contains("MOMENTUM_20D", "PE_TTM");
        assertThat(report.content().strategyPerformance().versionNo()).isEqualTo("v2.3");
        assertThat(report.content().freshness().status()).isEqualTo("REALTIME");
        assertThat(report.markdownContent()).contains("猫狗智投", "推荐关注", "持仓风险");
    }

    @Test
    void freezesDailyInsightSummaryAndDecisionEvidenceIntoTheReportJson() throws Exception {
        Fixture fixture = fixture(source("SUCCESS", null));
        AiResearchDailyReportService service = service(fixture);

        AiResearchDailyReportService.ReportView report = service.generate(
                request("REPORT:MERGED", "SUCCESS", null, null));

        JsonNode content = new ObjectMapper().findAndRegisterModules()
                .readTree(fixture.reports.get(0).contentJson);
        assertThat(content.at("/insightSummary/snapshotId").asLong()).isEqualTo(41L);
        assertThat(content.at("/insightSummary/overallHitRate").decimalValue())
                .isEqualByComparingTo("61.80");
        assertThat(content.at("/insightSummary/itemCount").asInt()).isEqualTo(3);
        assertThat(content.at("/insightSummary/lowSampleCount").asInt()).isEqualTo(1);

        JsonNode recommendation = content.at("/recommendations/0");
        assertThat(recommendation.get("systemScore").decimalValue()).isEqualByComparingTo("74.5");
        assertThat(recommendation.get("aiDecision").asText()).isEqualTo("BUY");
        assertThat(recommendation.get("aiConfidence").decimalValue()).isEqualByComparingTo("82.5");
        assertThat(recommendation.get("targetDirection").asText()).isEqualTo("UP");
        assertThat(recommendation.get("riskLevel").asText()).isEqualTo("MEDIUM");
        assertThat(recommendation.get("dataQualityScore").decimalValue()).isEqualByComparingTo("92");
        assertThat(recommendation.get("freshnessScore").decimalValue()).isEqualByComparingTo("95");
        assertThat(recommendation.get("freshnessMessage").asText()).isEqualTo("行情与样本均为当日数据");
        assertThat(recommendation.get("triggerFactors")).hasSize(1);
        assertThat(recommendation.at("/triggerFactors/0/factorCode").asText()).isEqualTo("MOMENTUM_20D");
        assertThat(report.markdownContent())
                .contains("平均命中率 61.80%", "数据质量 92.50%", "低样本结论 1 只");
    }

    @Test
    void sameIdempotencyKeyReturnsTheOriginalCurrentReport() {
        Fixture fixture = fixture(source("SUCCESS", null));
        AiResearchDailyReportService service = service(fixture);
        AiResearchDailyReportService.GenerationRequest request = request(
                "REPORT:81:SUCCESS", "SUCCESS", null, null);

        AiResearchDailyReportService.ReportView first = service.generate(request);
        AiResearchDailyReportService.ReportView second = service.generate(request);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.reportVersion()).isEqualTo(1);
        verify(fixture.reportMapper, times(1)).insert(any(AiResearchDailyReport.class));
    }

    @Test
    void aNewBuildSupersedesThePreviousCurrentVersion() {
        Fixture fixture = fixture(source("SUCCESS", null));
        AiResearchDailyReportService service = service(fixture);

        AiResearchDailyReportService.ReportView first = service.generate(request(
                "REPORT:81:SUCCESS", "SUCCESS", null, null));
        AiResearchDailyReportService.ReportView second = service.generate(request(
                "MANUAL:2026-07-10:2", "MANUAL", null, null));

        assertThat(second.reportVersion()).isEqualTo(2);
        assertThat(second.supersedesReportId()).isEqualTo(first.id());
        assertThat(second.current()).isTrue();
        assertThat(fixture.reports.get(0).isCurrent).isEqualTo(0);
        assertThat(fixture.reports).filteredOn(item -> item.isCurrent == 1).hasSize(1);
    }

    @Test
    void failedPipelineStillProducesAnExceptionReportWithTheFailedStep() {
        Fixture fixture = fixture(source("FAILED", "GENERATE_REPORTS"));
        AiResearchDailyReportService service = service(fixture);

        AiResearchDailyReportService.ReportView report = service.generate(request(
                "REPORT:81:FAILED:GENERATE_REPORTS", "FAILED", "GENERATE_REPORTS",
                "模型服务暂时不可用"));

        assertThat(report.reportStatus()).isEqualTo("FAILED_PIPELINE");
        assertThat(report.executiveSummary()).contains("GENERATE_REPORTS", "模型服务暂时不可用");
        assertThat(report.content().pipeline().status()).isEqualTo("FAILED");
        assertThat(report.content().pipeline().failedStep()).isEqualTo("GENERATE_REPORTS");
        assertThat(report.markdownContent()).contains("流水线异常", "仅展示已固化数据");
    }

    @Test
    void manualRebuildUsesTheLatestExpectedTradingDateInsteadOfTheNaturalDate() {
        Fixture fixture = fixture(source("SUCCESS", null));
        LocalDate expectedTradeDate = LocalDate.of(2026, 7, 10);
        when(fixture.tradingCalendarService.latestExpectedKlineDate(any(LocalDateTime.class)))
                .thenReturn(expectedTradeDate);
        AiResearchDailyReportService service = service(fixture);
        AtomicReference<AiResearchDailyReportService.ReportView> result = new AtomicReference<>();

        AuthContext.runAs(5L, () -> result.set(service.rebuildToday()));

        assertThat(result.get().tradeDate()).isEqualTo(expectedTradeDate);
        verify(fixture.source).load(
                org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(expectedTradeDate),
                any());
    }

    @Test
    void manualRebuildCanTargetTheTradingDateOfTheSelectedHistoricalReport() {
        Fixture fixture = fixture(source("SUCCESS", null));
        LocalDate selectedTradeDate = LocalDate.of(2026, 7, 9);
        when(fixture.tradingCalendarService.latestExpectedKlineDate(any(LocalDateTime.class)))
                .thenReturn(LocalDate.of(2026, 7, 10));
        AiResearchDailyReportService service = service(fixture);
        AtomicReference<AiResearchDailyReportService.ReportView> result = new AtomicReference<>();

        AuthContext.runAs(5L, () -> result.set(service.rebuild(selectedTradeDate)));

        assertThat(result.get().tradeDate()).isEqualTo(selectedTradeDate);
        verify(fixture.source).load(
                org.mockito.ArgumentMatchers.eq(5L),
                org.mockito.ArgumentMatchers.eq(selectedTradeDate),
                any());
    }

    @Test
    void reportVersionSwitchIsTransactional() throws NoSuchMethodException {
        Transactional annotation = AiResearchDailyReportServiceImpl.class
                .getMethod("generate", AiResearchDailyReportService.GenerationRequest.class)
                .getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
    }

    @Test
    void latestReportNeverSelectsAReportAfterTheExpectedTradingDate() {
        Fixture fixture = fixture(source("SUCCESS", null));
        LocalDate expectedTradeDate = LocalDate.of(2026, 7, 10);
        when(fixture.tradingCalendarService.latestExpectedKlineDate(any(LocalDateTime.class)))
                .thenReturn(expectedTradeDate);
        when(fixture.reportMapper.selectLatestCurrent(5L, expectedTradeDate)).thenReturn(null);
        AiResearchDailyReportService service = service(fixture);

        AiResearchDailyReportService.ReportView result = service.latestOrNull(5L);

        assertThat(result).isNull();
        verify(fixture.reportMapper).selectLatestCurrent(5L, expectedTradeDate);
    }

    @Test
    void successfulPipelineWithNoInsightItemsProducesAnExplicitEmptyReport() {
        AiDailyInsightSnapshot snapshot = new AiDailyInsightSnapshot();
        snapshot.tradeDate = LocalDate.of(2026, 7, 10);
        snapshot.freshnessStatus = "EMPTY";
        snapshot.dataQualityScore = BigDecimal.ZERO;
        AiResearchDailyReportPayloads.PipelineSummary pipeline =
                new AiResearchDailyReportPayloads.PipelineSummary(
                        81L, "SUCCESS", "BUILD_RESEARCH_DAILY_REPORT", null,
                        9, 9, 0, null, List.of());
        AiResearchDailyReportSource.ReportSource emptySource = new AiResearchDailyReportSource.ReportSource(
                snapshot, List.of(), List.of(), "DEFENSIVE", null, pipeline);
        Fixture fixture = fixture(emptySource);
        AiResearchDailyReportService service = service(fixture);

        AiResearchDailyReportService.ReportView report = service.generate(request(
                "REPORT:EMPTY", "SUCCESS", null, null));

        assertThat(report.reportStatus()).isEqualTo("EMPTY_RESULT");
        assertThat(report.executiveSummary()).contains("没有可用投研结论");
    }

    @Test
    void nonEmptyButUnusableMarketDataProducesDataUnavailableReport() {
        AiDailyInsightSnapshot snapshot = new AiDailyInsightSnapshot();
        snapshot.tradeDate = LocalDate.of(2026, 7, 10);
        snapshot.freshnessStatus = "UNAVAILABLE";
        snapshot.dataQualityScore = new BigDecimal("35");
        AiDailyInsightItem unavailable = item(
                "600519", "贵州茅台", "UNAVAILABLE", "WATCH", "20", "80", "QUOTE_STALE");
        unavailable.confidenceLevel = "DATA_UNAVAILABLE";
        unavailable.freshnessStatus = "UNAVAILABLE";
        AiResearchDailyReportPayloads.PipelineSummary pipeline =
                new AiResearchDailyReportPayloads.PipelineSummary(
                        81L, "SUCCESS", "BUILD_RESEARCH_DAILY_REPORT", null,
                        9, 9, 0, null, List.of());
        AiResearchDailyReportSource.ReportSource unavailableSource = new AiResearchDailyReportSource.ReportSource(
                snapshot, List.of(unavailable), List.of(), "DEFENSIVE", null, pipeline);
        AiResearchDailyReportService service = service(fixture(unavailableSource));

        AiResearchDailyReportService.ReportView report = service.generate(request(
                "REPORT:UNAVAILABLE", "PARTIAL_SUCCESS", null, "行情质量不足"));

        assertThat(report.reportStatus()).isEqualTo("DATA_UNAVAILABLE");
        assertThat(report.watchCount()).isEqualTo(1);
    }

    private static AiResearchDailyReportService service(Fixture fixture) {
        return new AiResearchDailyReportServiceImpl(
                fixture.reportMapper, fixture.source, new ObjectMapper().findAndRegisterModules(),
                fixture.tradingCalendarService);
    }

    private static AiResearchDailyReportService.GenerationRequest request(
            String idempotencyKey,
            String status,
            String failedStep,
            String message
    ) {
        return new AiResearchDailyReportService.GenerationRequest(
                5L,
                LocalDate.of(2026, 7, 10),
                81L,
                91L,
                101L,
                idempotencyKey,
                status,
                failedStep,
                message,
                LocalDateTime.of(2026, 7, 10, 16, 30)
        );
    }

    private static AiResearchDailyReportSource.ReportSource source(String status, String failedStep) {
        AiDailyInsightSnapshot snapshot = new AiDailyInsightSnapshot();
        snapshot.id = 41L;
        snapshot.userId = 5L;
        snapshot.tradeDate = LocalDate.of(2026, 7, 10);
        snapshot.generatedAt = LocalDateTime.of(2026, 7, 10, 16, 20);
        snapshot.pipelineStatus = status;
        snapshot.freshnessStatus = "REALTIME";
        snapshot.dataQualityScore = new BigDecimal("92.50");
        snapshot.latestReportAt = LocalDateTime.of(2026, 7, 10, 16, 18);
        snapshot.latestSampleAt = LocalDateTime.of(2026, 7, 10, 15, 5);
        snapshot.itemCount = 3;
        snapshot.lowSampleCount = 1;
        snapshot.overallHitRate = new BigDecimal("61.80");
        snapshot.latestJobLogId = 701L;
        List<AiDailyInsightItem> items = List.of(
                item("600519", "贵州茅台", "BUY", "RECOMMEND", "78.2", "35", "MOMENTUM_20D"),
                item("000001", "平安银行", "WATCH", "WATCH", "61.0", "45", "PE_TTM"),
                item("300058", "蓝色光标", "REDUCE", "AVOID", "40.0", "82", "MOMENTUM_20D")
        );
        TradeRecord holding = new TradeRecord();
        holding.userId = 5L;
        holding.stockCode = "300058";
        holding.stockName = "蓝色光标";
        holding.side = TradeSide.BUY;
        holding.quantity = 1200;
        holding.price = new BigDecimal("8.50");
        holding.tradedAt = LocalDateTime.of(2026, 7, 1, 10, 0);
        AiResearchDailyReportPayloads.StrategyPerformance strategy =
                new AiResearchDailyReportPayloads.StrategyPerformance(
                        91L, "v2.3", "Champion v2.3", 101L,
                        new BigDecimal("0.0820"), new BigDecimal("0.0510"),
                        new BigDecimal("-0.0430"), new BigDecimal("1.25"),
                        126, new BigDecimal("0.6180"), "NORMAL");
        AiResearchDailyReportPayloads.PipelineSummary pipeline =
                new AiResearchDailyReportPayloads.PipelineSummary(
                        81L, status, failedStep, failedStep,
                        9, "FAILED".equals(status) ? 6 : 9,
                        "FAILED".equals(status) ? 3 : 0,
                        "FAILED".equals(status) ? "模型服务暂时不可用" : null,
                        List.of(
                                new AiResearchDailyReportPayloads.PipelineStep(
                                        "FETCH_DATA", "SUCCESS", 3, 3, null),
                                new AiResearchDailyReportPayloads.PipelineStep(
                                        "GENERATE_REPORTS", "FAILED".equals(status) ? "FAILED" : "SUCCESS",
                                        3, "FAILED".equals(status) ? 0 : 3,
                                        "FAILED".equals(status) ? "模型服务暂时不可用" : null)
                        ));
        return new AiResearchDailyReportSource.ReportSource(
                snapshot, items, List.of(holding), "TRENDING", strategy, pipeline);
    }

    private static AiDailyInsightItem item(
            String code,
            String name,
            String action,
            String bucket,
            String score,
            String risk,
            String factorCode
    ) {
        AiDailyInsightItem item = new AiDailyInsightItem();
        item.stockCode = code;
        item.stockName = name;
        item.finalAction = action;
        item.actionBucket = bucket;
        item.compositeScore = new BigDecimal(score);
        item.systemScore = new BigDecimal("74.5");
        item.aiDecision = "BUY";
        item.aiConfidence = new BigDecimal("82.5");
        item.targetDirection = "UP";
        item.riskLevel = "MEDIUM";
        item.riskScore = new BigDecimal(risk);
        item.dataQualityScore = new BigDecimal("92");
        item.freshnessScore = new BigDecimal("95");
        item.freshnessStatus = "REALTIME";
        item.freshnessMessage = "行情与样本均为当日数据";
        item.historicalHitRate = new BigDecimal("61.8");
        item.historicalSampleCount = 26;
        item.confidenceLevel = "READY";
        item.reasonSummary = name + " 的结构化结论";
        item.triggerFactorsJson = "[{\"factorCode\":\"" + factorCode
                + "\",\"factorName\":\"" + factorCode
                + "\",\"direction\":\"SUPPORT\",\"contribution\":12.5,"
                + "\"evidence\":\"测试证据\"}]";
        item.reportId = (long) Math.abs(code.hashCode());
        item.predictionId = item.reportId + 1;
        item.sampleId = item.reportId + 2;
        item.reportGeneratedAt = LocalDateTime.of(2026, 7, 10, 16, 18);
        item.sampleTime = LocalDateTime.of(2026, 7, 10, 15, 5);
        return item;
    }

    private static Fixture fixture(AiResearchDailyReportSource.ReportSource reportSource) {
        AiResearchDailyReportMapper mapper = mock(AiResearchDailyReportMapper.class);
        AiResearchDailyReportSource source = mock(AiResearchDailyReportSource.class);
        TradingCalendarService tradingCalendarService = mock(TradingCalendarService.class);
        List<AiResearchDailyReport> reports = new ArrayList<>();
        AtomicLong ids = new AtomicLong(8000);
        when(mapper.lockUser(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        when(mapper.selectByIdempotencyForShare(anyLong(), anyString())).thenAnswer(invocation -> reports.stream()
                .filter(item -> item.userId.equals(invocation.getArgument(0))
                        && item.idempotencyKey.equals(invocation.getArgument(1)))
                .findFirst().orElse(null));
        when(mapper.selectCurrentForUpdate(anyLong(), any(LocalDate.class))).thenAnswer(invocation -> reports.stream()
                .filter(item -> item.userId.equals(invocation.getArgument(0))
                        && item.tradeDate.equals(invocation.getArgument(1))
                        && item.isCurrent == 1)
                .findFirst().orElse(null));
        when(mapper.selectMaxVersionForUpdate(anyLong(), any(LocalDate.class))).thenAnswer(invocation -> reports.stream()
                .filter(item -> item.userId.equals(invocation.getArgument(0))
                        && item.tradeDate.equals(invocation.getArgument(1)))
                .map(item -> item.reportVersion)
                .max(Integer::compareTo)
                .orElse(0));
        when(mapper.insert(any(AiResearchDailyReport.class))).thenAnswer(invocation -> {
            AiResearchDailyReport item = invocation.getArgument(0);
            item.id = ids.incrementAndGet();
            reports.add(item);
            return 1;
        });
        when(mapper.updateById(any(AiResearchDailyReport.class))).thenReturn(1);
        when(source.load(anyLong(), any(LocalDate.class), any())).thenReturn(reportSource);
        return new Fixture(mapper, source, reports, tradingCalendarService);
    }

    private record Fixture(
            AiResearchDailyReportMapper reportMapper,
            AiResearchDailyReportSource source,
            List<AiResearchDailyReport> reports,
            TradingCalendarService tradingCalendarService
    ) {
    }
}
