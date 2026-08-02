package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;
import com.maogou.stock.domain.entity.research.AiSecurityDailyState;
import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseSnapshotMapper;
import com.maogou.stock.mapper.research.AiSecurityDailyStateMapper;
import com.maogou.stock.service.research.HistoricalUniverseCatalogService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;

/**
 * Historical universe resolver backed by imported vendor facts. Current
 * listing APIs are intentionally absent from this class.
 */
@Service
public class HistoricalUniverseCatalogServiceImpl implements HistoricalUniverseCatalogService {

    private final AiResearchUniverseSnapshotMapper snapshotMapper;
    private final AiResearchUniverseItemMapper itemMapper;
    private final AiSecurityDailyStateMapper stateMapper;

    public HistoricalUniverseCatalogServiceImpl(
            AiResearchUniverseSnapshotMapper snapshotMapper,
            AiResearchUniverseItemMapper itemMapper,
            AiSecurityDailyStateMapper stateMapper
    ) {
        this.snapshotMapper = snapshotMapper;
        this.itemMapper = itemMapper;
        this.stateMapper = stateMapper;
    }

    @Override
    public CatalogPlan load(List<LocalDate> tradeDates, LocalDateTime asOfTime, int targetStockCount) {
        if (tradeDates == null || tradeDates.isEmpty() || asOfTime == null || targetStockCount <= 0) {
            throw new IllegalArgumentException("历史股票池查询缺少交易日、截止时间或目标股票数");
        }
        Map<LocalDate, DayCatalog> byDate = new LinkedHashMap<>();
        Map<String, HistoricalMarketDataProvider.Security> union = new LinkedHashMap<>();
        List<String> blocking = new ArrayList<>();
        for (LocalDate tradeDate : tradeDates.stream().distinct().sorted().toList()) {
            LocalDateTime dayAsOf = tradeDate.atTime(asOfTime.toLocalTime());
            if (!tradeDate.equals(asOfTime.toLocalDate()) && asOfTime.toLocalDate().isBefore(tradeDate)) {
                blocking.add(tradeDate + ": 历史截止时间早于目标交易日");
                continue;
            }
            AiResearchUniverseSnapshot snapshot = snapshotMapper.selectOne(
                    new QueryWrapper<AiResearchUniverseSnapshot>()
                            .eq("trade_date", tradeDate)
                            .le("as_of_time", dayAsOf)
                            .eq("status", "FINALIZED")
                            .eq("quality_status", "READY")
                            .eq("point_in_time_status", "READY")
                            .orderByDesc("source_observed_at", "id")
                            .last("LIMIT 1"));
            if (snapshot == null) {
                blocking.add(tradeDate + ": 缺少 FINALIZED/READY/pointInTime=READY 历史股票池快照");
                continue;
            }
            if (blank(snapshot.membershipSourceName) || blank(snapshot.membershipSourceRevision)
                    || snapshot.sourceObservedAt == null || blank(snapshot.sourceFingerprint)) {
                blocking.add(tradeDate + ": 历史股票池来源版本、观测时间或指纹缺失");
                continue;
            }
            List<AiResearchUniverseItem> items = itemMapper.selectList(
                    new QueryWrapper<AiResearchUniverseItem>()
                            .eq("universe_snapshot_id", snapshot.id)
                            .eq("included", 1)
                            .orderByAsc("stock_code"));
            List<String> itemCodes = (items == null ? List.<AiResearchUniverseItem>of() : items).stream()
                    .filter(Objects::nonNull)
                    .map(item -> item.stockCode)
                    .filter(code -> code != null && !code.isBlank())
                    .distinct()
                    .toList();
            Map<String, AiSecurityDailyState> statesByCode = new LinkedHashMap<>();
            if (!itemCodes.isEmpty()) {
                List<AiSecurityDailyState> states = stateMapper.selectCurrentForStocksBetween(
                        itemCodes, tradeDate, tradeDate);
                for (AiSecurityDailyState state : states == null ? List.<AiSecurityDailyState>of() : states) {
                    if (state != null && state.stockCode != null) {
                        statesByCode.put(state.stockCode, state);
                    }
                }
            }
            List<AiResearchUniverseItem> eligibleItems = new ArrayList<>();
            for (AiResearchUniverseItem item : items == null ? List.<AiResearchUniverseItem>of() : items) {
                if (!validHistoricalItem(item, tradeDate)) {
                    continue;
                }
                AiSecurityDailyState state = statesByCode.get(item.stockCode);
                if (!validTradableState(state, tradeDate, item.stockName)) {
                    continue;
                }
                eligibleItems.add(item);
            }
            if (eligibleItems.size() < targetStockCount) {
                blocking.add(tradeDate + ": 历史股票池真实可执行成分不足，需 "
                        + targetStockCount + "，实际 " + eligibleItems.size());
                continue;
            }
            eligibleItems.sort(Comparator.comparing(item -> sha256(item.stockCode)));
            List<AiResearchUniverseItem> selected = eligibleItems.stream()
                    .limit(Math.max(targetStockCount, Math.min(eligibleItems.size(), targetStockCount * 2L)))
                    .toList();
            List<HistoricalMarketDataProvider.Security> securities = selected.stream()
                    .map(item -> new HistoricalMarketDataProvider.Security(
                            item.stockCode, item.stockName, item.market, item.effectiveFrom))
                    .toList();
            DayCatalog day = new DayCatalog(
                    tradeDate, snapshot.id, snapshot.membershipSourceName,
                    snapshot.membershipSourceRevision, snapshot.sourceObservedAt,
                    snapshotFingerprint(snapshot, selected), selected, securities);
            byDate.put(tradeDate, day);
            for (HistoricalMarketDataProvider.Security security : securities) {
                union.merge(security.stockCode(), security, HistoricalUniverseCatalogServiceImpl::mergeSecurity);
            }
        }
        if (!blocking.isEmpty()) {
            throw new IllegalStateException("历史股票池无法用于正式回放：" + String.join("；", blocking));
        }
        String fingerprint = sha256(byDate.values().stream()
                .sorted(Comparator.comparing(DayCatalog::tradeDate))
                .map(DayCatalog::sourceFingerprint)
                .reduce("HISTORICAL_UNIVERSE_PLAN/1.0.0", (left, right) -> left + "|" + right));
        return new CatalogPlan(byDate, union.values().stream()
                .sorted(Comparator.comparing(HistoricalMarketDataProvider.Security::stockCode))
                .toList(), fingerprint);
    }

    private static HistoricalMarketDataProvider.Security mergeSecurity(
            HistoricalMarketDataProvider.Security first,
            HistoricalMarketDataProvider.Security second
    ) {
        LocalDate listedOn = first.listedOn() == null ? second.listedOn()
                : second.listedOn() == null ? first.listedOn()
                : first.listedOn().isBefore(second.listedOn()) ? first.listedOn() : second.listedOn();
        return new HistoricalMarketDataProvider.Security(
                first.stockCode(),
                second.stockName() == null || second.stockName().isBlank() ? first.stockName() : second.stockName(),
                second.market() == null || second.market().isBlank() ? first.market() : second.market(),
                listedOn);
    }

    private static boolean validHistoricalItem(AiResearchUniverseItem item, LocalDate tradeDate) {
        if (item == null || item.id == null || blank(item.stockCode) || !item.stockCode.matches("[036]\\d{5}")) {
            return false;
        }
        String sourceType = upper(item.sourceType);
        return Integer.valueOf(1).equals(item.included)
                && "LISTED".equals(upper(item.listedStatus))
                && item.effectiveFrom != null && !item.effectiveFrom.isAfter(tradeDate)
                && (item.effectiveTo == null || !item.effectiveTo.isBefore(tradeDate))
                && !blank(item.stockName) && !blank(item.sourceFingerprint)
                && !blank(item.evidenceJson)
                && sourceType.contains("HISTORICAL")
                && !sourceType.contains("CURRENT");
    }

    private static boolean validTradableState(
            AiSecurityDailyState state,
            LocalDate tradeDate,
            String stockName
    ) {
        if (state == null || !tradeDate.equals(state.tradeDate)
                || !"READY".equals(upper(state.qualityStatus))
                || !Integer.valueOf(1).equals(state.isCurrent)
                || !Integer.valueOf(0).equals(state.isSt)
                || !Integer.valueOf(0).equals(state.suspended)
                || !Integer.valueOf(1).equals(state.buyTradable)
                || blank(state.sourceFingerprint) || blank(state.evidenceJson)) {
            return false;
        }
        String name = upper(stockName);
        return !name.startsWith("ST") && !name.startsWith("*ST")
                && !name.startsWith("PT") && !name.contains("退");
    }

    private static String snapshotFingerprint(
            AiResearchUniverseSnapshot snapshot,
            List<AiResearchUniverseItem> items
    ) {
        return sha256(snapshot.sourceFingerprint + "|" + snapshot.membershipSourceRevision + "|"
                + items.stream().map(item -> item.stockCode + ":" + item.sourceFingerprint)
                .sorted().reduce("", (left, right) -> left + "|" + right));
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
