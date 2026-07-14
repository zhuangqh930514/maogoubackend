package com.maogou.stock.service.impl.research;

import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiPointInTimeGate;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AiPointInTimeGateImplTest {

    private final AiPointInTimeGate gate = new AiPointInTimeGateImpl(
            new FixedTradingCalendar(Set.of(LocalDate.parse("2026-07-15")))
    );

    @Test
    void afterCloseResearchWaitsForTheCurrentCompleteKlineAndMarketCloses() {
        AiPointInTimeGate.GateResult result = gate.evaluate(new AiPointInTimeGate.GateInput(
                LocalDateTime.parse("2026-07-14T16:00:00"),
                AiPointInTimeGate.RunMode.AFTER_CLOSE_RESEARCH,
                LocalDate.parse("2026-07-13"),
                LocalDate.parse("2026-07-14"),
                LocalDate.parse("2026-07-14"),
                true,
                true
        ));

        assertThat(result.status()).isEqualTo(AiPointInTimeGate.GateStatus.WAITING_SOURCE);
        assertThat(result.expectedCompleteTradeDate()).isEqualTo(LocalDate.parse("2026-07-14"));
        assertThat(result.reasons()).containsExactly("当日完整日K尚未就绪");
    }

    @Test
    void liveAnalysisBeforeCloseUsesThePreviousCompleteTradingDay() {
        AiPointInTimeGate.GateResult result = gate.evaluate(readyInput(
                "2026-07-14T10:30:00",
                AiPointInTimeGate.RunMode.LIVE_ANALYSIS,
                "2026-07-13"
        ));

        assertThat(result.status()).isEqualTo(AiPointInTimeGate.GateStatus.READY);
        assertThat(result.expectedCompleteTradeDate()).isEqualTo(LocalDate.parse("2026-07-13"));
    }

    @Test
    void weekendAndHolidayUseThePreviousVersionedTradingDay() {
        AiPointInTimeGate.GateResult holiday = gate.evaluate(readyInput(
                "2026-07-15T16:00:00",
                AiPointInTimeGate.RunMode.AFTER_CLOSE_RESEARCH,
                "2026-07-14"
        ));
        AiPointInTimeGate.GateResult weekend = gate.evaluate(readyInput(
                "2026-07-18T16:00:00",
                AiPointInTimeGate.RunMode.AFTER_CLOSE_RESEARCH,
                "2026-07-17"
        ));

        assertThat(holiday.expectedCompleteTradeDate()).isEqualTo(LocalDate.parse("2026-07-14"));
        assertThat(weekend.expectedCompleteTradeDate()).isEqualTo(LocalDate.parse("2026-07-17"));
        assertThat(holiday.status()).isEqualTo(AiPointInTimeGate.GateStatus.READY);
        assertThat(weekend.status()).isEqualTo(AiPointInTimeGate.GateStatus.READY);
    }

    @Test
    void missingFinanceOrNewsOnlyMakesEligibleCoreDataPartial() {
        AiPointInTimeGate.GateResult result = gate.evaluate(new AiPointInTimeGate.GateInput(
                LocalDateTime.parse("2026-07-14T16:00:00"),
                AiPointInTimeGate.RunMode.AFTER_CLOSE_RESEARCH,
                LocalDate.parse("2026-07-14"),
                LocalDate.parse("2026-07-14"),
                LocalDate.parse("2026-07-14"),
                false,
                false
        ));

        assertThat(result.status()).isEqualTo(AiPointInTimeGate.GateStatus.PARTIAL);
        assertThat(result.reasons()).containsExactly(
                "财务数据缺失，相关因子降级为缺失",
                "资讯数据缺失，相关因子降级为缺失"
        );
    }

    @Test
    void announcementPublishedAfterTheCutoffIsFutureData() {
        AiPointInTimeGate.ObservationResult result = gate.evaluateObservation(
                new AiPointInTimeGate.ObservationInput(
                        "FINANCE",
                        LocalDateTime.parse("2026-07-14T18:00:00"),
                        LocalDateTime.parse("2026-07-14T18:00:00"),
                        LocalDateTime.parse("2026-07-14T15:40:00"),
                        LocalDateTime.parse("2026-07-14T15:41:00"),
                        LocalDateTime.parse("2026-07-14T16:00:00")
                )
        );

        assertThat(result.status()).isEqualTo(AiPointInTimeGate.ObservationStatus.FUTURE_DATA);
        assertThat(result.eligibleForSample()).isFalse();
    }

    @Test
    void marketDataWithoutPublishedTimeUsesExchangeEventTimeInsteadOfFetchTime() {
        AiPointInTimeGate.ObservationResult result = gate.evaluateObservation(
                new AiPointInTimeGate.ObservationInput(
                        "KLINE",
                        LocalDateTime.parse("2026-07-14T15:00:00"),
                        null,
                        LocalDateTime.parse("2026-07-14T15:00:10"),
                        LocalDateTime.parse("2026-07-14T15:00:11"),
                        LocalDateTime.parse("2026-07-14T16:00:00")
                )
        );

        assertThat(result.status()).isEqualTo(AiPointInTimeGate.ObservationStatus.ELIGIBLE);
        assertThat(result.effectivePublishedAt()).isEqualTo(LocalDateTime.parse("2026-07-14T15:00:00"));
        assertThat(result.eligibleForSample()).isTrue();
    }

    @Test
    void invalidDiscoveryTimelineNeverEntersTheSample() {
        AiPointInTimeGate.ObservationResult result = gate.evaluateObservation(
                new AiPointInTimeGate.ObservationInput(
                        "NEWS",
                        LocalDateTime.parse("2026-07-14T14:00:00"),
                        LocalDateTime.parse("2026-07-14T14:00:00"),
                        LocalDateTime.parse("2026-07-14T14:10:00"),
                        LocalDateTime.parse("2026-07-14T14:09:59"),
                        LocalDateTime.parse("2026-07-14T16:00:00")
                )
        );

        assertThat(result.status()).isEqualTo(AiPointInTimeGate.ObservationStatus.INVALID_TIMELINE);
        assertThat(result.eligibleForSample()).isFalse();
    }

    private static AiPointInTimeGate.GateInput readyInput(
            String asOf,
            AiPointInTimeGate.RunMode mode,
            String latestCompleteDate
    ) {
        LocalDate date = LocalDate.parse(latestCompleteDate);
        return new AiPointInTimeGate.GateInput(
                LocalDateTime.parse(asOf), mode, date, date, date, true, true
        );
    }

    private static final class FixedTradingCalendar implements TradingCalendarService {
        private final Set<LocalDate> holidays;

        private FixedTradingCalendar(Set<LocalDate> holidays) {
            this.holidays = holidays;
        }

        @Override
        public boolean isTradingDay(LocalDate date) {
            return date != null
                    && !holidays.contains(date)
                    && date.getDayOfWeek() != DayOfWeek.SATURDAY
                    && date.getDayOfWeek() != DayOfWeek.SUNDAY;
        }

        @Override
        public LocalDate previousTradingDay(LocalDate date) {
            LocalDate cursor = date.minusDays(1);
            while (!isTradingDay(cursor)) {
                cursor = cursor.minusDays(1);
            }
            return cursor;
        }

        @Override
        public LocalDate latestExpectedKlineDate(LocalDateTime now) {
            LocalDate cursor = now.toLocalDate();
            while (!isTradingDay(cursor)) {
                cursor = cursor.minusDays(1);
            }
            return cursor;
        }

        @Override
        public LocalDate minimumRequiredAnalysisKlineDate(LocalDateTime now) {
            return previousTradingDay(now.toLocalDate());
        }

        @Override
        public LocalDateTime nextTradingDateTime(LocalDateTime now, int hour, int minute) {
            throw new UnsupportedOperationException();
        }
    }
}
