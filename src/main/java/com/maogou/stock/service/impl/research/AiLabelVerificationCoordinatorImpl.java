package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiTradingCalendar;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiTradingCalendarMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiPredictionEvaluationService;
import com.maogou.stock.service.research.AiSampleLabelService;
import com.maogou.stock.service.research.AiLabelVerificationCoordinator;
import org.springframework.stereotype.Service;

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
public class AiLabelVerificationCoordinatorImpl implements AiLabelVerificationCoordinator {

    private static final int CANDIDATE_LIMIT = 300;

    private final AiPredictionMapper predictionMapper;
    private final AiSampleMapper sampleMapper;
    private final AiTradingCalendarMapper calendarMapper;
    private final MarketDataService marketDataService;
    private final AiSampleLabelService labelService;
    private final AiPredictionEvaluationService evaluationService;

    public AiLabelVerificationCoordinatorImpl(
            AiPredictionMapper predictionMapper,
            AiSampleMapper sampleMapper,
            AiTradingCalendarMapper calendarMapper,
            MarketDataService marketDataService,
            AiSampleLabelService labelService,
            AiPredictionEvaluationService evaluationService
    ) {
        this.predictionMapper = predictionMapper;
        this.sampleMapper = sampleMapper;
        this.calendarMapper = calendarMapper;
        this.marketDataService = marketDataService;
        this.labelService = labelService;
        this.evaluationService = evaluationService;
    }

    @Override
    public VerificationResult verifyMatured(Long userId, LocalDate tradeDate, LocalDateTime verifiedAt) {
        if (userId == null || tradeDate == null || verifiedAt == null) {
            throw new IllegalArgumentException("复盘验证缺少用户、交易日或验证时点");
        }
        List<AiPrediction> candidates = predictionMapper.selectUnverifiedCandidates(
                userId, tradeDate, AiResearchContract.LABEL_VERSION, CANDIDATE_LIMIT);
        if (candidates == null || candidates.isEmpty()) {
            return new VerificationResult(0, 0, 0, List.of(), sha256("EMPTY|" + userId + "|" + tradeDate));
        }

        Map<Long, AiSample> samples = sampleMapper.selectBatchIds(candidates.stream()
                        .map(item -> item.sampleId).filter(Objects::nonNull).distinct().toList())
                .stream().collect(Collectors.toMap(item -> item.id, item -> item));
        List<String> errors = new ArrayList<>();
        List<Long> evaluationIds = new ArrayList<>();
        int failed = 0;

        KlineSeriesSnapshot benchmark;
        List<AiTradingCalendar> calendars;
        try {
            benchmark = marketDataService.klineAt(
                    AiResearchContract.BENCHMARK_SYMBOL, "day", 240, verifiedAt);
            calendars = ensureCalendars(benchmark, verifiedAt);
            if (calendars.isEmpty()) {
                throw new IllegalStateException("基准指数未返回交易日历");
            }
        } catch (RuntimeException exception) {
            String message = "基准指数: " + rootMessage(exception);
            return new VerificationResult(candidates.size(), 0, candidates.size(), List.of(message),
                    sha256("FAILED|" + userId + "|" + tradeDate + "|" + message));
        }

        Map<GroupKey, List<AiPrediction>> groups = candidates.stream().collect(Collectors.groupingBy(
                item -> new GroupKey(item.tradeDate, item.samplePhase, item.horizonDays),
                LinkedHashMap::new,
                Collectors.toList()));
        for (Map.Entry<GroupKey, List<AiPrediction>> entry : groups.entrySet()) {
            List<AiSampleLabelService.SampleInput> inputs = new ArrayList<>();
            List<AiPrediction> predictions = new ArrayList<>();
            for (AiPrediction prediction : entry.getValue()) {
                AiSample sample = samples.get(prediction.sampleId);
                if (sample == null) {
                    failed++;
                    errors.add(prediction.stockCode + ": 找不到预测对应的不可变样本");
                    continue;
                }
                try {
                    KlineSeriesSnapshot stock = marketDataService.klineAt(
                            prediction.stockCode, "day", 240, verifiedAt);
                    inputs.add(new AiSampleLabelService.SampleInput(
                            sample.id,
                            sample.stockCode,
                            sample.tradeDate,
                            sample.tradableStatus,
                            sample.sourceFingerprint,
                            stock,
                            benchmark,
                            null
                    ));
                    predictions.add(prediction);
                } catch (RuntimeException exception) {
                    failed++;
                    errors.add(prediction.stockCode + ": " + rootMessage(exception));
                }
            }
            if (inputs.isEmpty()) {
                continue;
            }
            try {
                List<AiSampleLabel> labels = labelService.matureAndStore(new AiSampleLabelService.LabelBatch(
                        inputs,
                        calendars.stream().map(this::tradingDay).toList(),
                        AiResearchContract.CALENDAR_VERSION,
                        AiResearchContract.LABEL_VERSION,
                        List.of(entry.getKey().horizonDays),
                        verifiedAt));
                List<AiPredictionEvaluation> evaluations = evaluationService.evaluateAndStore(
                        new AiPredictionEvaluationService.EvaluationBatch(
                                predictions.stream().map(this::predictionInput).toList(),
                                labels,
                                "EVALUATION/1.0.0",
                                verifiedAt
                        )
                );
                evaluations.stream().map(item -> item.id).filter(Objects::nonNull).forEach(evaluationIds::add);
            } catch (RuntimeException exception) {
                failed += inputs.size();
                errors.add(entry.getKey().tradeDate + " T+" + entry.getKey().horizonDays + ": "
                        + rootMessage(exception));
            }
        }
        int success = evaluationIds.size();
        String fingerprint = sha256(candidates.stream().map(item -> String.valueOf(item.id))
                .collect(Collectors.joining(",")) + "|" + evaluationIds + "|" + errors);
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
            calendar.calendarVersion = AiResearchContract.CALENDAR_VERSION;
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
                        "CN_A_SHARE", AiResearchContract.CALENDAR_VERSION, dates);
    }

    private AiSampleLabelService.TradingDay tradingDay(AiTradingCalendar calendar) {
        return new AiSampleLabelService.TradingDay(
                calendar.id,
                calendar.tradeDate,
                Integer.valueOf(1).equals(calendar.isTradeDay),
                calendar.sessionCloseTime,
                calendar.calendarVersion,
                calendar.sourceFingerprint
        );
    }

    private AiPredictionEvaluationService.PredictionInput predictionInput(AiPrediction prediction) {
        return new AiPredictionEvaluationService.PredictionInput(
                prediction.id,
                prediction.sampleId,
                prediction.horizonDays,
                prediction.action,
                prediction.actionBucket,
                prediction.targetDirection,
                prediction.expectedReturn,
                prediction.probabilityUp,
                prediction.probabilityDown,
                prediction.inputFingerprint
        );
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
