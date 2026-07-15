package com.maogou.stock.service.impl.research;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AiTrainingReadinessGateTest {

    @Test
    void reportsEveryRemainingColdStartRequirementPrecisely() {
        AiTrainingReadinessGate.Readiness result = new AiTrainingReadinessGate().evaluate(
                new AiTrainingReadinessGate.Evidence(
                        95,
                        180,
                        Map.of(1, 19_500, 2, 20_000, 3, 18_000, 5, 21_000),
                        Map.of("BULL", 20, "BEAR", 8, "RANGE", 0)));

        assertThat(result.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.remainingTradingDays()).isEqualTo(25);
        assertThat(result.remainingStocks()).isEqualTo(20);
        assertThat(result.remainingLabels()).containsExactlyInAnyOrderEntriesOf(
                Map.of(1, 500, 2, 0, 3, 2_000, 5, 0));
        assertThat(result.missingRegimes()).containsExactlyInAnyOrder("DOWN", "SIDEWAYS");
        assertThat(result.remainingRegimeDays()).containsEntry("DOWN", 12).containsEntry("SIDEWAYS", 20);
    }

    @Test
    void becomesReadyOnlyWhenAllCoreHorizonsAndRegimesPass() {
        AiTrainingReadinessGate.Readiness result = new AiTrainingReadinessGate().evaluate(
                new AiTrainingReadinessGate.Evidence(
                        120,
                        200,
                        Map.of(1, 20_000, 2, 20_100, 3, 25_000, 5, 20_000),
                        Map.of("UP", 20, "DOWN", 22, "SIDEWAYS", 21)));

        assertThat(result.status()).isEqualTo("READY");
        assertThat(result.remainingTradingDays()).isZero();
        assertThat(result.remainingStocks()).isZero();
        assertThat(result.remainingLabels().values()).containsOnly(0);
        assertThat(result.missingRegimes()).isEmpty();
    }

    @Test
    void doesNotCountUnknownRegimesOrUntrackedHorizonsAsCoverage() {
        AiTrainingReadinessGate.Readiness result = new AiTrainingReadinessGate().evaluate(
                new AiTrainingReadinessGate.Evidence(
                        200,
                        500,
                        Map.of(3, 50_000, 10, 80_000),
                        Map.of("UNKNOWN", 200, "UNCLASSIFIED", 200)));

        assertThat(result.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.remainingLabels()).containsEntry(1, 20_000)
                .containsEntry(2, 20_000).containsEntry(5, 20_000);
        assertThat(result.missingRegimes()).containsExactlyInAnyOrder("UP", "DOWN", "SIDEWAYS");
    }
}
