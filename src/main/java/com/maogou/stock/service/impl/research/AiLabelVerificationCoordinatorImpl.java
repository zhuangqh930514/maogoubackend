package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiTradingCalendar;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiTradingCalendarMapper;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.research.AiLabelVerificationCoordinator;
import com.maogou.stock.service.research.AiPredictionEvaluationService;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiSampleLabelService;
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
import java.util.List;
import java.util.Objects;

@Service
public class AiLabelVerificationCoordinatorImpl implements AiLabelVerificationCoordinator {

    private static final int CANDIDATE_LIMIT = 2000;
    private static final List<Integer> HORIZONS = List.of(1, 2, 3, 5);

    private final AiPredictionMapper predictionMapper;
    private final AiSampleMapper sampleMapper;
    private final AiSampleLabelMapper labelMapper;
    private final AiTradingCalendarMapper calendarMapper;
    private final MarketDataService marketDataService;
    private final AiSampleLabelService labelService;
    private final AiPredictionEvaluationService evaluationService;

    public AiLabelVerificationCoordinatorImpl(
            AiPredictionMapper predictionMapper,
            AiSampleMapper sampleMapper,
            AiSampleLabelMapper labelMapper,
            AiTradingCalendarMapper calendarMapper,
            MarketDataService marketDataService,
            AiSampleLabelService labelService,
            AiPredictionEvaluationService evaluationService
    ) {
        this.predictionMapper = predictionMapper;
        this.sampleMapper = sampleMapper;
        this.labelMapper = labelMapper;
        this.calendarMapper = calendarMapper;
        this.marketDataService = marketDataService;
        this.labelService = labelService;
        this.evaluationService = evaluationService;
    }

    @Override
    public VerificationResult matureSampleLabels(LocalDate tradeDate, LocalDateTime verifiedAt) {
        requireTime(tradeDate, verifiedAt, "标签成熟");
        List<AiSample> samples = sampleMapper.selectList(new QueryWrapper<AiSample>()
                .lt("trade_date", tradeDate)
                .in("quality_status", "READY", "PARTIAL")
                .orderByAsc("trade_date")
                .orderByAsc("stock_code")
                .last("LIMIT " + CANDIDATE_LIMIT));
        if (samples == null || samples.isEmpty()) {
            return empty("MATURE_LABELS", tradeDate);
        }

        List<String> errors = new ArrayList<>();
        KlineSeriesSnapshot benchmark;
        List<AiTradingCalendar> calendars;
        try {
            benchmark = marketDataService.klineAt(
                    AiResearchContract.BENCHMARK_SYMBOL, "day", 320, verifiedAt);
            calendars = ensureCalendars(benchmark, verifiedAt);
            if (calendars.isEmpty()) {
                throw new IllegalStateException("基准指数未返回交易日历");
            }
        } catch (RuntimeException exception) {
            String message = "基准指数: " + rootMessage(exception);
            return new VerificationResult(samples.size(), 0, samples.size(), List.of(message),
                    sha256("MATURE_FAILED|" + tradeDate + "|" + message));
        }

        List<AiSampleLabelService.SampleInput> inputs = new ArrayList<>();
        for (AiSample sample : samples) {
            try {
                KlineSeriesSnapshot stock = marketDataService.klineAt(
                        sample.stockCode, "day", 320, verifiedAt);
                inputs.add(new AiSampleLabelService.SampleInput(
                        sample.id, sample.stockCode, sample.tradeDate, sample.tradableStatus,
                        sample.sourceFingerprint, stock, benchmark, null));
            } catch (RuntimeException exception) {
                errors.add(sample.stockCode + ": " + rootMessage(exception));
            }
        }
        if (inputs.isEmpty()) {
            return new VerificationResult(samples.size(), 0, samples.size(), errors,
                    sha256("MATURE_EMPTY|" + tradeDate + "|" + errors));
        }

        List<AiSampleLabel> labels = labelService.matureAndStore(new AiSampleLabelService.LabelBatch(
                inputs,
                calendars.stream().map(this::tradingDay).toList(),
                AiResearchContract.CALENDAR_VERSION,
                AiResearchContract.LABEL_VERSION,
                HORIZONS,
                verifiedAt));
        int matured = (int) labels.stream().filter(label -> "MATURED".equals(label.labelStatus)).count();
        int failed = errors.size();
        return new VerificationResult(samples.size(), matured, failed, errors,
                sha256("MATURE|" + tradeDate + "|" + labels.stream().map(label -> String.valueOf(label.id)).toList()
                        + "|" + errors));
    }

    @Override
    public VerificationResult evaluatePredictions(LocalDate tradeDate, LocalDateTime evaluatedAt) {
        requireTime(tradeDate, evaluatedAt, "预测评价");
        List<AiPrediction> predictions = predictionMapper.selectUnevaluatedCandidates(
                tradeDate, AiPredictionEvaluationServiceImpl.VERSION, CANDIDATE_LIMIT);
        if (predictions == null || predictions.isEmpty()) {
            return empty("EVALUATE_PREDICTIONS", tradeDate);
        }
        List<Long> sampleIds = predictions.stream().map(prediction -> prediction.sampleId)
                .filter(Objects::nonNull).distinct().toList();
        List<AiSampleLabel> labels = labelMapper.selectList(new QueryWrapper<AiSampleLabel>()
                .in("sample_id", sampleIds)
                .eq("label_version", AiResearchContract.LABEL_VERSION)
                .eq("label_status", "MATURED"));
        List<AiPredictionEvaluation> evaluations = evaluationService.evaluateAndStore(
                new AiPredictionEvaluationService.EvaluationBatch(
                        predictions.stream().map(this::predictionInput).toList(),
                        labels,
                        AiPredictionEvaluationServiceImpl.VERSION,
                        evaluatedAt));
        return new VerificationResult(predictions.size(), evaluations.size(), 0, List.of(),
                sha256("EVALUATE|" + tradeDate + "|"
                        + evaluations.stream().map(value -> String.valueOf(value.id)).toList()));
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
        return dates.isEmpty() ? List.of() : calendarMapper.selectByDates(
                "CN_A_SHARE", AiResearchContract.CALENDAR_VERSION, dates);
    }

    private AiSampleLabelService.TradingDay tradingDay(AiTradingCalendar calendar) {
        return new AiSampleLabelService.TradingDay(
                calendar.id, calendar.tradeDate, Integer.valueOf(1).equals(calendar.isTradeDay),
                calendar.sessionCloseTime, calendar.calendarVersion, calendar.sourceFingerprint);
    }

    private AiPredictionEvaluationService.PredictionInput predictionInput(AiPrediction prediction) {
        return new AiPredictionEvaluationService.PredictionInput(
                prediction.id, prediction.sampleId, prediction.horizonDays, prediction.action,
                prediction.actionBucket, prediction.targetDirection, prediction.expectedReturn,
                prediction.probabilityUp, prediction.probabilityDown, prediction.inputFingerprint);
    }

    private static VerificationResult empty(String type, LocalDate tradeDate) {
        return new VerificationResult(0, 0, 0, List.of(), sha256(type + "|EMPTY|" + tradeDate));
    }

    private static void requireTime(LocalDate tradeDate, LocalDateTime at, String type) {
        if (tradeDate == null || at == null) {
            throw new IllegalArgumentException(type + "缺少交易日或执行时点");
        }
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
}
