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
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
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
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiSampleSnapshotService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
            case "COMPUTE_FACTORS" -> computeFactors(context);
            case "GENERATE_PREDICTIONS" -> generatePredictions(context);
            case "EVALUATE_PREDICTIONS" -> evaluatePredictions(context);
            default -> throw new IllegalArgumentException("未知全局日研究步骤：" + stepKey);
        };
        context.checkpointLease();
        return outcome;
    }

    private StepOutcome snapshotUniverse(PipelineContext context) {
        AiResearchUniverseService.SnapshotResult result = universeService.createSystemCoreSnapshot(
                new AiResearchUniverseService.SnapshotRequest(
                        context.tradeDate(), context.startedAt(), AiResearchContract.CALENDAR_VERSION, List.of()));
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
        LocalDateTime fetchStartedAt = LocalDateTime.now();
        AiDataBatch batch = sampleSnapshotService.startOrGetBatch(
                snapshotId, context.tradeDate(), "AFTER_CLOSE", fetchStartedAt,
                context.idempotencyKey() + ":BATCH");

        int success = 0;
        List<String> errors = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();
        for (AiResearchUniverseItem item : items) {
            if (!seenCodes.add(item.stockCode)) {
                continue;
            }
            context.checkpointLease();
            try {
                StockDetailResponse detail = marketDataService.stockDetailAt(item.stockCode, fetchStartedAt);
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
        batch.sourceStatus = success == seenCodes.size() ? "REALTIME" : success == 0 ? "UNAVAILABLE" : "PARTIAL";
        batch.qualityStatus = success == seenCodes.size() ? "READY" : success == 0 ? "UNAVAILABLE" : "PARTIAL";
        batch.status = "FETCHED";
        batch.errorMessage = errors.isEmpty() ? null : String.join("；", errors);
        dataBatchMapper.updateById(batch);

        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("universeSnapshotId", snapshotId);
        checkpoint.put("dataBatchId", batch.id);
        checkpoint.put("expectedCount", seenCodes.size());
        checkpoint.put("readyCount", success);
        checkpoint.put("fetchedAt", fetchStartedAt);
        return success("FETCH_SOURCE_DATA", seenCodes.size(), success,
                Math.max(0, seenCodes.size() - success), checkpoint, batch.id, errors);
    }

    private StepOutcome waitDataReady(PipelineContext context) {
        Long batchId = requiredCheckpointId(context, "FETCH_SOURCE_DATA", "dataBatchId");
        AiDataBatch batch = requiredBatch(batchId);
        int expected = includedItems(batch.universeSnapshotId).size();
        Map<String, AiSourceObservation> latest = latestObservations(batchId);
        int ready = (int) latest.values().stream()
                .filter(observation -> "READY".equals(observation.qualityStatus)).count();
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
        checkpoint.put("missingStockCodes", missing);
        if (expected == 0 || ready < expected) {
            LocalDateTime retryAt = LocalDateTime.now().plusMinutes(10);
            batch.status = "WAITING_SOURCE";
            batch.sourceStatus = ready == 0 ? "UNAVAILABLE" : "PARTIAL";
            batch.qualityStatus = ready == 0 ? "UNAVAILABLE" : "PARTIAL";
            batch.successCount = ready;
            batch.failedCount = Math.max(0, expected - ready);
            batch.errorMessage = expected == 0 ? "研究股票池为空" : "等待完整收盘数据：" + missing;
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
                samples.add(sampleSnapshotService.createOrGetSnapshot(
                        new AiSampleSnapshotService.SnapshotCommand(
                                batchId, item.id, context.tradeDate(), "AFTER_CLOSE", batch.asOfTime,
                                "UNCLASSIFIED", item.sectorCode, item.sectorName, detail)));
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
        AiLabelVerificationCoordinator.VerificationResult result =
                labelCoordinator.matureSampleLabels(context.tradeDate(), LocalDateTime.now());
        Map<String, Object> checkpoint = Map.of(
                "maturedCount", result.successCount(),
                "failedCount", result.failedCount(),
                "labelFingerprint", result.outputFingerprint());
        return success("MATURE_SAMPLE_LABELS", result.processedCount(), result.successCount(),
                result.failedCount(), checkpoint, null, result.errors());
    }

    private StepOutcome computeFactors(PipelineContext context) {
        Long batchId = requiredCheckpointId(context, "FETCH_SOURCE_DATA", "dataBatchId");
        List<AiSample> samples = samples(batchId, context.tradeDate());
        List<AiFactorEngine.FactorContext> factorContexts = new ArrayList<>();
        for (AiSample sample : samples) {
            StockDetailResponse detail = readFeatureSnapshot(sample);
            StockQuoteResponse quote = detail.quote();
            String source = quote == null ? "UNKNOWN" : quote.source();
            KlineSeriesSnapshot stockSeries = KlineSeriesSnapshot.create(
                    sample.stockCode, "day", "NONE", source, sample.asOfTime,
                    sample.createdAt == null ? sample.asOfTime : sample.createdAt,
                    detail.kline() == null ? List.of() : detail.kline());
            factorContexts.add(new AiFactorEngine.FactorContext(
                    sample, detail, List.of(), List.of(), null, null,
                    stockSeries, null, null));
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
                    LocalDateTime.now())));
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
        AiLabelVerificationCoordinator.VerificationResult result =
                labelCoordinator.evaluatePredictions(context.tradeDate(), LocalDateTime.now());
        Map<String, Object> checkpoint = Map.of(
                "evaluationCount", result.successCount(),
                "failedCount", result.failedCount(),
                "evaluationFingerprint", result.outputFingerprint());
        return success("EVALUATE_PREDICTIONS", result.processedCount(), result.successCount(),
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
        List<AiSourceObservation> observations = observationMapper.selectList(
                new QueryWrapper<AiSourceObservation>()
                        .eq("data_batch_id", batchId)
                        .eq("source_type", "STOCK_DAILY_SNAPSHOT")
                        .orderByDesc("fetched_at")
                        .orderByDesc("id"));
        Map<String, AiSourceObservation> latest = new LinkedHashMap<>();
        for (AiSourceObservation observation : observations) {
            latest.putIfAbsent(observation.stockCode, observation);
        }
        return latest;
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
