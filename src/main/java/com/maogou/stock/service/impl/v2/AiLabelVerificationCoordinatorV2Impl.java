package com.maogou.stock.service.impl.v2;

import com.maogou.stock.domain.entity.v2.AiLabelV2;
import com.maogou.stock.domain.entity.v2.AiPredictionV2;
import com.maogou.stock.domain.entity.v2.AiSampleV2;
import com.maogou.stock.domain.entity.v2.AiTradingCalendar;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.mapper.v2.AiPredictionV2Mapper;
import com.maogou.stock.mapper.v2.AiSampleV2Mapper;
import com.maogou.stock.mapper.v2.AiTradingCalendarMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.v2.AiEvolutionV2Contract;
import com.maogou.stock.service.v2.AiLabelServiceV2;
import com.maogou.stock.service.v2.AiLabelVerificationCoordinatorV2;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
import java.util.stream.Collectors;

@Service
public class AiLabelVerificationCoordinatorV2Impl implements AiLabelVerificationCoordinatorV2 {

    private static final int CANDIDATE_LIMIT = 300;

    private final AiPredictionV2Mapper predictionMapper;
    private final AiSampleV2Mapper sampleMapper;
    private final AiTradingCalendarMapper calendarMapper;
    private final MarketDataService marketDataService;
    private final AiLabelServiceV2 labelService;

    public AiLabelVerificationCoordinatorV2Impl(
            AiPredictionV2Mapper predictionMapper,
            AiSampleV2Mapper sampleMapper,
            AiTradingCalendarMapper calendarMapper,
            MarketDataService marketDataService,
            AiLabelServiceV2 labelService
    ) {
        this.predictionMapper = predictionMapper;
        this.sampleMapper = sampleMapper;
        this.calendarMapper = calendarMapper;
        this.marketDataService = marketDataService;
        this.labelService = labelService;
    }

    @Override
    public VerificationResult verifyMatured(Long userId, LocalDate tradeDate, LocalDateTime verifiedAt) {
        if (userId == null || tradeDate == null || verifiedAt == null) {
            throw new IllegalArgumentException("复盘验证缺少用户、交易日或验证时点");
        }
        List<AiPredictionV2> candidates = predictionMapper.selectUnverifiedCandidates(
                userId, tradeDate, AiEvolutionV2Contract.LABEL_VERSION, CANDIDATE_LIMIT);
        if (candidates == null || candidates.isEmpty()) {
            return new VerificationResult(0, 0, 0, List.of(), sha256("EMPTY|" + userId + "|" + tradeDate));
        }

        Map<Long, AiSampleV2> samples = sampleMapper.selectBatchIds(candidates.stream()
                        .map(item -> item.sampleId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(item -> item.id, item -> item));
        List<String> errors = new ArrayList<>();
        List<Long> labelIds = new ArrayList<>();
        int failed = 0;

        KlineSeriesSnapshot benchmark;
        List<AiTradingCalendar> calendars;
        try {
            benchmark = marketDataService.klineAt(
                    AiEvolutionV2Contract.BENCHMARK_SYMBOL, "day", 240, verifiedAt);
            calendars = ensureCalendars(benchmark, verifiedAt);
            if (calendars.isEmpty()) {
                throw new IllegalStateException("基准指数未返回交易日历");
            }
        } catch (RuntimeException exception) {
            String message = "基准指数: " + rootMessage(exception);
            return new VerificationResult(candidates.size(), 0, candidates.size(), List.of(message),
                    sha256("FAILED|" + userId + "|" + tradeDate + "|" + message));
        }

        Map<GroupKey, List<AiPredictionV2>> groups = candidates.stream().collect(Collectors.groupingBy(
                item -> new GroupKey(item.tradeDate, item.samplePhase, item.horizonDays),
                LinkedHashMap::new,
                Collectors.toList()));
        for (Map.Entry<GroupKey, List<AiPredictionV2>> entry : groups.entrySet()) {
            List<AiLabelServiceV2.LabelInput> inputs = new ArrayList<>();
            for (AiPredictionV2 prediction : entry.getValue()) {
                AiSampleV2 sample = samples.get(prediction.sampleId);
                if (sample == null) {
                    failed++;
                    errors.add(prediction.stockCode + ": 找不到预测对应的不可变样本");
                    continue;
                }
                try {
                    KlineSeriesSnapshot stock = marketDataService.klineAt(
                            prediction.stockCode, "day", 240, verifiedAt);
                    inputs.add(new AiLabelServiceV2.LabelInput(prediction, sample, stock, benchmark, null));
                } catch (RuntimeException exception) {
                    failed++;
                    errors.add(prediction.stockCode + ": " + rootMessage(exception));
                }
            }
            if (inputs.isEmpty()) {
                continue;
            }
            try {
                List<AiLabelV2> labels = labelService.verifyAndStore(new AiLabelServiceV2.LabelBatch(
                        inputs,
                        calendars,
                        AiEvolutionV2Contract.CALENDAR_VERSION,
                        AiEvolutionV2Contract.LABEL_VERSION,
                        List.of(entry.getKey().horizonDays),
                        defaultCostModel(),
                        verifiedAt));
                labels.stream().map(item -> item.id).filter(Objects::nonNull).forEach(labelIds::add);
            } catch (RuntimeException exception) {
                failed += inputs.size();
                errors.add(entry.getKey().tradeDate + " T+" + entry.getKey().horizonDays + ": "
                        + rootMessage(exception));
            }
        }
        int success = labelIds.size();
        String fingerprint = sha256(candidates.stream().map(item -> String.valueOf(item.id))
                .collect(Collectors.joining(",")) + "|" + labelIds + "|" + errors);
        return new VerificationResult(candidates.size(), success, failed, errors, fingerprint);
    }

    private List<AiTradingCalendar> ensureCalendars(KlineSeriesSnapshot benchmark, LocalDateTime verifiedAt) {
        List<KlinePointResponse> points = benchmark == null || benchmark.points() == null
                ? List.of()
                : benchmark.points().stream()
                .filter(Objects::nonNull)
                .filter(point -> point.tradeDate() != null && !point.tradeDate().isAfter(verifiedAt.toLocalDate()))
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                .toList();
        for (int index = 0; index < points.size(); index++) {
            LocalDate date = points.get(index).tradeDate();
            AiTradingCalendar calendar = new AiTradingCalendar();
            calendar.marketCode = "CN_A_SHARE";
            calendar.tradeDate = date;
            calendar.calendarVersion = AiEvolutionV2Contract.CALENDAR_VERSION;
            calendar.isTradeDay = 1;
            calendar.sessionOpenTime = LocalTime.of(9, 30);
            calendar.sessionCloseTime = LocalTime.of(15, 0);
            calendar.previousTradeDate = index == 0 ? null : points.get(index - 1).tradeDate();
            calendar.nextTradeDate = index + 1 >= points.size() ? null : points.get(index + 1).tradeDate();
            calendar.sourceName = benchmark.source();
            calendar.sourceAsOf = verifiedAt;
            calendar.sourceFingerprint = sha256(benchmark.sourceFingerprint() + "|" + date);
            calendar.createdAt = LocalDateTime.now();
            calendarMapper.insertIgnore(calendar);
        }
        List<LocalDate> dates = points.stream().map(KlinePointResponse::tradeDate).distinct().toList();
        return dates.isEmpty()
                ? List.of()
                : calendarMapper.selectByDates(
                        "CN_A_SHARE", AiEvolutionV2Contract.CALENDAR_VERSION, dates);
    }

    private static AiLabelServiceV2.CostModel defaultCostModel() {
        return new AiLabelServiceV2.CostModel(
                "CN_A_SHARE_COST_V1",
                new BigDecimal("0.0003"),
                new BigDecimal("0.0003"),
                new BigDecimal("0.0005"),
                new BigDecimal("0.00001"),
                new BigDecimal("5"),
                new BigDecimal("100"));
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record GroupKey(LocalDate tradeDate, String samplePhase, Integer horizonDays) {
    }
}
