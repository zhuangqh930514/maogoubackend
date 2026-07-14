package com.maogou.stock.service.impl.v2;

import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.domain.entity.v2.AiDataBatch;
import com.maogou.stock.domain.entity.v2.AiFactorValueV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.domain.entity.v2.AiStrategyRelease;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.ai.AiDailyInsightPayloads;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.v2.AiStrategyReleaseMapper;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.AiConditionalTradeStrategyService;
import com.maogou.stock.service.AiDailyInsightService;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.v2.AiDailyPipelineExecutor;
import com.maogou.stock.service.v2.AiFactorEngineV2;
import com.maogou.stock.service.v2.AiLabelVerificationCoordinatorV2;
import com.maogou.stock.service.v2.AiPredictionEngineV2;
import com.maogou.stock.service.v2.AiSampleSnapshotService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaogouDailyPipelineExecutorTest {

    @Test
    void resumesPredictionsFromPersistedSamplesAndFactorsWithoutRefetchingCurrentMarketData() {
        WatchStockMapper watchStockMapper = mock(WatchStockMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiSampleSnapshotService sampleSnapshotService = mock(AiSampleSnapshotService.class);
        AiFactorEngineV2 factorEngine = mock(AiFactorEngineV2.class);
        AiPredictionEngineV2 predictionEngine = mock(AiPredictionEngineV2.class);
        AiAnalysisService aiAnalysisService = mock(AiAnalysisService.class);
        AiDailyInsightService aiDailyInsightService = mock(AiDailyInsightService.class);
        AiLabelVerificationCoordinatorV2 labelVerificationCoordinator = mock(AiLabelVerificationCoordinatorV2.class);
        AiResearchDailyReportService researchDailyReportService = mock(AiResearchDailyReportService.class);
        AiSampleV2 sample = new AiSampleV2();
        sample.id = 21L;
        sample.userId = 5L;
        sample.dataBatchId = 11L;
        sample.stockCode = "600519";
        sample.stockName = "贵州茅台";
        sample.tradeDate = LocalDate.of(2026, 7, 10);
        sample.samplePhase = "AFTER_CLOSE";
        sample.asOfTime = LocalDateTime.of(2026, 7, 10, 16, 0);
        sample.featureSnapshot = "{\"quote\":{\"code\":\"600519\",\"name\":\"贵州茅台\","
                + "\"price\":1500,\"change\":0,\"percent\":0,\"volumeRatio\":0,"
                + "\"market\":\"SH\",\"source\":\"SINA\",\"fetchedAt\":\"2026-07-10T16:00:00\"},"
                + "\"finance\":null,\"intraday\":[],\"kline\":[]}";
        when(sampleSnapshotService.findBatchSnapshots(5L, 11L, LocalDate.of(2026, 7, 10)))
                .thenReturn(List.of(sample));
        AiFactorValueV2 factor = new AiFactorValueV2();
        factor.sampleId = 21L;
        factor.factorCode = "MOMENTUM_RETURN_5D";
        when(factorEngine.findStoredForSamples(List.of(21L))).thenReturn(List.of(factor));
        AiPredictionV2 prediction = new AiPredictionV2();
        prediction.id = 31L;
        prediction.sampleId = 21L;
        when(predictionEngine.predictAndStore(any())).thenReturn(List.of(prediction));

        MaogouDailyPipelineExecutor executor = new MaogouDailyPipelineExecutor(
                watchStockMapper, marketDataService, sampleSnapshotService, factorEngine,
                predictionEngine, aiAnalysisService, aiDailyInsightService, labelVerificationCoordinator,
                researchDailyReportService);

        AiDailyPipelineExecutor.StepOutcome outcome = executor.execute("GENERATE_PREDICTIONS", context());

        assertThat(outcome.successCount()).isEqualTo(3);
        verify(marketDataService, never()).stockDetailForAnalysis(any());
        verify(marketDataService, never()).stockDetailAt(any(), any());
        verify(sampleSnapshotService, never()).createOrGetSnapshot(any());
        verify(factorEngine, never()).computeAndStoreCrossSection(any());
        verify(predictionEngine, times(3)).predictAndStore(any());
    }

    @Test
    void generatesShadowChallengerPredictionsWithoutReplacingChampionIdentity() {
        WatchStockMapper watchStockMapper = mock(WatchStockMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiSampleSnapshotService sampleSnapshotService = mock(AiSampleSnapshotService.class);
        AiFactorEngineV2 factorEngine = mock(AiFactorEngineV2.class);
        AiPredictionEngineV2 predictionEngine = mock(AiPredictionEngineV2.class);
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        AiAnalysisService aiAnalysisService = mock(AiAnalysisService.class);
        AiDailyInsightService aiDailyInsightService = mock(AiDailyInsightService.class);
        AiLabelVerificationCoordinatorV2 labelCoordinator = mock(AiLabelVerificationCoordinatorV2.class);
        AiResearchDailyReportService reportService = mock(AiResearchDailyReportService.class);
        AiSampleV2 sample = new AiSampleV2();
        sample.id = 21L;
        sample.userId = 5L;
        sample.dataBatchId = 11L;
        sample.stockCode = "600519";
        sample.tradeDate = LocalDate.of(2026, 7, 10);
        sample.samplePhase = "AFTER_CLOSE";
        sample.sourceFingerprint = "sample";
        sample.featureSnapshot = "{\"quote\":{\"code\":\"600519\",\"price\":1500}}";
        when(sampleSnapshotService.findBatchSnapshots(5L, 11L, LocalDate.of(2026, 7, 10)))
                .thenReturn(List.of(sample));
        AiFactorValueV2 factor = new AiFactorValueV2();
        factor.sampleId = 21L;
        factor.factorCode = "MOMENTUM_RETURN_5D";
        when(factorEngine.findStoredForSamples(List.of(21L))).thenReturn(List.of(factor));
        AiStrategyRelease challenger = new AiStrategyRelease();
        challenger.id = 22L;
        challenger.userId = 5L;
        challenger.releaseRole = "CHALLENGER";
        challenger.status = "SHADOW";
        challenger.modelVersionId = 202L;
        when(releaseMapper.selectShadowChallengers(5L)).thenReturn(List.of(challenger));
        AiPredictionV2 stored = new AiPredictionV2();
        stored.id = 31L;
        when(predictionEngine.predictAndStore(any())).thenReturn(List.of(stored));
        MaogouDailyPipelineExecutor executor = new MaogouDailyPipelineExecutor(
                watchStockMapper, marketDataService, sampleSnapshotService, factorEngine,
                predictionEngine, releaseMapper, aiAnalysisService, aiDailyInsightService,
                labelCoordinator, reportService);

        AiDailyPipelineExecutor.PipelineContext modelContext = new AiDailyPipelineExecutor.PipelineContext(
                100L, 5L, LocalDate.of(2026, 7, 10), 11L, 10L, 101L,
                "AUTO_CLOSE:2026-07-10", "input-fingerprint",
                LocalDateTime.of(2026, 7, 10, 16, 0));
        AiDailyPipelineExecutor.StepOutcome outcome = executor.execute("GENERATE_PREDICTIONS", modelContext);

        ArgumentCaptor<AiPredictionEngineV2.PredictionBatch> batches = ArgumentCaptor.forClass(
                AiPredictionEngineV2.PredictionBatch.class);
        verify(predictionEngine, times(6)).predictAndStore(batches.capture());
        assertThat(batches.getAllValues()).filteredOn(item -> "CHAMPION".equals(item.inferenceMode()))
                .hasSize(3)
                .allMatch(item -> item.strategyReleaseId().equals(10L));
        assertThat(batches.getAllValues()).filteredOn(item -> "CHALLENGER_SHADOW".equals(item.inferenceMode()))
                .hasSize(3)
                .allMatch(item -> item.strategyReleaseId().equals(22L) && item.modelVersionId().equals(202L));
        assertThat(outcome.processedCount()).isEqualTo(6);
        assertThat(outcome.successCount()).isEqualTo(6);
        assertThat(outcome.errors()).isEmpty();
    }

    @Test
    void buildsSamplesFactorsAndPredictionsFromWatchlist() {
        WatchStockMapper watchStockMapper = mock(WatchStockMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiSampleSnapshotService sampleSnapshotService = mock(AiSampleSnapshotService.class);
        AiFactorEngineV2 factorEngine = mock(AiFactorEngineV2.class);
        AiPredictionEngineV2 predictionEngine = mock(AiPredictionEngineV2.class);
        AiAnalysisService aiAnalysisService = mock(AiAnalysisService.class);
        AiDailyInsightService aiDailyInsightService = mock(AiDailyInsightService.class);
        AiLabelVerificationCoordinatorV2 labelVerificationCoordinator = mock(AiLabelVerificationCoordinatorV2.class);
        AiResearchDailyReportService researchDailyReportService = mock(AiResearchDailyReportService.class);

        WatchStock stock = new WatchStock();
        stock.userId = 5L;
        stock.stockCode = "600519";
        stock.stockName = "贵州茅台";
        when(watchStockMapper.selectList(any())).thenReturn(List.of(stock));

        StockDetailResponse detail = new StockDetailResponse(
                new StockQuoteResponse(
                        "600519", "贵州茅台", new BigDecimal("1500"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, "SH",
                        "SINA", LocalDateTime.of(2026, 7, 10, 15, 0)),
                null, List.of(), List.of(), null, null);
        when(marketDataService.stockDetailAt(
                "600519", LocalDateTime.of(2026, 7, 10, 16, 0))).thenReturn(detail);
        KlineSeriesSnapshot stockSeries = KlineSeriesSnapshot.create(
                "600519", "day", "NONE", "SINA_TEST",
                LocalDateTime.of(2026, 7, 10, 16, 0),
                LocalDateTime.of(2026, 7, 12, 10, 0),
                List.of());
        when(marketDataService.klineAt(
                "600519", "day", 60, LocalDateTime.of(2026, 7, 10, 16, 0)))
                .thenReturn(stockSeries);

        AiDataBatch batch = new AiDataBatch();
        batch.id = 11L;
        batch.userId = 5L;
        batch.tradeDate = LocalDate.of(2026, 7, 10);
        batch.samplePhase = "AFTER_CLOSE";
        batch.asOfTime = LocalDateTime.of(2026, 7, 10, 16, 0);
        when(sampleSnapshotService.startOrGetBatch(any(), any(), any(), any(), any())).thenReturn(batch);

        AiSampleV2 sample = new AiSampleV2();
        sample.id = 21L;
        sample.userId = 5L;
        sample.dataBatchId = 11L;
        sample.stockCode = "600519";
        sample.stockName = "贵州茅台";
        sample.tradeDate = LocalDate.of(2026, 7, 10);
        sample.samplePhase = "AFTER_CLOSE";
        sample.asOfTime = LocalDateTime.of(2026, 7, 10, 16, 0);
        sample.universeCode = "WATCHLIST";
        sample.universeVersion = "DAILY_CLOSE";
        sample.sourceFingerprint = "sample-fingerprint";
        sample.dataQualityScore = new BigDecimal("88");
        when(sampleSnapshotService.createOrGetSnapshot(any())).thenReturn(sample);

        AiFactorValueV2 factor = new AiFactorValueV2();
        factor.sampleId = 21L;
        factor.userId = 5L;
        factor.stockCode = "600519";
        factor.factorCode = "MOMENTUM_RETURN_5D";
        factor.factorVersion = "2.0.0";
        factor.inputFingerprint = "factor-fingerprint";
        factor.normalizedValue = new BigDecimal("1.2");
        when(factorEngine.computeAndStoreCrossSection(any())).thenReturn(List.of(factor));

        AiPredictionV2 prediction = new AiPredictionV2();
        prediction.id = 31L;
        prediction.userId = 5L;
        prediction.sampleId = 21L;
        prediction.stockCode = "600519";
        prediction.tradeDate = LocalDate.of(2026, 7, 10);
        prediction.action = "BUY";
        prediction.actionBucket = "RECOMMEND";
        when(predictionEngine.predictAndStore(any())).thenReturn(List.of(prediction));

        MaogouDailyPipelineExecutor executor = new MaogouDailyPipelineExecutor(
                watchStockMapper,
                marketDataService,
                sampleSnapshotService,
                factorEngine,
                predictionEngine,
                aiAnalysisService,
                aiDailyInsightService,
                labelVerificationCoordinator,
                researchDailyReportService);

        AiDailyPipelineExecutor.StepOutcome outcome = executor.execute(
                "GENERATE_PREDICTIONS",
                new AiDailyPipelineExecutor.PipelineContext(
                        100L,
                        5L,
                        LocalDate.of(2026, 7, 10),
                        11L,
                        91L,
                        101L,
                        "AUTO_CLOSE:2026-07-10",
                        "input-fingerprint",
                        LocalDateTime.of(2026, 7, 12, 10, 0)));

        assertThat(outcome.processedCount()).isEqualTo(3);
        assertThat(outcome.successCount()).isEqualTo(3);
        assertThat(outcome.failedCount()).isEqualTo(0);
        assertThat(outcome.outputFingerprint()).isNotBlank();
        verify(sampleSnapshotService).createOrGetSnapshot(org.mockito.ArgumentMatchers.argThat(command ->
                LocalDate.of(2026, 7, 10).equals(command.tradeDate())
                        && LocalDateTime.of(2026, 7, 10, 16, 0).equals(command.asOfTime())));
        verify(factorEngine).computeAndStoreCrossSection(org.mockito.ArgumentMatchers.argThat(contexts ->
                contexts.size() == 1 && contexts.get(0).stockSeries() != null
                        && contexts.get(0).stockSeries().fingerprintMatches()));
        verify(predictionEngine, times(3)).predictAndStore(any());
    }

    @Test
    void reusesOneMarketSnapshotAcrossAllDataFlowSteps() {
        WatchStockMapper watchStockMapper = mock(WatchStockMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiSampleSnapshotService sampleSnapshotService = mock(AiSampleSnapshotService.class);
        AiFactorEngineV2 factorEngine = mock(AiFactorEngineV2.class);
        AiPredictionEngineV2 predictionEngine = mock(AiPredictionEngineV2.class);
        AiAnalysisService aiAnalysisService = mock(AiAnalysisService.class);
        AiDailyInsightService aiDailyInsightService = mock(AiDailyInsightService.class);
        AiLabelVerificationCoordinatorV2 labelVerificationCoordinator = mock(AiLabelVerificationCoordinatorV2.class);
        AiResearchDailyReportService researchDailyReportService = mock(AiResearchDailyReportService.class);

        WatchStock stock = new WatchStock();
        stock.userId = 5L;
        stock.stockCode = "600519";
        stock.stockName = "贵州茅台";
        when(watchStockMapper.selectList(any())).thenReturn(List.of(stock));
        StockDetailResponse detail = new StockDetailResponse(
                new StockQuoteResponse(
                        "600519", "贵州茅台", new BigDecimal("1500"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, "SH", "SINA",
                        LocalDateTime.of(2026, 7, 10, 15, 0)),
                null, List.of(), List.of(), null, null);
        when(marketDataService.stockDetailAt(
                "600519", LocalDateTime.of(2026, 7, 10, 16, 0))).thenReturn(detail);
        when(marketDataService.klineAt(
                "600519", "day", 60, LocalDateTime.of(2026, 7, 10, 16, 0)))
                .thenReturn(KlineSeriesSnapshot.create(
                        "600519", "day", "NONE", "SINA_TEST",
                        LocalDateTime.of(2026, 7, 10, 16, 0),
                        LocalDateTime.of(2026, 7, 10, 16, 0),
                        List.of()));
        AiDataBatch batch = new AiDataBatch();
        batch.id = 11L;
        when(sampleSnapshotService.startOrGetBatch(any(), any(), any(), any(), any())).thenReturn(batch);
        AiSampleV2 sample = new AiSampleV2();
        sample.id = 21L;
        sample.userId = 5L;
        sample.stockCode = "600519";
        sample.tradeDate = LocalDate.of(2026, 7, 10);
        sample.samplePhase = "AFTER_CLOSE";
        sample.sourceFingerprint = "sample-fingerprint";
        sample.dataQualityScore = new BigDecimal("88");
        sample.qualityStatus = "READY";
        when(sampleSnapshotService.createOrGetSnapshot(any())).thenReturn(sample);
        when(factorEngine.computeAndStoreCrossSection(any())).thenReturn(List.of());
        when(predictionEngine.predictAndStore(any())).thenReturn(List.of());

        MaogouDailyPipelineExecutor executor = new MaogouDailyPipelineExecutor(
                watchStockMapper, marketDataService, sampleSnapshotService, factorEngine,
                predictionEngine, aiAnalysisService, aiDailyInsightService, labelVerificationCoordinator,
                researchDailyReportService);
        AiDailyPipelineExecutor.PipelineContext context = context();

        executor.execute("FETCH_DATA", context);
        executor.execute("CHECK_DATA_QUALITY", context);
        executor.execute("BUILD_SAMPLES", context);
        executor.execute("COMPUTE_FACTORS", context);
        executor.execute("GENERATE_PREDICTIONS", context);

        verify(marketDataService, times(1)).stockDetailAt(
                "600519", LocalDateTime.of(2026, 7, 10, 16, 0));
        verify(marketDataService, times(1)).klineAt(
                "600519", "day", 60, LocalDateTime.of(2026, 7, 10, 16, 0));
        verify(sampleSnapshotService, times(1)).createOrGetSnapshot(any());
        verify(factorEngine, times(1)).computeAndStoreCrossSection(any());
        verify(predictionEngine, times(3)).predictAndStore(any());
        verify(sampleSnapshotService, times(1)).completeBatch(
                org.mockito.ArgumentMatchers.eq(11L), any());
    }

    @Test
    void verifyLabelsRunsPredictionAndConditionalPlanReviewsTogether() {
        WatchStockMapper watchStockMapper = mock(WatchStockMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiSampleSnapshotService sampleSnapshotService = mock(AiSampleSnapshotService.class);
        AiFactorEngineV2 factorEngine = mock(AiFactorEngineV2.class);
        AiPredictionEngineV2 predictionEngine = mock(AiPredictionEngineV2.class);
        AiAnalysisService aiAnalysisService = mock(AiAnalysisService.class);
        AiDailyInsightService aiDailyInsightService = mock(AiDailyInsightService.class);
        AiLabelVerificationCoordinatorV2 labelVerificationCoordinator = mock(AiLabelVerificationCoordinatorV2.class);
        AiConditionalTradeStrategyService conditionalTradeStrategyService = mock(AiConditionalTradeStrategyService.class);
        AiResearchDailyReportService researchDailyReportService = mock(AiResearchDailyReportService.class);
        when(labelVerificationCoordinator.verifyMatured(any(), any(), any())).thenReturn(
                new AiLabelVerificationCoordinatorV2.VerificationResult(
                        3, 2, 1, List.of("300058: K线暂不可用"), "label-fingerprint"));
        when(conditionalTradeStrategyService.verifyMatured(5L, LocalDate.of(2026, 7, 10))).thenReturn(
                new AiConditionalTradeStrategyService.ReviewRunResult(
                        2, 1, 1, 0, 0, List.of()));
        MaogouDailyPipelineExecutor executor = new MaogouDailyPipelineExecutor(
                watchStockMapper, marketDataService, sampleSnapshotService, factorEngine,
                predictionEngine, null, aiAnalysisService, aiDailyInsightService, labelVerificationCoordinator,
                conditionalTradeStrategyService, researchDailyReportService);

        AiDailyPipelineExecutor.StepOutcome outcome = executor.execute("VERIFY_LABELS", context());

        assertThat(outcome.processedCount()).isEqualTo(5);
        assertThat(outcome.successCount()).isEqualTo(4);
        assertThat(outcome.failedCount()).isEqualTo(1);
        assertThat(outcome.errors()).containsExactly("300058: K线暂不可用");
        verify(labelVerificationCoordinator).verifyMatured(
                5L, LocalDate.of(2026, 7, 10), LocalDateTime.of(2026, 7, 10, 16, 0));
        verify(conditionalTradeStrategyService).verifyMatured(5L, LocalDate.of(2026, 7, 10));
    }

    @Test
    void generateReportsCallsRealAnalysisServiceAndToleratesSingleStockFailures() {
        WatchStockMapper watchStockMapper = mock(WatchStockMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiSampleSnapshotService sampleSnapshotService = mock(AiSampleSnapshotService.class);
        AiFactorEngineV2 factorEngine = mock(AiFactorEngineV2.class);
        AiPredictionEngineV2 predictionEngine = mock(AiPredictionEngineV2.class);
        AiAnalysisService aiAnalysisService = mock(AiAnalysisService.class);
        AiDailyInsightService aiDailyInsightService = mock(AiDailyInsightService.class);
        AiLabelVerificationCoordinatorV2 labelVerificationCoordinator = mock(AiLabelVerificationCoordinatorV2.class);
        AiResearchDailyReportService researchDailyReportService = mock(AiResearchDailyReportService.class);

        WatchStock ok = new WatchStock();
        ok.userId = 5L;
        ok.stockCode = "600519";
        ok.stockName = "贵州茅台";
        WatchStock fail = new WatchStock();
        fail.userId = 5L;
        fail.stockCode = "300058";
        fail.stockName = "蓝色光标";
        when(watchStockMapper.selectList(any())).thenReturn(List.of(ok, fail));

        when(aiAnalysisService.analyzeStockForTradeDate(
                "600519", false, null, null, LocalDate.of(2026, 7, 10))).thenReturn(
                new AiAnalysisReportResponse(1L, "贵州茅台", "600519", 88, "建议观察上攻延续", LocalDateTime.now(),
                        "技术面", "风险", "买卖点", "{}", List.of(), "摘要", "qwen", "SUCCESS", null,
                        11L, 21L, 31L, BigDecimal.ONE, BigDecimal.ONE));
        when(aiAnalysisService.analyzeStockForTradeDate(
                "300058", false, null, null, LocalDate.of(2026, 7, 10)))
                .thenThrow(new IllegalStateException("模型服务超时"));

        MaogouDailyPipelineExecutor executor = new MaogouDailyPipelineExecutor(
                watchStockMapper,
                marketDataService,
                sampleSnapshotService,
                factorEngine,
                predictionEngine,
                aiAnalysisService,
                aiDailyInsightService,
                labelVerificationCoordinator,
                researchDailyReportService);

        AiDailyInsightPayloads.SnapshotSummary summary = new AiDailyInsightPayloads.SnapshotSummary(
                88L,
                LocalDate.of(2026, 7, 10),
                LocalDateTime.of(2026, 7, 10, 16, 5),
                "SUCCESS",
                "ok",
                "REALTIME",
                BigDecimal.valueOf(88),
                2,
                1,
                1,
                4,
                0,
                BigDecimal.valueOf(66),
                LocalDateTime.of(2026, 7, 10, 16, 5),
                LocalDateTime.of(2026, 7, 10, 16, 0),
                9L
        );
        when(aiDailyInsightService.rebuildForPipeline(
                5L, LocalDate.of(2026, 7, 10), 100L,
                "SUCCESS", "自动收盘流水线已完成每日投研聚合"))
                .thenReturn(new AiDailyInsightPayloads.DailyInsightResponse(
                        true,
                        true,
                        "ok",
                        summary,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()
                ));

        AiDailyPipelineExecutor.StepOutcome outcome = executor.execute(
                "GENERATE_REPORTS",
                new AiDailyPipelineExecutor.PipelineContext(
                        100L,
                        5L,
                        LocalDate.of(2026, 7, 10),
                        11L,
                        91L,
                        null,
                        "AUTO_CLOSE:2026-07-10",
                        "input-fingerprint",
                        LocalDateTime.of(2026, 7, 10, 16, 0)));

        assertThat(outcome.processedCount()).isEqualTo(2);
        assertThat(outcome.successCount()).isEqualTo(1);
        assertThat(outcome.failedCount()).isEqualTo(1);
        assertThat(outcome.errors()).contains("300058: 模型服务超时");
        verify(aiAnalysisService, times(1)).analyzeStockForTradeDate(
                "600519", false, null, null, LocalDate.of(2026, 7, 10));
        verify(aiAnalysisService, times(1)).analyzeStockForTradeDate(
                "300058", false, null, null, LocalDate.of(2026, 7, 10));

        AiDailyPipelineExecutor.StepOutcome dailyInsightOutcome = executor.execute(
                "BUILD_DAILY_INSIGHT",
                new AiDailyPipelineExecutor.PipelineContext(
                        100L,
                        5L,
                        LocalDate.of(2026, 7, 10),
                        11L,
                        91L,
                        null,
                        "AUTO_CLOSE:2026-07-10",
                        "input-fingerprint",
                        LocalDateTime.of(2026, 7, 10, 16, 0)));

        assertThat(dailyInsightOutcome.processedCount()).isEqualTo(4);
        assertThat(dailyInsightOutcome.successCount()).isEqualTo(4);
        assertThat(dailyInsightOutcome.failedCount()).isEqualTo(0);
        verify(aiDailyInsightService).rebuildForPipeline(
                5L, LocalDate.of(2026, 7, 10), 100L,
                "SUCCESS", "自动收盘流水线已完成每日投研聚合");
    }

    @Test
    void failedAnalysisResponseIsNotCountedAsASuccessfulReport() {
        WatchStockMapper watchStockMapper = mock(WatchStockMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiSampleSnapshotService sampleSnapshotService = mock(AiSampleSnapshotService.class);
        AiFactorEngineV2 factorEngine = mock(AiFactorEngineV2.class);
        AiPredictionEngineV2 predictionEngine = mock(AiPredictionEngineV2.class);
        AiAnalysisService aiAnalysisService = mock(AiAnalysisService.class);
        AiDailyInsightService aiDailyInsightService = mock(AiDailyInsightService.class);
        AiLabelVerificationCoordinatorV2 labelVerificationCoordinator = mock(AiLabelVerificationCoordinatorV2.class);
        AiResearchDailyReportService researchDailyReportService = mock(AiResearchDailyReportService.class);
        WatchStock stock = new WatchStock();
        stock.userId = 5L;
        stock.stockCode = "600519";
        when(watchStockMapper.selectList(any())).thenReturn(List.of(stock));
        when(aiAnalysisService.analyzeStockForTradeDate(
                "600519", false, null, null, LocalDate.of(2026, 7, 10))).thenReturn(
                new AiAnalysisReportResponse(9L, "贵州茅台", "600519", 0, "", LocalDateTime.now(),
                        "", "", "", "{}", List.of(), "", "qwen", "FAILED", "模型输出无法解析",
                        null, null, null, BigDecimal.ZERO, BigDecimal.ZERO));
        MaogouDailyPipelineExecutor executor = new MaogouDailyPipelineExecutor(
                watchStockMapper, marketDataService, sampleSnapshotService, factorEngine,
                predictionEngine, aiAnalysisService, aiDailyInsightService,
                labelVerificationCoordinator, researchDailyReportService);

        AiDailyPipelineExecutor.StepOutcome outcome = executor.execute("GENERATE_REPORTS", context());

        assertThat(outcome.successCount()).isZero();
        assertThat(outcome.failedCount()).isEqualTo(1);
        assertThat(outcome.errors()).containsExactly("600519: 模型输出无法解析");
    }

    @Test
    void emptyWatchlistStillFinalizesItsDataBatch() {
        WatchStockMapper watchStockMapper = mock(WatchStockMapper.class);
        MarketDataService marketDataService = mock(MarketDataService.class);
        AiSampleSnapshotService sampleSnapshotService = mock(AiSampleSnapshotService.class);
        AiFactorEngineV2 factorEngine = mock(AiFactorEngineV2.class);
        AiPredictionEngineV2 predictionEngine = mock(AiPredictionEngineV2.class);
        AiAnalysisService aiAnalysisService = mock(AiAnalysisService.class);
        AiDailyInsightService aiDailyInsightService = mock(AiDailyInsightService.class);
        AiLabelVerificationCoordinatorV2 labelVerificationCoordinator = mock(AiLabelVerificationCoordinatorV2.class);
        AiResearchDailyReportService researchDailyReportService = mock(AiResearchDailyReportService.class);
        when(watchStockMapper.selectList(any())).thenReturn(List.of());
        AiDataBatch batch = new AiDataBatch();
        batch.id = 11L;
        when(sampleSnapshotService.startOrGetBatch(any(), any(), any(), any(), any())).thenReturn(batch);
        MaogouDailyPipelineExecutor executor = new MaogouDailyPipelineExecutor(
                watchStockMapper, marketDataService, sampleSnapshotService, factorEngine,
                predictionEngine, aiAnalysisService, aiDailyInsightService,
                labelVerificationCoordinator, researchDailyReportService);

        AiDailyPipelineExecutor.StepOutcome outcome = executor.execute("CHECK_DATA_QUALITY", context());

        assertThat(outcome.processedCount()).isZero();
        verify(sampleSnapshotService).completeBatch(
                org.mockito.ArgumentMatchers.eq(11L), any());
    }

    private static AiDailyPipelineExecutor.PipelineContext context() {
        return new AiDailyPipelineExecutor.PipelineContext(
                100L,
                5L,
                LocalDate.of(2026, 7, 10),
                11L,
                91L,
                null,
                "AUTO_CLOSE:2026-07-10",
                "input-fingerprint",
                LocalDateTime.of(2026, 7, 10, 16, 0));
    }
}
