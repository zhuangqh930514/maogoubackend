package com.maogou.stock.service.impl.research;

import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;
import com.maogou.stock.infrastructure.market.HistoricalProviderRetryExecutor;
import com.maogou.stock.infrastructure.market.MarketSourceHealthRegistry;
import com.maogou.stock.service.research.HistoricalProviderPreflightService;
import com.maogou.stock.service.research.HistoricalUniverseCatalogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Performs a small real request against every historical provider before a
 * run can be created. A provider is ready only when its historical universe,
 * unadjusted bars and adjusted bars all pass the same basic contract used by
 * import. A verified database archive may satisfy the universe contract; a
 * current-listing endpoint never does.
 */
@Service
public class HistoricalProviderPreflightServiceImpl implements HistoricalProviderPreflightService {

    private static final int MIN_PREFLIGHT_BARS = 20;
    private static final String DATASET_HISTORICAL_UNIVERSE = "HISTORICAL_UNIVERSE";
    private static final String DATASET_DAILY_NONE = "DAILY_BAR_NONE";
    private static final String DATASET_DAILY_QFQ = "DAILY_BAR_QFQ";

    private final List<HistoricalMarketDataProvider> providers;
    private final MarketSourceHealthRegistry healthRegistry;
    private final HistoricalProviderRetryExecutor retryExecutor;
    private final HistoricalUniverseCatalogService historicalUniverseCatalogService;
    private final Clock clock;

    @Autowired
    public HistoricalProviderPreflightServiceImpl(
            List<HistoricalMarketDataProvider> providers,
            MarketSourceHealthRegistry healthRegistry,
            HistoricalProviderRetryExecutor retryExecutor,
            HistoricalUniverseCatalogService historicalUniverseCatalogService
    ) {
        this(providers, healthRegistry, retryExecutor, historicalUniverseCatalogService,
                Clock.systemDefaultZone());
    }

    public HistoricalProviderPreflightServiceImpl(
            List<HistoricalMarketDataProvider> providers,
            MarketSourceHealthRegistry healthRegistry
    ) {
        this(providers, healthRegistry, HistoricalProviderRetryExecutor.direct(), Clock.systemDefaultZone());
    }

    public HistoricalProviderPreflightServiceImpl(
            List<HistoricalMarketDataProvider> providers,
            MarketSourceHealthRegistry healthRegistry,
            HistoricalUniverseCatalogService historicalUniverseCatalogService
    ) {
        this(providers, healthRegistry, HistoricalProviderRetryExecutor.direct(),
                historicalUniverseCatalogService, Clock.systemDefaultZone());
    }

    HistoricalProviderPreflightServiceImpl(
            List<HistoricalMarketDataProvider> providers,
            MarketSourceHealthRegistry healthRegistry,
            Clock clock
    ) {
        this(providers, healthRegistry, HistoricalProviderRetryExecutor.direct(), null, clock);
    }

    HistoricalProviderPreflightServiceImpl(
            List<HistoricalMarketDataProvider> providers,
            MarketSourceHealthRegistry healthRegistry,
            HistoricalProviderRetryExecutor retryExecutor,
            Clock clock
    ) {
        this(providers, healthRegistry, retryExecutor, null, clock);
    }

    HistoricalProviderPreflightServiceImpl(
            List<HistoricalMarketDataProvider> providers,
            MarketSourceHealthRegistry healthRegistry,
            HistoricalProviderRetryExecutor retryExecutor,
            HistoricalUniverseCatalogService historicalUniverseCatalogService,
            Clock clock
    ) {
        this.providers = providers == null ? List.of() : providers.stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(value -> value.providerCode() == null ? "" : value.providerCode()))
                .toList();
        this.healthRegistry = healthRegistry;
        this.retryExecutor = retryExecutor == null ? HistoricalProviderRetryExecutor.direct() : retryExecutor;
        this.historicalUniverseCatalogService = historicalUniverseCatalogService;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Override
    public PreflightResult check(LocalDateTime asOfTime, String benchmarkSymbol) {
        LocalDateTime checkedAt = asOfTime == null ? LocalDateTime.now(clock) : asOfTime;
        String symbol = benchmarkSymbol == null || benchmarkSymbol.isBlank()
                ? "000300.SH" : benchmarkSymbol.trim();
        List<Map<String, Object>> capabilities = new ArrayList<>();
        List<Map<String, Object>> blocking = new ArrayList<>();
        int readyProviders = 0;
        boolean archiveReady = historicalArchiveReady(checkedAt);

        for (HistoricalMarketDataProvider provider : providers) {
            Map<String, Object> capability = checkProvider(provider, checkedAt, symbol, archiveReady);
            capabilities.add(capability);
            if ("READY".equals(capability.get("preflightStatus"))) {
                readyProviders++;
            } else {
                blocking.add(issue(provider.providerCode(), capability));
            }
        }
        if (providers.isEmpty()) {
            blocking.add(Map.of(
                    "reasonCode", "NO_HISTORICAL_PROVIDER",
                    "title", "没有配置真实历史数据源",
                    "reason", "必须至少配置一个能返回历史股票池、未复权 K 线和前复权 K 线的真实 provider，或先导入可审计历史股票池",
                    "retryable", false));
        } else if (readyProviders == 0) {
            blocking.add(Map.of(
                    "reasonCode", "NO_READY_HISTORICAL_PROVIDER",
                    "title", "没有通过历史数据源预检",
                    "reason", "所有 provider 均未通过历史股票池、NONE 和 QFQ 数据合同，且本地没有 READY 的历史股票池快照，不能创建正式历史补齐运行",
                    "retryable", true));
        }
        return new PreflightResult(capabilities, blocking, readyProviders > 0);
    }

    private Map<String, Object> checkProvider(
            HistoricalMarketDataProvider provider,
            LocalDateTime asOfTime,
            String benchmarkSymbol,
            boolean archiveReady
    ) {
        String providerCode = normalize(provider.providerCode());
        long started = System.nanoTime();
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("provider", providerCode);
        capability.put("sourceOfTruth", !provider.syntheticSource());
        capability.put("checkedAt", asOfTime);
        capability.put("endpoints", endpointMap(providerCode));
        Map<String, String> states = new LinkedHashMap<>();
        List<Map<String, Object>> attempts = new ArrayList<>();

        if (provider.syntheticSource()) {
            capability.put("preflightStatus", "REJECTED_SOURCE");
            capability.put("message", "拒绝演示、fixture 或 local provider，不能作为正式历史证据");
            capability.put("latencyMs", elapsedMillis(started));
            return capability;
        }

        boolean historicalUniverseReady = checkHistoricalUniverse(provider, asOfTime, states, attempts);
        if (!historicalUniverseReady && archiveReady) {
            states.put(DATASET_HISTORICAL_UNIVERSE, "ARCHIVE_READY");
            historicalUniverseReady = true;
        }
        boolean noneReady = checkBars(provider, benchmarkSymbol, asOfTime, "NONE", states, attempts);
        boolean qfqReady = checkBars(provider, benchmarkSymbol, asOfTime, "QFQ", states, attempts);
        capability.put("capabilities", states);
        capability.put("attempts", attempts);
        capability.put("latencyMs", elapsedMillis(started));
        capability.put("preflightStatus", historicalUniverseReady && noneReady && qfqReady ? "READY" : "UNAVAILABLE");
        capability.put("message", historicalUniverseReady && noneReady && qfqReady
                ? "历史股票池、未复权和前复权历史 K 线均通过预检"
                : "历史 provider 能力不完整或请求失败，不能进入正式导入");
        return capability;
    }

    private boolean checkHistoricalUniverse(
            HistoricalMarketDataProvider provider,
            LocalDateTime asOfTime,
            Map<String, String> states,
            List<Map<String, Object>> attempts
    ) {
        String endpoint = endpoint(provider.providerCode(), DATASET_HISTORICAL_UNIVERSE);
        String healthEndpoint = healthEndpoint(DATASET_HISTORICAL_UNIVERSE);
        try {
            if (!provider.historicalCapabilities().contains(DATASET_HISTORICAL_UNIVERSE)) {
                throw new IllegalStateException("provider 未声明按历史日期提供证券主数据");
            }
            HistoricalMarketDataProvider.HistoricalUniverse universe = retryExecutor.execute(
                    provider.providerCode(), DATASET_HISTORICAL_UNIVERSE,
                    () -> provider.fetchHistoricalUniverse(1, asOfTime.toLocalDate(), asOfTime));
            if (universe == null || universe.tradeDate() == null
                    || !asOfTime.toLocalDate().equals(universe.tradeDate())
                    || universe.securities().isEmpty()
                    || blank(universe.sourceFingerprint()) || blank(universe.sourceUri())
                    || blank(universe.sourceRevision()) || universe.observedAt() == null) {
                throw new IllegalStateException("历史股票池为空或缺少日期、版本、观测时间和来源指纹");
            }
            states.put(DATASET_HISTORICAL_UNIVERSE, "READY");
            success(provider.providerCode(), healthEndpoint, universe.sourceFingerprint(), asOfTime);
            attempts.add(attempt(provider.providerCode(), endpoint, "READY", null));
            return true;
        } catch (RuntimeException exception) {
            return failed(provider.providerCode(), endpoint, healthEndpoint, DATASET_HISTORICAL_UNIVERSE, exception,
                    states, attempts, asOfTime);
        }
    }

    private boolean checkBars(
            HistoricalMarketDataProvider provider,
            String symbol,
            LocalDateTime asOfTime,
            String adjustment,
            Map<String, String> states,
            List<Map<String, Object>> attempts
    ) {
        String dataset = "QFQ".equals(adjustment) ? DATASET_DAILY_QFQ : DATASET_DAILY_NONE;
        String endpoint = endpoint(provider.providerCode(), dataset);
        String healthEndpoint = healthEndpoint(dataset);
        try {
            KlineSeriesSnapshot series = retryExecutor.execute(
                    provider.providerCode(), dataset,
                    () -> provider.fetchHistoricalKline(symbol, MIN_PREFLIGHT_BARS + 5, asOfTime, adjustment));
            validateSeries(series, asOfTime, adjustment);
            states.put(dataset, "READY");
            success(provider.providerCode(), healthEndpoint, series.sourceFingerprint(), asOfTime);
            attempts.add(attempt(provider.providerCode(), endpoint, "READY", null));
            return true;
        } catch (RuntimeException exception) {
            return failed(provider.providerCode(), endpoint, healthEndpoint, dataset, exception,
                    states, attempts, asOfTime);
        }
    }

    private boolean failed(
            String providerCode,
            String endpoint,
            String healthEndpoint,
            String dataset,
            RuntimeException exception,
            Map<String, String> states,
            List<Map<String, Object>> attempts,
            LocalDateTime attemptedAt
    ) {
        String message = rootMessage(exception);
        states.put(dataset, "UNAVAILABLE");
        failure(providerCode, healthEndpoint, message, attemptedAt);
        attempts.add(attempt(providerCode, endpoint, "UNAVAILABLE", message));
        return false;
    }

    private void validateSeries(KlineSeriesSnapshot series, LocalDateTime asOfTime, String adjustment) {
        if (series == null || series.points() == null || series.points().size() < MIN_PREFLIGHT_BARS
                || !series.fingerprintMatches()
                || !adjustment.equalsIgnoreCase(series.adjustmentMode())
                || blank(series.sourceFingerprint())
                || blank(series.source())) {
            throw new IllegalStateException("K 线为空、指纹不匹配或复权模式错误");
        }
        LocalDate previousDate = null;
        for (KlinePointResponse point : series.points()) {
            if (point == null || point.tradeDate() == null || point.tradeDate().isAfter(asOfTime.toLocalDate())
                    || previousDate != null && !point.tradeDate().isAfter(previousDate)
                    || !positive(point.open()) || !positive(point.close())
                    || !positive(point.high()) || !positive(point.low())
                    || point.high().compareTo(point.low()) < 0) {
                throw new IllegalStateException("K 线存在未来日期、重复日期或非法 OHLC");
            }
            previousDate = point.tradeDate();
        }
    }

    private void success(String provider, String endpoint, String fingerprint, LocalDateTime at) {
        if (healthRegistry != null) {
            healthRegistry.recordSuccess(provider, endpoint, fingerprint, at);
        }
    }

    private boolean historicalArchiveReady(LocalDateTime asOfTime) {
        if (historicalUniverseCatalogService == null) {
            return false;
        }
        try {
            HistoricalUniverseCatalogService.CatalogPlan plan = historicalUniverseCatalogService.load(
                    List.of(asOfTime.toLocalDate()), asOfTime, 1);
            return plan != null && plan.byDate().containsKey(asOfTime.toLocalDate())
                    && plan.byDate().get(asOfTime.toLocalDate()).snapshotId() != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void failure(String provider, String endpoint, String message, LocalDateTime at) {
        if (healthRegistry != null) {
            healthRegistry.recordFailure(provider, endpoint, message, at);
        }
    }

    private static Map<String, Object> attempt(
            String provider, String endpoint, String status, String error
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("provider", provider);
        value.put("endpoint", endpoint);
        value.put("status", status);
        value.put("attempt", 1);
        value.put("nextRetryAt", null);
        if (error != null) {
            value.put("error", error);
        }
        return value;
    }

    private static Map<String, Object> issue(String provider, Map<String, Object> capability) {
        return Map.of(
                "reasonCode", "HISTORICAL_PROVIDER_PREFLIGHT_FAILED",
                "title", "历史数据源预检失败",
                "reason", provider + "：" + capability.getOrDefault("message", "能力检查失败")
                        + "；请查看 capabilities 和 attempts 中的具体 endpoint/错误",
                "provider", provider,
                "retryable", !"REJECTED_SOURCE".equals(capability.get("preflightStatus")));
    }

    private static Map<String, String> endpointMap(String provider) {
        Map<String, String> value = new LinkedHashMap<>();
        value.put(DATASET_HISTORICAL_UNIVERSE, endpoint(provider, DATASET_HISTORICAL_UNIVERSE));
        value.put(DATASET_DAILY_NONE, endpoint(provider, DATASET_DAILY_NONE));
        value.put(DATASET_DAILY_QFQ, endpoint(provider, DATASET_DAILY_QFQ));
        return value;
    }

    static String endpoint(String provider, String dataset) {
        String code = normalize(provider);
        if ("EASTMONEY".equals(code)) {
            return switch (dataset) {
                case DATASET_HISTORICAL_UNIVERSE -> "EASTMONEY/HISTORICAL_UNIVERSE_UNAVAILABLE";
                case DATASET_DAILY_NONE, DATASET_DAILY_QFQ -> "https://push2his.eastmoney.com/api/qt/stock/kline/get";
                default -> "EASTMONEY/" + dataset;
            };
        }
        if ("SINA_TENCENT".equals(code)) {
            return switch (dataset) {
                case DATASET_HISTORICAL_UNIVERSE -> "SINA_TENCENT/HISTORICAL_UNIVERSE_UNAVAILABLE";
                case DATASET_DAILY_NONE, DATASET_DAILY_QFQ -> "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
                default -> "SINA_TENCENT/" + dataset;
            };
        }
        return code + "/" + dataset;
    }

    private static String healthEndpoint(String dataset) {
        return "HISTORICAL_PREFLIGHT_" + dataset;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
