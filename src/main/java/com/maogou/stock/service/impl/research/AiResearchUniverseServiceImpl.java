package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.domain.entity.research.AiResearchUniverse;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;
import com.maogou.stock.domain.enums.TradeSide;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseSnapshotMapper;
import com.maogou.stock.service.research.AiResearchUniverseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
public class AiResearchUniverseServiceImpl implements AiResearchUniverseService {

    static final String SYSTEM_CORE = "CN_A_SYSTEM_CORE";

    private final AiResearchUniverseMapper universeMapper;
    private final AiResearchUniverseSnapshotMapper snapshotMapper;
    private final AiResearchUniverseItemMapper itemMapper;
    private final WatchStockMapper watchStockMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final ObjectMapper objectMapper;

    public AiResearchUniverseServiceImpl(
            AiResearchUniverseMapper universeMapper,
            AiResearchUniverseSnapshotMapper snapshotMapper,
            AiResearchUniverseItemMapper itemMapper,
            WatchStockMapper watchStockMapper,
            TradeRecordMapper tradeRecordMapper,
            ObjectMapper objectMapper
    ) {
        this.universeMapper = universeMapper;
        this.snapshotMapper = snapshotMapper;
        this.itemMapper = itemMapper;
        this.watchStockMapper = watchStockMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public SnapshotResult createSystemCoreSnapshot(SnapshotRequest request) {
        validate(request);
        AiResearchUniverse universe = loadOrCreateUniverse(request.asOfTime());
        List<MergedCandidate> candidates = mergeCandidates(request);
        String snapshotFingerprint = snapshotFingerprint(request, candidates);

        AiResearchUniverseSnapshot existing = snapshotMapper.selectOne(
                new QueryWrapper<AiResearchUniverseSnapshot>()
                        .eq("research_universe_id", universe.id)
                        .eq("source_fingerprint", snapshotFingerprint)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            List<AiResearchUniverseItem> items = itemMapper.selectList(
                    new QueryWrapper<AiResearchUniverseItem>()
                            .eq("universe_snapshot_id", existing.id)
                            .orderByAsc("stock_code")
            );
            return new SnapshotResult(universe, existing, items, true);
        }

        long revision = snapshotMapper.selectCount(
                new QueryWrapper<AiResearchUniverseSnapshot>()
                        .eq("research_universe_id", universe.id)
                        .eq("trade_date", request.tradeDate())
        ) + 1;
        int includedCount = (int) candidates.stream().filter(MergedCandidate::included).count();
        AiResearchUniverseSnapshot snapshot = new AiResearchUniverseSnapshot();
        snapshot.researchUniverseId = universe.id;
        snapshot.tradeDate = request.tradeDate();
        snapshot.asOfTime = request.asOfTime();
        snapshot.universeVersion = "%s/%s/R%04d".formatted(SYSTEM_CORE, request.tradeDate(), revision);
        snapshot.calendarVersion = request.calendarVersion();
        snapshot.sourceFingerprint = snapshotFingerprint;
        snapshot.itemCount = candidates.size();
        snapshot.qualityStatus = qualityStatus(includedCount, universe.minimumStockCount);
        snapshot.status = "FINALIZED";
        snapshot.createdAt = LocalDateTime.now();
        snapshotMapper.insert(snapshot);

        List<AiResearchUniverseItem> items = new ArrayList<>(candidates.size());
        for (MergedCandidate candidate : candidates) {
            AiResearchUniverseItem item = toItem(snapshot.id, candidate, request.tradeDate());
            itemMapper.insert(item);
            items.add(item);
        }
        return new SnapshotResult(universe, snapshot, items, false);
    }

    private AiResearchUniverse loadOrCreateUniverse(LocalDateTime now) {
        AiResearchUniverse universe = universeMapper.selectOne(
                new QueryWrapper<AiResearchUniverse>()
                        .eq("universe_code", SYSTEM_CORE)
                        .last("LIMIT 1")
        );
        if (universe != null) {
            return universe;
        }
        universe = new AiResearchUniverse();
        universe.universeCode = SYSTEM_CORE;
        universe.universeName = "A股系统研究池";
        universe.marketCode = "CN_A";
        universe.selectionPolicyJson = "{\"selection\":\"CONFIGURED_PLUS_ALL_USER_INTERESTS\",\"pointInTime\":true}";
        universe.minimumStockCount = 200;
        universe.enabled = 1;
        universe.seedVersion = "20260714-unified-1.1";
        universe.createdAt = now;
        universe.updatedAt = now;
        universeMapper.insert(universe);
        return universe;
    }

    private List<MergedCandidate> mergeCandidates(SnapshotRequest request) {
        Map<String, CandidateAccumulator> merged = new LinkedHashMap<>();
        for (UniverseCandidate configured : request.configuredComponents()) {
            add(merged, configured);
        }

        List<WatchStock> watchStocks = watchStockMapper.selectList(new QueryWrapper<WatchStock>());
        for (WatchStock watch : watchStocks) {
            add(merged, new UniverseCandidate(
                    watch.stockCode,
                    watch.stockName,
                    watch.market,
                    "USER_WATCHLIST",
                    true,
                    null,
                    dateOrDefault(watch.createdAt, request.tradeDate())
            ));
        }

        Map<String, Integer> positionQuantity = new LinkedHashMap<>();
        Map<String, TradeRecord> latestTrade = new LinkedHashMap<>();
        for (TradeRecord trade : tradeRecordMapper.selectList(new QueryWrapper<TradeRecord>())) {
            if (trade.stockCode == null || trade.quantity == null) {
                continue;
            }
            String accountPosition = trade.userId + "|" + normalizeCode(trade.stockCode);
            int signedQuantity = trade.side == TradeSide.SELL ? -trade.quantity : trade.quantity;
            positionQuantity.merge(accountPosition, signedQuantity, Integer::sum);
            latestTrade.put(accountPosition, trade);
        }
        for (Map.Entry<String, Integer> position : positionQuantity.entrySet()) {
            if (position.getValue() <= 0) {
                continue;
            }
            TradeRecord trade = latestTrade.get(position.getKey());
            add(merged, new UniverseCandidate(
                    trade.stockCode,
                    trade.stockName,
                    inferMarket(trade.stockCode),
                    "USER_HOLDING",
                    true,
                    null,
                    dateOrDefault(trade.tradedAt, request.tradeDate())
            ));
        }

        return merged.values().stream()
                .map(CandidateAccumulator::build)
                .sorted(Comparator.comparing(MergedCandidate::stockCode))
                .toList();
    }

    private AiResearchUniverseItem toItem(
            Long snapshotId,
            MergedCandidate candidate,
            LocalDate tradeDate
    ) {
        AiResearchUniverseItem item = new AiResearchUniverseItem();
        item.universeSnapshotId = snapshotId;
        item.stockCode = candidate.stockCode();
        item.stockName = candidate.stockName();
        item.market = candidate.market();
        item.listedStatus = "LISTED";
        item.sourceType = String.join(",", candidate.sourceTypes());
        item.included = candidate.included() ? 1 : 0;
        item.inclusionReason = candidate.included()
                ? "由" + String.join("、", candidate.sourceTypes()) + "纳入研究池"
                : null;
        item.excludeReason = candidate.included() ? null : candidate.excludeReason();
        item.effectiveFrom = candidate.effectiveFrom() == null ? tradeDate : candidate.effectiveFrom();
        item.evidenceJson = json(Map.of(
                "sourceTypes", candidate.sourceTypes(),
                "included", candidate.included(),
                "effectiveFrom", item.effectiveFrom.toString(),
                "excludeReason", candidate.excludeReason() == null ? "" : candidate.excludeReason()
        ));
        item.sourceFingerprint = sha256(String.join("|",
                item.stockCode,
                item.sourceType,
                String.valueOf(item.included),
                item.effectiveFrom.toString(),
                item.excludeReason == null ? "" : item.excludeReason
        ));
        item.createdAt = LocalDateTime.now();
        return item;
    }

    private String snapshotFingerprint(SnapshotRequest request, List<MergedCandidate> candidates) {
        String canonicalItems = candidates.stream()
                .map(candidate -> String.join("|",
                        candidate.stockCode(),
                        String.join(",", candidate.sourceTypes()),
                        String.valueOf(candidate.included()),
                        candidate.effectiveFrom() == null ? "" : candidate.effectiveFrom().toString(),
                        candidate.excludeReason() == null ? "" : candidate.excludeReason()
                ))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return sha256(String.join("|",
                SYSTEM_CORE,
                request.tradeDate().toString(),
                request.asOfTime().toString(),
                request.calendarVersion(),
                canonicalItems
        ));
    }

    private static void add(Map<String, CandidateAccumulator> target, UniverseCandidate candidate) {
        if (candidate == null || candidate.stockCode() == null || candidate.stockCode().isBlank()) {
            return;
        }
        String code = normalizeCode(candidate.stockCode());
        target.computeIfAbsent(code, CandidateAccumulator::new).merge(candidate);
    }

    private static String qualityStatus(int includedCount, Integer minimumStockCount) {
        if (includedCount == 0) {
            return "UNAVAILABLE";
        }
        int minimum = minimumStockCount == null ? 200 : minimumStockCount;
        return includedCount >= minimum ? "READY" : "PARTIAL";
    }

    private static LocalDate dateOrDefault(LocalDateTime value, LocalDate fallback) {
        return value == null ? fallback : value.toLocalDate();
    }

    private static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (normalized.startsWith("SH") || normalized.startsWith("SZ") || normalized.startsWith("BJ")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private static String inferMarket(String stockCode) {
        String code = normalizeCode(stockCode);
        if (code.startsWith("6")) {
            return "SH";
        }
        if (code.startsWith("4") || code.startsWith("8")) {
            return "BJ";
        }
        return "SZ";
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化股票池证据", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static void validate(SnapshotRequest request) {
        if (request == null || request.tradeDate() == null || request.asOfTime() == null
                || request.calendarVersion() == null || request.calendarVersion().isBlank()) {
            throw new IllegalArgumentException("股票池快照缺少交易日、截止时间或日历版本");
        }
    }

    private record MergedCandidate(
            String stockCode,
            String stockName,
            String market,
            Set<String> sourceTypes,
            boolean included,
            String excludeReason,
            LocalDate effectiveFrom
    ) {
    }

    private static final class CandidateAccumulator {
        private final String stockCode;
        private String stockName;
        private String market;
        private final Set<String> sourceTypes = new TreeSet<>();
        private boolean included;
        private String excludeReason;
        private LocalDate effectiveFrom;

        private CandidateAccumulator(String stockCode) {
            this.stockCode = stockCode;
        }

        private void merge(UniverseCandidate candidate) {
            if (candidate.stockName() != null && !candidate.stockName().isBlank()) {
                stockName = candidate.stockName().trim();
            }
            if (candidate.market() != null && !candidate.market().isBlank()) {
                market = candidate.market().trim().toUpperCase();
            }
            if (candidate.sourceType() != null && !candidate.sourceType().isBlank()) {
                sourceTypes.add(candidate.sourceType().trim().toUpperCase());
            }
            included = included || candidate.included();
            if (!candidate.included() && candidate.excludeReason() != null && !candidate.excludeReason().isBlank()) {
                excludeReason = candidate.excludeReason().trim();
            }
            if (candidate.effectiveFrom() != null
                    && (effectiveFrom == null || candidate.effectiveFrom().isBefore(effectiveFrom))) {
                effectiveFrom = candidate.effectiveFrom();
            }
        }

        private MergedCandidate build() {
            Set<String> sources = sourceTypes.isEmpty()
                    ? Set.of("CONFIGURED_BASELINE")
                    : new LinkedHashSet<>(sourceTypes);
            return new MergedCandidate(
                    stockCode,
                    stockName == null ? stockCode : stockName,
                    market == null ? inferMarket(stockCode) : market,
                    sources,
                    included,
                    included ? null : excludeReason,
                    effectiveFrom
            );
        }
    }
}
