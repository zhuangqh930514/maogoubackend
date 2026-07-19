package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiSourceObservation;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.NewsFlashResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.infrastructure.market.ResearchMarketDataClient;
import com.maogou.stock.infrastructure.market.ResearchSourceResult;
import com.maogou.stock.mapper.research.AiDataBatchMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseSnapshotMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiSourceObservationMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.research.AiFactorEngine;
import com.maogou.stock.service.research.AiGlobalDailyResearchExecutor;
import com.maogou.stock.service.research.AiLabelVerificationCoordinator;
import com.maogou.stock.service.research.AiIndustryDailyBarService;
import com.maogou.stock.service.research.AiPredictionEngine;
import com.maogou.stock.service.research.AiResearchUniverseService;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiSampleSnapshotService;
import com.maogou.stock.service.research.BenchmarkSeriesService;
import com.maogou.stock.service.research.ExternalIoTransactionGuard;
import com.maogou.stock.service.research.IndustryMembershipService;
import com.maogou.stock.service.research.NewsSentimentFeatureService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class GlobalDailyResearchExecutor implements AiGlobalDailyResearchExecutor {

    private static final List<Integer> PREDICTION_HORIZONS = List.of(1, 2, 3, 5);

    private final AiResearchUniverseService universeService;
    private final AiResearchUniverseSnapshotMapper universeSnapshotMapper;
    private final AiResearchUniverseItemMapper universeItemMapper;
    private final AiDataBatchMapper dataBatchMapper;
    private final AiSourceObservationMapper observationMapper;
    private final AiSampleMapper sampleMapper;
    private final AiSampleSnapshotService sampleSnapshotService;
    private final MarketDataService marketDataService;
    private final AiFactorEngine factorEngine;
    private final AiPredictionEngine predictionEngine;
    private final AiLabelVerificationCoordinator labelCoordinator;
    private final ResearchMarketDataClient resilientMarketDataClient;
    private final BenchmarkSeriesService benchmarkSeriesService;
    private final IndustryMembershipService industryMembershipService;
    private final AiIndustryDailyBarService industryDailyBarService;
    private final NewsSentimentFeatureService newsSentimentFeatureService;
    private final ObjectMapper objectMapper;

    public GlobalDailyResearchExecutor(
            AiResearchUniverseService universeService,
            AiResearchUniverseSnapshotMapper universeSnapshotMapper,
            AiResearchUniverseItemMapper universeItemMapper,
            AiDataBatchMapper dataBatchMapper,
            AiSourceObservationMapper observationMapper,
            AiSampleMapper sampleMapper,
            AiSampleSnapshotService sampleSnapshotService,
            MarketDataService marketDataService,
            AiFactorEngine factorEngine,
            AiPredictionEngine predictionEngine,
            AiLabelVerificationCoordinator labelCoordinator,
            ResearchMarketDataClient resilientMarketDataClient,
            BenchmarkSeriesService benchmarkSeriesService,
            IndustryMembershipService industryMembershipService,
            AiIndustryDailyBarService industryDailyBarService,
            NewsSentimentFeatureService newsSentimentFeatureService,
            ObjectMapper objectMapper
    ) {
        this.universeService = universeService;
        this.universeSnapshotMapper = universeSnapshotMapper;
        this.universeItemMapper = universeItemMapper;
        this.dataBatchMapper = dataBatchMapper;
        this.observationMapper = observationMapper;
        this.sampleMapper = sampleMapper;
        this.sampleSnapshotService = sampleSnapshotService;
        this.marketDataService = marketDataService;
        this.factorEngine = factorEngine;
        this.predictionEngine = predictionEngine;
        this.labelCoordinator = labelCoordinator;
        this.resilientMarketDataClient = resilientMarketDataClient;
        this.benchmarkSeriesService = benchmarkSeriesService;
        this.industryMembershipService = industryMembershipService;
        this.industryDailyBarService = industryDailyBarService;
        this.newsSentimentFeatureService = newsSentimentFeatureService;
        this.objectMapper = objectMapper;
    }

    @Override
    public StepOutcome execute(String stepKey, PipelineContext context) {
        context.checkpointLease();
        StepOutcome outcome = switch (stepKey) {
            case "SNAPSHOT_UNIVERSE" -> snapshotUniverse(context);
            case "FETCH_SOURCE_DATA" -> fetchSourceData(context);
            case "WAIT_DATA_READY" -> waitDataReady(context);
            case "BUILD_SAMPLES" -> buildSamples(context);
            case "MATURE_SAMPLE_LABELS" -> matureSampleLabels(context);
            case "MATURE_HISTORICAL_SAMPLE_LABELS" -> matureSampleLabels(
                    context, "MATURE_HISTORICAL_SAMPLE_LABELS", HISTORICAL_FINALIZE_BATCH_SIZE);
            case "COMPUTE_FACTORS" -> computeFactors(context);
            case "GENERATE_PREDICTIONS" -> generatePredictions(context);
            case "EVALUATE_PREDICTIONS" -> evaluatePredictions(context);
            case "EVALUATE_HISTORICAL_PREDICTIONS" -> evaluatePredictions(
                    context, "EVALUATE_HISTORICAL_PREDICTIONS", HISTORICAL_FINALIZE_BATCH_SIZE);
            default -> throw new IllegalArgumentException("未知全局日研究步骤：" + stepKey);
        };
        context.checkpointLease();
        return outcome;
    }

    private StepOutcome snapshotUniverse(PipelineContext context) {
        boolean observedOnTradeDate = context.tradeDate().equals(context.startedAt().toLocalDate());
        AiResearchUniverseService.SnapshotResult result = universeService.createSystemCoreSnapshot(
                new AiResearchUniverseService.SnapshotRequest(
                        context.tradeDate(), context.startedAt(), AiResearchContract.CALENDAR_VERSION, List.of(), true,
                        "SYSTEM_CORE_LIVE_PROVIDER", AiResearchContract.UNIVERSE_MEMBERSHIP_VERSION,
                        context.startedAt(), observedOnTradeDate ? "READY" : "PARTIAL",
                        observedOnTradeDate ? null : "当前股票目录不能作为历史交易日的完整成分证据"));
        if (result.snapshot() == null || result.snapshot().id == null || result.universe() == null) {
            throw new IllegalStateException("全局研究股票池未生成有效快照");
        }
        int included = (int) result.items().stream().filter(item -> Integer.valueOf(1).equals(item.included)).count();
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("researchUniverseId", result.universe().id);
        checkpoint.put("universeSnapshotId", result.snapshot().id);
        checkpoint.put("universeVersion", result.snapshot().universeVersion);
        checkpoint.put("includedCount", included);
        checkpoint.put("qualityStatus", result.snapshot().qualityStatus);
        return success("SNAPSHOT_UNIVERSE", result.items().size(), included,
                result.items().size() - included, checkpoint, null,
                included == 0 ? List.of("研究股票池没有可用股票") : List.of());
    }

    private StepOutcome fetchSourceData(PipelineContext context) {
        Long snapshotId = requiredCheckpointId(context, "SNAPSHOT_UNIVERSE", "universeSnapshotId");
        AiResearchUniverseSnapshot snapshot = requiredSnapshot(snapshotId);
        List<AiResearchUniverseItem> items = includedItems(snapshotId);
        LocalDateTime fetchStartedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        AiDataBatch batch = sampleSnapshotService.startOrGetBatch(
                snapshotId, context.tradeDate(), "AFTER_CLOSE", fetchStartedAt,
                batchIdempotencyKey(context));
        List<AiSourceObservation> existingObservations = observations(batch.id);
        if (!existingObservations.isEmpty()) {
            return resumeExistingSourceData(batch, items, existingObservations);
        }

        ResearchSourceResult<KlineSeriesSnapshot> benchmark = ExternalIoTransactionGuard.call(
                "基准行情调用", () -> benchmarkSeriesService.load(fetchStartedAt, 80));
        storeObservation(sourceObservation(
                batch, null, "MARKET_BENCHMARK", "KLINE", benchmark.providerCode(),
                benchmark.sourceUpdatedAt(), null, fetchStartedAt, benchmark.data(),
                benchmark.formalReady() ? "READY" : benchmark.qualityStatus(), benchmark.message(),
                benchmark.responseFingerprint()));
        boolean benchmarkReady = benchmark.formalReady()
                && latestTradeDate(benchmark.data()).filter(context.tradeDate()::equals).isPresent();

        NewsSentimentFeatureService.NewsBatch newsBatch = ExternalIoTransactionGuard.call(
                "研究资讯调用", () -> newsSentimentFeatureService.load(fetchStartedAt, 100));
        storeObservation(sourceObservation(
                batch, null, "NEWS_RAW", "NEWS", newsBatch.providerCode(), null,
                newsBatch.news().stream().map(NewsFlashResponse::publishedAt).filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()).orElse(null),
                fetchStartedAt, newsBatch.news(), newsBatch.available() ? "READY" : "UNAVAILABLE",
                newsBatch.missingReason(), newsBatch.sourceFingerprint()));

        int success = 0;
        List<String> errors = new ArrayList<>();
        if (!benchmarkReady) {
            errors.add("市场基准数据不可用：" + benchmark.message());
        }
        Set<String> seenCodes = new LinkedHashSet<>();
        Map<String, ResearchSourceResult<KlineSeriesSnapshot>> sectorSeriesByIndustry = new LinkedHashMap<>();
        Set<String> normalizedIndustryBars = new LinkedHashSet<>();
        for (AiResearchUniverseItem item : items) {
            if (!seenCodes.add(item.stockCode)) {
                continue;
            }
            context.checkpointLease();
            IndustryMembershipService.Membership membership = ExternalIoTransactionGuard.call(
                    "行业归属数据调用",
                    () -> industryMembershipService.resolve(item.stockCode, fetchStartedAt));
            storeObservation(sourceObservation(
                    batch, item.stockCode, "INDUSTRY_MEMBERSHIP", "INDUSTRY_MEMBERSHIP",
                    membership.providerCode(), membership.effectiveFrom() == null ? null
                            : membership.effectiveFrom().atStartOfDay(), null,
                    fetchStartedAt, membership, membership.available() ? "READY" : "UNAVAILABLE",
                    membership.missingReason(), membership.sourceFingerprint()));

            if (membership.available()) {
                ResearchSourceResult<KlineSeriesSnapshot> sectorSeries = sectorSeriesByIndustry.computeIfAbsent(
                        membership.industryCode(), ignored -> ExternalIoTransactionGuard.call(
                                "行业行情调用",
                                () -> resilientMarketDataClient.fetchKlineAt(
                                        membership.industryCode(), "day", 80, fetchStartedAt)));
                if (sectorSeries.formalReady() && normalizedIndustryBars.add(membership.industryCode())) {
                    try {
                        persistIndustryBars(membership, sectorSeries, context.tradeDate(), fetchStartedAt);
                    } catch (RuntimeException exception) {
                        errors.add(membership.industryCode() + ": 行业日线固化失败：" + rootMessage(exception));
                    }
                }
                storeObservation(sourceObservation(
                        batch, item.stockCode, "INDUSTRY_BENCHMARK", "KLINE",
                        sectorSeries.providerCode(), sectorSeries.sourceUpdatedAt(), null,
                        fetchStartedAt, sectorSeries.data(),
                        sectorSeries.formalReady() ? "READY" : sectorSeries.qualityStatus(),
                        sectorSeries.message(), sectorSeries.responseFingerprint()));
            } else {
                storeObservation(sourceObservation(
                        batch, item.stockCode, "INDUSTRY_BENCHMARK", "KLINE", "UNAVAILABLE",
                        null, null, fetchStartedAt, null, "UNAVAILABLE",
                        membership.missingReason(), membership.sourceFingerprint()));
            }

            NewsSentimentFeatureService.Feature newsFeature = newsSentimentFeatureService.calculate(
                    newsBatch.news(), item.stockCode, item.stockName, membership.industryName(), fetchStartedAt);
            storeObservation(sourceObservation(
                    batch, item.stockCode, "NEWS_SENTIMENT_FEATURE", "DERIVED_NEWS_FEATURE",
                    newsBatch.providerCode(), null, newsFeature.latestPublishedAt(), fetchStartedAt,
                    newsFeature, newsBatch.available() && newsFeature.available() ? "READY" : "UNAVAILABLE",
                    newsBatch.available() ? newsFeature.missingReason() : newsBatch.missingReason(),
                    newsFeature.sourceFingerprint()));
            try {
                StockDetailResponse detail = ExternalIoTransactionGuard.call(
                        "研究个股行情调用",
                        () -> marketDataService.stockDetailAt(item.stockCode, fetchStartedAt));
                AiSourceObservation observation = observation(
                        batch, item.stockCode, detail, context.tradeDate(), fetchStartedAt, null);
                storeObservation(observation);
                if ("READY".equals(observation.qualityStatus)) {
                    success++;
                } else {
                    errors.add(item.stockCode + ": " + observation.missingReason);
                }
            } catch (RuntimeException exception) {
                String message = rootMessage(exception);
                storeObservation(observation(
                        batch, item.stockCode, null, context.tradeDate(), fetchStartedAt, message));
                errors.add(item.stockCode + ": " + message);
            }
        }

        batch.itemCount = seenCodes.size();
        batch.successCount = success;
        batch.failedCount = Math.max(0, seenCodes.size() - success);
        boolean coreReady = benchmarkReady && success == seenCodes.size() && !seenCodes.isEmpty();
        batch.sourceStatus = coreReady ? "REALTIME" : success == 0 ? "UNAVAILABLE" : "PARTIAL";
        batch.qualityStatus = coreReady ? "READY" : success == 0 ? "UNAVAILABLE" : "PARTIAL";
        batch.benchmarkAsOf = latestTradeDate(benchmark.data()).orElse(null);
        batch.newsAsOf = newsBatch.news().stream().map(NewsFlashResponse::publishedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        batch.sectorAsOf = fetchStartedAt;
        batch.status = "FETCHED";
        batch.errorMessage = errors.isEmpty() ? null : String.join("；", errors);
        dataBatchMapper.updateById(batch);

        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("universeSnapshotId", snapshotId);
        checkpoint.put("dataBatchId", batch.id);
        checkpoint.put("expectedCount", seenCodes.size());
        checkpoint.put("readyCount", success);
        checkpoint.put("benchmarkReady", benchmarkReady);
        checkpoint.put("newsReady", newsBatch.available());
        checkpoint.put("fetchedAt", fetchStartedAt);
        return success("FETCH_SOURCE_DATA", seenCodes.size(), success,
                Math.max(0, seenCodes.size() - success), checkpoint, batch.id, errors);
    }

    private void persistIndustryBars(
            IndustryMembershipService.Membership membership,
            ResearchSourceResult<KlineSeriesSnapshot> result,
            LocalDate tradeDate,
            LocalDateTime observedAt
    ) {
        KlineSeriesSnapshot series = result.data();
        if (series == null || series.points() == null || series.points().isEmpty()
                || membership.industryCode() == null || membership.industryName() == null) {
            throw new IllegalStateException("行业日线或行业归属为空");
        }
        String responseFingerprint = result.responseFingerprint() == null
                ? series.sourceFingerprint() : result.responseFingerprint();
        String sourceRevision = "LIVE-" + responseFingerprint.substring(
                0, Math.min(48, responseFingerprint.length()));
        for (KlinePointResponse point : series.points().stream()
                .filter(Objects::nonNull)
                .filter(value -> value.tradeDate() != null && !value.tradeDate().isAfter(tradeDate))
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                .toList()) {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("format", "MAOGOU_LIVE_INDUSTRY_BAR_V1");
            evidence.put("providerCode", result.providerCode());
            evidence.put("responseFingerprint", responseFingerprint);
            evidence.put("seriesFingerprint", series.sourceFingerprint());
            evidence.put("seriesAsOfTime", series.asOfTime());
            evidence.put("seriesFetchedAt", series.fetchedAt());
            evidence.put("observedAt", observedAt);
            String sourceRef = result.providerCode() + "/industry-kline/" + membership.industryCode();
            String fingerprint = fingerprint(String.join("|",
                    "LIVE_INDUSTRY_BAR/1.0.0", membership.industryCode(), point.tradeDate().toString(),
                    String.valueOf(point.open()), String.valueOf(point.high()), String.valueOf(point.low()),
                    String.valueOf(point.close()), String.valueOf(point.volume()), String.valueOf(point.amount()),
                    responseFingerprint));
            industryDailyBarService.store(new AiIndustryDailyBarService.BarCommand(
                    membership.industryCode(), membership.industryName(), "PROVIDER_NATIVE",
                    point.tradeDate(), point.open(), point.high(), point.low(), point.close(),
                    BigDecimal.valueOf(point.volume()), point.amount() == null ? BigDecimal.ZERO : point.amount(),
                    result.providerCode(), sourceRevision, "READY", sourceRef, json(evidence),
                    fingerprint, observedAt));
        }
    }

    private StepOutcome resumeExistingSourceData(
            AiDataBatch batch,
            List<AiResearchUniverseItem> items,
            List<AiSourceObservation> observations
    ) {
        Map<String, AiSourceObservation> index = new LinkedHashMap<>();
        observations.stream()
                .sorted(Comparator.comparing((AiSourceObservation value) -> value.fetchedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(observation -> index.putIfAbsent(
                        observationKey(observation.sourceType, observation.stockCode), observation));
        List<String> expectedCodes = items.stream().map(item -> item.stockCode).distinct().sorted().toList();
        long ready = expectedCodes.stream()
                .map(code -> index.get(observationKey("STOCK_DAILY_SNAPSHOT", code)))
                .filter(Objects::nonNull)
                .filter(observation -> "READY".equals(observation.qualityStatus))
                .count();
        AiSourceObservation benchmark = index.get(observationKey("MARKET_BENCHMARK", null));
        boolean benchmarkReady = benchmark != null && "READY".equals(benchmark.qualityStatus);
        List<String> missing = expectedCodes.stream()
                .filter(code -> {
                    AiSourceObservation observation = index.get(
                            observationKey("STOCK_DAILY_SNAPSHOT", code));
                    return observation == null || !"READY".equals(observation.qualityStatus);
                })
                .toList();
        List<String> errors = new ArrayList<>();
        if (!benchmarkReady) {
            errors.add("原数据批次缺少 READY 市场基准证据");
        }
        if (!missing.isEmpty()) {
            errors.add("原数据批次缺少 READY 个股证据：" + missing);
        }
        boolean coreReady = benchmarkReady && ready == expectedCodes.size() && !expectedCodes.isEmpty();
        batch.itemCount = expectedCodes.size();
        batch.successCount = Math.toIntExact(ready);
        batch.failedCount = Math.max(0, expectedCodes.size() - Math.toIntExact(ready));
        batch.sourceStatus = coreReady ? "REALTIME" : ready == 0 ? "UNAVAILABLE" : "PARTIAL";
        batch.qualityStatus = coreReady ? "READY" : ready == 0 ? "UNAVAILABLE" : "PARTIAL";
        batch.status = "FETCHED";
        batch.errorMessage = errors.isEmpty() ? null : String.join("；", errors);
        dataBatchMapper.updateById(batch);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("universeSnapshotId", batch.universeSnapshotId);
        checkpoint.put("dataBatchId", batch.id);
        checkpoint.put("expectedCount", expectedCodes.size());
        checkpoint.put("readyCount", ready);
        checkpoint.put("benchmarkReady", benchmarkReady);
        checkpoint.put("reusedPersistedEvidence", true);
        return success("FETCH_SOURCE_DATA", expectedCodes.size(), Math.toIntExact(ready),
                Math.max(0, expectedCodes.size() - Math.toIntExact(ready)),
                checkpoint, batch.id, errors);
    }

    private StepOutcome waitDataReady(PipelineContext context) {
        Long batchId = requiredCheckpointId(context, "FETCH_SOURCE_DATA", "dataBatchId");
        AiDataBatch batch = requiredBatch(batchId);
        int expected = includedItems(batch.universeSnapshotId).size();
        Map<String, AiSourceObservation> latest = latestObservations(batchId);
        int ready = (int) latest.values().stream()
                .filter(observation -> "READY".equals(observation.qualityStatus)).count();
        AiSourceObservation benchmark = latestObservation(batchId, "MARKET_BENCHMARK", null);
        boolean benchmarkReady = benchmark != null && "READY".equals(benchmark.qualityStatus)
                && "REALTIME".equals(benchmark.freshnessStatus);
        List<String> missing = includedItems(batch.universeSnapshotId).stream()
                .map(item -> item.stockCode)
                .filter(code -> latest.get(code) == null || !"READY".equals(latest.get(code).qualityStatus))
                .distinct()
                .sorted()
                .toList();

        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("universeSnapshotId", batch.universeSnapshotId);
        checkpoint.put("dataBatchId", batchId);
        checkpoint.put("expectedCount", expected);
        checkpoint.put("readyCount", ready);
        checkpoint.put("benchmarkReady", benchmarkReady);
        checkpoint.put("missingStockCodes", missing);
        if (expected == 0 || ready < expected || !benchmarkReady) {
            LocalDateTime retryAt = LocalDateTime.now().plusMinutes(10);
            batch.status = "WAITING_SOURCE";
            batch.sourceStatus = ready == 0 ? "UNAVAILABLE" : "PARTIAL";
            batch.qualityStatus = ready == 0 ? "UNAVAILABLE" : "PARTIAL";
            batch.successCount = ready;
            batch.failedCount = Math.max(0, expected - ready);
            batch.errorMessage = expected == 0 ? "研究股票池为空"
                    : !benchmarkReady ? "等待同期市场基准完整收盘数据"
                    : "等待完整收盘数据：" + missing;
            dataBatchMapper.updateById(batch);
            return new StepOutcome(
                    "WAITING_SOURCE", expected, ready, 0, json(checkpoint),
                    fingerprint("WAIT_DATA_READY", batchId, expected, ready, missing),
                    List.of(batch.errorMessage), batchId, retryAt);
        }

        LocalDateTime latestFetchedAt = latest.values().stream().map(value -> value.fetchedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(batch.asOfTime);
        batch.asOfTime = latestFetchedAt;
        batch.klineAsOf = context.tradeDate();
        batch.sourceStatus = "REALTIME";
        batch.qualityStatus = "READY";
        batch.status = "READY";
        batch.successCount = ready;
        batch.failedCount = 0;
        batch.errorMessage = null;
        if (batch.completedAt == null) {
            batch.completedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
        }
        dataBatchMapper.updateById(batch);
        return success("WAIT_DATA_READY", expected, ready, 0, checkpoint, batchId, List.of());
    }

    private StepOutcome buildSamples(PipelineContext context) {
        Long batchId = requiredCheckpointId(context, "FETCH_SOURCE_DATA", "dataBatchId");
        AiDataBatch batch = requiredBatch(batchId);
        if (!"READY".equals(batch.qualityStatus)) {
            throw new IllegalStateException("数据批次未通过完整收盘数据门");
        }
        Map<String, AiSourceObservation> observations = latestObservations(batchId);
        Map<String, AiSourceObservation> sourceIndex = latestObservationIndex(batchId);
        KlineSeriesSnapshot benchmarkSeries = readPayload(
                sourceIndex.get(observationKey("MARKET_BENCHMARK", null)), KlineSeriesSnapshot.class);
        String marketRegime = marketRegime(benchmarkSeries);
        List<AiResearchUniverseItem> items = includedItems(batch.universeSnapshotId);
        List<AiSample> samples = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (AiResearchUniverseItem item : items) {
            AiSourceObservation observation = observations.get(item.stockCode);
            if (observation == null || !"READY".equals(observation.qualityStatus)) {
                errors.add(item.stockCode + ": 缺少 READY 来源快照");
                continue;
            }
            try {
                StockDetailResponse detail = objectMapper.readValue(
                        observation.payloadJson, StockDetailResponse.class);
                IndustryMembershipService.Membership membership = readPayload(
                        sourceIndex.get(observationKey("INDUSTRY_MEMBERSHIP", item.stockCode)),
                        IndustryMembershipService.Membership.class);
                samples.add(sampleSnapshotService.createOrGetSnapshot(
                        new AiSampleSnapshotService.SnapshotCommand(
                                batchId, item.id, context.tradeDate(), "AFTER_CLOSE", batch.asOfTime,
                                marketRegime,
                                membership == null ? null : membership.industryCode(),
                                membership == null ? null : membership.industryName(),
                                item.stockName,
                                detail)));
            } catch (JsonProcessingException exception) {
                errors.add(item.stockCode + ": 来源快照无法反序列化");
            }
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("universeSnapshotId", batch.universeSnapshotId);
        checkpoint.put("dataBatchId", batchId);
        checkpoint.put("sampleIds", samples.stream().map(sample -> sample.id).toList());
        return success("BUILD_SAMPLES", items.size(), samples.size(),
                Math.max(0, items.size() - samples.size()), checkpoint, batchId, errors);
    }

    private StepOutcome matureSampleLabels(PipelineContext context) {
        return matureSampleLabels(context, "MATURE_SAMPLE_LABELS", null);
    }

    private StepOutcome matureSampleLabels(PipelineContext context, String stepKey, Integer candidateLimit) {
        AiLabelVerificationCoordinator.VerificationResult result =
                candidateLimit == null
                        ? labelCoordinator.matureSampleLabels(context.tradeDate(), context.startedAt())
                        : labelCoordinator.matureSampleLabels(
                                context.tradeDate(), context.startedAt(), candidateLimit);
        Map<String, Object> checkpoint = Map.of(
                "maturedCount", result.successCount(),
                "failedCount", result.failedCount(),
                "labelFingerprint", result.outputFingerprint());
        return success(stepKey, result.processedCount(), result.successCount(),
                result.failedCount(), checkpoint, null, result.errors());
    }

    private StepOutcome computeFactors(PipelineContext context) {
        Long batchId = requiredCheckpointId(context, "FETCH_SOURCE_DATA", "dataBatchId");
        List<AiSample> samples = samples(batchId, context.tradeDate());
        Map<String, AiSourceObservation> sourceIndex = latestObservationIndex(batchId);
        KlineSeriesSnapshot marketSeries = readPayload(
                sourceIndex.get(observationKey("MARKET_BENCHMARK", null)), KlineSeriesSnapshot.class);
        List<AiFactorEngine.FactorContext> factorContexts = new ArrayList<>();
        for (AiSample sample : samples) {
            StockDetailResponse detail = readFeatureSnapshot(sample);
            StockQuoteResponse quote = detail.quote();
            String source = quote == null ? "UNKNOWN" : quote.source();
            KlineSeriesSnapshot stockSeries = KlineSeriesSnapshot.create(
                    sample.stockCode, "day", "NONE", source, sample.asOfTime,
                    sample.createdAt == null ? sample.asOfTime : sample.createdAt,
                    detail.kline() == null ? List.of() : detail.kline());
            KlineSeriesSnapshot sectorSeries = readPayload(
                    sourceIndex.get(observationKey("INDUSTRY_BENCHMARK", sample.stockCode)),
                    KlineSeriesSnapshot.class);
            NewsSentimentFeatureService.Feature newsFeature = readPayload(
                    sourceIndex.get(observationKey("NEWS_SENTIMENT_FEATURE", sample.stockCode)),
                    NewsSentimentFeatureService.Feature.class);
            factorContexts.add(new AiFactorEngine.FactorContext(
                    sample, detail, List.of(), List.of(),
                    newsFeature == null ? null : newsFeature.sentiment(),
                    newsFeature == null ? null : newsFeature.latestPublishedAt(),
                    stockSeries, marketSeries, sectorSeries,
                    newsFeature == null ? null : newsFeature.sourceFingerprint()));
        }
        List<AiFactorValue> factors = factorEngine.computeAndStoreCrossSection(factorContexts);
        Map<String, Object> checkpoint = Map.of(
                "dataBatchId", batchId,
                "sampleCount", samples.size(),
                "factorCount", factors.size());
        return success("COMPUTE_FACTORS", samples.size(), samples.size(), 0,
                checkpoint, batchId, List.of());
    }

    private StepOutcome generatePredictions(PipelineContext context) {
        Long batchId = requiredCheckpointId(context, "FETCH_SOURCE_DATA", "dataBatchId");
        List<AiSample> samples = samples(batchId, context.tradeDate());
        List<AiFactorValue> factors = factorEngine.findStoredForSamples(
                samples.stream().map(sample -> sample.id).toList());
        Map<Long, List<AiFactorValue>> bySample = factors.stream()
                .collect(Collectors.groupingBy(value -> value.sampleId, LinkedHashMap::new, Collectors.toList()));
        List<AiPredictionEngine.PredictionInput> inputs = samples.stream()
                .map(sample -> new AiPredictionEngine.PredictionInput(
                        sample, bySample.getOrDefault(sample.id, List.of())))
                .toList();
        List<AiPrediction> predictions = new ArrayList<>();
        for (Integer horizon : PREDICTION_HORIZONS) {
            context.checkpointLease();
            predictions.addAll(predictionEngine.predictAndStore(new AiPredictionEngine.PredictionBatch(
                    inputs, context.strategyReleaseId(), context.modelVersionId(), horizon,
                    Math.max(3, Math.min(10, samples.size())),
                    context.modelVersionId() == null ? "RULE_BASELINE" : "CHAMPION",
                    context.startedAt())));
        }
        Map<String, Object> checkpoint = Map.of(
                "dataBatchId", batchId,
                "sampleCount", samples.size(),
                "predictionCount", predictions.size(),
                "horizons", PREDICTION_HORIZONS);
        return success("GENERATE_PREDICTIONS", samples.size() * PREDICTION_HORIZONS.size(),
                predictions.size(), Math.max(0, samples.size() * PREDICTION_HORIZONS.size() - predictions.size()),
                checkpoint, batchId, List.of());
    }

    private StepOutcome evaluatePredictions(PipelineContext context) {
        return evaluatePredictions(context, "EVALUATE_PREDICTIONS", null);
    }

    private StepOutcome evaluatePredictions(PipelineContext context, String stepKey, Integer candidateLimit) {
        AiLabelVerificationCoordinator.VerificationResult result =
                candidateLimit == null
                        ? labelCoordinator.evaluatePredictions(context.tradeDate(), LocalDateTime.now())
                        : labelCoordinator.evaluatePredictions(
                                context.tradeDate(), LocalDateTime.now(), candidateLimit);
        Map<String, Object> checkpoint = Map.of(
                "evaluationCount", result.successCount(),
                "failedCount", result.failedCount(),
                "evaluationFingerprint", result.outputFingerprint());
        return success(stepKey, result.processedCount(), result.successCount(),
                result.failedCount(), checkpoint, null, result.errors());
    }

    private AiSourceObservation observation(
            AiDataBatch batch,
            String stockCode,
            StockDetailResponse detail,
            LocalDate tradeDate,
            LocalDateTime fetchedAt,
            String error
    ) {
        StockQuoteResponse quote = detail == null ? null : detail.quote();
        LocalDate latestKline = detail == null || detail.kline() == null ? null
                : detail.kline().stream().map(KlinePointResponse::tradeDate).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        boolean ready = error == null && quote != null && quote.price() != null
                && quote.price().signum() > 0 && realSource(quote.source())
                && tradeDate.equals(latestKline);
        String payload = detail == null ? null : json(detail);
        String payloadChecksum = fingerprint(payload == null ? "" : payload);
        AiSourceObservation observation = new AiSourceObservation();
        observation.dataBatchId = batch.id;
        observation.stockCode = stockCode;
        observation.sourceType = "STOCK_DAILY_SNAPSHOT";
        observation.providerCode = quote == null ? "UNAVAILABLE" : normalize(quote.source(), "UNKNOWN");
        observation.endpointType = "STOCK_DETAIL";
        observation.eventTime = latestKline == null ? null : latestKline.atTime(LocalTime.of(15, 0));
        observation.firstSeenAt = fetchedAt;
        observation.fetchedAt = fetchedAt;
        observation.asOfTime = fetchedAt;
        observation.availableAt = fetchedAt;
        observation.observedAt = fetchedAt;
        observation.sourceRevision = latestKline == null ? "UNKNOWN" : latestKline.toString();
        observation.payloadJson = payload;
        observation.payloadChecksum = payloadChecksum;
        observation.sourceFingerprint = fingerprint(batch.id, stockCode, payloadChecksum, error);
        observation.freshnessStatus = ready ? "REALTIME" : "UNAVAILABLE";
        observation.qualityStatus = ready ? "READY" : "UNAVAILABLE";
        observation.missingReason = ready ? null : error != null ? error
                : latestKline == null ? "K线为空"
                : !tradeDate.equals(latestKline) ? "最近K线日期为 " + latestKline + "，等待 " + tradeDate
                : "行情来源或价格不可用";
        observation.createdAt = fetchedAt;
        return observation;
    }

    private AiSourceObservation sourceObservation(
            AiDataBatch batch,
            String stockCode,
            String sourceType,
            String endpointType,
            String providerCode,
            LocalDateTime eventTime,
            LocalDateTime publishedAt,
            LocalDateTime fetchedAt,
            Object payload,
            String qualityStatus,
            String missingReason,
            String responseFingerprint
    ) {
        String payloadJson = payload == null ? null : json(payload);
        String payloadChecksum = responseFingerprint == null || responseFingerprint.isBlank()
                ? fingerprint(payloadJson == null ? "" : payloadJson)
                : responseFingerprint;
        String quality = normalize(qualityStatus, "UNAVAILABLE");
        String freshness = "READY".equals(quality) ? "REALTIME"
                : "STALE".equals(quality) ? "STALE" : "UNAVAILABLE";
        AiSourceObservation observation = new AiSourceObservation();
        observation.dataBatchId = batch.id;
        observation.stockCode = stockCode;
        observation.sourceType = sourceType;
        observation.providerCode = normalize(providerCode, "UNAVAILABLE");
        observation.endpointType = endpointType;
        observation.eventTime = eventTime;
        observation.publishedAt = publishedAt;
        observation.firstSeenAt = fetchedAt;
        observation.fetchedAt = fetchedAt;
        observation.asOfTime = fetchedAt;
        observation.availableAt = publishedAt != null ? publishedAt : eventTime != null ? eventTime : fetchedAt;
        observation.observedAt = fetchedAt;
        observation.sourceRevision = eventTime == null ? fetchedAt.toString() : eventTime.toString();
        observation.payloadJson = payloadJson;
        observation.payloadChecksum = payloadChecksum;
        observation.sourceFingerprint = fingerprint(
                batch.id, sourceType, stockCode, observation.providerCode, payloadChecksum, quality);
        observation.freshnessStatus = freshness;
        observation.qualityStatus = quality;
        observation.missingReason = "READY".equals(quality) ? null : missingReason;
        observation.createdAt = fetchedAt;
        return observation;
    }

    private void storeObservation(AiSourceObservation observation) {
        try {
            observationMapper.insert(observation);
        } catch (DuplicateKeyException ignored) {
            // The same source fingerprint is immutable and already persisted by this or another worker.
        }
    }

    private AiResearchUniverseSnapshot requiredSnapshot(Long snapshotId) {
        AiResearchUniverseSnapshot snapshot = universeSnapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw new IllegalStateException("研究股票池快照不存在：" + snapshotId);
        }
        return snapshot;
    }

    private AiDataBatch requiredBatch(Long batchId) {
        AiDataBatch batch = dataBatchMapper.selectById(batchId);
        if (batch == null) {
            throw new IllegalStateException("研究数据批次不存在：" + batchId);
        }
        return batch;
    }

    private List<AiResearchUniverseItem> includedItems(Long snapshotId) {
        return List.copyOf(universeItemMapper.selectList(new QueryWrapper<AiResearchUniverseItem>()
                .eq("universe_snapshot_id", snapshotId)
                .eq("included", 1)
                .orderByAsc("stock_code")));
    }

    private Map<String, AiSourceObservation> latestObservations(Long batchId) {
        List<AiSourceObservation> observations = latestObservationIndex(batchId).values().stream()
                .filter(observation -> "STOCK_DAILY_SNAPSHOT".equals(observation.sourceType))
                .toList();
        Map<String, AiSourceObservation> latest = new LinkedHashMap<>();
        for (AiSourceObservation observation : observations) {
            latest.putIfAbsent(observation.stockCode, observation);
        }
        return latest;
    }

    private AiSourceObservation latestObservation(Long batchId, String sourceType, String stockCode) {
        return latestObservationIndex(batchId).get(observationKey(sourceType, stockCode));
    }

    private Map<String, AiSourceObservation> latestObservationIndex(Long batchId) {
        List<AiSourceObservation> observations = observations(batchId);
        Map<String, AiSourceObservation> latest = new LinkedHashMap<>();
        for (AiSourceObservation observation : observations) {
            latest.putIfAbsent(observationKey(observation.sourceType, observation.stockCode), observation);
        }
        return latest;
    }

    private List<AiSourceObservation> observations(Long batchId) {
        List<AiSourceObservation> loaded = observationMapper.selectList(
                new QueryWrapper<AiSourceObservation>()
                        .eq("data_batch_id", batchId)
                        .orderByDesc("fetched_at")
                        .orderByDesc("id"));
        return loaded == null ? List.of() : List.copyOf(loaded);
    }

    private static String observationKey(String sourceType, String stockCode) {
        return sourceType + "|" + (stockCode == null ? "<GLOBAL>" : stockCode);
    }

    private List<AiSample> samples(Long batchId, LocalDate tradeDate) {
        return List.copyOf(sampleMapper.selectList(new QueryWrapper<AiSample>()
                .eq("data_batch_id", batchId)
                .eq("trade_date", tradeDate)
                .orderByAsc("stock_code")));
    }

    private StockDetailResponse readFeatureSnapshot(AiSample sample) {
        if (sample.featureSnapshot == null || sample.featureSnapshot.isBlank()) {
            throw new IllegalStateException("样本缺少不可变特征快照：" + sample.id);
        }
        try {
            JsonNode root = objectMapper.readTree(sample.featureSnapshot);
            return new StockDetailResponse(
                    objectMapper.treeToValue(root.path("quote"), StockQuoteResponse.class),
                    root.path("finance").isMissingNode() || root.path("finance").isNull()
                            ? null : objectMapper.treeToValue(root.path("finance"),
                            com.maogou.stock.dto.market.FinanceSnapshotResponse.class),
                    treeList(root.path("intraday"), com.maogou.stock.dto.market.IntradayPointResponse.class),
                    treeList(root.path("kline"), KlinePointResponse.class),
                    null,
                    null);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法恢复不可变样本特征：" + sample.id, exception);
        }
    }

    private <T> T readPayload(AiSourceObservation observation, Class<T> type) {
        if (observation == null || !"READY".equals(observation.qualityStatus)
                || observation.payloadJson == null || observation.payloadJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(observation.payloadJson, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法恢复来源证据：" + observation.sourceType, exception);
        }
    }

    private static Optional<LocalDate> latestTradeDate(KlineSeriesSnapshot series) {
        if (series == null || series.points() == null) {
            return Optional.empty();
        }
        return series.points().stream().map(KlinePointResponse::tradeDate)
                .filter(Objects::nonNull).max(Comparator.naturalOrder());
    }

    private static String batchIdempotencyKey(PipelineContext context) {
        return "BATCH:" + fingerprint(
                context.pipelineRunId(), context.idempotencyKey(), context.tradeDate(), context.attemptNo());
    }

    private static String marketRegime(KlineSeriesSnapshot series) {
        if (series == null || series.points() == null || series.points().size() < 4) {
            return "UNKNOWN";
        }
        List<KlinePointResponse> points = series.points();
        BigDecimal latest = points.get(points.size() - 1).close();
        BigDecimal base = points.get(points.size() - 4).close();
        if (latest == null || base == null || base.signum() <= 0) {
            return "UNKNOWN";
        }
        BigDecimal change = latest.divide(base, 8, java.math.RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE);
        if (change.compareTo(new BigDecimal("0.02")) >= 0) {
            return "STRONG";
        }
        if (change.compareTo(new BigDecimal("-0.02")) <= 0) {
            return "WEAK";
        }
        return "SIDEWAYS";
    }

    private <T> List<T> treeList(JsonNode node, Class<T> type) throws JsonProcessingException {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<T> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(objectMapper.treeToValue(item, type));
        }
        return List.copyOf(values);
    }

    private Long requiredCheckpointId(PipelineContext context, String stepKey, String field) {
        String checkpoint = context.checkpoint(stepKey);
        if (checkpoint == null || checkpoint.isBlank()) {
            throw new IllegalStateException("恢复步骤缺少数据库 checkpoint：" + stepKey);
        }
        try {
            JsonNode value = objectMapper.readTree(checkpoint).path(field);
            if (!value.canConvertToLong()) {
                throw new IllegalStateException("checkpoint 缺少字段 " + field + "：" + stepKey);
            }
            return value.longValue();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("checkpoint 无法解析：" + stepKey, exception);
        }
    }

    private StepOutcome success(
            String stepKey,
            int processed,
            int succeeded,
            int failed,
            Object checkpoint,
            Long dataBatchId,
            List<String> errors
    ) {
        String checkpointJson = checkpoint instanceof String text ? text : json(checkpoint);
        String status = failed > 0 || errors != null && !errors.isEmpty()
                ? "SUCCESS_WITH_WARNINGS" : "SUCCESS";
        return new StepOutcome(status, processed, succeeded, failed, checkpointJson,
                fingerprint(stepKey, checkpointJson, processed, succeeded, failed),
                errors, dataBatchId, null);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化研究流水线证据", exception);
        }
    }

    private static boolean realSource(String source) {
        String normalized = normalize(source, "");
        return !normalized.isBlank() && !normalized.contains("MOCK")
                && !normalized.contains("FALLBACK") && !normalized.contains("FIXTURE");
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String fingerprint(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '|');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
