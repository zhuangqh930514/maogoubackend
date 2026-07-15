package com.maogou.stock.infrastructure.market;

import com.maogou.stock.config.AppProperties;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.NewsFlashResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Component
public class ResilientMarketDataClient implements ResearchMarketDataClient {

    private final List<ResearchMarketDataProvider> providers;
    private final MarketSourceHealthRegistry healthRegistry;
    private final AppProperties properties;
    private final Clock clock;
    private final Map<String, CachedValue<?>> lastRealValues = new ConcurrentHashMap<>();

    @Autowired
    public ResilientMarketDataClient(
            List<ResearchMarketDataProvider> providers,
            MarketSourceHealthRegistry healthRegistry,
            AppProperties properties
    ) {
        this(providers, healthRegistry, properties, Clock.systemDefaultZone());
    }

    public ResilientMarketDataClient(
            List<ResearchMarketDataProvider> providers,
            MarketSourceHealthRegistry healthRegistry,
            AppProperties properties,
            Clock clock
    ) {
        this.properties = properties;
        this.clock = clock;
        this.healthRegistry = healthRegistry;
        Map<String, ResearchMarketDataProvider> byCode = new LinkedHashMap<>();
        for (ResearchMarketDataProvider provider : providers == null ? List.<ResearchMarketDataProvider>of() : providers) {
            byCode.put(provider.providerCode().toUpperCase(Locale.ROOT), provider);
        }
        List<ResearchMarketDataProvider> ordered = new ArrayList<>();
        for (String code : properties.getMarket().getResearchProviderOrder().split(",")) {
            ResearchMarketDataProvider provider = byCode.remove(code.trim().toUpperCase(Locale.ROOT));
            if (provider != null) {
                ordered.add(provider);
            }
        }
        ordered.addAll(byCode.values());
        this.providers = List.copyOf(ordered);
    }

    @Override
    public ResearchSourceResult<KlineSeriesSnapshot> fetchKlineAt(
            String symbol,
            String period,
            int limit,
            LocalDateTime asOfTime
    ) {
        if (asOfTime == null) {
            throw new IllegalArgumentException("研究 K 线 asOfTime 不能为空");
        }
        String phase = asOfTime.toLocalTime().isBefore(LocalTime.of(15, 5)) ? "LIVE" : "CLOSE";
        String key = "KLINE|" + normalize(symbol) + "|" + normalize(period) + "|" + limit
                + "|" + asOfTime.toLocalDate() + "|" + phase;
        LocalDateTime now = LocalDateTime.now(clock);
        List<ResearchSourceResult.ProviderAttempt> attempts = new ArrayList<>();
        List<KlineSeriesSnapshot> successes = new ArrayList<>();
        for (ResearchMarketDataProvider provider : availableProviders(
                ResearchMarketDataProvider.ENDPOINT_KLINE, symbol, now)) {
            try {
                KlineSeriesSnapshot series = provider.fetchKlineAt(symbol, period, limit, asOfTime);
                validateSeries(series, symbol, asOfTime);
                String fingerprint = series.sourceFingerprint();
                healthRegistry.recordSuccess(provider.providerCode(), ResearchMarketDataProvider.ENDPOINT_KLINE,
                        fingerprint, now);
                attempts.add(attempt(provider, ResearchMarketDataProvider.ENDPOINT_KLINE,
                        ResearchSourceStatus.REALTIME, now, fingerprint, null));
                successes.add(series);
            } catch (RuntimeException exception) {
                String message = rootMessage(exception);
                healthRegistry.recordFailure(provider.providerCode(), ResearchMarketDataProvider.ENDPOINT_KLINE,
                        message, now);
                attempts.add(attempt(provider, ResearchMarketDataProvider.ENDPOINT_KLINE,
                        ResearchSourceStatus.UNAVAILABLE, now, null, message));
            }
        }
        if (successes.size() > 1 && !consistent(successes)) {
            return new ResearchSourceResult<>(null, ResearchSourceStatus.UNAVAILABLE, "SOURCE_CONFLICT",
                    joinProviders(successes), null, now, null,
                    "东方财富与新浪关键 OHLCV 字段冲突，禁止进入正式样本", attempts);
        }
        if (!successes.isEmpty()) {
            KlineSeriesSnapshot selected = successes.get(0);
            String providers = joinProviders(successes);
            KlineSeriesSnapshot canonical = KlineSeriesSnapshot.create(
                    selected.symbol(), selected.period(), "NONE", providers, selected.asOfTime(), now, selected.points());
            lastRealValues.put(key, new CachedValue<>(canonical, providers, canonical.sourceFingerprint(), now));
            return new ResearchSourceResult<>(canonical, ResearchSourceStatus.REALTIME, "READY", providers,
                    latestTradeTime(canonical), now, canonical.sourceFingerprint(), "实时研究行情可用", attempts);
        }
        return staleOrUnavailable(key, now, attempts, "所有研究行情来源均不可用");
    }

    @Override
    public ResearchSourceResult<ResearchMarketDataProvider.IndustryMembershipData> fetchIndustryAt(
            String stockCode,
            LocalDateTime asOfTime
    ) {
        String key = "INDUSTRY|" + normalize(stockCode) + "|" + asOfTime.toLocalDate();
        return fetchFirst(key, ResearchMarketDataProvider.ENDPOINT_INDUSTRY, asOfTime,
                provider -> provider.fetchIndustryAt(stockCode, asOfTime),
                ResearchMarketDataProvider.IndustryMembershipData::sourceFingerprint,
                "行业归属来源均不可用");
    }

    @Override
    public ResearchSourceResult<List<NewsFlashResponse>> fetchNewsAt(int limit, LocalDateTime asOfTime) {
        String key = "NEWS|" + asOfTime.toLocalDate() + "|" + limit;
        return fetchFirst(key, ResearchMarketDataProvider.ENDPOINT_NEWS, asOfTime,
                provider -> provider.fetchNewsAt(limit, asOfTime),
                value -> Integer.toHexString(Objects.hash(value)),
                "资讯来源均不可用");
    }

    private <T> ResearchSourceResult<T> fetchFirst(
            String key,
            String endpoint,
            LocalDateTime asOfTime,
            Function<ResearchMarketDataProvider, T> loader,
            Function<T, String> fingerprint,
            String unavailableMessage
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<ResearchSourceResult.ProviderAttempt> attempts = new ArrayList<>();
        for (ResearchMarketDataProvider provider : availableProviders(endpoint, null, now)) {
            try {
                T data = loader.apply(provider);
                if (data == null || data instanceof List<?> list && list.isEmpty()) {
                    throw new IllegalStateException("来源返回空数据");
                }
                String responseFingerprint = fingerprint.apply(data);
                healthRegistry.recordSuccess(provider.providerCode(), endpoint, responseFingerprint, now);
                attempts.add(attempt(provider, endpoint, ResearchSourceStatus.REALTIME,
                        now, responseFingerprint, null));
                lastRealValues.put(key, new CachedValue<>(data, provider.providerCode(), responseFingerprint, now));
                return new ResearchSourceResult<>(data, ResearchSourceStatus.REALTIME, "READY",
                        provider.providerCode(), asOfTime, now, responseFingerprint, "实时来源可用", attempts);
            } catch (RuntimeException exception) {
                String message = rootMessage(exception);
                healthRegistry.recordFailure(provider.providerCode(), endpoint, message, now);
                attempts.add(attempt(provider, endpoint, ResearchSourceStatus.UNAVAILABLE,
                        now, null, message));
            }
        }
        return staleOrUnavailable(key, now, attempts, unavailableMessage);
    }

    private List<ResearchMarketDataProvider> availableProviders(
            String endpointType,
            String symbol,
            LocalDateTime now
    ) {
        return providers.stream()
                .filter(provider -> provider.supports(endpointType, symbol))
                .filter(provider -> !healthRegistry.isCoolingDown(provider.providerCode(), endpointType, now))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private <T> ResearchSourceResult<T> staleOrUnavailable(
            String key,
            LocalDateTime now,
            List<ResearchSourceResult.ProviderAttempt> attempts,
            String message
    ) {
        CachedValue<T> cached = (CachedValue<T>) lastRealValues.get(key);
        if (cached != null) {
            return new ResearchSourceResult<>(cached.data(), ResearchSourceStatus.STALE, "STALE",
                    cached.providerCode(), cached.updatedAt(), now, cached.fingerprint(),
                    message + "，仅返回上次真实缓存", attempts);
        }
        return new ResearchSourceResult<>(null, ResearchSourceStatus.UNAVAILABLE, "UNAVAILABLE",
                "UNAVAILABLE", null, now, null, message, attempts);
    }

    private boolean consistent(List<KlineSeriesSnapshot> series) {
        KlineSeriesSnapshot baseline = series.get(0);
        for (int index = 1; index < series.size(); index++) {
            if (!consistent(baseline, series.get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean consistent(KlineSeriesSnapshot left, KlineSeriesSnapshot right) {
        Map<LocalDate, KlinePointResponse> rightByDate = new LinkedHashMap<>();
        right.points().forEach(point -> rightByDate.put(point.tradeDate(), point));
        int compared = 0;
        for (KlinePointResponse leftPoint : left.points()) {
            KlinePointResponse rightPoint = rightByDate.get(leftPoint.tradeDate());
            if (rightPoint == null) {
                continue;
            }
            compared++;
            if (!ohlcValid(leftPoint) || !ohlcValid(rightPoint)
                    || outsideTolerance(leftPoint.open(), rightPoint.open())
                    || outsideTolerance(leftPoint.close(), rightPoint.close())
                    || outsideTolerance(leftPoint.low(), rightPoint.low())
                    || outsideTolerance(leftPoint.high(), rightPoint.high())
                    || volumeOutsideTolerance(leftPoint.volume(), rightPoint.volume())) {
                return false;
            }
        }
        return compared > 0
                && latestDate(left).equals(latestDate(right));
    }

    private boolean outsideTolerance(BigDecimal left, BigDecimal right) {
        if (left == null || right == null || left.signum() <= 0 || right.signum() <= 0) {
            return true;
        }
        BigDecimal base = left.abs().max(right.abs());
        BigDecimal difference = left.subtract(right).abs().divide(base, 8, RoundingMode.HALF_UP);
        return difference.compareTo(properties.getMarket().getSourcePriceTolerancePct()) > 0;
    }

    private boolean volumeOutsideTolerance(Long left, Long right) {
        if (left == null || right == null || left <= 0 || right <= 0) {
            return false;
        }
        BigDecimal leftValue = BigDecimal.valueOf(left);
        BigDecimal rightValue = BigDecimal.valueOf(right);
        BigDecimal difference = leftValue.subtract(rightValue).abs()
                .divide(leftValue.max(rightValue), 8, RoundingMode.HALF_UP);
        return difference.compareTo(properties.getMarket().getSourceVolumeTolerancePct()) > 0;
    }

    private static boolean ohlcValid(KlinePointResponse point) {
        if (point.open() == null || point.close() == null || point.low() == null || point.high() == null) {
            return false;
        }
        BigDecimal max = point.open().max(point.close()).max(point.low());
        BigDecimal min = point.open().min(point.close()).min(point.high());
        return point.high().compareTo(max) >= 0 && point.low().compareTo(min) <= 0;
    }

    private void validateSeries(KlineSeriesSnapshot series, String symbol, LocalDateTime asOfTime) {
        if (series == null || series.points() == null || series.points().isEmpty()
                || !series.fingerprintMatches() || !"NONE".equalsIgnoreCase(series.adjustmentMode())) {
            throw new IllegalStateException("来源未返回可验证的未复权 K 线");
        }
        if (!asOfTime.equals(series.asOfTime())) {
            throw new IllegalStateException("来源 K 线研究时点不一致");
        }
        if (!normalizeSecurityCode(symbol).equals(normalizeSecurityCode(series.symbol()))) {
            throw new IllegalStateException("来源 K 线证券代码不一致");
        }
        if (series.points().stream().anyMatch(point -> point.tradeDate() == null
                || point.tradeDate().isAfter(asOfTime.toLocalDate()) || !ohlcValid(point))) {
            throw new IllegalStateException("来源 K 线包含未来日期或无效 OHLC");
        }
        List<LocalDate> dates = series.points().stream().map(KlinePointResponse::tradeDate).toList();
        if (new HashSet<>(dates).size() != dates.size()
                || !dates.equals(dates.stream().sorted().toList())) {
            throw new IllegalStateException("来源 K 线交易日重复或未按时间排序");
        }
        for (int index = 1; index < series.points().size(); index++) {
            BigDecimal previousClose = series.points().get(index - 1).close();
            BigDecimal close = series.points().get(index).close();
            if (previousClose == null || close == null || previousClose.signum() <= 0) {
                throw new IllegalStateException("来源 K 线前收盘连续性无法验证");
            }
            BigDecimal dailyChange = close.divide(previousClose, 8, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE).abs();
            if (dailyChange.compareTo(properties.getMarket().getSourceMaxDailyChangePct()) > 0) {
                throw new IllegalStateException("来源 K 线涨跌幅超出 A 股合理范围");
            }
        }
    }

    private static LocalDate latestDate(KlineSeriesSnapshot series) {
        return series.points().stream().map(KlinePointResponse::tradeDate).max(Comparator.naturalOrder()).orElseThrow();
    }

    private static LocalDateTime latestTradeTime(KlineSeriesSnapshot series) {
        return latestDate(series).atTime(LocalTime.of(15, 0));
    }

    private static ResearchSourceResult.ProviderAttempt attempt(
            ResearchMarketDataProvider provider,
            String endpoint,
            ResearchSourceStatus status,
            LocalDateTime attemptedAt,
            String fingerprint,
            String error
    ) {
        return new ResearchSourceResult.ProviderAttempt(
                provider.providerCode(), endpoint, status, attemptedAt, fingerprint, error);
    }

    private static String joinProviders(List<KlineSeriesSnapshot> values) {
        return values.stream().map(KlineSeriesSnapshot::source).distinct().sorted().reduce((a, b) -> a + "+" + b)
                .orElse("UNAVAILABLE");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeSecurityCode(String value) {
        String normalized = normalize(value);
        if (normalized.startsWith("BK")) {
            return normalized;
        }
        String digits = normalized.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(digits.length() - 6) : digits;
    }

    private static String rootMessage(Throwable throwable) {
        String summary = throwable.getMessage() == null
                ? throwable.getClass().getSimpleName() : throwable.getMessage();
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String root = current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
        return summary.equals(root) ? summary : summary + "；根因：" + root;
    }

    private record CachedValue<T>(T data, String providerCode, String fingerprint, LocalDateTime updatedAt) {
    }
}
