package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiLabelCostEvidence;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.service.research.AiSampleLabelService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLabelPolicyTest {

    private static final LocalDate SIGNAL_DATE = LocalDate.parse("2026-07-10");
    private static final LocalDateTime VERIFIED_AT = LocalDateTime.parse("2026-07-22T16:00:00");
    private static final String CALENDAR_VERSION = "CN_A_CALENDAR/2026.1";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final DefaultLabelPolicy policy = new DefaultLabelPolicy(objectMapper);

    @Test
    void onePriceLimitUpAndSuspensionAreUnfilledRatherThanFalseLosses() {
        DefaultLabelPolicy.BuildResult limitUp = build(
                1,
                stockSeries(List.of(
                        bar("2026-07-10", "10", "10", "9.8", "10.2", 1000),
                        bar("2026-07-13", "11", "11", "11", "11", 1000)
                )),
                calendars("2026-07-13")
        );
        DefaultLabelPolicy.BuildResult suspended = build(
                1,
                stockSeries(List.of(
                        bar("2026-07-10", "10", "10", "9.8", "10.2", 1000),
                        bar("2026-07-13", "10", "10", "10", "10", 0)
                )),
                calendars("2026-07-13")
        );

        assertThat(limitUp.label().executionStatus).isEqualTo("UNFILLED");
        assertThat(limitUp.label().fillStatus).isEqualTo("UNFILLED");
        assertThat(limitUp.label().executionReason).isEqualTo("LIMIT_UP_ENTRY");
        assertThat(limitUp.label().netReturn).isNull();
        assertThat(limitUp.costEvidence()).isNull();
        assertThat(suspended.label().executionStatus).isEqualTo("UNFILLED");
        assertThat(suspended.label().executionReason).isEqualTo("SUSPENDED_ENTRY");
    }

    @Test
    void limitDownExitIsDelayedToNextTradableDay() {
        DefaultLabelPolicy.BuildResult result = build(
                2,
                stockSeries(List.of(
                        bar("2026-07-10", "10", "10", "9.8", "10.2", 1000),
                        bar("2026-07-13", "10", "10", "9.8", "10.2", 1000),
                        bar("2026-07-14", "9", "9", "9", "9", 1000),
                        bar("2026-07-15", "9.1", "9.3", "9", "9.4", 1000)
                )),
                calendars("2026-07-13", "2026-07-14", "2026-07-15")
        );

        assertThat(result.label().executionStatus).isEqualTo("EXECUTED");
        assertThat(result.label().fillStatus).isEqualTo("FILLED");
        assertThat(result.label().exitTradeDate).isEqualTo(LocalDate.parse("2026-07-15"));
        assertThat(result.label().executionReason).isEqualTo("EXIT_DELAYED_1_TRADING_DAYS");
        assertThat(result.label().marketEvidenceJson).contains("2026-07-14");
    }

    @Test
    void exitBlockedForMoreThanFiveTradingDaysDoesNotInventExecutableReturn() {
        DefaultLabelPolicy.BuildResult result = build(
                2,
                stockSeries(List.of(
                        bar("2026-07-10", "10", "10", "9.8", "10.2", 1000),
                        bar("2026-07-13", "10", "10", "9.8", "10.2", 1000),
                        bar("2026-07-14", "9", "9", "9", "9", 0),
                        bar("2026-07-15", "9", "9", "9", "9", 0),
                        bar("2026-07-16", "9", "9", "9", "9", 0),
                        bar("2026-07-17", "9", "9", "9", "9", 0),
                        bar("2026-07-20", "9", "9", "9", "9", 0),
                        bar("2026-07-21", "9", "9", "9", "9", 0)
                )),
                calendars(
                        "2026-07-13", "2026-07-14", "2026-07-15", "2026-07-16",
                        "2026-07-17", "2026-07-20", "2026-07-21")
        );

        assertThat(result.label().executionStatus).isEqualTo("EXIT_BLOCKED");
        assertThat(result.label().fillStatus).isEqualTo("PARTIAL");
        assertThat(result.label().netReturn).isNull();
        assertThat(result.label().exitTradeDate).isEqualTo(LocalDate.parse("2026-07-21"));
        assertThat(result.costEvidence()).isNull();
    }

    @Test
    void standardPrincipalUsesBoardLotsMinimumCommissionAndVersionedPolicySnapshot() throws Exception {
        DefaultLabelPolicy.PolicyConfig config = new DefaultLabelPolicy.PolicyConfig(
                DefaultLabelPolicy.VERSION,
                "COST/TEST/1.0.0",
                new BigDecimal("100000"),
                100,
                new BigDecimal("0.00001"),
                new BigDecimal("0.00001"),
                new BigDecimal("5.00"),
                new BigDecimal("0.0005"),
                new BigDecimal("0.00001"),
                new BigDecimal("2.0"),
                new BigDecimal("3.0"),
                5,
                new BigDecimal("0.08"),
                new BigDecimal("-0.08")
        );
        DefaultLabelPolicy lowCommissionPolicy = new DefaultLabelPolicy(objectMapper, config);
        DefaultLabelPolicy.BuildResult result = lowCommissionPolicy.build(
                sample(stockSeries(List.of(
                        bar("2026-07-10", "500", "500", "495", "505", 1000),
                        bar("2026-07-13", "500", "505", "498", "506", 1000)
                ))),
                1,
                calendars("2026-07-13"),
                CALENDAR_VERSION,
                DefaultLabelPolicy.VERSION,
                VERIFIED_AT
        );

        AiLabelCostEvidence cost = result.costEvidence();
        assertThat(cost.quantity).isEqualByComparingTo("200");
        assertThat(cost.buyCommissionAmount).isEqualByComparingTo("5.00");
        assertThat(cost.sellCommissionAmount).isEqualByComparingTo("5.00");
        assertThat(cost.impactCostBps).isEqualByComparingTo("3.0");
        assertThat(cost.impactCostAmount).isPositive();
        JsonNode snapshot = objectMapper.readTree(result.label().policySnapshotJson);
        assertThat(snapshot.path("labelVersion").asText()).isEqualTo(DefaultLabelPolicy.VERSION);
        assertThat(snapshot.path("standardPrincipal").decimalValue()).isEqualByComparingTo("100000");
        assertThat(snapshot.path("minimumCommission").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(result.label().inputFingerprint).hasSize(64);
    }

    @Test
    void sameDayTargetAndStopUsesConservativeStopFirstWithoutMinuteSequence() {
        KlinePointResponse bar = bar("2026-07-13", "10", "10", "9", "11", 1000);

        assertThat(policy.evaluateSameDayBarrier(new BigDecimal("10"), bar, false))
                .isEqualTo(DefaultLabelPolicy.BarrierOutcome.STOP_LOSS);
        assertThat(policy.evaluateSameDayBarrier(new BigDecimal("10"), bar, true))
                .isEqualTo(DefaultLabelPolicy.BarrierOutcome.REQUIRES_MINUTE_SEQUENCE);
    }

    @Test
    void policyVersionCannotBeReusedWithDifferentConfiguration() {
        assertThatThrownBy(() -> policy.build(
                sample(stockSeries(List.of(
                        bar("2026-07-10", "10", "10", "9.8", "10.2", 1000),
                        bar("2026-07-13", "10", "10.1", "9.9", "10.2", 1000)
                ))),
                1,
                calendars("2026-07-13"),
                CALENDAR_VERSION,
                "LABEL/2.0.0",
                VERIFIED_AT
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("标签版本");
    }

    @Test
    void verifiedTradingStatesAreRequiredForResearchLabelsAndParticipateInFingerprint() {
        KlineSeriesSnapshot series = stockSeries(List.of(
                bar("2026-07-10", "10", "10", "9.8", "10.2", 1000),
                bar("2026-07-13", "10", "10.1", "9.9", "10.2", 1000),
                bar("2026-07-14", "10.2", "10.3", "10.1", "10.4", 1000)
        ));
        List<AiSampleLabelService.TradingDay> days = calendars("2026-07-13", "2026-07-14");
        AiSampleLabelService.SampleInput missing = strictSample(series, Map.of());
        AiSampleLabelService.SampleInput first = strictSample(series, Map.of(
                LocalDate.parse("2026-07-13"), state("state-13-v1"),
                LocalDate.parse("2026-07-14"), state("state-14-v1")));
        AiSampleLabelService.SampleInput revised = strictSample(series, Map.of(
                LocalDate.parse("2026-07-13"), state("state-13-v2"),
                LocalDate.parse("2026-07-14"), state("state-14-v1")));

        DefaultLabelPolicy.BuildResult blocked = policy.build(missing, 1, days, CALENDAR_VERSION,
                DefaultLabelPolicy.VERSION, VERIFIED_AT);
        DefaultLabelPolicy.BuildResult initial = policy.build(first, 1, days, CALENDAR_VERSION,
                DefaultLabelPolicy.VERSION, VERIFIED_AT);
        DefaultLabelPolicy.BuildResult changed = policy.build(revised, 1, days, CALENDAR_VERSION,
                DefaultLabelPolicy.VERSION, VERIFIED_AT);

        assertThat(blocked.label().executionStatus).isEqualTo("UNFILLED");
        assertThat(blocked.label().fillStatus).isEqualTo("INVALID_SOURCE");
        assertThat(blocked.label().executionReason).isEqualTo("UNVERIFIED_ENTRY_TRADING_STATE");
        assertThat(initial.label().executionStatus).isEqualTo("EXECUTED");
        assertThat(changed.label().inputFingerprint).isNotEqualTo(initial.label().inputFingerprint);
        assertThat(changed.label().marketEvidenceJson).contains("state-13-v2");
    }

    @Test
    void filledLabelIncludesRelativeSectorRiskAndHoldingMetrics() {
        KlineSeriesSnapshot stock = stockSeries(List.of(
                bar("2026-07-10", "10", "10", "9.8", "10.2", 1000),
                bar("2026-07-13", "10", "10.5", "9", "11", 1000),
                bar("2026-07-14", "10.5", "11", "10", "12", 1000)
        ));
        KlineSeriesSnapshot benchmark = stockSeries(List.of(
                bar("2026-07-13", "100", "100.5", "99", "101", 1000),
                bar("2026-07-14", "100.5", "101", "100", "102", 1000)
        ));
        KlineSeriesSnapshot sector = stockSeries(List.of(
                bar("2026-07-13", "100", "101", "99", "102", 1000),
                bar("2026-07-14", "101", "102", "100", "103", 1000)
        ));
        AiSampleLabelService.SampleInput input = new AiSampleLabelService.SampleInput(
                101L, "600519", SIGNAL_DATE, "NORMAL", "sample-fingerprint-101",
                stock, benchmark, sector);

        DefaultLabelPolicy.BuildResult result = policy.build(
                input, 2, calendars("2026-07-13", "2026-07-14"),
                CALENDAR_VERSION, DefaultLabelPolicy.VERSION, VERIFIED_AT);

        assertThat(result.label().fillStatus).isEqualTo("FILLED");
        assertThat(result.label().plannedExitTradeDate).isEqualTo(LocalDate.parse("2026-07-14"));
        assertThat(result.label().exitDelayTradingDays).isZero();
        assertThat(result.label().holdingTradingDays).isEqualTo(2);
        assertThat(result.label().sectorExcessReturn).isNotNull();
        assertThat(result.label().sectorMembershipFingerprint).isNull();
        assertThat(result.label().marketEvidenceJson).contains("\"sectorEvidenceStatus\":\"AVAILABLE\"");
        assertThat(result.label().maxDrawdown).isNegative();
        assertThat(result.label().holdingVolatility).isNotNegative();
        assertThat(result.costEvidence().impactCostAmount).isPositive();
    }

    @Test
    void revisedSectorEvidenceChangesImmutableLabelFingerprint() {
        KlineSeriesSnapshot stock = stockSeries(List.of(
                bar("2026-07-10", "10", "10", "9.8", "10.2", 1000),
                bar("2026-07-13", "10", "10.5", "9.9", "10.6", 1000),
                bar("2026-07-14", "10.5", "11", "10.4", "11.2", 1000)
        ));
        KlineSeriesSnapshot initialSector = stockSeries(List.of(
                bar("2026-07-13", "100", "101", "99", "102", 1000),
                bar("2026-07-14", "101", "102", "100", "103", 1000)
        ));
        KlineSeriesSnapshot revisedSector = stockSeries(List.of(
                bar("2026-07-13", "100", "101", "99", "102", 1000),
                bar("2026-07-14", "101", "99", "98", "102", 1000)
        ));
        AiSampleLabelService.SampleInput initial = new AiSampleLabelService.SampleInput(
                101L, "600519", SIGNAL_DATE, "NORMAL", "sample-fingerprint-101",
                stock, null, initialSector);
        AiSampleLabelService.SampleInput revised = new AiSampleLabelService.SampleInput(
                101L, "600519", SIGNAL_DATE, "NORMAL", "sample-fingerprint-101",
                stock, null, revisedSector);

        DefaultLabelPolicy.BuildResult first = policy.build(initial, 2,
                calendars("2026-07-13", "2026-07-14"), CALENDAR_VERSION,
                DefaultLabelPolicy.VERSION, VERIFIED_AT);
        DefaultLabelPolicy.BuildResult second = policy.build(revised, 2,
                calendars("2026-07-13", "2026-07-14"), CALENDAR_VERSION,
                DefaultLabelPolicy.VERSION, VERIFIED_AT);

        assertThat(second.label().sectorExcessReturn).isNotEqualByComparingTo(
                first.label().sectorExcessReturn);
        assertThat(second.label().inputFingerprint).isNotEqualTo(first.label().inputFingerprint);
    }

    @Test
    void strictSectorEvidenceRequiresMembershipFingerprint() {
        KlineSeriesSnapshot series = stockSeries(List.of(
                bar("2026-07-10", "10", "10", "9.8", "10.2", 1000),
                bar("2026-07-13", "10", "10.5", "9.9", "10.6", 1000)
        ));
        AiSampleLabelService.SampleInput input = new AiSampleLabelService.SampleInput(
                101L, "600519", SIGNAL_DATE, "NORMAL", "sample-fingerprint-101",
                series, null, series, null, Map.of(
                LocalDate.parse("2026-07-13"), state("state-13-v1")), true);

        assertThatThrownBy(() -> policy.build(input, 1, calendars("2026-07-13"),
                CALENDAR_VERSION, DefaultLabelPolicy.VERSION, VERIFIED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("行业归属证据");
    }

    @Test
    void revisedSectorMembershipChangesImmutableLabelFingerprint() {
        KlineSeriesSnapshot series = stockSeries(List.of(
                bar("2026-07-10", "10", "10", "9.8", "10.2", 1000),
                bar("2026-07-13", "10", "10.5", "9.9", "10.6", 1000)
        ));
        Map<LocalDate, AiSampleLabelService.TradingState> states = Map.of(
                LocalDate.parse("2026-07-13"), state("state-13-v1"));
        AiSampleLabelService.SampleInput initial = new AiSampleLabelService.SampleInput(
                101L, "600519", SIGNAL_DATE, "NORMAL", "sample-fingerprint-101",
                series, null, series, "membership-v1", states, true);
        AiSampleLabelService.SampleInput revised = new AiSampleLabelService.SampleInput(
                101L, "600519", SIGNAL_DATE, "NORMAL", "sample-fingerprint-101",
                series, null, series, "membership-v2", states, true);

        DefaultLabelPolicy.BuildResult first = policy.build(initial, 1,
                calendars("2026-07-13"), CALENDAR_VERSION, DefaultLabelPolicy.VERSION, VERIFIED_AT);
        DefaultLabelPolicy.BuildResult second = policy.build(revised, 1,
                calendars("2026-07-13"), CALENDAR_VERSION, DefaultLabelPolicy.VERSION, VERIFIED_AT);

        assertThat(second.label().inputFingerprint).isNotEqualTo(first.label().inputFingerprint);
        assertThat(second.label().marketEvidenceJson).contains("membership-v2");
        assertThat(second.label().sectorMembershipFingerprint).isEqualTo("membership-v2");
    }

    @Test
    void missingSectorEvidenceIsExplicitAndNeverConvertedToZeroReturn() {
        DefaultLabelPolicy.BuildResult result = build(
                1,
                stockSeries(List.of(
                        bar("2026-07-10", "10", "10", "9.8", "10.2", 1000),
                        bar("2026-07-13", "10", "10.2", "9.9", "10.3", 1000)
                )),
                calendars("2026-07-13")
        );

        assertThat(result.label().sectorReturn).isNull();
        assertThat(result.label().sectorExcessReturn).isNull();
        assertThat(result.label().marketEvidenceJson)
                .contains("\"sectorEvidenceStatus\":\"UNAVAILABLE\"");
    }

    private DefaultLabelPolicy.BuildResult build(
            int horizon,
            KlineSeriesSnapshot series,
            List<AiSampleLabelService.TradingDay> calendars
    ) {
        return policy.build(
                sample(series),
                horizon,
                calendars,
                CALENDAR_VERSION,
                DefaultLabelPolicy.VERSION,
                VERIFIED_AT
        );
    }

    private static AiSampleLabelService.SampleInput sample(KlineSeriesSnapshot series) {
        return new AiSampleLabelService.SampleInput(
                101L,
                "600519",
                SIGNAL_DATE,
                "NORMAL",
                "sample-fingerprint-101",
                series,
                null,
                null
        );
    }

    private static AiSampleLabelService.SampleInput strictSample(
            KlineSeriesSnapshot series,
            Map<LocalDate, AiSampleLabelService.TradingState> states
    ) {
        return new AiSampleLabelService.SampleInput(
                101L, "600519", SIGNAL_DATE, "NORMAL", "sample-fingerprint-101",
                series, null, null, null, states, true);
    }

    private static AiSampleLabelService.TradingState state(String fingerprint) {
        return new AiSampleLabelService.TradingState(
                LocalDate.parse("2026-07-13"), fingerprint, "READY", 1, 1, 0, 0,
                new BigDecimal("0.100000"), 0, 0);
    }

    private static KlineSeriesSnapshot stockSeries(List<KlinePointResponse> points) {
        return KlineSeriesSnapshot.create(
                "600519",
                "DAY",
                "NONE",
                "AKSHARE_HTTP",
                LocalDateTime.parse("2026-07-22T15:10:00"),
                LocalDateTime.parse("2026-07-22T15:11:00"),
                points
        );
    }

    private static List<AiSampleLabelService.TradingDay> calendars(String... dates) {
        return java.util.stream.IntStream.range(0, dates.length)
                .mapToObj(index -> new AiSampleLabelService.TradingDay(
                        1000L + index,
                        LocalDate.parse(dates[index]),
                        true,
                        LocalTime.of(15, 0),
                        CALENDAR_VERSION,
                        "calendar-" + dates[index]
                ))
                .toList();
    }

    private static KlinePointResponse bar(
            String date,
            String open,
            String close,
            String low,
            String high,
            long volume
    ) {
        return new KlinePointResponse(
                LocalDate.parse(date),
                new BigDecimal(open),
                new BigDecimal(close),
                new BigDecimal(low),
                new BigDecimal(high),
                volume,
                BigDecimal.ZERO
        );
    }
}
