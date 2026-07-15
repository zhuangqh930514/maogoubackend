package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;
import com.maogou.stock.domain.entity.research.AiSourceObservation;
import com.maogou.stock.domain.entity.research.AiTradingCalendar;
import com.maogou.stock.mapper.research.AiDataBatchMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseSnapshotMapper;
import com.maogou.stock.mapper.research.AiSourceObservationMapper;
import com.maogou.stock.mapper.research.AiTradingCalendarMapper;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.HistoricalUniverseSourceService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class HistoricalUniverseSourceServiceImpl implements HistoricalUniverseSourceService {

    private static final String MARKET_CODE = "CN_A";
    private static final List<String> REQUIRED_STOCK_SOURCES = List.of(
            "STOCK_DAILY_SNAPSHOT",
            "ADJUSTMENT_FACTOR",
            "INDUSTRY_MEMBERSHIP",
            "INDUSTRY_BENCHMARK"
    );

    private final AiTradingCalendarMapper calendarMapper;
    private final AiResearchUniverseSnapshotMapper snapshotMapper;
    private final AiResearchUniverseItemMapper itemMapper;
    private final AiDataBatchMapper batchMapper;
    private final AiSourceObservationMapper observationMapper;

    public HistoricalUniverseSourceServiceImpl(
            AiTradingCalendarMapper calendarMapper,
            AiResearchUniverseSnapshotMapper snapshotMapper,
            AiResearchUniverseItemMapper itemMapper,
            AiDataBatchMapper batchMapper,
            AiSourceObservationMapper observationMapper
    ) {
        this.calendarMapper = calendarMapper;
        this.snapshotMapper = snapshotMapper;
        this.itemMapper = itemMapper;
        this.batchMapper = batchMapper;
        this.observationMapper = observationMapper;
    }

    @Override
    public HistoricalDayEvidence load(LocalDate tradeDate, LocalDateTime asOfTime) {
        if (tradeDate == null || asOfTime == null || !tradeDate.equals(asOfTime.toLocalDate())) {
            throw new IllegalArgumentException("历史来源查询缺少一致的交易日和截止时间");
        }
        List<String> missing = new ArrayList<>();
        AiTradingCalendar calendar = calendar(tradeDate);
        if (calendar == null || blank(calendar.sourceFingerprint) || blank(calendar.sourceName)
                || calendar.sourceAsOf == null || calendar.sourceAsOf.isAfter(asOfTime)
                || mockSource(calendar.sourceName)) {
            return missing(tradeDate, asOfTime, null, null,
                    List.of("缺少当时可见且带来源指纹的历史交易日历"));
        }
        if (!Integer.valueOf(1).equals(calendar.isTradeDay)) {
            return new HistoricalDayEvidence(
                    "NOT_TRADING_DAY", tradeDate, asOfTime, null, null, 0,
                    calendar.sourceFingerprint, List.of());
        }

        AiResearchUniverseSnapshot snapshot = snapshotMapper.selectOne(
                new QueryWrapper<AiResearchUniverseSnapshot>()
                        .eq("trade_date", tradeDate)
                        .le("as_of_time", asOfTime)
                        .eq("status", "FINALIZED")
                        .eq("quality_status", "READY")
                        .orderByDesc("as_of_time", "id")
                        .last("LIMIT 1"));
        if (snapshot == null || snapshot.id == null || blank(snapshot.sourceFingerprint)) {
            return missing(tradeDate, asOfTime, null, null,
                    List.of("缺少当日有效且已固化的历史股票池"));
        }

        List<AiResearchUniverseItem> items = itemMapper.selectList(
                new QueryWrapper<AiResearchUniverseItem>()
                        .eq("universe_snapshot_id", snapshot.id)
                        .eq("included", 1)
                        .orderByAsc("stock_code"));
        if (items == null || items.isEmpty()) {
            missing.add("历史股票池没有可用成分");
        } else {
            for (AiResearchUniverseItem item : items) {
                if (item == null || item.id == null || blank(item.stockCode)
                        || !"LISTED".equals(item.listedStatus)
                        || item.effectiveFrom == null || item.effectiveFrom.isAfter(tradeDate)
                        || blank(item.sourceFingerprint)) {
                    missing.add((item == null || blank(item.stockCode) ? "未知股票" : item.stockCode)
                            + " 缺少当日有效上市状态或成分来源指纹");
                }
            }
        }

        AiDataBatch batch = batchMapper.selectOne(new QueryWrapper<AiDataBatch>()
                .eq("universe_snapshot_id", snapshot.id)
                .eq("trade_date", tradeDate)
                .eq("quality_status", "READY")
                .in("status", List.of("READY", "SUCCESS", "PARTIAL_SUCCESS"))
                .le("as_of_time", asOfTime)
                .orderByDesc("as_of_time", "id")
                .last("LIMIT 1"));
        if (batch == null || batch.id == null) {
            missing.add("缺少当日 READY 历史行情数据批次");
            return missing(tradeDate, asOfTime, snapshot.id, null, missing);
        }

        List<AiSourceObservation> observations = observationMapper.selectList(
                new QueryWrapper<AiSourceObservation>()
                        .eq("data_batch_id", batch.id)
                        .orderByAsc("source_type", "stock_code", "id"));
        Map<String, AiSourceObservation> sourceIndex = new LinkedHashMap<>();
        if (observations != null) {
            for (AiSourceObservation observation : observations) {
                if (!visibleAt(observation, asOfTime)) {
                    missing.add(sourceLabel(observation) + " 晚于历史研究截止时间");
                    continue;
                }
                if (!realReadySource(observation)) {
                    if (requiredSource(observation)) {
                        missing.add(sourceLabel(observation) + " 不是 READY 真实来源证据");
                    }
                    continue;
                }
                sourceIndex.put(sourceKey(observation.sourceType, observation.stockCode), observation);
            }
        }

        require(sourceIndex, "MARKET_BENCHMARK", null, "缺少同期市场基准", missing);
        if (items != null) {
            for (AiResearchUniverseItem item : items) {
                if (item == null || blank(item.stockCode)) {
                    continue;
                }
                for (String sourceType : REQUIRED_STOCK_SOURCES) {
                    String label = switch (sourceType) {
                        case "STOCK_DAILY_SNAPSHOT" -> "未复权日 K";
                        case "ADJUSTMENT_FACTOR" -> "复权因子";
                        case "INDUSTRY_MEMBERSHIP" -> "行业归属";
                        case "INDUSTRY_BENCHMARK" -> "同期行业基准";
                        default -> sourceType;
                    };
                    require(sourceIndex, sourceType, item.stockCode,
                            item.stockCode + " 缺少" + label, missing);
                }
            }
        }
        if (!missing.isEmpty()) {
            return missing(tradeDate, asOfTime, snapshot.id, batch.id, missing);
        }

        String sourceFingerprint = evidenceFingerprint(
                calendar, snapshot, batch, items, sourceIndex.values().stream().toList());
        return new HistoricalDayEvidence(
                "READY", tradeDate, asOfTime, snapshot.id, batch.id,
                items.size(), sourceFingerprint, List.of());
    }

    private AiTradingCalendar calendar(LocalDate tradeDate) {
        List<AiTradingCalendar> values = calendarMapper.selectByDates(
                MARKET_CODE, AiResearchContract.CALENDAR_VERSION, List.of(tradeDate));
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static void require(
            Map<String, AiSourceObservation> index,
            String sourceType,
            String stockCode,
            String message,
            List<String> missing
    ) {
        if (!index.containsKey(sourceKey(sourceType, stockCode))) {
            missing.add(message);
        }
    }

    private static boolean requiredSource(AiSourceObservation observation) {
        return observation != null && ("MARKET_BENCHMARK".equals(observation.sourceType)
                || REQUIRED_STOCK_SOURCES.contains(observation.sourceType));
    }

    private static boolean visibleAt(AiSourceObservation observation, LocalDateTime asOfTime) {
        if (observation == null) {
            return false;
        }
        return notAfter(observation.eventTime, asOfTime)
                && notAfter(observation.publishedAt, asOfTime)
                && notAfter(observation.availableAt, asOfTime)
                && notAfter(observation.asOfTime, asOfTime);
    }

    private static boolean notAfter(LocalDateTime value, LocalDateTime asOfTime) {
        return value == null || !value.isAfter(asOfTime);
    }

    private static boolean realReadySource(AiSourceObservation observation) {
        return observation != null
                && "READY".equals(observation.qualityStatus)
                && !blank(observation.sourceFingerprint)
                && !blank(observation.payloadChecksum)
                && !blank(observation.providerCode)
                && !mockSource(observation.providerCode);
    }

    private static boolean mockSource(String source) {
        String normalized = source == null ? "" : source.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("MOCK") || normalized.contains("DEMO")
                || normalized.contains("FALLBACK") || normalized.contains("SAMPLE");
    }

    private static String sourceKey(String sourceType, String stockCode) {
        return Objects.requireNonNullElse(sourceType, "") + "|" + Objects.requireNonNullElse(stockCode, "");
    }

    private static String sourceLabel(AiSourceObservation observation) {
        if (observation == null) {
            return "未知来源";
        }
        return Objects.requireNonNullElse(observation.stockCode, "全市场")
                + "/" + Objects.requireNonNullElse(observation.sourceType, "UNKNOWN");
    }

    private static HistoricalDayEvidence missing(
            LocalDate tradeDate,
            LocalDateTime asOfTime,
            Long snapshotId,
            Long batchId,
            List<String> missing
    ) {
        return new HistoricalDayEvidence(
                "MISSING_HISTORICAL_UNIVERSE", tradeDate, asOfTime,
                snapshotId, batchId, 0, null, List.copyOf(missing));
    }

    private static String evidenceFingerprint(
            AiTradingCalendar calendar,
            AiResearchUniverseSnapshot snapshot,
            AiDataBatch batch,
            List<AiResearchUniverseItem> items,
            List<AiSourceObservation> observations
    ) {
        List<String> parts = new ArrayList<>();
        parts.add("HISTORICAL_SOURCE/1.0.0");
        parts.add(calendar.sourceFingerprint);
        parts.add(snapshot.sourceFingerprint);
        parts.add(String.valueOf(batch.id));
        items.stream().sorted(Comparator.comparing(item -> item.stockCode))
                .forEach(item -> parts.add(item.sourceFingerprint));
        observations.stream()
                .sorted(Comparator.comparing(
                                (AiSourceObservation value) -> Objects.requireNonNullElse(value.sourceType, ""))
                        .thenComparing(value -> Objects.requireNonNullElse(value.stockCode, "")))
                .forEach(value -> parts.add(value.sourceFingerprint));
        return sha256(String.join("|", parts));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
