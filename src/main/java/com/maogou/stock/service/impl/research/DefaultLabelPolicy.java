package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiLabelCostEvidence;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.service.research.AiSampleLabelService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
import java.util.Map;
import java.util.Objects;

@Component
public class DefaultLabelPolicy {

    public static final String VERSION = "LABEL/1.1.0";
    public static final PolicyConfig DEFAULT_CONFIG = new PolicyConfig(
            VERSION,
            "COST/CN_A/1.1.0",
            new BigDecimal("100000"),
            100,
            new BigDecimal("0.0003"),
            new BigDecimal("0.0003"),
            new BigDecimal("5.00"),
            new BigDecimal("0.0005"),
            new BigDecimal("0.00001"),
            new BigDecimal("2.0"),
            new BigDecimal("3.0"),
            5,
            new BigDecimal("0.08"),
            new BigDecimal("-0.08")
    );

    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");

    private final ObjectMapper objectMapper;
    private final PolicyConfig config;

    @Autowired
    public DefaultLabelPolicy(ObjectMapper objectMapper) {
        this(objectMapper, DEFAULT_CONFIG);
    }

    public DefaultLabelPolicy(ObjectMapper objectMapper, PolicyConfig config) {
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public BuildResult build(
            AiSampleLabelService.SampleInput input,
            int horizon,
            List<AiSampleLabelService.TradingDay> calendars,
            String calendarVersion,
            String labelVersion,
            LocalDateTime verifiedAt
    ) {
        validate(input, horizon, calendars, calendarVersion, labelVersion, verifiedAt);
        List<AiSampleLabelService.TradingDay> future = calendars.stream()
                .filter(AiSampleLabelService.TradingDay::tradingDay)
                .filter(day -> calendarVersion.equals(day.calendarVersion()))
                .filter(day -> day.tradeDate().isAfter(input.signalTradeDate()))
                .sorted(Comparator.comparing(AiSampleLabelService.TradingDay::tradeDate))
                .toList();
        if (future.size() < horizon) {
            return null;
        }

        AiSampleLabelService.TradingDay entryDay = future.get(0);
        AiSampleLabelService.TradingDay plannedExitDay = future.get(horizon - 1);
        if (!isSessionMature(plannedExitDay, verifiedAt)) {
            return null;
        }

        Map<LocalDate, KlinePointResponse> stockBars = barsByDate(input.stockSeries());
        KlinePointResponse entryBar = stockBars.get(entryDay.tradeDate());
        AiSampleLabel label = baseLabel(input, horizon, entryDay, plannedExitDay, calendarVersion, labelVersion);
        String entryBlock = entryBlockReason(input, stockBars, entryDay.tradeDate(), entryBar);
        if (entryBlock != null) {
            label.executionStatus = "UNFILLED";
            label.fillStatus = invalidSourceReason(entryBlock) ? "INVALID_SOURCE" : "UNFILLED";
            label.executionReason = entryBlock;
            label.labelStatus = "MATURED";
            label.labelAvailableAt = closeAt(entryDay);
            label.maturedAt = label.labelAvailableAt;
            label.verifiedAt = verifiedAt;
            label.policySnapshotJson = policySnapshot();
            label.marketEvidenceJson = marketEvidence(input, plannedExitDay, null, null, entryBlock, List.of());
            label.inputFingerprint = labelFingerprint(input, label, calendars);
            return new BuildResult(label, null);
        }

        ExitResolution exit = resolveExit(
                stockBars,
                future,
                horizon - 1,
                input,
                verifiedAt
        );
        if (exit == null) {
            return null;
        }
        if (exit.blocked()) {
            label.entryPrice = scalePrice(entryBar.open().multiply(BigDecimal.ONE.add(executionFrictionRate())));
            label.exitCalendarId = exit.day().id();
            label.exitTradeDate = exit.day().tradeDate();
            label.exitDelayTradingDays = exit.blockedDates().size();
            label.executionStatus = "EXIT_BLOCKED";
            label.fillStatus = invalidSourceReason(exit.reason()) ? "INVALID_SOURCE" : "PARTIAL";
            label.executionReason = exit.reason();
            label.labelStatus = "MATURED";
            label.labelAvailableAt = closeAt(exit.day());
            label.maturedAt = label.labelAvailableAt;
            label.verifiedAt = verifiedAt;
            label.policySnapshotJson = policySnapshot();
            label.marketEvidenceJson = marketEvidence(
                    input, plannedExitDay, exit.day(), entryBar, exit.reason(), exit.blockedDates());
            label.inputFingerprint = labelFingerprint(input, label, calendars);
            return new BuildResult(label, null);
        }

        KlinePointResponse exitBar = exit.bar();
        CostCalculation cost = calculateCosts(entryBar.open(), exitBar.close());
        if (cost.quantity().signum() == 0) {
            label.executionStatus = "UNFILLED";
            label.fillStatus = "UNFILLED";
            label.executionReason = "STANDARD_PRINCIPAL_BELOW_ONE_LOT";
            label.labelStatus = "MATURED";
            label.labelAvailableAt = closeAt(entryDay);
            label.maturedAt = label.labelAvailableAt;
            label.verifiedAt = verifiedAt;
            label.policySnapshotJson = policySnapshot();
            label.marketEvidenceJson = marketEvidence(
                    input, plannedExitDay, exit.day(), entryBar, label.executionReason, exit.blockedDates());
            label.inputFingerprint = labelFingerprint(input, label, calendars);
            return new BuildResult(label, null);
        }

        label.exitCalendarId = exit.day().id();
        label.exitTradeDate = exit.day().tradeDate();
        label.exitDelayTradingDays = exit.blockedDates().size();
        label.entryPrice = scalePrice(entryBar.open().multiply(BigDecimal.ONE.add(executionFrictionRate())));
        label.exitPrice = scalePrice(exitBar.close().multiply(BigDecimal.ONE.subtract(executionFrictionRate())));
        label.grossReturn = returnRate(exitBar.close(), entryBar.open());
        label.netReturn = cost.netReturn();
        label.benchmarkReturn = seriesReturn(input.benchmarkSeries(), entryDay.tradeDate(), exit.day().tradeDate());
        label.sectorReturn = seriesReturn(input.sectorSeries(), entryDay.tradeDate(), exit.day().tradeDate());
        label.excessReturn = label.benchmarkReturn == null
                ? null
                : label.netReturn.subtract(label.benchmarkReturn).setScale(6, RoundingMode.HALF_UP);
        label.sectorExcessReturn = label.sectorReturn == null
                ? null
                : label.netReturn.subtract(label.sectorReturn).setScale(6, RoundingMode.HALF_UP);
        List<KlinePointResponse> holdingBars = stockBars.values().stream()
                .filter(bar -> !bar.tradeDate().isBefore(entryDay.tradeDate())
                        && !bar.tradeDate().isAfter(exit.day().tradeDate()))
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                .toList();
        label.maxFavorableReturn = holdingBars.stream()
                .map(KlinePointResponse::high)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .map(high -> returnRate(high, entryBar.open()))
                .orElse(null);
        label.maxAdverseReturn = holdingBars.stream()
                .map(KlinePointResponse::low)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .map(low -> returnRate(low, entryBar.open()))
                .orElse(null);
        label.maxDrawdown = maxDrawdown(holdingBars, entryBar.open());
        label.holdingVolatility = holdingVolatility(holdingBars, entryBar.open());
        label.holdingTradingDays = holdingBars.size();
        label.actualDirection = direction(label.netReturn);
        label.executionStatus = "EXECUTED";
        label.fillStatus = "FILLED";
        label.executionReason = exit.blockedDates().isEmpty()
                ? "PLANNED_EXIT"
                : "EXIT_DELAYED_" + exit.blockedDates().size() + "_TRADING_DAYS";
        label.labelStatus = "MATURED";
        label.labelAvailableAt = closeAt(exit.day());
        label.maturedAt = label.labelAvailableAt;
        label.verifiedAt = verifiedAt;
        label.policySnapshotJson = policySnapshot();
        label.marketEvidenceJson = marketEvidence(
                input, plannedExitDay, exit.day(), entryBar, label.executionReason, exit.blockedDates());
        label.inputFingerprint = labelFingerprint(input, label, calendars);

        AiLabelCostEvidence evidence = costEvidence(label, cost);
        return new BuildResult(label, evidence);
    }

    public BarrierOutcome evaluateSameDayBarrier(
            BigDecimal entryPrice,
            KlinePointResponse bar,
            boolean minuteSequenceAvailable
    ) {
        if (entryPrice == null || bar == null || bar.low() == null || bar.high() == null) {
            return BarrierOutcome.NOT_TRIGGERED;
        }
        BigDecimal stop = entryPrice.multiply(BigDecimal.ONE.add(config.stopLossRate()));
        BigDecimal target = entryPrice.multiply(BigDecimal.ONE.add(config.takeProfitRate()));
        boolean stopHit = bar.low().compareTo(stop) <= 0;
        boolean targetHit = bar.high().compareTo(target) >= 0;
        if (stopHit && targetHit) {
            return minuteSequenceAvailable ? BarrierOutcome.REQUIRES_MINUTE_SEQUENCE : BarrierOutcome.STOP_LOSS;
        }
        if (stopHit) {
            return BarrierOutcome.STOP_LOSS;
        }
        return targetHit ? BarrierOutcome.TAKE_PROFIT : BarrierOutcome.NOT_TRIGGERED;
    }

    private ExitResolution resolveExit(
            Map<LocalDate, KlinePointResponse> bars,
            List<AiSampleLabelService.TradingDay> future,
            int plannedIndex,
            AiSampleLabelService.SampleInput input,
            LocalDateTime verifiedAt
    ) {
        List<LocalDate> blockedDates = new ArrayList<>();
        for (int delay = 0; delay <= config.maxExitDelayTradingDays(); delay++) {
            int index = plannedIndex + delay;
            if (index >= future.size()) {
                return null;
            }
            AiSampleLabelService.TradingDay day = future.get(index);
            if (!isSessionMature(day, verifiedAt)) {
                return null;
            }
            KlinePointResponse bar = bars.get(day.tradeDate());
            String blockedReason = exitBlockReason(bars, day.tradeDate(), bar, input);
            if (blockedReason == null) {
                return new ExitResolution(day, bar, false, null, blockedDates);
            }
            blockedDates.add(day.tradeDate());
            if (delay == config.maxExitDelayTradingDays()) {
                return new ExitResolution(day, bar, true, blockedReason, blockedDates);
            }
        }
        throw new IllegalStateException("退出顺延计算未收敛");
    }

    private String entryBlockReason(
            AiSampleLabelService.SampleInput input,
            Map<LocalDate, KlinePointResponse> bars,
            LocalDate entryDate,
            KlinePointResponse entry
    ) {
        String stateBlock = entryStateBlockReason(input, entryDate);
        if (stateBlock != null) {
            return stateBlock;
        }
        if (entry == null || entry.open() == null || entry.open().signum() <= 0) {
            return "NO_VALID_OPEN_PRICE";
        }
        if (entry.volume() == null || entry.volume() <= 0) {
            return "SUSPENDED_ENTRY";
        }
        KlinePointResponse previous = previousBar(bars, entryDate);
        if (previous == null || previous.close() == null) {
            return "ENTRY_REFERENCE_PRICE_UNAVAILABLE";
        }
        BigDecimal upper = previous.close().multiply(BigDecimal.ONE.add(limitRatio(input, entryDate)));
        if (sealed(entry) && entry.open().compareTo(upper.multiply(new BigDecimal("0.999"))) >= 0) {
            return "LIMIT_UP_ENTRY";
        }
        return null;
    }

    private static String exitBlockReason(
            Map<LocalDate, KlinePointResponse> bars,
            LocalDate exitDate,
            KlinePointResponse exit,
            AiSampleLabelService.SampleInput input
    ) {
        String stateBlock = exitStateBlockReason(input, exitDate);
        if (stateBlock != null) {
            return stateBlock;
        }
        if (exit == null || exit.close() == null || exit.volume() == null || exit.volume() <= 0) {
            return "SUSPENDED_OR_MISSING_EXIT";
        }
        KlinePointResponse previous = previousBar(bars, exitDate);
        if (previous == null || previous.close() == null) {
            return "EXIT_REFERENCE_PRICE_UNAVAILABLE";
        }
        BigDecimal lower = previous.close().multiply(BigDecimal.ONE.subtract(limitRatio(input, exitDate)));
        if (sealed(exit) && exit.close().compareTo(lower.multiply(new BigDecimal("1.001"))) <= 0) {
            return "LIMIT_DOWN_EXIT";
        }
        return null;
    }

    private CostCalculation calculateCosts(BigDecimal rawEntryPrice, BigDecimal rawExitPrice) {
        BigDecimal lots = config.standardPrincipal()
                .divide(rawEntryPrice.multiply(BigDecimal.valueOf(config.lotSize())), 0, RoundingMode.DOWN);
        BigDecimal quantity = lots.multiply(BigDecimal.valueOf(config.lotSize()));
        if (quantity.signum() == 0) {
            return CostCalculation.empty();
        }
        BigDecimal entryNotional = rawEntryPrice.multiply(quantity);
        BigDecimal exitNotional = rawExitPrice.multiply(quantity);
        BigDecimal buyCommission = maximum(
                entryNotional.multiply(config.buyCommissionRate()), config.minimumCommission());
        BigDecimal sellCommission = maximum(
                exitNotional.multiply(config.sellCommissionRate()), config.minimumCommission());
        BigDecimal stampDuty = exitNotional.multiply(config.stampDutyRate());
        BigDecimal transferFee = entryNotional.add(exitNotional).multiply(config.transferFeeRate());
        BigDecimal buySlippage = entryNotional.multiply(slippageRate());
        BigDecimal sellSlippage = exitNotional.multiply(slippageRate());
        BigDecimal slippage = buySlippage.add(sellSlippage);
        BigDecimal buyImpact = entryNotional.multiply(impactCostRate());
        BigDecimal sellImpact = exitNotional.multiply(impactCostRate());
        BigDecimal impactCost = buyImpact.add(sellImpact);
        BigDecimal totalCost = buyCommission.add(sellCommission).add(stampDuty)
                .add(transferFee).add(slippage).add(impactCost);
        BigDecimal invested = entryNotional.add(buyCommission)
                .add(entryNotional.multiply(config.transferFeeRate())).add(buySlippage).add(buyImpact);
        BigDecimal proceeds = exitNotional.subtract(sellCommission).subtract(stampDuty)
                .subtract(exitNotional.multiply(config.transferFeeRate())).subtract(sellSlippage)
                .subtract(sellImpact);
        BigDecimal netReturn = proceeds.divide(invested, 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
        return new CostCalculation(
                quantity,
                scaleAmount(entryNotional),
                scaleAmount(exitNotional),
                scaleAmount(buyCommission),
                scaleAmount(sellCommission),
                scaleAmount(stampDuty),
                scaleAmount(transferFee),
                scaleAmount(slippage),
                scaleAmount(impactCost),
                scaleAmount(totalCost),
                netReturn
        );
    }

    private AiSampleLabel baseLabel(
            AiSampleLabelService.SampleInput input,
            int horizon,
            AiSampleLabelService.TradingDay entry,
            AiSampleLabelService.TradingDay exit,
            String calendarVersion,
            String labelVersion
    ) {
        AiSampleLabel label = new AiSampleLabel();
        label.sampleId = input.sampleId();
        label.entryCalendarId = entry.id();
        label.exitCalendarId = exit.id();
        label.stockCode = input.stockCode();
        label.horizonTradingDays = horizon;
        label.labelVersion = labelVersion;
        label.calendarVersion = calendarVersion;
        label.sectorMembershipFingerprint = input.sectorMembershipFingerprint();
        label.entryTradeDate = entry.tradeDate();
        label.plannedExitTradeDate = exit.tradeDate();
        label.exitTradeDate = exit.tradeDate();
        label.exitDelayTradingDays = 0;
        label.fillStatus = "INVALID_SOURCE";
        label.executionStatus = "PENDING";
        label.labelStatus = "PENDING";
        label.createdAt = LocalDateTime.now();
        return label;
    }

    private AiLabelCostEvidence costEvidence(AiSampleLabel label, CostCalculation cost) {
        AiLabelCostEvidence evidence = new AiLabelCostEvidence();
        evidence.costModelVersion = config.costModelVersion();
        evidence.currency = "CNY";
        evidence.quantity = cost.quantity();
        evidence.entryNotional = cost.entryNotional();
        evidence.exitNotional = cost.exitNotional();
        evidence.buyCommissionRate = config.buyCommissionRate();
        evidence.sellCommissionRate = config.sellCommissionRate();
        evidence.stampDutyRate = config.stampDutyRate();
        evidence.transferFeeRate = config.transferFeeRate();
        evidence.slippageBps = config.slippageBps();
        evidence.impactCostBps = config.impactCostBps();
        evidence.buyCommissionAmount = cost.buyCommission();
        evidence.sellCommissionAmount = cost.sellCommission();
        evidence.stampDutyAmount = cost.stampDuty();
        evidence.transferFeeAmount = cost.transferFee();
        evidence.slippageAmount = cost.slippage();
        evidence.impactCostAmount = cost.impactCost();
        evidence.totalCostAmount = cost.totalCost();
        evidence.evidenceJson = json(Map.of(
                "standardPrincipal", config.standardPrincipal(),
                "lotSize", config.lotSize(),
                "minimumCommission", config.minimumCommission(),
                "impactCostBps", config.impactCostBps(),
                "netReturn", cost.netReturn()
        ));
        evidence.sourceFingerprint = sha256(label.inputFingerprint + "|" + evidence.evidenceJson);
        evidence.createdAt = LocalDateTime.now();
        return evidence;
    }

    private String policySnapshot() {
        return json(config);
    }

    private String marketEvidence(
            AiSampleLabelService.SampleInput input,
            AiSampleLabelService.TradingDay plannedExit,
            AiSampleLabelService.TradingDay actualExit,
            KlinePointResponse entryBar,
            String executionReason,
            List<LocalDate> blockedDates
    ) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("stockSeriesFingerprint", input.stockSeries().sourceFingerprint());
        evidence.put("benchmarkSeriesFingerprint", input.benchmarkSeries() == null
                ? null : input.benchmarkSeries().sourceFingerprint());
        evidence.put("sectorSeriesFingerprint", input.sectorSeries() == null
                ? null : input.sectorSeries().sourceFingerprint());
        evidence.put("sectorMembershipFingerprint", input.sectorMembershipFingerprint());
        evidence.put("sectorEvidenceStatus", input.sectorSeries() == null ? "UNAVAILABLE" : "AVAILABLE");
        evidence.put("plannedExitDate", plannedExit.tradeDate().toString());
        evidence.put("actualExitDate", actualExit == null ? null : actualExit.tradeDate().toString());
        evidence.put("rawEntryOpen", entryBar == null ? null : entryBar.open());
        evidence.put("executionReason", executionReason);
        evidence.put("blockedExitDates", blockedDates.stream().map(LocalDate::toString).toList());
        evidence.put("tradingStates", input.tradingStates().entrySet().stream()
                .filter(entry -> !entry.getKey().isBefore(input.signalTradeDate()))
                .filter(entry -> actualExit == null || !entry.getKey().isAfter(actualExit.tradeDate()))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.of(
                        "tradeDate", entry.getKey().toString(),
                        "sourceFingerprint", String.valueOf(entry.getValue().sourceFingerprint()),
                        "qualityStatus", String.valueOf(entry.getValue().qualityStatus()),
                        "buyTradable", String.valueOf(entry.getValue().buyTradable()),
                        "sellTradable", String.valueOf(entry.getValue().sellTradable()),
                        "limitRatio", String.valueOf(entry.getValue().limitRatio())))
                .toList());
        return json(evidence);
    }

    private String labelFingerprint(
            AiSampleLabelService.SampleInput input,
            AiSampleLabel label,
            List<AiSampleLabelService.TradingDay> calendars
    ) {
        String calendarEvidence = calendars.stream()
                .filter(day -> label.entryTradeDate != null && label.exitTradeDate != null
                        && !day.tradeDate().isBefore(label.entryTradeDate)
                        && !day.tradeDate().isAfter(label.exitTradeDate))
                .sorted(Comparator.comparing(AiSampleLabelService.TradingDay::tradeDate))
                .map(day -> day.id() + ":" + day.tradeDate() + ":" + day.sourceFingerprint())
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        return sha256(String.join("|",
                input.sampleFingerprint(),
                input.stockSeries().sourceFingerprint(),
                input.benchmarkSeries() == null ? "" : input.benchmarkSeries().sourceFingerprint(),
                input.sectorSeries() == null ? "" : input.sectorSeries().sourceFingerprint(),
                input.sectorMembershipFingerprint() == null ? "" : input.sectorMembershipFingerprint(),
                label.labelVersion,
                String.valueOf(label.horizonTradingDays),
                String.valueOf(label.executionStatus),
                String.valueOf(label.entryTradeDate),
                String.valueOf(label.exitTradeDate),
                label.policySnapshotJson,
                calendarEvidence,
                stateEvidence(input, label.entryTradeDate, label.exitTradeDate)
        ));
    }

    private static String entryStateBlockReason(AiSampleLabelService.SampleInput input, LocalDate entryDate) {
        if (!input.requiresVerifiedState()) {
            return null;
        }
        AiSampleLabelService.TradingState state = input.tradingStates().get(entryDate);
        if (state == null || !"READY".equals(state.qualityStatus())) {
            return "UNVERIFIED_ENTRY_TRADING_STATE";
        }
        return state.readyForBuy() ? null : "ENTRY_NOT_TRADABLE_STATE";
    }

    private static String exitStateBlockReason(AiSampleLabelService.SampleInput input, LocalDate exitDate) {
        if (!input.requiresVerifiedState()) {
            return null;
        }
        AiSampleLabelService.TradingState state = input.tradingStates().get(exitDate);
        if (state == null || !"READY".equals(state.qualityStatus())) {
            return "UNVERIFIED_EXIT_TRADING_STATE";
        }
        return state.readyForSell() ? null : "EXIT_NOT_TRADABLE_STATE";
    }

    private static String stateEvidence(
            AiSampleLabelService.SampleInput input,
            LocalDate entryDate,
            LocalDate exitDate
    ) {
        return input.tradingStates().entrySet().stream()
                .filter(entry -> entryDate != null && exitDate != null
                        && !entry.getKey().isBefore(entryDate) && !entry.getKey().isAfter(exitDate))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue().sourceFingerprint()
                        + ":" + entry.getValue().qualityStatus() + ":" + entry.getValue().buyTradable()
                        + ":" + entry.getValue().sellTradable())
                .reduce((left, right) -> left + "|" + right).orElse("");
    }

    private void validate(
            AiSampleLabelService.SampleInput input,
            int horizon,
            List<AiSampleLabelService.TradingDay> calendars,
            String calendarVersion,
            String labelVersion,
            LocalDateTime verifiedAt
    ) {
        if (input == null || input.sampleId() == null || input.stockCode() == null
                || input.signalTradeDate() == null || input.sampleFingerprint() == null
                || input.sampleFingerprint().isBlank() || calendars == null || calendars.isEmpty()
                || calendarVersion == null || labelVersion == null || verifiedAt == null) {
            throw new IllegalArgumentException("标签输入缺少样本、日历、版本或验证时间");
        }
        if (!List.of(1, 2, 3, 5).contains(horizon)) {
            throw new IllegalArgumentException("标签周期仅支持 T+1/T+2/T+3/T+5");
        }
        if (!config.labelVersion().equals(labelVersion)) {
            throw new IllegalArgumentException("标签版本必须与固化的策略配置一致");
        }
        if (calendars.stream().anyMatch(day -> day == null || day.id() == null || day.tradeDate() == null
                || !calendarVersion.equals(day.calendarVersion())
                || day.sourceFingerprint() == null || day.sourceFingerprint().isBlank())) {
            throw new IllegalArgumentException("交易日历证据不完整");
        }
        validateSeries(input.stockSeries(), input.stockCode(), verifiedAt);
        if (input.benchmarkSeries() != null) {
            validateSeries(input.benchmarkSeries(), null, verifiedAt);
        }
        if (input.sectorSeries() != null) {
            if (input.requiresVerifiedState()
                    && (input.sectorMembershipFingerprint() == null
                    || input.sectorMembershipFingerprint().isBlank())) {
                throw new IllegalArgumentException("严格研究标签的行业行情必须关联可验证的行业归属证据");
            }
            validateSeries(input.sectorSeries(), null, verifiedAt);
        }
    }

    private static void validateSeries(
            KlineSeriesSnapshot series,
            String expectedCode,
            LocalDateTime verifiedAt
    ) {
        if (series == null || !series.fingerprintMatches()
                || !"NONE".equalsIgnoreCase(series.adjustmentMode())
                || !"DAY".equalsIgnoreCase(series.period())
                || invalidSource(series.source())
                || series.asOfTime() == null || series.asOfTime().isAfter(verifiedAt)
                || series.fetchedAt() == null || series.fetchedAt().isAfter(verifiedAt)) {
            throw new IllegalArgumentException("标签必须使用可验证、真实来源的时点化未复权日线");
        }
        if (series.points() == null || series.points().stream().anyMatch(point -> point == null
                || point.tradeDate() == null || point.tradeDate().isAfter(series.asOfTime().toLocalDate()))) {
            throw new IllegalArgumentException("标签日线包含研究时点后的数据");
        }
        if (expectedCode != null && !normalizeCode(expectedCode).equals(normalizeCode(series.symbol()))) {
            throw new IllegalArgumentException("标签日线股票代码与样本不一致");
        }
    }

    private static boolean invalidSource(String source) {
        if (source == null || source.isBlank()) {
            return true;
        }
        String normalized = source.toUpperCase();
        return normalized.contains("MOCK")
                || normalized.contains("FALLBACK")
                || normalized.contains("UNAVAILABLE");
    }

    private static boolean isSessionMature(AiSampleLabelService.TradingDay day, LocalDateTime verifiedAt) {
        return !verifiedAt.isBefore(closeAt(day));
    }

    private static LocalDateTime closeAt(AiSampleLabelService.TradingDay day) {
        return day.tradeDate().atTime(day.sessionCloseTime() == null ? LocalTime.of(15, 0) : day.sessionCloseTime());
    }

    private static Map<LocalDate, KlinePointResponse> barsByDate(KlineSeriesSnapshot series) {
        Map<LocalDate, KlinePointResponse> result = new LinkedHashMap<>();
        series.points().stream()
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                .forEach(bar -> result.put(bar.tradeDate(), bar));
        return result;
    }

    private static KlinePointResponse previousBar(Map<LocalDate, KlinePointResponse> bars, LocalDate date) {
        return bars.values().stream()
                .filter(bar -> bar.tradeDate().isBefore(date))
                .max(Comparator.comparing(KlinePointResponse::tradeDate))
                .orElse(null);
    }

    private static boolean sealed(KlinePointResponse bar) {
        return bar.open() != null && bar.high() != null && bar.low() != null
                && bar.open().compareTo(bar.high()) == 0
                && bar.open().compareTo(bar.low()) == 0;
    }

    private static BigDecimal limitRatio(AiSampleLabelService.SampleInput input, LocalDate tradeDate) {
        AiSampleLabelService.TradingState state = input.tradingStates().get(tradeDate);
        if (state != null && state.limitRatio() != null) {
            return state.limitRatio();
        }
        if (input.tradableStatus() != null && input.tradableStatus().toUpperCase().contains("ST")) {
            return new BigDecimal("0.05");
        }
        String code = normalizeCode(input.stockCode());
        return code.startsWith("300") || code.startsWith("301") || code.startsWith("688")
                ? new BigDecimal("0.20")
                : new BigDecimal("0.10");
    }

    private static BigDecimal seriesReturn(KlineSeriesSnapshot series, LocalDate entryDate, LocalDate exitDate) {
        if (series == null) {
            return null;
        }
        Map<LocalDate, KlinePointResponse> bars = barsByDate(series);
        KlinePointResponse entry = bars.get(entryDate);
        KlinePointResponse exit = bars.get(exitDate);
        if (entry == null || exit == null || entry.open() == null || exit.close() == null) {
            return null;
        }
        return returnRate(exit.close(), entry.open());
    }

    private static BigDecimal returnRate(BigDecimal end, BigDecimal start) {
        return end.divide(start, 12, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal slippageRate() {
        return config.slippageBps().divide(TEN_THOUSAND, 12, RoundingMode.HALF_UP);
    }

    private BigDecimal impactCostRate() {
        return config.impactCostBps().divide(TEN_THOUSAND, 12, RoundingMode.HALF_UP);
    }

    private BigDecimal executionFrictionRate() {
        return slippageRate().add(impactCostRate());
    }

    private static boolean invalidSourceReason(String reason) {
        return reason != null && (reason.startsWith("UNVERIFIED_")
                || reason.startsWith("NO_VALID_")
                || reason.endsWith("_REFERENCE_PRICE_UNAVAILABLE")
                || reason.startsWith("MISSING_"));
    }

    private static BigDecimal maxDrawdown(List<KlinePointResponse> bars, BigDecimal entryPrice) {
        if (entryPrice == null || entryPrice.signum() <= 0 || bars == null || bars.isEmpty()) {
            return null;
        }
        BigDecimal runningPeak = entryPrice;
        BigDecimal worst = BigDecimal.ZERO;
        for (KlinePointResponse bar : bars) {
            if (bar.high() != null && bar.high().compareTo(runningPeak) > 0) {
                runningPeak = bar.high();
            }
            if (bar.low() != null && runningPeak.signum() > 0) {
                BigDecimal drawdown = returnRate(bar.low(), runningPeak);
                if (drawdown.compareTo(worst) < 0) {
                    worst = drawdown;
                }
            }
        }
        return worst.setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal holdingVolatility(List<KlinePointResponse> bars, BigDecimal entryPrice) {
        if (entryPrice == null || entryPrice.signum() <= 0 || bars == null || bars.isEmpty()) {
            return null;
        }
        List<BigDecimal> returns = new ArrayList<>();
        BigDecimal previous = entryPrice;
        for (KlinePointResponse bar : bars) {
            if (bar.close() == null || bar.close().signum() <= 0) {
                continue;
            }
            returns.add(returnRate(bar.close(), previous));
            previous = bar.close();
        }
        if (returns.isEmpty()) {
            return null;
        }
        BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 12, RoundingMode.HALF_UP);
        BigDecimal variance = returns.stream()
                .map(value -> value.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 12, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(Math.sqrt(variance.doubleValue())).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal maximum(BigDecimal left, BigDecimal right) {
        return left.max(right);
    }

    private static BigDecimal scalePrice(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaleAmount(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP);
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

    private static String normalizeCode(String value) {
        String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(digits.length() - 6) : digits;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化标签证据", exception);
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

    public record PolicyConfig(
            String labelVersion,
            String costModelVersion,
            BigDecimal standardPrincipal,
            int lotSize,
            BigDecimal buyCommissionRate,
            BigDecimal sellCommissionRate,
            BigDecimal minimumCommission,
            BigDecimal stampDutyRate,
            BigDecimal transferFeeRate,
            BigDecimal slippageBps,
            BigDecimal impactCostBps,
            int maxExitDelayTradingDays,
            BigDecimal takeProfitRate,
            BigDecimal stopLossRate
    ) {
    }

    public record BuildResult(AiSampleLabel label, AiLabelCostEvidence costEvidence) {
    }

    public enum BarrierOutcome {
        NOT_TRIGGERED,
        TAKE_PROFIT,
        STOP_LOSS,
        REQUIRES_MINUTE_SEQUENCE
    }

    private record ExitResolution(
            AiSampleLabelService.TradingDay day,
            KlinePointResponse bar,
            boolean blocked,
            String reason,
            List<LocalDate> blockedDates
    ) {
        private ExitResolution {
            blockedDates = List.copyOf(blockedDates);
        }
    }

    private record CostCalculation(
            BigDecimal quantity,
            BigDecimal entryNotional,
            BigDecimal exitNotional,
            BigDecimal buyCommission,
            BigDecimal sellCommission,
            BigDecimal stampDuty,
            BigDecimal transferFee,
            BigDecimal slippage,
            BigDecimal impactCost,
            BigDecimal totalCost,
            BigDecimal netReturn
    ) {
        private static CostCalculation empty() {
            BigDecimal zero = BigDecimal.ZERO.setScale(6);
            return new CostCalculation(
                    BigDecimal.ZERO, zero, zero, zero, zero, zero, zero, zero, zero, zero, null);
        }
    }
}
