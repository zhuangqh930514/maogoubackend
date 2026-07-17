package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiSourceObservation;
import com.maogou.stock.domain.entity.research.AiTradingCalendar;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;
import com.maogou.stock.mapper.research.AiDataBatchMapper;
import com.maogou.stock.mapper.research.AiSourceObservationMapper;
import com.maogou.stock.mapper.research.AiTradingCalendarMapper;
import com.maogou.stock.service.research.AiHistoricalEvidenceImportService;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiResearchUniverseService;
import com.maogou.stock.service.research.AiSampleSnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class AiHistoricalEvidenceImportServiceImpl implements AiHistoricalEvidenceImportService {

    private static final int MINIMUM_TRAILING_BARS = 20;
    private static final int MAX_KLINE_LIMIT = 500;
    private static final String CATALOG_SOURCE = "CURRENT_LISTED_HISTORICAL_COHORT";

    private final AiTradingCalendarMapper calendarMapper;
    private final List<HistoricalMarketDataProvider> marketDataProviders;
    private final AiResearchUniverseService universeService;
    private final AiSampleSnapshotService snapshotService;
    private final AiDataBatchMapper dataBatchMapper;
    private final AiSourceObservationMapper observationMapper;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiHistoricalEvidenceImportServiceImpl(
            AiTradingCalendarMapper calendarMapper,
            List<HistoricalMarketDataProvider> marketDataProviders,
            AiResearchUniverseService universeService,
            AiSampleSnapshotService snapshotService,
            AiDataBatchMapper dataBatchMapper,
            AiSourceObservationMapper observationMapper,
            ObjectMapper objectMapper
    ) {
        this.calendarMapper = calendarMapper;
        this.marketDataProviders = orderedProviders(marketDataProviders);
        this.universeService = universeService;
        this.snapshotService = snapshotService;
        this.dataBatchMapper = dataBatchMapper;
        this.observationMapper = observationMapper;
        this.objectMapper = objectMapper;
    }

    AiHistoricalEvidenceImportServiceImpl(
            AiTradingCalendarMapper calendarMapper,
            HistoricalMarketDataProvider marketDataProvider,
            AiResearchUniverseService universeService,
            AiSampleSnapshotService snapshotService,
            AiDataBatchMapper dataBatchMapper,
            AiSourceObservationMapper observationMapper,
            ObjectMapper objectMapper
    ) {
        this(calendarMapper, List.of(marketDataProvider), universeService, snapshotService,
                dataBatchMapper, observationMapper, objectMapper);
    }

    @Override
    public ColdStartPlan plan(LocalDate endDate, int trainingTradingDays, int targetStockCount) {
        if (endDate == null || trainingTradingDays <= 0 || targetStockCount <= 0) {
            throw new IllegalArgumentException("历史初始化缺少截止日、训练交易日数或股票数");
        }
        int replayDays = Math.addExact(trainingTradingDays, MATURITY_BUFFER_TRADING_DAYS);
        List<AiTradingCalendar> calendars = loadOrSeedCalendars(endDate, replayDays);
        List<LocalDate> dates = calendars == null ? List.of() : calendars.stream()
                .filter(Objects::nonNull)
                .filter(value -> Integer.valueOf(1).equals(value.isTradeDay))
                .filter(AiHistoricalEvidenceImportServiceImpl::validCalendar)
                .map(value -> value.tradeDate)
                .distinct()
                .sorted()
                .toList();
        if (dates.size() < replayDays) {
            throw new IllegalStateException("正式交易日历不足：需要 " + replayDays + " 个，当前只有 " + dates.size() + " 个");
        }
        return new ColdStartPlan(
                dates.get(0), dates.get(dates.size() - 1), trainingTradingDays,
                dates.size(), targetStockCount, dates);
    }

    private List<AiTradingCalendar> loadOrSeedCalendars(LocalDate endDate, int replayDays) {
        List<AiTradingCalendar> existing = calendarMapper.selectRecentTradingDays(
                endDate, AiResearchContract.CALENDAR_VERSION, replayDays);
        if (existing != null && existing.stream().filter(
                AiHistoricalEvidenceImportServiceImpl::validCalendar).count() >= replayDays) {
            return existing;
        }
        KlineSeriesSnapshot benchmark = fetchHistoricalKline(
                AiResearchContract.BENCHMARK_SYMBOL,
                Math.min(MAX_KLINE_LIMIT, replayDays + 40),
                endDate.atTime(16, 0),
                "NONE",
                new java.util.LinkedHashSet<>());
        List<KlinePointResponse> points = benchmark.points().stream()
                .filter(Objects::nonNull)
                .filter(point -> point.tradeDate() != null && !point.tradeDate().isAfter(endDate))
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                .toList();
        LocalDateTime createdAt = LocalDateTime.now();
        for (int index = 0; index < points.size(); index++) {
            AiTradingCalendar calendar = new AiTradingCalendar();
            calendar.marketCode = "CN_A_SHARE";
            calendar.tradeDate = points.get(index).tradeDate();
            calendar.calendarVersion = AiResearchContract.CALENDAR_VERSION;
            calendar.isTradeDay = 1;
            calendar.sessionOpenTime = LocalTime.of(9, 30);
            calendar.sessionCloseTime = LocalTime.of(15, 0);
            calendar.previousTradeDate = index == 0 ? null : points.get(index - 1).tradeDate();
            calendar.nextTradeDate = index + 1 >= points.size()
                    ? null : points.get(index + 1).tradeDate();
            calendar.sourceName = benchmark.source();
            calendar.sourceAsOf = benchmark.fetchedAt();
            calendar.sourceFingerprint = sha256(
                    benchmark.sourceFingerprint() + "|TRADING_CALENDAR|" + calendar.tradeDate);
            calendar.createdAt = createdAt;
            calendarMapper.insertIgnore(calendar);
        }
        return calendarMapper.selectRecentTradingDays(
                endDate, AiResearchContract.CALENDAR_VERSION, replayDays);
    }

    @Override
    public ImportResult importEvidence(ImportRequest request) {
        validate(request);
        ColdStartPlan plan = request.plan();
        java.util.Set<String> unavailableProviders = new java.util.LinkedHashSet<>();
        int requestedCatalogSize = Math.min(1000, Math.max(
                plan.targetStockCount() + 200,
                (int) Math.ceil(plan.targetStockCount() * 1.5d)));
        HistoricalMarketDataProvider.UniverseCatalog catalog = fetchCatalog(
                requestedCatalogSize, request.requestedAt(), unavailableProviders);
        validateCatalog(catalog, plan);

        int historyLimit = Math.min(MAX_KLINE_LIMIT,
                plan.replayTradingDays() + MINIMUM_TRAILING_BARS + 40);
        KlineSeriesSnapshot benchmark = fetchHistoricalKline(
                AiResearchContract.BENCHMARK_SYMBOL, historyLimit,
                plan.endDate().atTime(16, 0), "NONE", unavailableProviders);

        Map<String, PreparedSecurity> prepared = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        for (HistoricalMarketDataProvider.Security security : catalog.securities()) {
            if (prepared.size() >= requestedCatalogSize) {
                break;
            }
            if (!validSecurity(security, plan.endDate())) {
                continue;
            }
            try {
                PreparedSecurity value = fetchPreparedSecurity(
                        security, historyLimit, plan.endDate().atTime(16, 0), unavailableProviders);
                prepared.put(security.stockCode(), value);
            } catch (RuntimeException exception) {
                warnings.add(security.stockCode() + "：" + rootMessage(exception));
            }
        }

        int imported = 0;
        int reused = 0;
        int maximumPrepared = 0;
        for (LocalDate tradeDate : plan.tradingDates()) {
            List<PreparedSecurity> selected = prepared.values().stream()
                    .filter(value -> value.security().listedOn() != null
                            && !value.security().listedOn().isAfter(tradeDate))
                    .filter(value -> usableAt(value, tradeDate))
                    .sorted(Comparator.comparing(value -> sha256(value.security().stockCode())))
                    .limit(plan.targetStockCount())
                    .toList();
            if (selected.size() < plan.targetStockCount()) {
                throw new IllegalStateException("交易日 " + tradeDate + " 只有 " + selected.size()
                        + " 只股票具备完整未复权和复权证据，目标为 " + plan.targetStockCount());
            }
            maximumPrepared = Math.max(maximumPrepared, selected.size());
            DayImportResult day = importDay(request, catalog, benchmark, tradeDate, selected);
            if (day.reused()) {
                reused++;
            } else {
                imported++;
            }
        }
        return new ImportResult(
                imported, reused, maximumPrepared,
                sha256(catalog.sourceFingerprint() + "|" + plan.tradingDates() + "|" + maximumPrepared),
                warnings);
    }

    private HistoricalMarketDataProvider.UniverseCatalog fetchCatalog(
            int limit,
            LocalDateTime requestedAt,
            java.util.Set<String> unavailableProviders
    ) {
        RuntimeException lastFailure = null;
        for (HistoricalMarketDataProvider provider : marketDataProviders) {
            if (unavailableProviders.contains(provider.providerCode())) {
                continue;
            }
            try {
                return provider.fetchCurrentListedUniverse(limit, requestedAt);
            } catch (RuntimeException exception) {
                unavailableProviders.add(provider.providerCode());
                lastFailure = exception;
            }
        }
        throw new IllegalStateException(
                "所有真实历史股票目录来源均不可用：" + rootMessage(lastFailure), lastFailure);
    }

    private KlineSeriesSnapshot fetchHistoricalKline(
            String symbol,
            int limit,
            LocalDateTime asOfTime,
            String adjustmentMode,
            java.util.Set<String> unavailableProviders
    ) {
        RuntimeException lastFailure = null;
        for (HistoricalMarketDataProvider provider : marketDataProviders) {
            if (unavailableProviders.contains(provider.providerCode())) {
                continue;
            }
            try {
                KlineSeriesSnapshot result = provider.fetchHistoricalKline(
                        symbol, limit, asOfTime, adjustmentMode);
                validateSeries(result, adjustmentMode);
                return result;
            } catch (RuntimeException exception) {
                unavailableProviders.add(provider.providerCode());
                lastFailure = exception;
            }
        }
        throw new IllegalStateException(
                "所有真实历史 K 线来源均不可用：" + rootMessage(lastFailure), lastFailure);
    }

    private PreparedSecurity fetchPreparedSecurity(
            HistoricalMarketDataProvider.Security security,
            int limit,
            LocalDateTime asOfTime,
            java.util.Set<String> unavailableProviders
    ) {
        RuntimeException lastFailure = null;
        for (HistoricalMarketDataProvider provider : marketDataProviders) {
            if (unavailableProviders.contains(provider.providerCode())) {
                continue;
            }
            try {
                KlineSeriesSnapshot raw = provider.fetchHistoricalKline(
                        security.stockCode(), limit, asOfTime, "NONE");
                KlineSeriesSnapshot adjusted = provider.fetchHistoricalKline(
                        security.stockCode(), limit, asOfTime, "QFQ");
                validateSeries(raw, "NONE");
                validateSeries(adjusted, "QFQ");
                LocalDate observedSince = raw.points().stream()
                        .map(KlinePointResponse::tradeDate)
                        .filter(Objects::nonNull)
                        .min(Comparator.naturalOrder())
                        .orElseThrow(() -> new IllegalStateException("历史 K 线缺少最早可验证交易日"));
                HistoricalMarketDataProvider.Security effectiveSecurity =
                        new HistoricalMarketDataProvider.Security(
                                security.stockCode(), security.stockName(), security.market(),
                                security.listedOn() == null ? observedSince : security.listedOn());
                return new PreparedSecurity(effectiveSecurity, raw, adjusted);
            } catch (RuntimeException exception) {
                if (providerUnavailable(exception)) {
                    unavailableProviders.add(provider.providerCode());
                }
                lastFailure = exception;
            }
        }
        throw new IllegalStateException(
                security.stockCode() + " 的真实历史 K 线来源均不可用：" + rootMessage(lastFailure),
                lastFailure);
    }

    private DayImportResult importDay(
            ImportRequest request,
            HistoricalMarketDataProvider.UniverseCatalog catalog,
            KlineSeriesSnapshot benchmark,
            LocalDate tradeDate,
            List<PreparedSecurity> selected
    ) {
        LocalDateTime researchAsOf = tradeDate.atTime(16, 0);
        List<AiResearchUniverseService.UniverseCandidate> candidates = selected.stream()
                .map(value -> new AiResearchUniverseService.UniverseCandidate(
                        value.security().stockCode(), value.security().stockName(), value.security().market(),
                        CATALOG_SOURCE, true, null, value.security().listedOn()))
                .toList();
        AiResearchUniverseService.SnapshotResult snapshot = universeService.createSystemCoreSnapshot(
                new AiResearchUniverseService.SnapshotRequest(
                        tradeDate, researchAsOf, AiResearchContract.CALENDAR_VERSION, candidates, false));
        if (snapshot.snapshot() == null || snapshot.snapshot().id == null
                || !"READY".equals(snapshot.snapshot().qualityStatus)
                || snapshot.snapshot().sourceFingerprint == null
                || snapshot.snapshot().sourceFingerprint.isBlank()) {
            throw new IllegalStateException("交易日 " + tradeDate + " 的历史股票池未达到 READY");
        }
        String dayKey = "HISTORICAL_EVIDENCE/1.0.0:" + tradeDate + ":"
                + fingerprint(List.of(
                        snapshot.snapshot().sourceFingerprint,
                        catalog.sourceFingerprint(),
                        selected.stream().map(value -> value.security().stockCode()).toList()));
        AiDataBatch batch = snapshotService.startOrGetBatch(
                snapshot.snapshot().id, tradeDate, "AFTER_CLOSE", researchAsOf, dayKey);
        if (batch == null || batch.id == null) {
            throw new IllegalStateException("历史数据批次创建失败：" + tradeDate);
        }
        if ("READY".equals(batch.qualityStatus)
                && "SUCCESS".equals(batch.status)
                && value(batch.successCount) >= selected.size()) {
            return new DayImportResult(true);
        }

        store(observation(
                batch.id, null, "MARKET_BENCHMARK", "KLINE",
                benchmark.source(), researchAsOf, benchmark.fetchedAt(),
                pointInTimeSeries(benchmark, tradeDate, 80), sourceUri(benchmark.source())));

        Map<String, AiResearchUniverseItem> items = new LinkedHashMap<>();
        for (AiResearchUniverseItem item : snapshot.items()) {
            if (item != null && Integer.valueOf(1).equals(item.included)) {
                items.put(item.stockCode, item);
            }
        }
        for (PreparedSecurity security : selected) {
            AiResearchUniverseItem item = items.get(security.security().stockCode());
            if (item == null) {
                throw new IllegalStateException("历史股票池缺少已选择证券：" + security.security().stockCode());
            }
            KlineSeriesSnapshot raw = pointInTimeSeries(security.raw(), tradeDate, 60);
            KlineSeriesSnapshot adjusted = pointInTimeSeries(security.adjusted(), tradeDate, 60);
            StockDetailResponse detail = detail(security.security(), raw, researchAsOf);
            store(observation(
                    batch.id, item.stockCode, "STOCK_DAILY_SNAPSHOT", "STOCK_DETAIL",
                    raw.source(), researchAsOf, raw.fetchedAt(), detail, sourceUri(raw.source())));
            store(observation(
                    batch.id, item.stockCode, "ADJUSTMENT_FACTOR", "ADJUSTMENT_FACTOR",
                    adjusted.source(), researchAsOf, latestFetchedAt(raw, adjusted),
                    adjustmentEvidence(raw, adjusted, tradeDate, latestFetchedAt(raw, adjusted)),
                    sourceUri(adjusted.source())));
        }

        AiDataBatch completed = snapshotService.completeBatch(batch.id, new AiSampleSnapshotService.BatchCompletion(
                "HISTORICAL", BigDecimal.valueOf(100), "READY",
                selected.size(), selected.size(), 0, null, request.requestedAt()));
        AiDataBatch persisted = completed == null ? batch : completed;
        persisted.universeSnapshotId = snapshot.snapshot().id;
        persisted.tradeDate = tradeDate;
        persisted.samplePhase = "AFTER_CLOSE";
        persisted.asOfTime = researchAsOf;
        persisted.sourceStatus = "HISTORICAL";
        persisted.quoteAsOf = researchAsOf;
        persisted.klineAsOf = tradeDate;
        persisted.benchmarkAsOf = tradeDate;
        persisted.qualityScore = BigDecimal.valueOf(100);
        persisted.qualityStatus = "READY";
        persisted.itemCount = selected.size();
        persisted.successCount = selected.size();
        persisted.failedCount = 0;
        persisted.status = "SUCCESS";
        persisted.completedAt = request.requestedAt();
        dataBatchMapper.updateById(persisted);
        return new DayImportResult(false);
    }

    private AiSourceObservation observation(
            Long batchId,
            String stockCode,
            String sourceType,
            String endpointType,
            String providerCode,
            LocalDateTime researchAsOf,
            LocalDateTime fetchedAt,
            Object payload,
            String sourceUri
    ) {
        String payloadJson = json(payload);
        String checksum = sha256(payloadJson);
        AiSourceObservation observation = new AiSourceObservation();
        observation.dataBatchId = batchId;
        observation.stockCode = stockCode;
        observation.sourceType = sourceType;
        observation.providerCode = normalize(providerCode, "UNAVAILABLE");
        observation.endpointType = endpointType;
        observation.eventTime = researchAsOf.toLocalDate().atTime(15, 0);
        observation.firstSeenAt = fetchedAt;
        observation.fetchedAt = fetchedAt;
        observation.asOfTime = researchAsOf;
        observation.availableAt = researchAsOf.toLocalDate().atTime(15, 5);
        observation.observedAt = fetchedAt;
        observation.sourceRevision = researchAsOf.toLocalDate().toString();
        observation.sourceUri = sourceUri;
        observation.payloadJson = payloadJson;
        observation.payloadChecksum = checksum;
        observation.sourceFingerprint = sha256(String.join("|",
                String.valueOf(batchId), Objects.requireNonNullElse(stockCode, "<MARKET>"),
                sourceType, checksum));
        observation.freshnessStatus = "REALTIME";
        observation.qualityStatus = "READY";
        observation.createdAt = fetchedAt;
        return observation;
    }

    private void store(AiSourceObservation observation) {
        try {
            observationMapper.insert(observation);
        } catch (DuplicateKeyException ignored) {
            // Immutable evidence with the same fingerprint is already stored.
        }
    }

    private static StockDetailResponse detail(
            HistoricalMarketDataProvider.Security security,
            KlineSeriesSnapshot raw,
            LocalDateTime researchAsOf
    ) {
        List<KlinePointResponse> points = raw.points();
        KlinePointResponse latest = points.get(points.size() - 1);
        KlinePointResponse previous = points.size() > 1 ? points.get(points.size() - 2) : latest;
        BigDecimal change = latest.close().subtract(previous.close());
        BigDecimal percent = previous.close().signum() == 0 ? BigDecimal.ZERO
                : change.multiply(BigDecimal.valueOf(100))
                .divide(previous.close(), 4, RoundingMode.HALF_UP);
        StockQuoteResponse quote = new StockQuoteResponse(
                security.stockCode(), security.stockName(), latest.close(), change, percent,
                volumeRatio(points), security.market(), raw.source(), researchAsOf);
        return new StockDetailResponse(
                quote, FinanceSnapshotResponse.empty(), List.of(), points, "历史收盘研究样本", 50);
    }

    private static AdjustmentEvidence adjustmentEvidence(
            KlineSeriesSnapshot raw,
            KlineSeriesSnapshot adjusted,
            LocalDate tradeDate,
            LocalDateTime fetchedAt
    ) {
        KlinePointResponse rawPoint = byDate(raw).get(tradeDate);
        KlinePointResponse adjustedPoint = byDate(adjusted).get(tradeDate);
        if (rawPoint == null || adjustedPoint == null || rawPoint.close() == null
                || adjustedPoint.close() == null || rawPoint.close().signum() <= 0) {
            throw new IllegalStateException("缺少可验证复权因子：" + raw.symbol() + " / " + tradeDate);
        }
        BigDecimal factor = adjustedPoint.close().divide(rawPoint.close(), 12, RoundingMode.HALF_UP);
        return new AdjustmentEvidence(
                tradeDate, rawPoint.close(), adjustedPoint.close(), factor,
                raw.sourceFingerprint(), adjusted.sourceFingerprint(), fetchedAt);
    }

    private static KlineSeriesSnapshot pointInTimeSeries(
            KlineSeriesSnapshot source,
            LocalDate tradeDate,
            int limit
    ) {
        List<KlinePointResponse> points = source.points().stream()
                .filter(value -> value != null && value.tradeDate() != null
                        && !value.tradeDate().isAfter(tradeDate))
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                .toList();
        if (points.size() < MINIMUM_TRAILING_BARS || !byDate(source).containsKey(tradeDate)) {
            throw new IllegalStateException("历史序列缺少目标交易日或前置窗口：" + source.symbol() + " / " + tradeDate);
        }
        List<KlinePointResponse> tail = points.subList(Math.max(0, points.size() - limit), points.size());
        return KlineSeriesSnapshot.create(
                source.symbol(), "day", source.adjustmentMode(), source.source(),
                tradeDate.atTime(16, 0), source.fetchedAt(), tail);
    }

    private static boolean usableAt(PreparedSecurity security, LocalDate tradeDate) {
        return byDate(security.raw()).containsKey(tradeDate)
                && byDate(security.adjusted()).containsKey(tradeDate)
                && security.raw().points().stream().filter(point -> !point.tradeDate().isAfter(tradeDate)).count()
                >= MINIMUM_TRAILING_BARS;
    }

    private static Map<LocalDate, KlinePointResponse> byDate(KlineSeriesSnapshot series) {
        Map<LocalDate, KlinePointResponse> result = new LinkedHashMap<>();
        if (series != null && series.points() != null) {
            for (KlinePointResponse point : series.points()) {
                if (point != null && point.tradeDate() != null) {
                    result.put(point.tradeDate(), point);
                }
            }
        }
        return result;
    }

    private static BigDecimal volumeRatio(List<KlinePointResponse> points) {
        if (points == null || points.size() < 2) {
            return null;
        }
        KlinePointResponse latest = points.get(points.size() - 1);
        List<Long> history = points.subList(Math.max(0, points.size() - 6), points.size() - 1).stream()
                .map(KlinePointResponse::volume)
                .filter(value -> value != null && value > 0)
                .toList();
        if (latest.volume() == null || latest.volume() <= 0 || history.isEmpty()) {
            return null;
        }
        BigDecimal average = history.stream().map(BigDecimal::valueOf)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(history.size()), 8, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(latest.volume()).divide(average, 4, RoundingMode.HALF_UP);
    }

    private static LocalDateTime latestFetchedAt(
            KlineSeriesSnapshot first,
            KlineSeriesSnapshot second
    ) {
        LocalDateTime firstTime = first == null ? null : first.fetchedAt();
        LocalDateTime secondTime = second == null ? null : second.fetchedAt();
        if (firstTime == null) {
            return secondTime == null ? LocalDateTime.now() : secondTime;
        }
        return secondTime != null && secondTime.isAfter(firstTime) ? secondTime : firstTime;
    }

    private static boolean validSecurity(HistoricalMarketDataProvider.Security security, LocalDate endDate) {
        return security != null && security.stockCode() != null
                && security.stockCode().matches("[036]\\d{5}")
                && security.stockName() != null && !security.stockName().isBlank()
                && (security.listedOn() == null || !security.listedOn().isAfter(endDate));
    }

    private static List<HistoricalMarketDataProvider> orderedProviders(
            List<HistoricalMarketDataProvider> providers
    ) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个真实历史行情来源");
        }
        return providers.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(provider -> switch (provider.providerCode()) {
                    case "EASTMONEY" -> 0;
                    case "SINA_TENCENT" -> 1;
                    default -> 10;
                }))
                .toList();
    }

    private static String sourceUri(String providerCode) {
        String provider = normalize(providerCode, "");
        if (provider.contains("TENCENT")) {
            return "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
        }
        if (provider.contains("EASTMONEY")) {
            return "https://push2his.eastmoney.com/api/qt/stock/kline/get";
        }
        return null;
    }

    private static boolean providerUnavailable(Throwable throwable) {
        String message = rootMessage(throwable).toLowerCase(Locale.ROOT);
        return message.contains("unexpected end of file")
                || message.contains("empty reply")
                || message.contains("timed out")
                || message.contains("timeout")
                || message.contains("connection reset")
                || message.contains("connection refused")
                || message.contains("remote host terminated")
                || message.contains("status code 403")
                || message.contains("status code 429");
    }

    private static boolean validCalendar(AiTradingCalendar calendar) {
        return calendar != null
                && Integer.valueOf(1).equals(calendar.isTradeDay)
                && calendar.tradeDate != null
                && calendar.sourceName != null
                && !calendar.sourceName.isBlank()
                && !mockSource(calendar.sourceName)
                && calendar.sourceFingerprint != null
                && !calendar.sourceFingerprint.isBlank();
    }

    private static void validateCatalog(
            HistoricalMarketDataProvider.UniverseCatalog catalog,
            ColdStartPlan plan
    ) {
        if (catalog == null || catalog.securities().size() < plan.targetStockCount()
                || catalog.providerCode() == null || mockSource(catalog.providerCode())
                || catalog.sourceFingerprint() == null || catalog.sourceFingerprint().isBlank()
                || catalog.fetchedAt() == null) {
            throw new IllegalStateException("历史股票目录不足或来源不可验证");
        }
    }

    private static void validateSeries(KlineSeriesSnapshot series, String adjustmentMode) {
        if (series == null || series.points() == null || series.points().isEmpty()
                || !series.fingerprintMatches()
                || !adjustmentMode.equalsIgnoreCase(series.adjustmentMode())
                || mockSource(series.source())) {
            throw new IllegalStateException("历史 K 线缺少真实来源、复权模式或完整指纹");
        }
    }

    private static void validate(ImportRequest request) {
        if (request == null || request.plan() == null || request.plan().tradingDates().isEmpty()
                || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.requestedAt() == null) {
            throw new IllegalArgumentException("历史证据导入缺少计划、幂等键或请求时间");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化历史研究证据", exception);
        }
    }

    private static String fingerprint(Object value) {
        return sha256(String.valueOf(value));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String normalize(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? fallback : normalized;
    }

    private static boolean mockSource(String value) {
        String normalized = normalize(value, "");
        return normalized.contains("MOCK") || normalized.contains("DEMO")
                || normalized.contains("FALLBACK") || normalized.contains("SAMPLE");
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record PreparedSecurity(
            HistoricalMarketDataProvider.Security security,
            KlineSeriesSnapshot raw,
            KlineSeriesSnapshot adjusted
    ) {
    }

    private record DayImportResult(boolean reused) {
    }

    private record AdjustmentEvidence(
            LocalDate tradeDate,
            BigDecimal rawClose,
            BigDecimal adjustedClose,
            BigDecimal adjustmentFactor,
            String rawSourceFingerprint,
            String adjustedSourceFingerprint,
            LocalDateTime fetchedAt
    ) {
    }
}
