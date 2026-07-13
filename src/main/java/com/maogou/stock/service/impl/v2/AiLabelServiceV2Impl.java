package com.maogou.stock.service.impl.v2;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.v2.AiLabelCostEvidence;
import com.maogou.stock.domain.entity.v2.AiLabelV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.domain.entity.v2.AiTradingCalendar;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.mapper.v2.AiLabelCostEvidenceMapper;
import com.maogou.stock.mapper.v2.AiLabelV2Mapper;
import com.maogou.stock.service.v2.AiLabelServiceV2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AiLabelServiceV2Impl implements AiLabelServiceV2 {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    private static final Set<Integer> SUPPORTED_HORIZONS = Set.of(1, 3, 5);

    private final AiLabelV2Mapper labelMapper;
    private final AiLabelCostEvidenceMapper costMapper;
    private final ObjectMapper objectMapper;

    public AiLabelServiceV2Impl(
            AiLabelV2Mapper labelMapper,
            AiLabelCostEvidenceMapper costMapper,
            ObjectMapper objectMapper
    ) {
        this.labelMapper = labelMapper;
        this.costMapper = costMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public List<AiLabelV2> verifyAndStore(LabelBatch batch) {
        validateBatch(batch);
        List<AiTradingCalendar> tradingDays = batch.calendars().stream()
                .filter(day -> day != null && day.isTradeDay != null && day.isTradeDay == 1)
                .filter(day -> batch.calendarVersion().equals(day.calendarVersion))
                .sorted(Comparator.comparing(day -> day.tradeDate))
                .toList();
        Map<LabelKey, CostAmounts> costsByKey = new HashMap<>();
        List<AiLabelV2> candidates = new ArrayList<>();
        for (LabelInput input : batch.inputs()) {
            validateInput(input, batch);
            for (Integer horizon : batch.horizons().stream().distinct().sorted().toList()) {
                BuiltLabel built = buildLabel(input, horizon, tradingDays, batch);
                if (built != null) {
                    candidates.add(built.label());
                    if (built.costs() != null) {
                        costsByKey.put(key(built.label()), built.costs());
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        Long userId = candidates.get(0).userId;
        labelMapper.insertBatchImmutable(candidates);
        List<Long> predictionIds = new ArrayList<>(new LinkedHashSet<>(
                candidates.stream().map(item -> item.predictionId).toList()));
        List<AiLabelV2> persisted = labelMapper.selectByPredictionIdsForShare(
                userId, predictionIds, batch.labelVersion());
        Map<LabelKey, AiLabelV2> persistedByKey = new HashMap<>();
        persisted.forEach(item -> persistedByKey.put(key(item), item));
        List<AiLabelV2> result = new ArrayList<>(candidates.size());
        List<AiLabelCostEvidence> evidence = new ArrayList<>();
        for (AiLabelV2 expected : candidates) {
            AiLabelV2 actual = persistedByKey.get(key(expected));
            if (actual == null) {
                throw new IllegalStateException("标签写入后未读取到记录：" + key(expected));
            }
            if (!Objects.equals(expected.inputFingerprint, actual.inputFingerprint)) {
                throw new IllegalStateException("不可变标签冲突：" + key(expected));
            }
            result.add(actual);
            CostAmounts amounts = costsByKey.get(key(expected));
            if (amounts != null) {
                evidence.add(costEvidence(actual, amounts, batch.costModel()));
            }
        }
        if (!evidence.isEmpty()) {
            costMapper.insertBatchImmutable(evidence);
        }
        return result;
    }

    private BuiltLabel buildLabel(
            LabelInput input,
            int horizon,
            List<AiTradingCalendar> tradingDays,
            LabelBatch batch
    ) {
        AiPredictionV2 prediction = input.prediction();
        List<AiTradingCalendar> future = tradingDays.stream()
                .filter(day -> day.tradeDate.isAfter(prediction.tradeDate))
                .toList();
        if (future.size() < horizon) {
            return null;
        }
        AiTradingCalendar entryCalendar = future.get(0);
        AiTradingCalendar exitCalendar = future.get(horizon - 1);
        LocalTime exitClose = exitCalendar.sessionCloseTime == null
                ? LocalTime.of(15, 0) : exitCalendar.sessionCloseTime;
        if (batch.verifiedAt().isBefore(exitCalendar.tradeDate.atTime(exitClose))) {
            return null;
        }
        Map<LocalDate, KlinePointResponse> stock = byDate(input.stockSeries());
        KlinePointResponse entry = stock.get(entryCalendar.tradeDate);
        KlinePointResponse exit = stock.get(exitCalendar.tradeDate);
        if (entry == null || exit == null) {
            return null;
        }

        AiLabelV2 label = baseLabel(input, horizon, entryCalendar, exitCalendar, batch);
        KlinePointResponse previous = stock.values().stream()
                .filter(point -> point.tradeDate().isBefore(entryCalendar.tradeDate))
                .max(Comparator.comparing(KlinePointResponse::tradeDate))
                .orElse(null);
        KlinePointResponse previousToExit = stock.values().stream()
                .filter(point -> point.tradeDate().isBefore(exitCalendar.tradeDate))
                .max(Comparator.comparing(KlinePointResponse::tradeDate))
                .orElse(null);
        String executionStatus = executionStatus(
                input.sample(), input.prediction().stockCode, previous, previousToExit, entry, exit);
        if (!"EXECUTED".equals(executionStatus)) {
            label.executionStatus = executionStatus;
            label.executionReason = executionStatus;
            label.actionEvaluation = "NOT_EXECUTABLE";
            label.labelStatus = "VERIFIED";
            label.maturedAt = exitCalendar.tradeDate.atTime(15, 0);
            label.verifiedAt = batch.verifiedAt();
            label.inputFingerprint = labelFingerprint(input, label, batch);
            return new BuiltLabel(label, null);
        }

        label.entryPrice = scalePrice(entry.open());
        label.exitPrice = scalePrice(exit.close());
        label.grossReturn = returnRate(exit.close(), entry.open());
        CostAmounts costs = calculateCosts(entry.open(), exit.close(), batch.costModel());
        label.netReturn = costs.netReturn();
        label.benchmarkReturn = seriesReturn(input.benchmarkSeries(), entryCalendar.tradeDate, exitCalendar.tradeDate);
        label.sectorReturn = seriesReturn(input.sectorSeries(), entryCalendar.tradeDate, exitCalendar.tradeDate);
        label.excessReturn = label.benchmarkReturn == null
                ? null
                : label.netReturn.subtract(label.benchmarkReturn).setScale(6, RoundingMode.HALF_UP);
        List<KlinePointResponse> holding = stock.values().stream()
                .filter(point -> !point.tradeDate().isBefore(entryCalendar.tradeDate)
                        && !point.tradeDate().isAfter(exitCalendar.tradeDate))
                .toList();
        label.maxFavorableReturn = holding.stream().map(KlinePointResponse::high)
                .filter(Objects::nonNull).max(BigDecimal::compareTo)
                .map(high -> returnRate(high, entry.open())).orElse(null);
        label.maxAdverseReturn = holding.stream().map(KlinePointResponse::low)
                .filter(Objects::nonNull).min(BigDecimal::compareTo)
                .map(low -> returnRate(low, entry.open())).orElse(null);
        evaluateAction(label, prediction);
        label.executionStatus = "EXECUTED";
        label.labelStatus = "VERIFIED";
        label.maturedAt = exitCalendar.tradeDate.atTime(15, 0);
        label.verifiedAt = batch.verifiedAt();
        label.inputFingerprint = labelFingerprint(input, label, batch);
        return new BuiltLabel(label, costs);
    }

    private static AiLabelV2 baseLabel(
            LabelInput input,
            int horizon,
            AiTradingCalendar entryCalendar,
            AiTradingCalendar exitCalendar,
            LabelBatch batch
    ) {
        AiLabelV2 label = new AiLabelV2();
        label.userId = input.prediction().userId;
        label.predictionId = input.prediction().id;
        label.sampleId = input.sample().id;
        label.entryCalendarId = entryCalendar.id;
        label.exitCalendarId = exitCalendar.id;
        label.stockCode = input.prediction().stockCode;
        label.horizonDays = horizon;
        label.labelVersion = batch.labelVersion();
        label.calendarVersion = batch.calendarVersion();
        label.entryTradeDate = entryCalendar.tradeDate;
        label.exitTradeDate = exitCalendar.tradeDate;
        label.executionStatus = "PENDING";
        label.actionEvaluation = "PENDING";
        label.labelStatus = "PENDING";
        label.createdAt = LocalDateTime.now();
        return label;
    }

    private static String executionStatus(
            AiSampleV2 sample,
            String stockCode,
            KlinePointResponse previousToEntry,
            KlinePointResponse previousToExit,
            KlinePointResponse entry,
            KlinePointResponse exit
    ) {
        if (sample.tradableStatus != null && sample.tradableStatus.contains("ST")) {
            return "ST_RESTRICTED";
        }
        if (entry.volume() == null || entry.volume() <= 0) {
            return "SUSPENDED_ENTRY";
        }
        if (previousToEntry == null || previousToEntry.close() == null || entry.open() == null) {
            return "ENTRY_DATA_UNAVAILABLE";
        }
        BigDecimal limit = limitRatio(stockCode, false);
        BigDecimal upper = previousToEntry.close().multiply(BigDecimal.ONE.add(limit));
        if (sealed(entry) && entry.open().compareTo(upper.multiply(new BigDecimal("0.999"))) >= 0) {
            return "LIMIT_UP_ENTRY_BLOCKED";
        }
        if (exit.volume() == null || exit.volume() <= 0) {
            return "SUSPENDED_EXIT";
        }
        if (previousToExit == null || previousToExit.close() == null) {
            return "EXIT_DATA_UNAVAILABLE";
        }
        BigDecimal lower = previousToExit.close().multiply(
                BigDecimal.ONE.subtract(limitRatio(stockCode, false)));
        if (sealed(exit) && exit.close().compareTo(lower.multiply(new BigDecimal("1.001"))) <= 0) {
            return "LIMIT_DOWN_EXIT_BLOCKED";
        }
        return "EXECUTED";
    }

    private static boolean sealed(KlinePointResponse point) {
        return point.open() != null && point.high() != null && point.low() != null
                && point.open().compareTo(point.high()) == 0
                && point.open().compareTo(point.low()) == 0;
    }

    private static BigDecimal limitRatio(String stockCode, boolean st) {
        if (st) {
            return new BigDecimal("0.05");
        }
        return stockCode != null && (stockCode.startsWith("300") || stockCode.startsWith("301")
                || stockCode.startsWith("688"))
                ? new BigDecimal("0.20")
                : new BigDecimal("0.10");
    }

    private static CostAmounts calculateCosts(BigDecimal entry, BigDecimal exit, CostModel model) {
        BigDecimal quantity = model.quantity();
        BigDecimal entryNotional = entry.multiply(quantity);
        BigDecimal exitNotional = exit.multiply(quantity);
        BigDecimal buyCommission = entryNotional.multiply(model.buyCommissionRate());
        BigDecimal sellCommission = exitNotional.multiply(model.sellCommissionRate());
        BigDecimal stampDuty = exitNotional.multiply(model.stampDutyRate());
        BigDecimal transferFee = entryNotional.add(exitNotional).multiply(model.transferFeeRate());
        BigDecimal slippageRate = model.slippageBps().divide(TEN_THOUSAND, 12, RoundingMode.HALF_UP);
        BigDecimal buySlippage = entryNotional.multiply(slippageRate);
        BigDecimal sellSlippage = exitNotional.multiply(slippageRate);
        BigDecimal totalCosts = buyCommission.add(sellCommission).add(stampDuty)
                .add(transferFee).add(buySlippage).add(sellSlippage);
        BigDecimal invested = entryNotional.add(buyCommission)
                .add(entryNotional.multiply(model.transferFeeRate())).add(buySlippage);
        BigDecimal proceeds = exitNotional.subtract(sellCommission).subtract(stampDuty)
                .subtract(exitNotional.multiply(model.transferFeeRate())).subtract(sellSlippage);
        BigDecimal netReturn = proceeds.divide(invested, 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
        return new CostAmounts(
                scaleAmount(entryNotional), scaleAmount(exitNotional), scaleAmount(buyCommission),
                scaleAmount(sellCommission), scaleAmount(stampDuty), scaleAmount(transferFee),
                scaleAmount(buySlippage.add(sellSlippage)), scaleAmount(totalCosts), netReturn);
    }

    private void evaluateAction(AiLabelV2 label, AiPredictionV2 prediction) {
        String actualDirection = direction(label.grossReturn);
        label.hitDirection = "SIDEWAYS".equals(prediction.targetDirection)
                ? ("SIDEWAYS".equals(actualDirection) ? 1 : 0)
                : (Objects.equals(prediction.targetDirection, actualDirection) ? 1 : 0);
        if ("WATCH".equals(prediction.action) || "ABSTAIN".equals(prediction.actionBucket)) {
            label.actionEvaluation = "ABSTAIN";
            label.hitDirection = null;
            label.hitTarget = null;
            label.labelScore = null;
        } else if ("REDUCE".equals(prediction.action) || "AVOID".equals(prediction.actionBucket)) {
            boolean hit = label.grossReturn.signum() < 0;
            label.actionEvaluation = hit ? "AVOID_HIT" : "AVOID_MISS";
            label.hitTarget = hit ? 1 : 0;
            label.labelScore = hit ? new BigDecimal("100") : BigDecimal.ZERO;
        } else {
            boolean hit = label.netReturn.signum() > 0
                    && (label.excessReturn == null || label.excessReturn.signum() > 0);
            label.actionEvaluation = hit ? "HIT" : "MISS";
            label.hitTarget = prediction.expectedReturn == null
                    ? (hit ? 1 : 0)
                    : (label.netReturn.compareTo(prediction.expectedReturn) >= 0 ? 1 : 0);
            double score = 40d + (hit ? 35d : 0d) + (label.hitDirection != null && label.hitDirection == 1 ? 20d : 0d)
                    + Math.max(-10d, Math.min(10d,
                    label.excessReturn == null ? 0d : label.excessReturn.doubleValue() * 100d));
            label.labelScore = BigDecimal.valueOf(Math.max(0d, Math.min(100d, score)))
                    .setScale(4, RoundingMode.HALF_UP);
        }
        label.hitStopLoss = label.maxAdverseReturn != null
                && label.maxAdverseReturn.compareTo(new BigDecimal("-0.03")) <= 0 ? 1 : 0;
    }

    private static String direction(BigDecimal value) {
        if (value.compareTo(new BigDecimal("0.01")) >= 0) {
            return "UP";
        }
        if (value.compareTo(new BigDecimal("-0.01")) <= 0) {
            return "DOWN";
        }
        return "SIDEWAYS";
    }

    private static BigDecimal seriesReturn(
            KlineSeriesSnapshot series,
            LocalDate entryDate,
            LocalDate exitDate
    ) {
        if (series == null) {
            return null;
        }
        Map<LocalDate, KlinePointResponse> byDate = byDate(series);
        KlinePointResponse entry = byDate.get(entryDate);
        KlinePointResponse exit = byDate.get(exitDate);
        if (entry == null || exit == null || entry.open() == null || exit.close() == null) {
            return null;
        }
        return returnRate(exit.close(), entry.open());
    }

    private static Map<LocalDate, KlinePointResponse> byDate(KlineSeriesSnapshot series) {
        Map<LocalDate, KlinePointResponse> result = new LinkedHashMap<>();
        if (series != null && series.points() != null) {
            series.points().stream().sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                    .forEach(point -> result.put(point.tradeDate(), point));
        }
        return result;
    }

    private String labelFingerprint(LabelInput input, AiLabelV2 label, LabelBatch batch) {
        return sha256(String.join("|",
                String.valueOf(input.prediction().inputFingerprint),
                String.valueOf(input.sample().sourceFingerprint),
                input.stockSeries().sourceFingerprint(),
                input.benchmarkSeries() == null ? "" : input.benchmarkSeries().sourceFingerprint(),
                input.sectorSeries() == null ? "" : input.sectorSeries().sourceFingerprint(),
                batch.calendarVersion(),
                calendarFingerprint(batch.calendars(), label.entryCalendarId),
                calendarFingerprint(batch.calendars(), label.exitCalendarId),
                batch.labelVersion(),
                String.valueOf(label.horizonDays),
                costModelFingerprint(batch.costModel()),
                String.valueOf(label.executionStatus),
                String.valueOf(label.entryTradeDate),
                String.valueOf(label.exitTradeDate)));
    }

    private static String calendarFingerprint(List<AiTradingCalendar> calendars, Long calendarId) {
        return calendars.stream()
                .filter(day -> Objects.equals(day.id, calendarId))
                .findFirst()
                .map(day -> day.id + ":" + day.tradeDate + ":" + day.sourceFingerprint)
                .orElseThrow(() -> new IllegalArgumentException("标签关联的交易日历证据不存在"));
    }

    private static String costModelFingerprint(CostModel model) {
        return String.join(":",
                String.valueOf(model.version()),
                String.valueOf(model.buyCommissionRate()),
                String.valueOf(model.sellCommissionRate()),
                String.valueOf(model.stampDutyRate()),
                String.valueOf(model.transferFeeRate()),
                String.valueOf(model.slippageBps()),
                String.valueOf(model.quantity()));
    }

    private AiLabelCostEvidence costEvidence(AiLabelV2 label, CostAmounts costs, CostModel model) {
        AiLabelCostEvidence evidence = new AiLabelCostEvidence();
        evidence.labelId = label.id;
        evidence.costModelVersion = model.version();
        evidence.currency = "CNY";
        evidence.quantity = model.quantity();
        evidence.entryNotional = costs.entryNotional();
        evidence.exitNotional = costs.exitNotional();
        evidence.buyCommissionRate = model.buyCommissionRate();
        evidence.sellCommissionRate = model.sellCommissionRate();
        evidence.stampDutyRate = model.stampDutyRate();
        evidence.transferFeeRate = model.transferFeeRate();
        evidence.slippageBps = model.slippageBps();
        evidence.buyCommissionAmount = costs.buyCommission();
        evidence.sellCommissionAmount = costs.sellCommission();
        evidence.stampDutyAmount = costs.stampDuty();
        evidence.transferFeeAmount = costs.transferFee();
        evidence.slippageAmount = costs.slippage();
        evidence.totalCostAmount = costs.totalCosts();
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("entryNotional", costs.entryNotional());
        json.put("exitNotional", costs.exitNotional());
        json.put("netReturn", costs.netReturn());
        try {
            evidence.evidenceJson = objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化标签成本证据", ex);
        }
        evidence.sourceFingerprint = sha256(label.inputFingerprint + "|" + model.version());
        evidence.createdAt = LocalDateTime.now();
        return evidence;
    }

    private static void validateBatch(LabelBatch batch) {
        if (batch == null || batch.inputs() == null || batch.inputs().isEmpty()
                || batch.calendars() == null || batch.calendars().isEmpty()
                || batch.calendarVersion() == null || batch.labelVersion() == null
                || batch.horizons() == null || batch.horizons().isEmpty()
                || batch.costModel() == null || batch.verifiedAt() == null) {
            throw new IllegalArgumentException("标签批次缺少必需参数");
        }
        if (batch.horizons().stream().anyMatch(value -> !SUPPORTED_HORIZONS.contains(value))) {
            throw new IllegalArgumentException("标签周期仅支持 T+1/T+3/T+5");
        }
        if (batch.calendars().stream().anyMatch(day -> day == null || day.id == null
                || day.tradeDate == null || !Objects.equals(batch.calendarVersion(), day.calendarVersion)
                || day.sourceFingerprint == null || day.sourceFingerprint.isBlank())) {
            throw new IllegalArgumentException("交易日历证据缺少版本、日期、ID 或来源指纹");
        }
        Long userId = null;
        String batchScope = null;
        for (LabelInput input : batch.inputs()) {
            Long inputUserId = input == null || input.prediction() == null
                    ? null : input.prediction().userId;
            if (userId == null) {
                userId = inputUserId;
            } else if (!Objects.equals(userId, inputUserId)) {
                throw new IllegalArgumentException("一个标签批次只能包含同一用户");
            }
            if (input != null && input.prediction() != null) {
                String inputScope = input.prediction().tradeDate + "|" + input.prediction().samplePhase;
                if (batchScope == null) {
                    batchScope = inputScope;
                } else if (!batchScope.equals(inputScope)) {
                    throw new IllegalArgumentException("一个标签批次只能包含同一交易日和阶段");
                }
            }
        }
    }

    private static void validateInput(LabelInput input, LabelBatch batch) {
        if (input == null || input.prediction() == null || input.sample() == null
                || input.prediction().id == null || input.sample().id == null
                || !Objects.equals(input.prediction().userId, input.sample().userId)
                || !Objects.equals(input.prediction().sampleId, input.sample().id)
                || !Objects.equals(input.prediction().stockCode, input.sample().stockCode)) {
            throw new IllegalArgumentException("预测、样本和股票血缘不一致");
        }
        if (!Objects.equals(input.prediction().tradeDate, input.sample().tradeDate)
                || !Objects.equals(input.prediction().samplePhase, input.sample().samplePhase)) {
            throw new IllegalArgumentException("预测与样本的时点血缘不一致");
        }
        if (input.prediction().inputFingerprint == null || input.prediction().inputFingerprint.isBlank()
                || input.sample().sourceFingerprint == null || input.sample().sourceFingerprint.isBlank()) {
            throw new IllegalArgumentException("预测与样本缺少不可变指纹");
        }
        validateSeries(input.stockSeries(), input.prediction().stockCode, batch.verifiedAt());
        if (input.benchmarkSeries() != null) {
            validateSeries(input.benchmarkSeries(), null, batch.verifiedAt());
        }
        if (input.sectorSeries() != null) {
            validateSeries(input.sectorSeries(), null, batch.verifiedAt());
        }
    }

    private static void validateSeries(
            KlineSeriesSnapshot series,
            String expectedSymbol,
            LocalDateTime verifiedAt
    ) {
        if (series == null || !series.fingerprintMatches() || !"NONE".equalsIgnoreCase(series.adjustmentMode())
                || !"DAY".equalsIgnoreCase(series.period())
                || series.source() == null || series.source().isBlank()
                || "MOCK".equalsIgnoreCase(series.source()) || "FALLBACK".equalsIgnoreCase(series.source())
                || "UNAVAILABLE".equalsIgnoreCase(series.source())
                || series.asOfTime() == null || series.asOfTime().isAfter(verifiedAt)
                || series.fetchedAt() == null || series.fetchedAt().isAfter(verifiedAt)) {
            throw new IllegalArgumentException("标签必须使用可验证、真实来源的时点化未复权日线");
        }
        if (series.points() == null || series.points().stream().anyMatch(point -> point == null
                || point.tradeDate() == null || point.tradeDate().isAfter(series.asOfTime().toLocalDate()))) {
            throw new IllegalArgumentException("标签 K 线包含超出 asOf 时点的数据");
        }
        if (expectedSymbol != null && !normalizeCode(expectedSymbol).equals(normalizeCode(series.symbol()))) {
            throw new IllegalArgumentException("标签 K 线股票代码与预测不一致");
        }
    }

    private static String normalizeCode(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(digits.length() - 6) : digits;
    }

    private static BigDecimal returnRate(BigDecimal end, BigDecimal start) {
        return end.divide(start, 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal scalePrice(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleAmount(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static LabelKey key(AiLabelV2 label) {
        return new LabelKey(label.predictionId, label.horizonDays, label.labelVersion);
    }

    private record LabelKey(Long predictionId, Integer horizon, String version) {
    }

    private record BuiltLabel(AiLabelV2 label, CostAmounts costs) {
    }

    private record CostAmounts(
            BigDecimal entryNotional,
            BigDecimal exitNotional,
            BigDecimal buyCommission,
            BigDecimal sellCommission,
            BigDecimal stampDuty,
            BigDecimal transferFee,
            BigDecimal slippage,
            BigDecimal totalCosts,
            BigDecimal netReturn
    ) {
    }
}
