package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.AiFactorDefinition;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.mapper.research.AiFactorValueMapper;
import com.maogou.stock.service.research.AiFactorEngine;
import com.maogou.stock.service.research.AiResearchContract;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

@Service
public class AiFactorEngineImpl implements AiFactorEngine {

    static final String FACTOR_VERSION = AiResearchContract.FACTOR_VERSION;
    private static final int SCALE = 8;
    private static final int INSERT_BATCH_SIZE = 200;
    private static final MathContext MATH_CONTEXT = new MathContext(16, RoundingMode.HALF_UP);

    private final AiFactorValueMapper factorMapper;

    public AiFactorEngineImpl(AiFactorValueMapper factorMapper) {
        this.factorMapper = factorMapper;
    }

    @Override
    public List<AiFactorValue> compute(AiSample sample, StockDetailResponse detail) {
        return compute(new FactorContext(sample, detail, List.of(), List.of(), null, null));
    }

    @Override
    public List<AiFactorValue> findStoredForSamples(List<Long> sampleIds) {
        if (sampleIds == null || sampleIds.isEmpty()) {
            return List.of();
        }
        return List.copyOf(factorMapper.selectBySamples(sampleIds, FACTOR_VERSION));
    }

    @Override
    public List<AiFactorValue> compute(FactorContext context) {
        if (context == null) {
            throw new IllegalArgumentException("V2 因子上下文不能为空");
        }
        AiSample sample = context.sample();
        StockDetailResponse detail = context.detail();
        if (sample == null || sample.id == null || sample.asOfTime == null) {
            throw new IllegalArgumentException("V2 因子计算需要已固化且包含 asOfTime 的样本");
        }

        List<KlinePointResponse> klines = context.stockSeries() == null
                ? pointInTimeKlines(detail, sample.asOfTime)
                : pointInTimeKlines(context.stockSeries().points(), sample.asOfTime);
        FinanceSnapshotResponse finance = detail == null ? null : detail.finance();
        FinanceAvailability valuationAvailability = valuationAvailability(finance, sample.asOfTime);
        FinanceAvailability reportAvailability = reportAvailability(finance, sample.asOfTime);
        List<AiFactorValue> computed = new ArrayList<>();

        computed.add(returnFactor(sample, "MOMENTUM_RETURN_3D", "MOMENTUM", klines, 3));
        computed.add(returnFactor(sample, "MOMENTUM_RETURN_5D", "MOMENTUM", klines, 5));
        computed.add(movingAverageDistance(sample, "TREND_MA5_DISTANCE", klines, 5));
        computed.add(movingAverageDistance(sample, "TREND_MA20_DISTANCE", klines, 20));
        computed.add(volumeRatio(sample, klines, 5));
        computed.add(volatility(sample, klines, 10));
        computed.add(averageAmount(sample, klines, 5));

        computed.add(financeFactor(sample, "FUNDAMENTAL_PE", valuationAvailability, finance,
                FinanceSnapshotResponse::pe, "估值", false));
        computed.add(financeFactor(sample, "FUNDAMENTAL_PB", valuationAvailability, finance,
                FinanceSnapshotResponse::pb, "估值", false));
        computed.add(financeFactor(sample, "FUNDAMENTAL_ROE", reportAvailability, finance,
                FinanceSnapshotResponse::roe, "盈利质量", false));
        computed.add(financeFactor(sample, "FUNDAMENTAL_REVENUE_GROWTH", reportAvailability, finance,
                FinanceSnapshotResponse::revenueGrowth, "营收增长", false));
        computed.add(financeFactor(sample, "FUNDAMENTAL_PROFIT_GROWTH", reportAvailability, finance,
                FinanceSnapshotResponse::profitGrowth, "利润增长", false));
        computed.add(financeFactor(sample, "FUNDAMENTAL_DEBT_RATIO", reportAvailability, finance,
                FinanceSnapshotResponse::debtRatio, "负债约束", false));

        computed.add(relativeStrength(
                sample,
                "MARKET_RELATIVE_STRENGTH",
                "MARKET",
                klines,
                pointInTimeKlines(
                        context.marketSeries() == null ? context.marketKlines() : context.marketSeries().points(),
                        sample.asOfTime),
                "同期市场基准数据"
        ));
        computed.add(relativeStrength(
                sample,
                "SECTOR_RELATIVE_STRENGTH",
                "SECTOR",
                klines,
                pointInTimeKlines(
                        context.sectorSeries() == null ? context.sectorKlines() : context.sectorSeries().points(),
                        sample.asOfTime),
                "同期板块基准数据"
        ));
        computed.add(newsSentiment(sample, context.newsSentiment(), context.newsAsOfTime()));
        String evidenceFingerprint = sourceEvidenceFingerprint(context);
        computed.forEach(value -> value.sourceEvidenceFingerprint = evidenceFingerprint);
        return computed;
    }

    @Override
    public List<AiFactorValue> normalizeCrossSection(List<AiFactorValue> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        Map<String, List<AiFactorValue>> groups = new LinkedHashMap<>();
        for (AiFactorValue value : values) {
            String key = value.factorCode
                    + "@" + (value.factorVersion == null ? FACTOR_VERSION : value.factorVersion)
                    + "@" + (value.crossSectionKey == null ? "UNKNOWN_SECTION" : value.crossSectionKey)
                    + "@" + (value.calculatedAt == null ? "UNKNOWN_TIME" : value.calculatedAt);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
        }

        for (List<AiFactorValue> group : groups.values()) {
            List<BigDecimal> available = group.stream()
                    .filter(value -> value.missing == null || value.missing == 0)
                    .map(value -> value.rawValue)
                    .filter(value -> value != null)
                    .sorted()
                    .toList();
            if (available.isEmpty()) {
                continue;
            }

            BigDecimal lower = percentile(available, 0.05d);
            BigDecimal upper = percentile(available, 0.95d);
            List<BigDecimal> winsorized = available.stream()
                    .map(value -> clamp(value, lower, upper))
                    .toList();
            BigDecimal mean = winsorized.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(winsorized.size()), MATH_CONTEXT);
            BigDecimal variance = winsorized.stream()
                    .map(value -> value.subtract(mean).pow(2, MATH_CONTEXT))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(winsorized.size()), MATH_CONTEXT);
            double standardDeviation = Math.sqrt(variance.max(BigDecimal.ZERO).doubleValue());

            for (AiFactorValue value : group) {
                if (value.rawValue == null || (value.missing != null && value.missing == 1)) {
                    value.normalizedValue = null;
                    continue;
                }
                BigDecimal bounded = clamp(value.rawValue, lower, upper);
                value.normalizedValue = standardDeviation == 0d
                        ? BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP)
                        : bounded.subtract(mean)
                        .divide(BigDecimal.valueOf(standardDeviation), SCALE, RoundingMode.HALF_UP);
            }
        }
        return values;
    }

    @Override
    @Transactional
    public List<AiFactorValue> normalizeAndStoreCrossSection(List<AiFactorValue> values) {
        List<AiFactorValue> normalized = normalizeCrossSection(values);
        Map<String, AiFactorDefinition> definitions = enabledDefinitions();
        for (AiFactorValue value : normalized) {
            bindDefinition(value, definitions);
            prepareImmutableValue(value);
        }
        return persistInBatches(normalized);
    }

    @Override
    @Transactional
    public List<AiFactorValue> computeAndStoreCrossSection(List<FactorContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return List.of();
        }
        contexts.forEach(AiFactorEngineImpl::validateProductionContext);
        List<AiFactorValue> values = contexts.stream()
                .flatMap(context -> compute(context).stream())
                .toList();
        return normalizeAndStoreCrossSection(new ArrayList<>(values));
    }

    private List<AiFactorValue> persistInBatches(List<AiFactorValue> normalized) {
        List<AiFactorValue> persistedInInputOrder = new ArrayList<>(normalized.size());
        Map<String, List<AiFactorValue>> byVersion = new LinkedHashMap<>();
        for (AiFactorValue value : normalized) {
            byVersion.computeIfAbsent(value.factorVersion, ignored -> new ArrayList<>()).add(value);
        }
        for (Map.Entry<String, List<AiFactorValue>> entry : byVersion.entrySet()) {
            List<AiFactorValue> versionValues = entry.getValue();
            for (int offset = 0; offset < versionValues.size(); offset += INSERT_BATCH_SIZE) {
                List<AiFactorValue> batch = new ArrayList<>(versionValues.subList(
                        offset, Math.min(offset + INSERT_BATCH_SIZE, versionValues.size())));
                ensureUniqueBusinessKeys(batch);
                factorMapper.insertBatchImmutable(batch);
                List<Long> sampleIds = new ArrayList<>(new LinkedHashSet<>(
                        batch.stream().map(value -> value.sampleId).toList()));
                List<AiFactorValue> persisted = factorMapper.selectBySamplesForShare(sampleIds, entry.getKey());
                Map<String, AiFactorValue> persistedByKey = new HashMap<>();
                for (AiFactorValue value : persisted) {
                    persistedByKey.put(businessKey(value), value);
                }
                for (AiFactorValue expected : batch) {
                    AiFactorValue actual = persistedByKey.get(businessKey(expected));
                    if (actual == null) {
                        throw new IllegalStateException("因子批量写入后未读取到持久化记录：" + businessKey(expected));
                    }
                    if (!Objects.equals(expected.inputFingerprint, actual.inputFingerprint)) {
                        throw new IllegalStateException("不可变因子冲突：" + businessKey(expected));
                    }
                    persistedInInputOrder.add(actual);
                }
            }
        }
        return persistedInInputOrder;
    }

    private static void prepareImmutableValue(AiFactorValue value) {
        if (value.sampleId == null || value.factorDefinitionId == null
                || value.factorCode == null || value.factorGroup == null || value.calculatedAt == null) {
            throw new IllegalArgumentException("因子缺少不可变写入必需字段");
        }
        if (value.factorVersion == null || value.factorVersion.isBlank()) {
            value.factorVersion = FACTOR_VERSION;
        }
        if (value.createdAt == null) {
            value.createdAt = LocalDateTime.now();
        }
        value.evidenceJson = evidenceJson(value.evidence);
        value.inputFingerprint = sha256(String.join("|",
                String.valueOf(value.sampleId),
                String.valueOf(value.factorDefinitionId),
                value.stockCode,
                value.factorCode,
                value.factorVersion,
                value.factorGroup,
                String.valueOf(value.direction),
                decimalText(value.rawValue),
                decimalText(value.normalizedValue),
                String.valueOf(value.missing),
                String.valueOf(value.missingReason),
                String.valueOf(value.evidenceJson),
                String.valueOf(value.sourceEvidenceFingerprint),
                String.valueOf(value.calculatedAt)));
    }

    private static void ensureUniqueBusinessKeys(List<AiFactorValue> values) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (AiFactorValue value : values) {
            if (!keys.add(businessKey(value))) {
                throw new IllegalArgumentException("同一批次包含重复因子：" + businessKey(value));
            }
        }
    }

    private static String businessKey(AiFactorValue value) {
        return value.sampleId + "|" + value.factorDefinitionId;
    }

    private Map<String, AiFactorDefinition> enabledDefinitions() {
        List<AiFactorDefinition> definitions = factorMapper.selectEnabledDefinitions(FACTOR_VERSION);
        Map<String, AiFactorDefinition> byCode = new LinkedHashMap<>();
        for (AiFactorDefinition definition : definitions) {
            if (definition == null || definition.id == null || definition.factorCode == null) {
                throw new IllegalStateException("正式因子定义存在无效记录");
            }
            AiFactorDefinition previous = byCode.put(definition.factorCode, definition);
            if (previous != null) {
                throw new IllegalStateException("正式因子定义重复：" + definition.factorCode);
            }
        }
        return byCode;
    }

    private static void bindDefinition(
            AiFactorValue value,
            Map<String, AiFactorDefinition> definitions
    ) {
        AiFactorDefinition definition = definitions.get(value.factorCode);
        if (definition == null || !FACTOR_VERSION.equals(definition.versionNo)) {
            throw new IllegalStateException("未注册或版本不匹配的正式因子：" + value.factorCode);
        }
        value.factorDefinitionId = definition.id;
        value.factorVersion = definition.versionNo;
        value.factorGroup = definition.factorGroup;
        value.direction = definition.direction;
    }

    private static String evidenceJson(String evidence) {
        if (evidence == null) {
            return null;
        }
        return "{\"summary\":\"" + evidence
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "\"}";
    }

    private static String decimalText(BigDecimal value) {
        return value == null ? "<null>" : value.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static void validateProductionContext(FactorContext context) {
        if (context == null || context.sample() == null) {
            throw new IllegalArgumentException("生产因子上下文和样本不能为空");
        }
        validateSeries(
                context.stockSeries(), context.sample().asOfTime, "个股", context.sample().stockCode, true);
        if (context.marketSeries() == null
                && context.marketKlines() != null && !context.marketKlines().isEmpty()) {
            throw new IllegalArgumentException("市场因子禁止使用未版本化的原始 K 线列表");
        }
        if (context.sectorSeries() == null
                && context.sectorKlines() != null && !context.sectorKlines().isEmpty()) {
            throw new IllegalArgumentException("板块因子禁止使用未版本化的原始 K 线列表");
        }
        if (context.marketSeries() != null) {
            validateSeries(context.marketSeries(), context.sample().asOfTime, "市场", null, true);
        }
        if (context.sectorSeries() != null) {
            validateSeries(context.sectorSeries(), context.sample().asOfTime, "板块", null, true);
        }
    }

    private static void validateSeries(
            KlineSeriesSnapshot series,
            LocalDateTime sampleAsOfTime,
            String name,
            String expectedSymbol,
            boolean requireDaily
    ) {
        if (series == null
                || !"NONE".equalsIgnoreCase(series.adjustmentMode())
                || !series.fingerprintMatches()) {
            throw new IllegalArgumentException(name + "因子必须使用带指纹的时点化未复权 K 线");
        }
        if (series.asOfTime() == null || sampleAsOfTime == null || !series.asOfTime().equals(sampleAsOfTime)) {
            throw new IllegalArgumentException(name + "K 线时点必须与样本时点一致");
        }
        if (!realFinanceSource(series.source())) {
            throw new IllegalArgumentException(name + "K 线来源不可验证");
        }
        if (requireDaily && !("day".equalsIgnoreCase(series.period()) || "daily".equalsIgnoreCase(series.period()))) {
            throw new IllegalArgumentException(name + "因子只允许使用日线周期");
        }
        if (expectedSymbol != null
                && !normalizedSecurityCode(expectedSymbol).equals(normalizedSecurityCode(series.symbol()))) {
            throw new IllegalArgumentException(name + "K 线股票代码与样本不一致");
        }
    }

    private static String normalizedSecurityCode(String value) {
        if (value == null) {
            return "";
        }
        String digits = value.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(digits.length() - 6) : digits;
    }

    private static String sourceEvidenceFingerprint(FactorContext context) {
        return String.join("|",
                context.sample() == null ? "" : String.valueOf(context.sample().sourceFingerprint),
                context.stockSeries() == null ? "" : String.valueOf(context.stockSeries().sourceFingerprint()),
                context.marketSeries() == null ? "" : String.valueOf(context.marketSeries().sourceFingerprint()),
                context.sectorSeries() == null ? "" : String.valueOf(context.sectorSeries().sourceFingerprint()),
                context.newsSourceFingerprint() == null ? "" : context.newsSourceFingerprint());
    }

    private static List<KlinePointResponse> pointInTimeKlines(StockDetailResponse detail, LocalDateTime asOfTime) {
        if (detail == null || detail.kline() == null) {
            return List.of();
        }
        return pointInTimeKlines(detail.kline(), asOfTime);
    }

    private static List<KlinePointResponse> pointInTimeKlines(
            List<KlinePointResponse> source,
            LocalDateTime asOfTime
    ) {
        if (source == null) {
            return List.of();
        }
        LocalDate asOfDate = asOfTime.toLocalDate();
        boolean closingBarAvailable = !asOfTime.toLocalTime().isBefore(LocalTime.of(15, 5));
        return source.stream()
                .filter(point -> point != null && point.tradeDate() != null)
                .filter(point -> point.tradeDate().isBefore(asOfDate)
                        || (closingBarAvailable && point.tradeDate().isEqual(asOfDate)))
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                .toList();
    }

    private static AiFactorValue relativeStrength(
            AiSample sample,
            String code,
            String group,
            List<KlinePointResponse> stockKlines,
            List<KlinePointResponse> benchmarkKlines,
            String evidence
    ) {
        if (!sameTrailingTradingDates(stockKlines, benchmarkKlines, 4)) {
            return missing(sample, code, group, evidence + "未与个股交易日严格对齐");
        }
        BigDecimal stockReturn = returnValue(stockKlines, 3);
        BigDecimal benchmarkReturn = returnValue(benchmarkKlines, 3);
        if (stockReturn == null || benchmarkReturn == null) {
            return missing(sample, code, group, "样本未包含足够的" + evidence);
        }
        return available(sample, code, group, stockReturn.subtract(benchmarkReturn),
                "个股 3 日收益率减" + evidence + " 3 日收益率");
    }

    private static boolean sameTrailingTradingDates(
            List<KlinePointResponse> stockKlines,
            List<KlinePointResponse> benchmarkKlines,
            int count
    ) {
        if (stockKlines == null || benchmarkKlines == null
                || stockKlines.size() < count || benchmarkKlines.size() < count) {
            return false;
        }
        List<LocalDate> stockDates = stockKlines.subList(stockKlines.size() - count, stockKlines.size())
                .stream().map(KlinePointResponse::tradeDate).toList();
        List<LocalDate> benchmarkDates = benchmarkKlines
                .subList(benchmarkKlines.size() - count, benchmarkKlines.size())
                .stream().map(KlinePointResponse::tradeDate).toList();
        return stockDates.equals(benchmarkDates);
    }

    private static BigDecimal returnValue(List<KlinePointResponse> klines, int intervalCount) {
        if (klines == null || klines.size() <= intervalCount) {
            return null;
        }
        BigDecimal latest = close(klines.get(klines.size() - 1));
        BigDecimal base = close(klines.get(klines.size() - 1 - intervalCount));
        if (latest == null || base == null || base.signum() == 0) {
            return null;
        }
        return latest.divide(base, MATH_CONTEXT).subtract(BigDecimal.ONE);
    }

    private static AiFactorValue newsSentiment(
            AiSample sample,
            BigDecimal sentiment,
            LocalDateTime newsAsOfTime
    ) {
        if (sentiment == null || newsAsOfTime == null) {
            return missing(sample, "NEWS_SENTIMENT", "NEWS", "样本未包含时点化资讯情绪数据");
        }
        if (newsAsOfTime.isAfter(sample.asOfTime)) {
            return missing(sample, "NEWS_SENTIMENT", "NEWS", "资讯情绪晚于样本时点");
        }
        return available(sample, "NEWS_SENTIMENT", "NEWS", sentiment,
                "仅汇总截至样本时点已发布的资讯");
    }

    private static AiFactorValue returnFactor(
            AiSample sample,
            String code,
            String group,
            List<KlinePointResponse> klines,
            int intervalCount
    ) {
        if (klines.size() <= intervalCount) {
            return missing(sample, code, group, "历史 K 线不足 " + (intervalCount + 1) + " 个交易日");
        }
        BigDecimal latest = close(klines.get(klines.size() - 1));
        BigDecimal base = close(klines.get(klines.size() - 1 - intervalCount));
        if (latest == null || base == null || base.signum() == 0) {
            return missing(sample, code, group, "K 线收盘价缺失或无效");
        }
        BigDecimal raw = latest.divide(base, MATH_CONTEXT).subtract(BigDecimal.ONE);
        return available(sample, code, group, raw, "截至样本时点的 " + intervalCount + " 日收益率");
    }

    private static AiFactorValue movingAverageDistance(
            AiSample sample,
            String code,
            List<KlinePointResponse> klines,
            int window
    ) {
        if (klines.size() < window) {
            return missing(sample, code, "TREND", "历史 K 线不足 " + window + " 个交易日");
        }
        List<KlinePointResponse> tail = klines.subList(klines.size() - window, klines.size());
        if (tail.stream().map(AiFactorEngineImpl::close).anyMatch(value -> value == null)) {
            return missing(sample, code, "TREND", "均线窗口存在无效收盘价");
        }
        BigDecimal average = tail.stream().map(AiFactorEngineImpl::close)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(window), MATH_CONTEXT);
        BigDecimal latest = close(tail.get(tail.size() - 1));
        if (average.signum() == 0) {
            return missing(sample, code, "TREND", "均线价格为零");
        }
        return available(sample, code, "TREND", latest.divide(average, MATH_CONTEXT).subtract(BigDecimal.ONE),
                "收盘价相对 MA" + window + " 的偏离");
    }

    private static AiFactorValue volumeRatio(AiSample sample, List<KlinePointResponse> klines, int window) {
        String code = "VOLUME_RATIO_5D";
        if (klines.size() <= window) {
            return missing(sample, code, "VOLUME_PRICE", "成交量历史不足 " + (window + 1) + " 个交易日");
        }
        KlinePointResponse latest = klines.get(klines.size() - 1);
        List<KlinePointResponse> previous = klines.subList(klines.size() - 1 - window, klines.size() - 1);
        if (latest.volume() == null || previous.stream().anyMatch(point -> point.volume() == null)) {
            return missing(sample, code, "VOLUME_PRICE", "成交量数据缺失");
        }
        BigDecimal average = previous.stream().map(point -> BigDecimal.valueOf(point.volume()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(window), MATH_CONTEXT);
        if (average.signum() == 0) {
            return missing(sample, code, "VOLUME_PRICE", "历史平均成交量为零");
        }
        return available(sample, code, "VOLUME_PRICE",
                BigDecimal.valueOf(latest.volume()).divide(average, MATH_CONTEXT), "当日成交量 / 前 5 日均量");
    }

    private static AiFactorValue volatility(AiSample sample, List<KlinePointResponse> klines, int intervalCount) {
        String code = "VOLATILITY_10D";
        if (klines.size() <= intervalCount) {
            return missing(sample, code, "VOLATILITY", "历史 K 线不足 " + (intervalCount + 1) + " 个交易日");
        }
        List<KlinePointResponse> tail = klines.subList(klines.size() - 1 - intervalCount, klines.size());
        List<BigDecimal> returns = new ArrayList<>(intervalCount);
        for (int index = 1; index < tail.size(); index++) {
            BigDecimal previous = close(tail.get(index - 1));
            BigDecimal current = close(tail.get(index));
            if (previous == null || current == null || previous.signum() == 0) {
                return missing(sample, code, "VOLATILITY", "波动率窗口存在无效收盘价");
            }
            returns.add(current.divide(previous, MATH_CONTEXT).subtract(BigDecimal.ONE));
        }
        BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), MATH_CONTEXT);
        BigDecimal variance = returns.stream().map(value -> value.subtract(mean).pow(2, MATH_CONTEXT))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), MATH_CONTEXT);
        return available(sample, code, "VOLATILITY", BigDecimal.valueOf(Math.sqrt(variance.doubleValue())),
                "最近 10 个交易日收益率标准差");
    }

    private static AiFactorValue averageAmount(AiSample sample, List<KlinePointResponse> klines, int window) {
        String code = "LIQUIDITY_AVG_AMOUNT_5D";
        if (klines.size() < window) {
            return missing(sample, code, "TRADING_CONSTRAINT", "成交额历史不足 " + window + " 个交易日");
        }
        List<KlinePointResponse> tail = klines.subList(klines.size() - window, klines.size());
        if (tail.stream().anyMatch(point -> point.amount() == null)) {
            return missing(sample, code, "TRADING_CONSTRAINT", "成交额数据缺失");
        }
        BigDecimal raw = tail.stream().map(KlinePointResponse::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(window), MATH_CONTEXT);
        return available(sample, code, "TRADING_CONSTRAINT", raw, "最近 5 个交易日平均成交额");
    }

    private static AiFactorValue financeFactor(
            AiSample sample,
            String code,
            FinanceAvailability financeAvailability,
            FinanceSnapshotResponse finance,
            Function<FinanceSnapshotResponse, BigDecimal> extractor,
            String evidence,
            boolean requirePositive
    ) {
        if (!financeAvailability.available()) {
            return missing(sample, code, "FUNDAMENTAL", financeAvailability.reason());
        }
        BigDecimal value = extractor.apply(finance);
        if (value == null || (requirePositive && value.signum() <= 0)) {
            return missing(sample, code, "FUNDAMENTAL", "财务字段缺失或无效：" + evidence);
        }
        return available(sample, code, "FUNDAMENTAL", value, evidence + "（截至样本时点）");
    }

    private static FinanceAvailability valuationAvailability(
            FinanceSnapshotResponse finance,
            LocalDateTime sampleAsOfTime
    ) {
        if (finance == null) {
            return new FinanceAvailability(false, "财务估值快照缺失或为空");
        }
        if (finance.fetchedAt() == null) {
            return new FinanceAvailability(false, "财务估值快照缺少采集时点，无法验证样本时点");
        }
        if (finance.fetchedAt().isAfter(sampleAsOfTime)) {
            return new FinanceAvailability(false, "财务估值快照晚于样本时点，已拒绝使用");
        }
        if (!realFinanceSource(finance.source())) {
            return new FinanceAvailability(false, "财务估值快照来源不可验证");
        }
        return new FinanceAvailability(true, null);
    }

    private static FinanceAvailability reportAvailability(
            FinanceSnapshotResponse finance,
            LocalDateTime sampleAsOfTime
    ) {
        if (finance == null) {
            return new FinanceAvailability(false, "财务快照缺失或为空");
        }
        if (finance.reportDate() == null || finance.publishedAt() == null) {
            return new FinanceAvailability(false, "财务快照缺少报告期或发布日期，无法验证样本时点");
        }
        if (finance.reportDate().isAfter(sampleAsOfTime.toLocalDate())
                || finance.publishedAt().isAfter(sampleAsOfTime)) {
            return new FinanceAvailability(false, "财务快照晚于样本时点，已拒绝使用");
        }
        if (!realFinanceSource(finance.source())) {
            return new FinanceAvailability(false, "财务快照来源不可验证");
        }
        return new FinanceAvailability(true, null);
    }

    private static boolean realFinanceSource(String source) {
        String normalized = source == null ? "" : source.trim().toUpperCase();
        return !normalized.isBlank() && !"MOCK".equals(normalized) && !"LOCAL_FALLBACK".equals(normalized);
    }

    private static BigDecimal close(KlinePointResponse point) {
        return point == null ? null : point.close();
    }

    private static AiFactorValue available(
            AiSample sample,
            String code,
            String group,
            BigDecimal rawValue,
            String evidence
    ) {
        AiFactorValue value = base(sample, code, group);
        value.rawValue = rawValue == null ? null : rawValue.setScale(SCALE, RoundingMode.HALF_UP);
        value.missing = 0;
        value.missingReason = null;
        value.direction = direction(rawValue);
        value.evidence = evidence;
        return value;
    }

    private static AiFactorValue missing(AiSample sample, String code, String group, String reason) {
        AiFactorValue value = base(sample, code, group);
        value.rawValue = null;
        value.normalizedValue = null;
        value.missing = 1;
        value.missingReason = reason;
        value.direction = "NEUTRAL";
        value.evidence = reason;
        return value;
    }

    private static AiFactorValue base(AiSample sample, String code, String group) {
        AiFactorValue value = new AiFactorValue();
        value.sampleId = sample.id;
        value.stockCode = sample.stockCode;
        value.factorCode = code;
        value.factorVersion = FACTOR_VERSION;
        value.factorGroup = group;
        value.hit = 0;
        value.calculatedAt = sample.asOfTime == null ? LocalDateTime.now() : sample.asOfTime;
        value.createdAt = LocalDateTime.now();
        value.crossSectionKey = String.join(":",
                String.valueOf(sample.dataBatchId),
                String.valueOf(sample.samplePhase),
                String.valueOf(sample.asOfTime));
        return value;
    }

    private static String direction(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return "NEUTRAL";
        }
        return value.signum() > 0 ? "POSITIVE" : "NEGATIVE";
    }

    private static BigDecimal percentile(List<BigDecimal> sorted, double percentile) {
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double position = percentile * (sorted.size() - 1);
        int lowerIndex = (int) Math.floor(position);
        int upperIndex = (int) Math.ceil(position);
        if (lowerIndex == upperIndex) {
            return sorted.get(lowerIndex);
        }
        BigDecimal weight = BigDecimal.valueOf(position - lowerIndex);
        return sorted.get(lowerIndex).multiply(BigDecimal.ONE.subtract(weight), MATH_CONTEXT)
                .add(sorted.get(upperIndex).multiply(weight, MATH_CONTEXT));
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal lower, BigDecimal upper) {
        return value.max(lower).min(upper);
    }

    private record FinanceAvailability(boolean available, String reason) {
    }
}
