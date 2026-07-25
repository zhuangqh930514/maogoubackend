package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.domain.entity.research.AiResearchUniverse;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItemLineage;
import com.maogou.stock.domain.entity.research.AiResearchUniverseSnapshot;
import com.maogou.stock.domain.enums.TradeSide;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseItemLineageMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseSnapshotMapper;
import com.maogou.stock.service.research.AiResearchUniverseService;
import com.maogou.stock.service.research.AiSystemCoreUniverseProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

@Service
public class AiResearchUniverseServiceImpl implements AiResearchUniverseService {

    static final String SYSTEM_CORE = "CN_A_SYSTEM_CORE";

    private final AiResearchUniverseMapper universeMapper;
    private final AiResearchUniverseSnapshotMapper snapshotMapper;
    private final AiResearchUniverseItemMapper itemMapper;
    private final AiResearchUniverseItemLineageMapper lineageMapper;
    private final WatchStockMapper watchStockMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final AiSystemCoreUniverseProvider systemCoreUniverseProvider;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiResearchUniverseServiceImpl(
            AiResearchUniverseMapper universeMapper,
            AiResearchUniverseSnapshotMapper snapshotMapper,
            AiResearchUniverseItemMapper itemMapper,
            AiResearchUniverseItemLineageMapper lineageMapper,
            WatchStockMapper watchStockMapper,
            TradeRecordMapper tradeRecordMapper,
            AiSystemCoreUniverseProvider systemCoreUniverseProvider,
            ObjectMapper objectMapper
    ) {
        this.universeMapper = universeMapper;
        this.snapshotMapper = snapshotMapper;
        this.itemMapper = itemMapper;
        this.lineageMapper = lineageMapper;
        this.watchStockMapper = watchStockMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.systemCoreUniverseProvider = systemCoreUniverseProvider;
        this.objectMapper = objectMapper;
    }

    AiResearchUniverseServiceImpl(
            AiResearchUniverseMapper universeMapper,
            AiResearchUniverseSnapshotMapper snapshotMapper,
            AiResearchUniverseItemMapper itemMapper,
            WatchStockMapper watchStockMapper,
            TradeRecordMapper tradeRecordMapper,
            AiSystemCoreUniverseProvider systemCoreUniverseProvider,
            ObjectMapper objectMapper
    ) {
        this(universeMapper, snapshotMapper, itemMapper, null, watchStockMapper, tradeRecordMapper,
                systemCoreUniverseProvider, objectMapper);
    }

    AiResearchUniverseServiceImpl(
            AiResearchUniverseMapper universeMapper,
            AiResearchUniverseSnapshotMapper snapshotMapper,
            AiResearchUniverseItemMapper itemMapper,
            WatchStockMapper watchStockMapper,
            TradeRecordMapper tradeRecordMapper,
            ObjectMapper objectMapper
    ) {
        this(universeMapper, snapshotMapper, itemMapper, null, watchStockMapper, tradeRecordMapper,
                (tradeDate, asOfTime, minimumStockCount) -> List.of(), objectMapper);
    }

    @Override
    @Transactional
    public SnapshotResult createSystemCoreSnapshot(SnapshotRequest request) {
        validate(request);
        AiResearchUniverse universe = loadOrCreateUniverse(request.asOfTime());
        List<MergedCandidate> candidates = mergeCandidates(request, universe);
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
        snapshot.membershipSourceName = normalized(request.membershipSourceName());
        snapshot.membershipSourceRevision = normalized(request.membershipSourceRevision());
        snapshot.sourceObservedAt = request.sourceObservedAt();
        snapshot.pointInTimeStatus = request.pointInTimeStatus().trim().toUpperCase();
        snapshot.pointInTimeReason = normalized(request.pointInTimeReason());
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
            persistLineage(item, candidate, request.asOfTime());
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
        universe.selectionPolicyJson = "{\"selection\":\"SYSTEM_BASELINE_PLUS_ALL_USER_INTERESTS\",\"pointInTime\":true}";
        universe.minimumStockCount = 240;
        universe.enabled = 1;
        universe.seedVersion = "20260714-unified-1.1";
        universe.createdAt = now;
        universe.updatedAt = now;
        universeMapper.insert(universe);
        return universe;
    }

    private List<MergedCandidate> mergeCandidates(SnapshotRequest request, AiResearchUniverse universe) {
        Map<String, CandidateAccumulator> merged = new LinkedHashMap<>();
        List<UniverseCandidate> configuredCandidates = configuredComponents(request, universe);
        for (UniverseCandidate configuredCandidate : configuredCandidates) {
            add(merged, configuredCandidate);
        }

        if (request.includeUserInterests()) {
            List<WatchStock> watchStocks = watchStockMapper.selectList(new QueryWrapper<WatchStock>()
                    .eq("deleted", 0)
                    .le("created_at", request.asOfTime())
                    .orderByAsc("user_id", "stock_code", "id"));
            for (WatchStock watch : watchStocks) {
                if (!active(watch)) {
                    continue;
                }
                add(merged, new UniverseCandidate(
                        watch.stockCode,
                        watch.stockName,
                        watch.market,
                        "USER_WATCHLIST",
                        true,
                        null,
                        dateOrDefault(watch.createdAt, request.tradeDate()),
                        null,
                        "LISTED",
                        "watch_stock:" + watch.id,
                        sourceFingerprint("USER_WATCHLIST", watch.userId, watch.id, watch.stockCode,
                                watch.stockName, watch.createdAt),
                        null, null, null, null,
                        watch.userId, watch.id, true
                ));
            }

            Map<String, Integer> positionQuantity = new LinkedHashMap<>();
            Map<String, List<TradeRecord>> positionTrades = new LinkedHashMap<>();
            for (TradeRecord trade : tradeRecordMapper.selectList(new QueryWrapper<TradeRecord>()
                    .eq("deleted", 0)
                    .le("traded_at", request.asOfTime())
                    .orderByAsc("user_id", "stock_code", "traded_at", "id"))) {
                if (!active(trade) || trade.userId == null || trade.stockCode == null || trade.quantity == null) {
                    continue;
                }
                String accountPosition = trade.userId + "|" + normalizeCode(trade.stockCode);
                int signedQuantity = trade.side == TradeSide.SELL ? -trade.quantity : trade.quantity;
                positionQuantity.merge(accountPosition, signedQuantity, Integer::sum);
                positionTrades.computeIfAbsent(accountPosition, ignored -> new ArrayList<>()).add(trade);
            }
            for (Map.Entry<String, Integer> position : positionQuantity.entrySet()) {
                if (position.getValue() <= 0) {
                    continue;
                }
                for (TradeRecord trade : positionTrades.getOrDefault(position.getKey(), List.of())) {
                    add(merged, new UniverseCandidate(
                            trade.stockCode,
                            trade.stockName,
                            inferMarket(trade.stockCode),
                            "USER_HOLDING",
                            true,
                            null,
                            dateOrDefault(trade.tradedAt, request.tradeDate()),
                            null,
                            "LISTED",
                            "trade_record:" + trade.id,
                            sourceFingerprint("USER_HOLDING", trade.userId, trade.id, trade.stockCode,
                                    trade.stockName, trade.tradedAt),
                            null, null, null, null,
                            trade.userId, trade.id, true
                    ));
                }
            }
        }

        return merged.values().stream()
                .map(CandidateAccumulator::build)
                .sorted(Comparator.comparing(MergedCandidate::stockCode))
                .toList();
    }

    private List<UniverseCandidate> configuredComponents(SnapshotRequest request, AiResearchUniverse universe) {
        if (request.configuredComponents() != null && !request.configuredComponents().isEmpty()) {
            return request.configuredComponents();
        }
        if (!request.includeUserInterests()) {
            return List.of();
        }
        return systemCoreUniverseProvider.baselineCandidates(
                request.tradeDate(), request.asOfTime(), universe.minimumStockCount);
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
        item.industryCode = candidate.industryCode();
        item.industryName = candidate.industryName();
        item.industryStandard = candidate.industryStandard();
        item.listedStatus = candidate.listedStatus();
        item.sourceType = String.join(",", candidate.sourceTypes());
        String exclusionReason = exclusionReason(candidate, tradeDate);
        boolean effective = candidate.included()
                && "LISTED".equals(candidate.listedStatus())
                && (candidate.effectiveFrom() == null || !candidate.effectiveFrom().isAfter(tradeDate))
                && (candidate.effectiveTo() == null || !candidate.effectiveTo().isBefore(tradeDate))
                && exclusionReason == null;
        item.included = effective ? 1 : 0;
        item.inclusionReason = effective
                ? "由" + String.join("、", candidate.sourceTypes()) + "纳入研究池"
                : null;
        item.excludeReason = effective ? null : exclusionReason;
        item.effectiveFrom = candidate.effectiveFrom() == null ? tradeDate : candidate.effectiveFrom();
        item.effectiveTo = candidate.effectiveTo();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sourceTypes", candidate.sourceTypes());
        evidence.put("sourceReferences", candidate.sourceReferences());
        evidence.put("sourceEvidenceFingerprints", candidate.sourceEvidenceFingerprints());
        evidence.put("industryCode", item.industryCode == null ? "" : item.industryCode);
        evidence.put("industryName", item.industryName == null ? "" : item.industryName);
        evidence.put("industryStandard", item.industryStandard == null ? "" : item.industryStandard);
        evidence.put("industryEvidenceFingerprints", candidate.industryEvidenceFingerprints());
        evidence.put("included", effective);
        evidence.put("listedStatus", item.listedStatus);
        evidence.put("effectiveFrom", item.effectiveFrom.toString());
        evidence.put("effectiveTo", item.effectiveTo == null ? "" : item.effectiveTo.toString());
        evidence.put("excludeReason", item.excludeReason == null ? "" : item.excludeReason);
        item.evidenceJson = json(evidence);
        item.sourceFingerprint = sha256(String.join("|",
                item.stockCode,
                item.stockName == null ? "" : item.stockName,
                item.market == null ? "" : item.market,
                item.industryCode == null ? "" : item.industryCode,
                item.industryName == null ? "" : item.industryName,
                item.industryStandard == null ? "" : item.industryStandard,
                item.sourceType,
                String.valueOf(item.included),
                item.listedStatus,
                item.effectiveFrom.toString(),
                item.effectiveTo == null ? "" : item.effectiveTo.toString(),
                String.join(",", candidate.sourceReferences()),
                String.join(",", candidate.sourceEvidenceFingerprints()),
                String.join(",", candidate.industryEvidenceFingerprints()),
                item.excludeReason == null ? "" : item.excludeReason
        ));
        item.createdAt = LocalDateTime.now();
        return item;
    }

    private void persistLineage(AiResearchUniverseItem item, MergedCandidate candidate, LocalDateTime observedAt) {
        if (lineageMapper == null || item == null || item.id == null) {
            return;
        }
        for (SourceLineage source : candidate.sourceLineages()) {
            if (source.ownerUserId() == null || source.sourceRecordId() == null) {
                continue;
            }
            AiResearchUniverseItemLineage lineage = new AiResearchUniverseItemLineage();
            lineage.universeItemId = item.id;
            lineage.sourceType = source.sourceType();
            lineage.ownerUserId = source.ownerUserId();
            lineage.sourceRecordId = source.sourceRecordId();
            lineage.activeAtSnapshot = Boolean.TRUE.equals(source.activeAtSnapshot()) ? 1 : 0;
            lineage.sourceFingerprint = source.sourceFingerprint();
            lineage.evidenceJson = json(Map.of(
                    "sourceType", source.sourceType(),
                    "ownerUserId", source.ownerUserId(),
                    "sourceRecordId", source.sourceRecordId(),
                    "activeAtSnapshot", Boolean.TRUE.equals(source.activeAtSnapshot()),
                    "sourceReference", source.sourceReference() == null ? "" : source.sourceReference()
            ));
            lineage.observedAt = observedAt;
            lineage.createdAt = LocalDateTime.now();
            lineageMapper.insert(lineage);
        }
    }

    private String snapshotFingerprint(SnapshotRequest request, List<MergedCandidate> candidates) {
        String canonicalItems = candidates.stream()
                .map(candidate -> String.join("|",
                        candidate.stockCode(),
                        candidate.stockName() == null ? "" : candidate.stockName(),
                        candidate.market() == null ? "" : candidate.market(),
                        candidate.industryCode() == null ? "" : candidate.industryCode(),
                        candidate.industryName() == null ? "" : candidate.industryName(),
                        candidate.industryStandard() == null ? "" : candidate.industryStandard(),
                        String.join(",", candidate.sourceTypes()),
                        String.valueOf(candidate.included()),
                        candidate.listedStatus(),
                        candidate.effectiveFrom() == null ? "" : candidate.effectiveFrom().toString(),
                        candidate.effectiveTo() == null ? "" : candidate.effectiveTo().toString(),
                        String.join(",", candidate.sourceReferences()),
                        String.join(",", candidate.sourceEvidenceFingerprints()),
                        candidate.sourceLineages().stream()
                                .map(lineage -> String.join("/", lineage.sourceType(),
                                        String.valueOf(lineage.ownerUserId()), String.valueOf(lineage.sourceRecordId()),
                                        String.valueOf(lineage.activeAtSnapshot()), value(lineage.sourceFingerprint())))
                                .sorted()
                                .reduce((left, right) -> left + "," + right)
                                .orElse(""),
                        String.join(",", candidate.industryEvidenceFingerprints()),
                        candidate.excludeReason() == null ? "" : candidate.excludeReason()
                ))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return sha256(String.join("|",
                SYSTEM_CORE,
                request.tradeDate().toString(),
                request.asOfTime().toString(),
                request.calendarVersion(),
                value(request.membershipSourceName()),
                value(request.membershipSourceRevision()),
                request.sourceObservedAt() == null ? "" : request.sourceObservedAt().toString(),
                request.pointInTimeStatus(),
                value(request.pointInTimeReason()),
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

    private static boolean active(WatchStock stock) {
        return stock != null && (stock.deleted == null || stock.deleted == 0);
    }

    private static boolean active(TradeRecord record) {
        return record != null && (record.deleted == null || record.deleted == 0);
    }

    private static String exclusionReason(MergedCandidate candidate, LocalDate tradeDate) {
        if (candidate.excludeReason() != null && !candidate.excludeReason().isBlank()) {
            return candidate.excludeReason();
        }
        if (candidate.effectiveFrom() != null && candidate.effectiveFrom().isAfter(tradeDate)) {
            return "目标交易日尚未进入研究范围";
        }
        if (candidate.effectiveTo() != null && candidate.effectiveTo().isBefore(tradeDate)) {
            return "目标交易日已不在研究范围";
        }
        String status = candidate.listedStatus() == null ? "" : candidate.listedStatus().trim().toUpperCase(Locale.ROOT);
        if (!"LISTED".equals(status)) {
            return "证券当前不属于正常上市状态：" + (status.isBlank() ? "UNKNOWN" : status);
        }
        String name = candidate.stockName() == null ? "" : candidate.stockName().trim().toUpperCase(Locale.ROOT);
        if (name.startsWith("*ST") || name.startsWith("ST")) {
            return "证券名称命中 ST 风险标识，已排除正式研究池";
        }
        if (name.startsWith("PT")) {
            return "证券名称命中 PT 风险标识，已排除正式研究池";
        }
        if (name.contains("退")) {
            return "证券名称包含退市标识，已排除正式研究池";
        }
        return null;
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

    private static String sourceFingerprint(
            String sourceType,
            Long ownerUserId,
            Long sourceRecordId,
            String stockCode,
            String stockName,
            LocalDateTime observedAt
    ) {
        return sha256(String.join("|", value(sourceType), String.valueOf(ownerUserId), String.valueOf(sourceRecordId),
                value(normalizeCode(stockCode)), value(stockName), observedAt == null ? "" : observedAt.toString()));
    }

    private static void validate(SnapshotRequest request) {
        if (request == null || request.tradeDate() == null || request.asOfTime() == null
                || request.calendarVersion() == null || request.calendarVersion().isBlank()) {
            throw new IllegalArgumentException("股票池快照缺少交易日、截止时间或日历版本");
        }
        if (!Set.of("READY", "PARTIAL", "UNAVAILABLE").contains(
                request.pointInTimeStatus() == null ? "" : request.pointInTimeStatus().trim().toUpperCase())) {
            throw new IllegalArgumentException("股票池快照缺少合法的时点状态");
        }
        if ("READY".equalsIgnoreCase(request.pointInTimeStatus())
                && (request.membershipSourceName() == null || request.membershipSourceName().isBlank()
                || request.membershipSourceRevision() == null || request.membershipSourceRevision().isBlank()
                || request.sourceObservedAt() == null)) {
            throw new IllegalArgumentException("READY 股票池快照必须声明来源、版本和观测时间");
        }
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private record MergedCandidate(
            String stockCode,
            String stockName,
            String market,
            Set<String> sourceTypes,
            boolean included,
            String excludeReason,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String listedStatus,
            Set<String> sourceReferences,
            Set<String> sourceEvidenceFingerprints,
            Set<SourceLineage> sourceLineages,
            String industryCode,
            String industryName,
            String industryStandard,
            Set<String> industryEvidenceFingerprints
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
        private LocalDate effectiveTo;
        private boolean unboundedEffectiveTo;
        private String listedStatus;
        private final Set<String> sourceReferences = new TreeSet<>();
        private final Set<String> sourceEvidenceFingerprints = new TreeSet<>();
        private final Set<SourceLineage> sourceLineages = new TreeSet<>(Comparator
                .comparing(SourceLineage::sourceType)
                .thenComparing(lineage -> lineage.ownerUserId() == null ? -1L : lineage.ownerUserId())
                .thenComparing(lineage -> lineage.sourceRecordId() == null ? -1L : lineage.sourceRecordId()));
        private String industryCode;
        private String industryName;
        private String industryStandard;
        private final Set<String> industryEvidenceFingerprints = new TreeSet<>();

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
            if (candidate.effectiveTo() == null) {
                unboundedEffectiveTo = true;
                effectiveTo = null;
            } else if (!unboundedEffectiveTo
                    && (effectiveTo == null || candidate.effectiveTo().isAfter(effectiveTo))) {
                effectiveTo = candidate.effectiveTo();
            }
            if (candidate.listedStatus() != null && !candidate.listedStatus().isBlank()) {
                listedStatus = candidate.listedStatus().trim().toUpperCase();
            }
            if (candidate.sourceReference() != null && !candidate.sourceReference().isBlank()) {
                sourceReferences.add(candidate.sourceReference().trim());
            }
            if (candidate.sourceEvidenceFingerprint() != null
                    && !candidate.sourceEvidenceFingerprint().isBlank()) {
                sourceEvidenceFingerprints.add(candidate.sourceEvidenceFingerprint().trim());
            }
            if (candidate.sourceOwnerUserId() != null && candidate.sourceRecordId() != null) {
                sourceLineages.add(new SourceLineage(
                        candidate.sourceType() == null ? "UNKNOWN" : candidate.sourceType().trim().toUpperCase(),
                        candidate.sourceOwnerUserId(), candidate.sourceRecordId(),
                        candidate.sourceActiveAtSnapshot(), candidate.sourceReference(),
                        candidate.sourceEvidenceFingerprint()));
            }
            if (candidate.industryCode() != null && !candidate.industryCode().isBlank()) {
                String normalizedIndustryCode = candidate.industryCode().trim().toUpperCase();
                if (industryCode != null && !industryCode.equals(normalizedIndustryCode)) {
                    throw new IllegalArgumentException("同一股票在同一快照存在冲突行业归属：" + stockCode);
                }
                industryCode = normalizedIndustryCode;
            }
            if (candidate.industryName() != null && !candidate.industryName().isBlank()) {
                industryName = candidate.industryName().trim();
            }
            if (candidate.industryStandard() != null && !candidate.industryStandard().isBlank()) {
                String normalizedIndustryStandard = candidate.industryStandard().trim().toUpperCase();
                if (industryStandard != null && !industryStandard.equals(normalizedIndustryStandard)) {
                    throw new IllegalArgumentException("同一股票在同一快照存在冲突行业分类标准：" + stockCode);
                }
                industryStandard = normalizedIndustryStandard;
            }
            if (candidate.industryEvidenceFingerprint() != null
                    && !candidate.industryEvidenceFingerprint().isBlank()) {
                industryEvidenceFingerprints.add(candidate.industryEvidenceFingerprint().trim());
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
                    effectiveFrom,
                    effectiveTo,
                    listedStatus == null ? "LISTED" : listedStatus,
                    Set.copyOf(sourceReferences),
                    Set.copyOf(sourceEvidenceFingerprints),
                    Set.copyOf(sourceLineages),
                    industryCode,
                    industryName,
                    industryStandard,
                    Set.copyOf(industryEvidenceFingerprints)
            );
        }
    }

    private record SourceLineage(
            String sourceType,
            Long ownerUserId,
            Long sourceRecordId,
            Boolean activeAtSnapshot,
            String sourceReference,
            String sourceFingerprint
    ) {
    }
}
