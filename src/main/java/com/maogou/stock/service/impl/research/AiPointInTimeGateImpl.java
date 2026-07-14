package com.maogou.stock.service.impl.research;

import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiPointInTimeGate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class AiPointInTimeGateImpl implements AiPointInTimeGate {

    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 0);

    private final TradingCalendarService tradingCalendarService;

    public AiPointInTimeGateImpl(TradingCalendarService tradingCalendarService) {
        this.tradingCalendarService = tradingCalendarService;
    }

    @Override
    public GateResult evaluate(GateInput input) {
        if (input == null || input.asOfTime() == null || input.runMode() == null) {
            return new GateResult(GateStatus.UNAVAILABLE, null, List.of("缺少研究截止时间或运行模式"));
        }

        LocalDate expectedDate = expectedCompleteTradeDate(input.asOfTime(), input.runMode());
        List<String> coreReasons = new ArrayList<>();
        requireDate(input.latestKlineDate(), expectedDate, "当日完整日K尚未就绪", coreReasons);
        requireDate(input.benchmarkCloseDate(), expectedDate, "同期基准收盘数据尚未就绪", coreReasons);
        requireDate(input.sectorCloseDate(), expectedDate, "同期行业收盘数据尚未就绪", coreReasons);

        if (!coreReasons.isEmpty()) {
            GateStatus status = input.runMode() == RunMode.AFTER_CLOSE_RESEARCH
                    ? GateStatus.WAITING_SOURCE
                    : GateStatus.UNAVAILABLE;
            return new GateResult(status, expectedDate, coreReasons);
        }

        List<String> partialReasons = new ArrayList<>();
        if (!input.financeAvailable()) {
            partialReasons.add("财务数据缺失，相关因子降级为缺失");
        }
        if (!input.newsAvailable()) {
            partialReasons.add("资讯数据缺失，相关因子降级为缺失");
        }
        if (!partialReasons.isEmpty()) {
            return new GateResult(GateStatus.PARTIAL, expectedDate, partialReasons);
        }
        return new GateResult(GateStatus.READY, expectedDate, List.of());
    }

    @Override
    public ObservationResult evaluateObservation(ObservationInput input) {
        if (input == null || input.firstSeenAt() == null || input.fetchedAt() == null
                || input.asOfTime() == null) {
            return observation(ObservationStatus.INVALID_TIMELINE, null, "来源时间线字段不完整");
        }
        if (input.fetchedAt().isBefore(input.firstSeenAt())) {
            return observation(ObservationStatus.INVALID_TIMELINE, null, "抓取时间早于首次发现时间");
        }

        LocalDateTime effectivePublishedAt = input.publishedAt() != null
                ? input.publishedAt()
                : input.eventTime();
        if (effectivePublishedAt == null) {
            return observation(ObservationStatus.INVALID_TIMELINE, null,
                    "来源没有正式发布时间或交易所业务时间");
        }
        if (effectivePublishedAt.isAfter(input.asOfTime())) {
            return observation(ObservationStatus.FUTURE_DATA, effectivePublishedAt,
                    "来源发布时间晚于研究截止时间");
        }
        if (input.firstSeenAt().isAfter(input.asOfTime()) || input.fetchedAt().isAfter(input.asOfTime())) {
            return observation(ObservationStatus.AFTER_CUTOFF, effectivePublishedAt,
                    "来源在研究截止时间后才被系统发现或抓取");
        }
        return new ObservationResult(ObservationStatus.ELIGIBLE, true, effectivePublishedAt, "可进入样本");
    }

    private LocalDate expectedCompleteTradeDate(LocalDateTime asOfTime, RunMode runMode) {
        LocalDate date = asOfTime.toLocalDate();
        if (!tradingCalendarService.isTradingDay(date)) {
            return tradingCalendarService.latestExpectedKlineDate(asOfTime);
        }
        if (runMode == RunMode.LIVE_ANALYSIS || asOfTime.toLocalTime().isBefore(MARKET_CLOSE)) {
            return tradingCalendarService.previousTradingDay(date);
        }
        return date;
    }

    private static void requireDate(
            LocalDate actual,
            LocalDate expected,
            String message,
            List<String> reasons
    ) {
        if (expected == null || actual == null || actual.isBefore(expected)) {
            reasons.add(message);
        }
    }

    private static ObservationResult observation(
            ObservationStatus status,
            LocalDateTime effectivePublishedAt,
            String reason
    ) {
        return new ObservationResult(status, false, effectivePublishedAt, reason);
    }
}
