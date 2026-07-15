package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.config.AppProperties;
import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiSourceObservation;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.infrastructure.market.ResearchMarketDataClient;
import com.maogou.stock.infrastructure.market.ResearchSourceResult;
import com.maogou.stock.infrastructure.market.ResearchSourceStatus;
import com.maogou.stock.mapper.research.AiDataBatchMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseSnapshotMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiSourceObservationMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.research.AiFactorEngine;
import com.maogou.stock.service.research.AiGlobalDailyResearchExecutor;
import com.maogou.stock.service.research.AiLabelVerificationCoordinator;
import com.maogou.stock.service.research.AiPredictionEngine;
import com.maogou.stock.service.research.AiResearchUniverseService;
import com.maogou.stock.service.research.AiSampleSnapshotService;
import com.maogou.stock.service.research.BenchmarkSeriesService;
import com.maogou.stock.service.research.IndustryMembershipService;
import com.maogou.stock.service.research.NewsSentimentFeatureService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalDailyResearchExecutorTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 14);
    private static final LocalDateTime STARTED_AT = TRADE_DATE.atTime(16, 0);

    @Test
    void fetchesEachGlobalStockOnceEvenWhenUniverseEvidenceContainsMultipleUsers() {
        Fixture fixture = fixture();
        when(fixture.snapshotMapper.selectById(91L)).thenReturn(snapshot());
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(
                item(1L, "600519", "WATCHLIST:USER:5"),
                item(2L, "600519", "POSITION:USER:6"),
                item(3L, "000001", "WATCHLIST:USER:6")));
        when(fixture.snapshotService.startOrGetBatch(any(), any(), anyString(), any(), anyString()))
                .thenReturn(batch());
        when(fixture.marketDataService.stockDetailAt(anyString(), any()))
                .thenAnswer(invocation -> detail(invocation.getArgument(0), invocation.getArgument(1)));

        AiGlobalDailyResearchExecutor.StepOutcome outcome = fixture.executor.execute(
                "FETCH_SOURCE_DATA", context(Map.of(
                        "SNAPSHOT_UNIVERSE", "{\"universeSnapshotId\":91}")));

        assertThat(outcome.processedCount()).isEqualTo(2);
        assertThat(outcome.successCount()).isEqualTo(2);
        assertThat(outcome.dataBatchId()).isEqualTo(55L);
        verify(fixture.marketDataService, times(1)).stockDetailAt(
                org.mockito.ArgumentMatchers.eq("600519"), any());
        verify(fixture.marketDataService, times(1)).stockDetailAt(
                org.mockito.ArgumentMatchers.eq("000001"), any());
        verify(fixture.marketDataService, times(2)).stockDetailAt(anyString(), any());
    }

    @Test
    void usesOneMillisecondPrecisionCaptureTimeForTheWholeSourceBatch() {
        Fixture fixture = fixture();
        when(fixture.snapshotMapper.selectById(91L)).thenReturn(snapshot());
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(
                item(1L, "600519", "WATCHLIST:USER:5")));
        when(fixture.snapshotService.startOrGetBatch(any(), any(), anyString(), any(), anyString()))
                .thenReturn(batch());
        when(fixture.marketDataService.stockDetailAt(anyString(), any()))
                .thenAnswer(invocation -> detail(invocation.getArgument(0), invocation.getArgument(1)));

        fixture.executor.execute("FETCH_SOURCE_DATA", context(Map.of(
                "SNAPSHOT_UNIVERSE", "{\"universeSnapshotId\":91}")));

        ArgumentCaptor<LocalDateTime> batchTime = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> benchmarkTime = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> stockTime = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(fixture.snapshotService).startOrGetBatch(
                org.mockito.ArgumentMatchers.eq(91L),
                org.mockito.ArgumentMatchers.eq(TRADE_DATE),
                org.mockito.ArgumentMatchers.eq("AFTER_CLOSE"),
                batchTime.capture(), anyString());
        verify(fixture.resilientMarketDataClient).fetchKlineAt(
                org.mockito.ArgumentMatchers.eq("000300.SH"),
                org.mockito.ArgumentMatchers.eq("day"),
                org.mockito.ArgumentMatchers.eq(80),
                benchmarkTime.capture());
        verify(fixture.marketDataService).stockDetailAt(
                org.mockito.ArgumentMatchers.eq("600519"), stockTime.capture());
        assertThat(batchTime.getValue()).isEqualTo(benchmarkTime.getValue()).isEqualTo(stockTime.getValue());
        assertThat(batchTime.getValue().getNano() % 1_000_000).isZero();
    }

    @Test
    void waitingSourceRecoversWithTheSamePersistedBatchAfterExecutorRestart() {
        Fixture fixture = fixture();
        AiDataBatch batch = batch();
        when(fixture.dataBatchMapper.selectById(55L)).thenReturn(batch);
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(
                item(1L, "600519", "WATCHLIST:USER:5"),
                item(2L, "000001", "WATCHLIST:USER:6")));
        when(fixture.observationMapper.selectList(any())).thenReturn(List.of(
                observation("600519", "READY"),
                observation("000001", "UNAVAILABLE"),
                benchmarkObservation()));

        AiGlobalDailyResearchExecutor.StepOutcome waiting = fixture.executor.execute(
                "WAIT_DATA_READY", context(Map.of(
                        "FETCH_SOURCE_DATA", "{\"universeSnapshotId\":91,\"dataBatchId\":55}")));

        assertThat(waiting.status()).isEqualTo("WAITING_SOURCE");
        assertThat(waiting.dataBatchId()).isEqualTo(55L);
        assertThat(waiting.nextRetryAt()).isNotNull();

        when(fixture.observationMapper.selectList(any())).thenReturn(List.of(
                observation("600519", "READY"),
                observation("000001", "READY"),
                benchmarkObservation()));
        GlobalDailyResearchExecutor restarted = fixture.newExecutor();
        AiGlobalDailyResearchExecutor.StepOutcome recovered = restarted.execute(
                "WAIT_DATA_READY", context(Map.of(
                        "FETCH_SOURCE_DATA", "{\"universeSnapshotId\":91,\"dataBatchId\":55}")));

        assertThat(recovered.status()).isEqualTo("SUCCESS");
        assertThat(recovered.dataBatchId()).isEqualTo(55L);
        assertThat(batch.status).isEqualTo("READY");
        assertThat(batch.completedAt).isNotNull();
        assertThat(batch.completedAt.getNano() % 1_000_000).isZero();
    }

    @Test
    void rebuildsSamplesFromDatabaseCheckpointsWithoutJvmBusinessState() throws Exception {
        Fixture fixture = fixture();
        AiDataBatch batch = batch();
        batch.qualityStatus = "READY";
        when(fixture.dataBatchMapper.selectById(55L)).thenReturn(batch);
        AiResearchUniverseItem item = item(1L, "600519", "WATCHLIST:USER:5");
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(item));
        AiSourceObservation observation = observation("600519", "READY");
        observation.payloadJson = fixture.objectMapper.writeValueAsString(detail("600519", STARTED_AT));
        when(fixture.observationMapper.selectList(any())).thenReturn(List.of(observation));
        AiSample sample = new AiSample();
        sample.id = 801L;
        sample.dataBatchId = 55L;
        sample.universeItemId = 1L;
        sample.stockCode = "600519";
        when(fixture.snapshotService.createOrGetSnapshot(any())).thenReturn(sample);

        GlobalDailyResearchExecutor restarted = fixture.newExecutor();
        AiGlobalDailyResearchExecutor.StepOutcome outcome = restarted.execute(
                "BUILD_SAMPLES", context(Map.of(
                        "FETCH_SOURCE_DATA", "{\"universeSnapshotId\":91,\"dataBatchId\":55}")));

        assertThat(outcome.status()).isEqualTo("SUCCESS");
        assertThat(outcome.checkpointJson()).contains("\"sampleIds\":[801]");
        assertThat(GlobalDailyResearchExecutor.class.getDeclaredFields())
                .filteredOn(field -> !Modifier.isStatic(field.getModifiers()))
                .noneMatch(field -> Map.class.isAssignableFrom(field.getType())
                        || ConcurrentMap.class.isAssignableFrom(field.getType()));
    }

    @Test
    void retryOfAnExistingBatchReusesPersistedEvidenceWithoutRefetchingLateData() {
        Fixture fixture = fixture();
        when(fixture.snapshotMapper.selectById(91L)).thenReturn(snapshot());
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(
                item(1L, "600519", "WATCHLIST:USER:5")));
        when(fixture.snapshotService.startOrGetBatch(any(), any(), anyString(), any(), anyString()))
                .thenReturn(batch());
        when(fixture.observationMapper.selectList(any())).thenReturn(List.of(
                observation("600519", "READY"), benchmarkObservation()));
        clearInvocations(fixture.resilientMarketDataClient, fixture.marketDataService);

        AiGlobalDailyResearchExecutor.StepOutcome outcome = fixture.executor.execute(
                "FETCH_SOURCE_DATA", context(Map.of(
                        "SNAPSHOT_UNIVERSE", "{\"universeSnapshotId\":91}")));

        assertThat(outcome.checkpointJson()).contains("\"reusedPersistedEvidence\":true");
        verify(fixture.marketDataService, never()).stockDetailAt(anyString(), any());
        verify(fixture.resilientMarketDataClient, never()).fetchKlineAt(anyString(), anyString(), any(Integer.class), any());
    }

    @Test
    void waitingSourceRetryUsesANewImmutableBatchRevisionAndRefetches() {
        Fixture fixture = fixture();
        AiDataBatch retryBatch = batch();
        retryBatch.id = 56L;
        when(fixture.snapshotMapper.selectById(91L)).thenReturn(snapshot());
        when(fixture.itemMapper.selectList(any())).thenReturn(List.of(
                item(1L, "600519", "WATCHLIST:USER:5")));
        when(fixture.snapshotService.startOrGetBatch(any(), any(), anyString(), any(), anyString()))
                .thenReturn(retryBatch);
        when(fixture.observationMapper.selectList(any())).thenReturn(List.of());
        when(fixture.marketDataService.stockDetailAt(anyString(), any()))
                .thenAnswer(invocation -> detail(invocation.getArgument(0), invocation.getArgument(1)));
        clearInvocations(fixture.resilientMarketDataClient, fixture.marketDataService);

        AiGlobalDailyResearchExecutor.StepOutcome outcome = fixture.executor.execute(
                "FETCH_SOURCE_DATA", context(1, Map.of(
                        "SNAPSHOT_UNIVERSE", "{\"universeSnapshotId\":91}")));

        assertThat(outcome.dataBatchId()).isEqualTo(56L);
        assertThat(outcome.checkpointJson()).doesNotContain("reusedPersistedEvidence");
        verify(fixture.snapshotService).startOrGetBatch(
                org.mockito.ArgumentMatchers.eq(91L),
                org.mockito.ArgumentMatchers.eq(TRADE_DATE),
                org.mockito.ArgumentMatchers.eq("AFTER_CLOSE"),
                any(),
                org.mockito.ArgumentMatchers.argThat(key -> key.startsWith("BATCH:") && key.length() == 70));
        verify(fixture.marketDataService).stockDetailAt(
                org.mockito.ArgumentMatchers.eq("600519"), any());
        verify(fixture.resilientMarketDataClient).fetchKlineAt(
                org.mockito.ArgumentMatchers.eq("000300.SH"),
                org.mockito.ArgumentMatchers.eq("day"),
                org.mockito.ArgumentMatchers.eq(80),
                any());
    }

    private static Fixture fixture() {
        AiResearchUniverseItemMapper itemMapper = mock(AiResearchUniverseItemMapper.class);
        ResearchMarketDataClient researchMarketDataClient = mock(ResearchMarketDataClient.class);
        when(researchMarketDataClient.fetchKlineAt(anyString(), anyString(), any(Integer.class), any()))
                .thenReturn(benchmarkResult());
        when(researchMarketDataClient.fetchIndustryAt(anyString(), any())).thenReturn(
                new ResearchSourceResult<>(null, ResearchSourceStatus.UNAVAILABLE, "UNAVAILABLE",
                        "UNAVAILABLE", null, STARTED_AT, null, "无行业", List.of()));
        when(researchMarketDataClient.fetchNewsAt(any(Integer.class), any())).thenReturn(
                new ResearchSourceResult<>(null, ResearchSourceStatus.UNAVAILABLE, "UNAVAILABLE",
                        "UNAVAILABLE", null, STARTED_AT, null, "无资讯", List.of()));
        AppProperties properties = new AppProperties();
        return new Fixture(
                mock(AiResearchUniverseService.class),
                mock(AiResearchUniverseSnapshotMapper.class),
                itemMapper,
                mock(AiDataBatchMapper.class),
                mock(AiSourceObservationMapper.class),
                mock(AiSampleMapper.class),
                mock(AiSampleSnapshotService.class),
                mock(MarketDataService.class),
                mock(AiFactorEngine.class),
                mock(AiPredictionEngine.class),
                mock(AiLabelVerificationCoordinator.class),
                researchMarketDataClient,
                new BenchmarkSeriesService(researchMarketDataClient, properties),
                new IndustryMembershipService(itemMapper, researchMarketDataClient,
                        Clock.fixed(Instant.parse("2026-07-14T08:00:00Z"), ZoneId.of("Asia/Shanghai"))),
                new NewsSentimentFeatureService(researchMarketDataClient, properties),
                new ObjectMapper().findAndRegisterModules());
    }

    private static AiGlobalDailyResearchExecutor.PipelineContext context(Map<String, String> checkpoints) {
        return context(0, checkpoints);
    }

    private static AiGlobalDailyResearchExecutor.PipelineContext context(
            int attemptNo,
            Map<String, String> checkpoints
    ) {
        return new AiGlobalDailyResearchExecutor.PipelineContext(
                4001L, TRADE_DATE, 1L, null,
                "GLOBAL_DAILY:2026-07-14", "input-fingerprint", STARTED_AT,
                attemptNo, checkpoints, () -> { });
    }

    private static AiResearchUniverseSnapshot snapshot() {
        AiResearchUniverseSnapshot snapshot = new AiResearchUniverseSnapshot();
        snapshot.id = 91L;
        snapshot.researchUniverseId = 1L;
        snapshot.tradeDate = TRADE_DATE;
        snapshot.universeVersion = "CN_A_SYSTEM_CORE/2026-07-14/R0001";
        snapshot.qualityStatus = "READY";
        return snapshot;
    }

    private static AiDataBatch batch() {
        AiDataBatch batch = new AiDataBatch();
        batch.id = 55L;
        batch.universeSnapshotId = 91L;
        batch.tradeDate = TRADE_DATE;
        batch.asOfTime = STARTED_AT;
        batch.qualityStatus = "UNAVAILABLE";
        return batch;
    }

    private static AiResearchUniverseItem item(Long id, String code, String reason) {
        AiResearchUniverseItem item = new AiResearchUniverseItem();
        item.id = id;
        item.universeSnapshotId = 91L;
        item.stockCode = code;
        item.stockName = code;
        item.sourceType = "USER_UNION";
        item.inclusionReason = reason;
        item.included = 1;
        return item;
    }

    private static AiSourceObservation observation(String code, String status) {
        AiSourceObservation observation = new AiSourceObservation();
        observation.id = code == null ? 0L : Math.abs((long) code.hashCode());
        observation.dataBatchId = 55L;
        observation.stockCode = code;
        observation.sourceType = "STOCK_DAILY_SNAPSHOT";
        observation.qualityStatus = status;
        observation.fetchedAt = STARTED_AT;
        observation.missingReason = "READY".equals(status) ? null : "收盘数据未就绪";
        return observation;
    }

    private static AiSourceObservation benchmarkObservation() {
        AiSourceObservation observation = observation(null, "READY");
        observation.id = 9001L;
        observation.sourceType = "MARKET_BENCHMARK";
        observation.freshnessStatus = "REALTIME";
        return observation;
    }

    private static StockDetailResponse detail(String code, LocalDateTime fetchedAt) {
        StockQuoteResponse quote = new StockQuoteResponse(
                code, code, new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ONE, "CN_A", "EASTMONEY", fetchedAt);
        KlinePointResponse kline = new KlinePointResponse(
                TRADE_DATE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                BigDecimal.TEN, 1000L, new BigDecimal("10000"));
        return new StockDetailResponse(quote, FinanceSnapshotResponse.empty(), List.of(), List.of(kline), null, null);
    }

    private record Fixture(
            AiResearchUniverseService universeService,
            AiResearchUniverseSnapshotMapper snapshotMapper,
            AiResearchUniverseItemMapper itemMapper,
            AiDataBatchMapper dataBatchMapper,
            AiSourceObservationMapper observationMapper,
            AiSampleMapper sampleMapper,
            AiSampleSnapshotService snapshotService,
            MarketDataService marketDataService,
            AiFactorEngine factorEngine,
            AiPredictionEngine predictionEngine,
            AiLabelVerificationCoordinator labelCoordinator,
            ResearchMarketDataClient resilientMarketDataClient,
            BenchmarkSeriesService benchmarkSeriesService,
            IndustryMembershipService industryMembershipService,
            NewsSentimentFeatureService newsSentimentFeatureService,
            ObjectMapper objectMapper,
            GlobalDailyResearchExecutor executor
    ) {
        private Fixture(
                AiResearchUniverseService universeService,
                AiResearchUniverseSnapshotMapper snapshotMapper,
                AiResearchUniverseItemMapper itemMapper,
                AiDataBatchMapper dataBatchMapper,
                AiSourceObservationMapper observationMapper,
                AiSampleMapper sampleMapper,
                AiSampleSnapshotService snapshotService,
                MarketDataService marketDataService,
                AiFactorEngine factorEngine,
                AiPredictionEngine predictionEngine,
                AiLabelVerificationCoordinator labelCoordinator,
                ResearchMarketDataClient resilientMarketDataClient,
                BenchmarkSeriesService benchmarkSeriesService,
                IndustryMembershipService industryMembershipService,
                NewsSentimentFeatureService newsSentimentFeatureService,
                ObjectMapper objectMapper
        ) {
            this(universeService, snapshotMapper, itemMapper, dataBatchMapper, observationMapper,
                    sampleMapper, snapshotService, marketDataService, factorEngine, predictionEngine,
                    labelCoordinator, resilientMarketDataClient, benchmarkSeriesService,
                    industryMembershipService, newsSentimentFeatureService, objectMapper,
                    new GlobalDailyResearchExecutor(
                            universeService, snapshotMapper, itemMapper, dataBatchMapper, observationMapper,
                            sampleMapper, snapshotService, marketDataService, factorEngine, predictionEngine,
                            labelCoordinator, resilientMarketDataClient, benchmarkSeriesService,
                            industryMembershipService, newsSentimentFeatureService, objectMapper));
        }

        private GlobalDailyResearchExecutor newExecutor() {
            return new GlobalDailyResearchExecutor(
                    universeService, snapshotMapper, itemMapper, dataBatchMapper, observationMapper,
                    sampleMapper, snapshotService, marketDataService, factorEngine, predictionEngine,
                    labelCoordinator, resilientMarketDataClient, benchmarkSeriesService,
                    industryMembershipService, newsSentimentFeatureService, objectMapper);
        }
    }

    private static ResearchSourceResult<com.maogou.stock.dto.market.KlineSeriesSnapshot> benchmarkResult() {
        com.maogou.stock.dto.market.KlineSeriesSnapshot series =
                com.maogou.stock.dto.market.KlineSeriesSnapshot.create(
                        "000300", "day", "NONE", "EASTMONEY", STARTED_AT, STARTED_AT,
                        List.of(new KlinePointResponse(
                                TRADE_DATE, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN,
                                BigDecimal.TEN, 1000L, new BigDecimal("10000"))));
        return new ResearchSourceResult<>(series, ResearchSourceStatus.REALTIME, "READY",
                "EASTMONEY", STARTED_AT, STARTED_AT, series.sourceFingerprint(), "", List.of());
    }
}
