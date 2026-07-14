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
