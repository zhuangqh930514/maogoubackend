package com.maogou.stock.service.impl.v2;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.IntradayPointResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.v2.AiStrategyReleaseMapper;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.AiDailyInsightService;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.v2.AiDailyPipelineExecutor;
import com.maogou.stock.service.v2.AiFactorEngineV2;
import com.maogou.stock.service.v2.AiLabelVerificationCoordinatorV2;
import com.maogou.stock.service.v2.AiPredictionEngineV2;
import com.maogou.stock.service.v2.AiSampleSnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
public class MaogouDailyPipelineExecutor implements AiDailyPipelineExecutor {

    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final WatchStockMapper watchStockMapper;
    private final MarketDataService marketDataService;
    private final AiSampleSnapshotService sampleSnapshotService;
    private final AiFactorEngineV2 factorEngine;
    private final AiPredictionEngineV2 predictionEngine;
    private final AiStrategyReleaseMapper strategyReleaseMapper;
    private final AiAnalysisService aiAnalysisService;
    private final AiDailyInsightService aiDailyInsightService;
    private final AiLabelVerificationCoordinatorV2 labelVerificationCoordinator;
    private final AiResearchDailyReportService researchDailyReportService;
    private final ConcurrentMap<Long, DataFlowState> states = new ConcurrentHashMap<>();

    @Autowired
    public MaogouDailyPipelineExecutor(
            WatchStockMapper watchStockMapper,
            MarketDataService marketDataService,
            AiSampleSnapshotService sampleSnapshotService,
            AiFactorEngineV2 factorEngine,
            AiPredictionEngineV2 predictionEngine,
            AiStrategyReleaseMapper strategyReleaseMapper,
            AiAnalysisService aiAnalysisService,
            AiDailyInsightService aiDailyInsightService,
            AiLabelVerificationCoordinatorV2 labelVerificationCoordinator,
            AiResearchDailyReportService researchDailyReportService
    ) {
        this.watchStockMapper = watchStockMapper;
        this.marketDataService = marketDataService;
        this.sampleSnapshotService = sampleSnapshotService;
        this.factorEngine = factorEngine;
        this.predictionEngine = predictionEngine;
        this.strategyReleaseMapper = strategyReleaseMapper;
        this.aiAnalysisService = aiAnalysisService;
        this.aiDailyInsightService = aiDailyInsightService;
        this.labelVerificationCoordinator = labelVerificationCoordinator;
        this.researchDailyReportService = researchDailyReportService;
    }

    MaogouDailyPipelineExecutor(
            WatchStockMapper watchStockMapper,
            MarketDataService marketDataService,
            AiSampleSnapshotService sampleSnapshotService,
            AiFactorEngineV2 factorEngine,
            AiPredictionEngineV2 predictionEngine,
            AiAnalysisService aiAnalysisService,
            AiDailyInsightService aiDailyInsightService,
            AiLabelVerificationCoordinatorV2 labelVerificationCoordinator,
            AiResearchDailyReportService researchDailyReportService
    ) {
        this(watchStockMapper, marketDataService, sampleSnapshotService, factorEngine,
                predictionEngine, null, aiAnalysisService, aiDailyInsightService,
                labelVerificationCoordinator, researchDailyReportService);
    }

    @Override
    public StepOutcome execute(String stepKey, PipelineContext context) {
        return switch (stepKey) {
            case "FETCH_DATA", "CHECK_DATA_QUALITY", "BUILD_SAMPLES", "COMPUTE_FACTORS", "GENERATE_PREDICTIONS" ->
                    executeDataFlow(context, stepKey);
            case "VERIFY_LABELS" -> executeVerifyLabels(context);
            case "GENERATE_REPORTS" -> executeReports(context);
            case "BUILD_DAILY_INSIGHT" -> buildDailyInsight(context, "SUCCESS", null);
            case "BUILD_RESEARCH_DAILY_REPORT" -> buildResearchDailyReport(context, "SUCCESS", null);
            default -> throw new IllegalArgumentException("未知 V2 日流水线步骤：" + stepKey);
        };
    }

    @Override
    public StepOutcome buildResearchDailyReport(
            PipelineContext context,
            String pipelineStatus,
            String pipelineMessage
    ) {
        context.checkpointLease();
        AiResearchDailyReportService.ReportView report = researchDailyReportService.generate(
                new AiResearchDailyReportService.GenerationRequest(
                        context.userId(),
                        context.tradeDate(),
                        context.pipelineRunId(),
                        context.strategyReleaseId(),
                        context.modelVersionId(),
                        "REPORT:" + context.idempotencyKey() + ":" + pipelineStatus,
                        pipelineStatus,
                        null,
                        pipelineMessage,
                        LocalDateTime.now()));
        context.checkpointLease();
        if (report == null || report.id() == null) {
            throw new IllegalStateException("投研日报生成后未返回有效报告 ID");
        }
        states.remove(context.pipelineRunId());
        String checkpoint = "{\"reportId\":" + report.id() + ",\"reportVersion\":"
                + report.reportVersion() + "}";
        return new StepOutcome(1, 1, 0, checkpoint,
                fingerprint("BUILD_RESEARCH_DAILY_REPORT", context.idempotencyKey(),
                        String.valueOf(report.id()), String.valueOf(report.reportVersion())),
                List.of());
    }

    private StepOutcome executeVerifyLabels(PipelineContext context) {
        context.checkpointLease();
        AiLabelVerificationCoordinatorV2.VerificationResult result = labelVerificationCoordinator.verifyMatured(
                context.userId(), context.tradeDate(), context.startedAt());
        context.checkpointLease();
        return new StepOutcome(
                result.processedCount(),
                result.successCount(),
                result.failedCount(),
                "{\"verified\":" + result.successCount() + ",\"failed\":" + result.failedCount() + "}",
                result.outputFingerprint(),
                result.errors());
    }

    @Override
    public StepOutcome buildDailyInsight(
            PipelineContext context,
            String pipelineStatus,
            String pipelineMessage
    ) {
        context.checkpointLease();
        AiDailyInsightPayloads.DailyInsightResponse response = aiDailyInsightService.rebuildForPipeline(
                context.userId(),
                context.tradeDate(),
                context.pipelineRunId(),
                pipelineStatus,
                pipelineMessage == null || pipelineMessage.isBlank()
                        ? "自动收盘流水线已完成每日投研聚合"
                        : pipelineMessage);
        context.checkpointLease();
        int itemCount = response == null || response.summary() == null || response.summary().itemCount() == null
                ? 0
                : response.summary().itemCount();
        String checkpoint = "{\"snapshotReady\":" + (response != null && response.snapshotReady())
                + ",\"itemCount\":" + itemCount + "}";
        return new StepOutcome(
                itemCount,
                itemCount,
                0,
                checkpoint,
                fingerprint("BUILD_DAILY_INSIGHT", context.idempotencyKey(), String.valueOf(itemCount), checkpoint),
                List.of()
        );
    }

    private StepOutcome executeReports(PipelineContext context) {
        List<WatchStock> watchlist = loadWatchlist(context.userId());
        if (watchlist.isEmpty()) {
            return simple("GENERATE_REPORTS", 0, 0, 0, "自选股为空");
        }
        int success = 0;
        List<String> errors = new java.util.ArrayList<>();
        List<Long> reportIds = new java.util.ArrayList<>();
        for (WatchStock stock : watchlist) {
            context.checkpointLease();
            try {
                AiAnalysisReportResponse report = aiAnalysisService.analyzeStockForTradeDate(
                        stock.stockCode, false, null, null, context.tradeDate());
                if (report != null && report.id() != null) {
                    reportIds.add(report.id());
                }
                if (report == null || !"SUCCESS".equalsIgnoreCase(report.status())) {
                    String message = report == null
                            ? "分析服务未返回报告"
                            : report.errorMessage() == null || report.errorMessage().isBlank()
                            ? "分析报告状态为 " + report.status()
                            : report.errorMessage();
                    errors.add(stock.stockCode + ": " + message);
                    continue;
                }
                success++;
            } catch (RuntimeException exception) {
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                errors.add(stock.stockCode + ": " + message);
            }
            context.checkpointLease();
        }
        int failed = watchlist.size() - success;
        String checkpoint = "{\"reportIds\":" + reportIds + ",\"success\":" + success + ",\"failed\":" + failed + "}";
        return new StepOutcome(
                watchlist.size(),
                success,
                failed,
                checkpoint,
                fingerprint("GENERATE_REPORTS", context.idempotencyKey(), String.valueOf(success), String.valueOf(failed), reportIds.toString()),
                errors);
    }

    private StepOutcome executeDataFlow(PipelineContext context, String stepKey) {
        DataFlowState state = states.computeIfAbsent(context.pipelineRunId(), ignored ->
                recoverableStep(stepKey) ? rehydrateOrInitialize(context, stepKey) : initializeState(context));
        List<WatchStock> watchlist = state.watchlist;
        if (watchlist.isEmpty()) {
            if ("CHECK_DATA_QUALITY".equals(stepKey) && !state.batchCompleted) {
                sampleSnapshotService.completeBatch(state.batch.id, new AiSampleSnapshotService.BatchCompletion(
                        "UNAVAILABLE",
                        BigDecimal.ZERO,
                        "UNAVAILABLE",
                        0,
                        0,
                        0,
                        null,
                        LocalDateTime.now()));
                state.batchCompleted = true;
            }
            return simple(stepKey, 0, 0, 0, "自选股为空");
        }
        if ("FETCH_DATA".equals(stepKey)) {
            return outcome(stepKey, watchlist.size(), state.details.size(),
                    watchlist.size() - state.details.size(),
                    "{\"batchId\":" + state.batch.id + ",\"detailCount\":" + state.details.size() + "}",
                    state.errors);
        }
        if ("CHECK_DATA_QUALITY".equals(stepKey) || "BUILD_SAMPLES".equals(stepKey)) {
            long readyCount = state.samples.stream().filter(sample -> "READY".equals(sample.qualityStatus)).count();
            long usableCount = state.samples.stream()
                    .filter(sample -> "READY".equals(sample.qualityStatus) || "PARTIAL".equals(sample.qualityStatus))
                    .count();
            if ("CHECK_DATA_QUALITY".equals(stepKey) && !state.batchCompleted) {
                BigDecimal qualityScore = state.samples.stream()
                        .map(sample -> sample.dataQualityScore == null ? BigDecimal.ZERO : sample.dataQualityScore)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(Math.max(1, state.samples.size())), 2, java.math.RoundingMode.HALF_UP);
                int failedCount = watchlist.size() - (int) usableCount;
                sampleSnapshotService.completeBatch(state.batch.id, new AiSampleSnapshotService.BatchCompletion(
                        state.errors.isEmpty() ? "REALTIME" : usableCount == 0 ? "UNAVAILABLE" : "PARTIAL",
                        qualityScore,
                        usableCount == state.samples.size() && state.errors.isEmpty() ? "READY"
                                : usableCount == 0 ? "UNAVAILABLE" : "PARTIAL",
                        watchlist.size(),
                        (int) usableCount,
                        failedCount,
                        state.errors.isEmpty() ? null : String.join("；", state.errors),
                        LocalDateTime.now()));
                state.batchCompleted = true;
            }
            int successCount = "CHECK_DATA_QUALITY".equals(stepKey) ? (int) usableCount : state.samples.size();
            return outcome(stepKey, watchlist.size(), successCount,
                    watchlist.size() - successCount,
                    "{\"ready\":" + readyCount + ",\"batchId\":" + state.batch.id + "}",
                    state.errors);
        }

        context.checkpointLease();
        List<AiFactorValueV2> factors = factors(state);
        context.checkpointLease();
        if ("COMPUTE_FACTORS".equals(stepKey)) {
            return new StepOutcome(
                    state.samples.size(),
                    state.samples.size(),
                    0,
                    "{\"sampleCount\":" + state.samples.size() + ",\"factorCount\":" + factors.size() + "}",
                    fingerprint(stepKey, context.idempotencyKey(), String.valueOf(state.samples.size()), String.valueOf(factors.size())),
                    List.of());
        }

        List<AiPredictionV2> predictions = predictions(state, context);
        int expectedPredictionCount = state.expectedPredictionCount;
        return new StepOutcome(
                expectedPredictionCount,
                predictions.size(),
                Math.max(0, expectedPredictionCount - predictions.size()),
                "{\"sampleCount\":" + state.samples.size() + ",\"factorCount\":" + factors.size()
                        + ",\"predictionCount\":" + predictions.size() + "}",
                fingerprint(stepKey, context.idempotencyKey(), String.valueOf(state.samples.size()),
                        String.valueOf(factors.size()), String.valueOf(predictions.size())),
                state.predictionErrors == null ? List.of() : state.predictionErrors);
    }

    private DataFlowState initializeState(PipelineContext context) {
        List<WatchStock> watchlist = loadWatchlist(context.userId());
        LocalDateTime marketDataAsOf = AiDailyPipelinePreparationServiceImpl.marketDataAsOf(context.tradeDate());
        AiDataBatch batch = sampleSnapshotService.startOrGetBatch(
                context.userId(), context.tradeDate(), "AFTER_CLOSE", marketDataAsOf,
                context.idempotencyKey() + ":BATCH");
        if (context.dataBatchId() != null && !Objects.equals(context.dataBatchId(), batch.id)) {
            throw new IllegalStateException("流水线数据批次与样本批次不一致");
        }
        List<DetailEnvelope> details = new java.util.ArrayList<>();
        List<AiSampleV2> samples = new java.util.ArrayList<>();
        List<String> errors = new java.util.ArrayList<>();
        for (WatchStock stock : watchlist) {
            context.checkpointLease();
            try {
                StockDetailResponse detail = withStockName(
                        marketDataService.stockDetailAt(stock.stockCode, marketDataAsOf), stock.stockName);
                if (detail == null || detail.quote() == null) {
                    errors.add(stock.stockCode + ": 行情详情为空");
                    continue;
                }
                KlineSeriesSnapshot stockSeries = marketDataService.klineAt(
                        stock.stockCode, "day", 60, marketDataAsOf);
                details.add(new DetailEnvelope(stock, detail, stockSeries));
                samples.add(sampleSnapshotService.createOrGetSnapshot(new AiSampleSnapshotService.SnapshotCommand(
                        context.userId(), batch.id, context.tradeDate(), "WATCHLIST", "DAILY_CLOSE", "AFTER_CLOSE",
                        marketDataAsOf, "UNKNOWN", stock.market, stock.groupName, detail)));
            } catch (RuntimeException exception) {
                errors.add(stock.stockCode + ": " + rootMessage(exception));
            }
            context.checkpointLease();
        }
        return new DataFlowState(batch, List.copyOf(watchlist), List.copyOf(details), List.copyOf(samples), errors);
    }

    private DataFlowState rehydrateOrInitialize(PipelineContext context, String stepKey) {
        List<AiSampleV2> persisted = sampleSnapshotService.findBatchSnapshots(
                context.userId(), context.dataBatchId(), context.tradeDate());
        if (persisted == null || persisted.isEmpty()) {
            return initializeState(context);
        }
        AiDataBatch batch = new AiDataBatch();
        batch.id = context.dataBatchId();
        batch.userId = context.userId();
        batch.tradeDate = context.tradeDate();
        batch.samplePhase = "AFTER_CLOSE";
        batch.asOfTime = AiDailyPipelinePreparationServiceImpl.marketDataAsOf(context.tradeDate());
        List<WatchStock> watchlist = loadWatchlist(context.userId());
        Map<String, WatchStock> watchlistByCode = watchlist.stream()
                .collect(Collectors.toMap(item -> item.stockCode, item -> item, (left, right) -> left));
        List<DetailEnvelope> details = persisted.stream()
                .map(sample -> detailFromSnapshot(sample, watchlistByCode.get(sample.stockCode)))
                .toList();
        List<WatchStock> recoveredWatchlist = details.stream().map(DetailEnvelope::stock).toList();
        DataFlowState state = new DataFlowState(
                batch, recoveredWatchlist, details, List.copyOf(persisted), List.of());
        state.batchCompleted = true;
        if ("GENERATE_PREDICTIONS".equals(stepKey)) {
            state.factors = List.copyOf(factorEngine.findStoredForSamples(
                    persisted.stream().map(item -> item.id).filter(Objects::nonNull).toList()));
            if (state.factors.isEmpty()) {
                throw new IllegalStateException("恢复预测步骤时未找到已固化因子，拒绝使用当前行情重算");
            }
        }
        return state;
    }

    private static boolean recoverableStep(String stepKey) {
        return "COMPUTE_FACTORS".equals(stepKey) || "GENERATE_PREDICTIONS".equals(stepKey);
    }

    private DetailEnvelope detailFromSnapshot(AiSampleV2 sample, WatchStock watchStock) {
        if (sample.featureSnapshot == null || sample.featureSnapshot.isBlank()) {
            throw new IllegalStateException("持久化样本缺少特征快照：" + sample.id);
        }
        try {
            PersistedFeatureSnapshot snapshot = SNAPSHOT_MAPPER.readValue(
                    sample.featureSnapshot, PersistedFeatureSnapshot.class);
            StockDetailResponse detail = new StockDetailResponse(
                    snapshot.quote(), snapshot.finance(), safe(snapshot.intraday()), safe(snapshot.kline()), null, null);
            WatchStock stock = watchStock == null ? watchStock(sample, snapshot.quote()) : watchStock;
            KlineSeriesSnapshot series = KlineSeriesSnapshot.create(
                    sample.stockCode,
                    "day",
                    "NONE",
                    "PERSISTED_SAMPLE",
                    sample.asOfTime,
                    sample.createdAt == null ? sample.asOfTime : sample.createdAt,
                    safe(snapshot.kline()));
            return new DetailEnvelope(stock, detail, series);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法恢复不可变样本特征：" + sample.id, exception);
        }
    }

    private static WatchStock watchStock(AiSampleV2 sample, StockQuoteResponse quote) {
        WatchStock stock = new WatchStock();
        stock.userId = sample.userId;
        stock.stockCode = sample.stockCode;
        stock.stockName = sample.stockName == null && quote != null ? quote.name() : sample.stockName;
        stock.groupName = sample.universeCode;
        return stock;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private static StockDetailResponse withStockName(StockDetailResponse detail, String stockName) {
        if (detail == null || detail.quote() == null || stockName == null || stockName.isBlank()
                || Objects.equals(stockName, detail.quote().name())) {
            return detail;
        }
        StockQuoteResponse quote = detail.quote();
        return new StockDetailResponse(
                new StockQuoteResponse(
                        quote.code(), stockName, quote.price(), quote.change(), quote.percent(), quote.volumeRatio(),
                        quote.market(), quote.source(), quote.fetchedAt()),
                detail.finance(), detail.intraday(), detail.kline(), detail.aiAdvice(), detail.aiScore());
    }

    private List<AiFactorValueV2> factors(DataFlowState state) {
        if (state.factors == null) {
            List<AiFactorEngineV2.FactorContext> contexts = state.samples.stream()
                    .map(sample -> {
                        DetailEnvelope detail = state.details.stream()
                                .filter(item -> Objects.equals(item.stock().stockCode, sample.stockCode))
                                .findFirst().orElse(null);
                        return new AiFactorEngineV2.FactorContext(
                                sample,
                                detail == null ? null : detail.detail(),
                                List.of(), List.of(), null, null,
                                detail == null ? null : detail.stockSeries(), null, null);
                    })
                    .toList();
            state.factors = List.copyOf(factorEngine.computeAndStoreCrossSection(contexts));
        }
        return state.factors;
    }

    private List<AiPredictionV2> predictions(DataFlowState state, PipelineContext context) {
        if (state.predictions == null) {
            Map<Long, List<AiFactorValueV2>> factorsBySample = factors(state).stream()
                    .collect(Collectors.groupingBy(item -> item.sampleId, LinkedHashMap::new, Collectors.toList()));
            List<AiPredictionEngineV2.PredictionInput> inputs = state.samples.stream()
                    .map(sample -> new AiPredictionEngineV2.PredictionInput(
                            sample, factorsBySample.getOrDefault(sample.id, List.of())))
                    .toList();
            List<AiPredictionV2> predictions = new java.util.ArrayList<>();
            List<String> errors = new java.util.ArrayList<>();
            for (int horizonDays : List.of(1, 3, 5)) {
                context.checkpointLease();
                predictions.addAll(predictionEngine.predictAndStore(new AiPredictionEngineV2.PredictionBatch(
                        inputs,
                        context.strategyReleaseId(), context.modelVersionId(), horizonDays,
                        Math.max(3, Math.min(10, state.samples.size())),
                        context.modelVersionId() == null ? "RULE_BASELINE" : "CHAMPION",
                        LocalDateTime.now())));
                context.checkpointLease();
            }
            List<AiStrategyRelease> challengers = strategyReleaseMapper == null
                    ? List.of() : strategyReleaseMapper.selectShadowChallengers(context.userId());
            challengers = challengers == null ? List.of() : challengers;
            state.expectedPredictionCount = state.samples.size() * 3 * (1 + challengers.size());
            for (AiStrategyRelease challenger : challengers) {
                if (challenger == null || challenger.id == null || challenger.modelVersionId == null) {
                    errors.add("SHADOW Challenger 缺少策略或模型版本，已跳过");
                    continue;
                }
                for (int horizonDays : List.of(1, 3, 5)) {
                    context.checkpointLease();
                    try {
                        predictions.addAll(predictionEngine.predictAndStore(
                                new AiPredictionEngineV2.PredictionBatch(
                                        inputs,
                                        challenger.id,
                                        challenger.modelVersionId,
                                        horizonDays,
                                        Math.max(3, Math.min(10, state.samples.size())),
                                        "CHALLENGER_SHADOW",
                                        LocalDateTime.now())));
                    } catch (RuntimeException exception) {
                        errors.add("Challenger " + challenger.id + " T+" + horizonDays + "："
                                + rootMessage(exception));
                    }
                    context.checkpointLease();
                }
            }
            state.predictions = List.copyOf(predictions);
            state.predictionErrors = List.copyOf(errors);
        }
        return state.predictions;
    }

    private StepOutcome outcome(
            String stepKey,
            int processed,
            int success,
            int failed,
            String checkpoint,
            List<String> errors
    ) {
        return new StepOutcome(processed, success, failed, checkpoint,
                fingerprint(stepKey, checkpoint, String.valueOf(processed), String.valueOf(success)), errors);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private List<WatchStock> loadWatchlist(Long userId) {
        return watchStockMapper.selectList(new QueryWrapper<WatchStock>()
                .eq("user_id", userId)
                .eq("deleted", 0)
                .orderByDesc("priority")
                .orderByAsc("stock_code"));
    }

    private StepOutcome simple(String stepKey, int processed, int success, int failed, String message) {
        return new StepOutcome(
                processed,
                success,
                failed,
                "{\"step\":\"" + stepKey + "\",\"message\":\"" + message + "\"}",
                fingerprint(stepKey, String.valueOf(processed), String.valueOf(success), message),
                failed > 0 ? List.of(message) : List.of());
    }

    private static String fingerprint(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "null" : value).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record DetailEnvelope(
            WatchStock stock,
            StockDetailResponse detail,
            KlineSeriesSnapshot stockSeries
    ) {
    }

    private record PersistedFeatureSnapshot(
            StockQuoteResponse quote,
            FinanceSnapshotResponse finance,
            List<IntradayPointResponse> intraday,
            List<KlinePointResponse> kline
    ) {
    }

    private static final class DataFlowState {
        private final AiDataBatch batch;
        private final List<WatchStock> watchlist;
        private final List<DetailEnvelope> details;
        private final List<AiSampleV2> samples;
        private final List<String> errors;
        private List<AiFactorValueV2> factors;
        private List<AiPredictionV2> predictions;
        private List<String> predictionErrors;
        private int expectedPredictionCount;
        private boolean batchCompleted;

        private DataFlowState(
                AiDataBatch batch,
                List<WatchStock> watchlist,
                List<DetailEnvelope> details,
                List<AiSampleV2> samples,
                List<String> errors
        ) {
            this.batch = batch;
            this.watchlist = watchlist;
            this.details = details;
            this.samples = samples;
            this.errors = List.copyOf(errors);
        }
    }
}
