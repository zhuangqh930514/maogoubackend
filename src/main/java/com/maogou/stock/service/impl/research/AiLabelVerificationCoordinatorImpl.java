package com.maogou.stock.service.impl.research;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class AiLabelVerificationCoordinatorImpl implements AiLabelVerificationCoordinator {

    private static final int CANDIDATE_LIMIT = 2000;
    private static final int CANDIDATE_SCAN_PAGE_SIZE = 500;
    private static final int CANDIDATE_SCAN_MULTIPLIER = 10;
    private static final int LABEL_BATCH_SIZE = 500;
    private static final int EVALUATION_BATCH_SIZE = 2000;
    private static final List<Integer> HORIZONS = List.of(1, 2, 3, 5);

    private final AiPredictionMapper predictionMapper;
    private final AiSampleMapper sampleMapper;
    private final AiSampleLabelMapper labelMapper;
    private final AiTradingCalendarMapper calendarMapper;
    private final MarketDataService marketDataService;
    private final AiSampleLabelService labelService;
    private final AiPredictionEvaluationService evaluationService;
    private final Clock clock;

    @Autowired
    public AiLabelVerificationCoordinatorImpl(
            AiPredictionMapper predictionMapper,
            AiSampleMapper sampleMapper,
            AiSampleLabelMapper labelMapper,
            AiTradingCalendarMapper calendarMapper,
            MarketDataService marketDataService,
            AiSampleLabelService labelService,
            AiPredictionEvaluationService evaluationService
    ) {
        this(predictionMapper, sampleMapper, labelMapper, calendarMapper, marketDataService,
                labelService, evaluationService, Clock.systemDefaultZone());
    }

    AiLabelVerificationCoordinatorImpl(
            AiPredictionMapper predictionMapper,
            AiSampleMapper sampleMapper,
            AiSampleLabelMapper labelMapper,
            AiTradingCalendarMapper calendarMapper,
            MarketDataService marketDataService,
            AiSampleLabelService labelService,
            AiPredictionEvaluationService evaluationService,
            Clock clock
    ) {
        this.predictionMapper = predictionMapper;
        this.sampleMapper = sampleMapper;
        this.labelMapper = labelMapper;
        this.calendarMapper = calendarMapper;
        this.marketDataService = marketDataService;
        this.labelService = labelService;
        this.evaluationService = evaluationService;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public VerificationResult matureSampleLabels(LocalDate tradeDate, LocalDateTime verifiedAt) {
        return matureSampleLabels(tradeDate, verifiedAt, CANDIDATE_LIMIT);
    }

    @Override
    public VerificationResult matureSampleLabels(
            LocalDate tradeDate,
            LocalDateTime verifiedAt,
            int candidateLimit
    ) {
        requireTime(tradeDate, verifiedAt, "标签成熟");
        requireLimit(candidateLimit);
        List<AiSample> firstPage = pendingLabelCandidatesPage(
                tradeDate, null, candidateLimit);
        if (firstPage.isEmpty()) {
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
            return new VerificationResult(firstPage.size(), 0, firstPage.size(), List.of(message),
                    sha256("MATURE_FAILED|" + tradeDate + "|" + message));
        }

        Map<String, KlineSeriesSnapshot> stockSeries = new HashMap<>();
        Set<String> unavailableStocks = new LinkedHashSet<>();
        List<AiSampleLabelService.SampleInput> inputs = new ArrayList<>();
        int scanned = 0;
        int maximumScanCount = Math.multiplyExact(candidateLimit, CANDIDATE_SCAN_MULTIPLIER);
        List<AiSample> page = firstPage;
        while (!page.isEmpty() && scanned < maximumScanCount && inputs.size() < candidateLimit) {
            for (AiSample sample : page) {
                scanned++;
                try {
                    KlineSeriesSnapshot stock = stockSeries.get(sample.stockCode);
                    if (stock == null && !unavailableStocks.contains(sample.stockCode)) {
                        try {
                            stock = marketDataService.klineAt(sample.stockCode, "day", 320, verifiedAt);
                            stockSeries.put(sample.stockCode, stock);
                        } catch (RuntimeException exception) {
                            unavailableStocks.add(sample.stockCode);
                            errors.add(sample.stockCode + ": " + rootMessage(exception));
                        }
                    }
                    if (stock == null) {
                        continue;
                    }
                    inputs.add(new AiSampleLabelService.SampleInput(
                            sample.id, sample.stockCode, sample.tradeDate, sample.tradableStatus,
                            sample.sourceFingerprint, stock, benchmark, null));
                } catch (RuntimeException exception) {
                    errors.add(sample.stockCode + ": " + rootMessage(exception));
                }
                if (inputs.size() >= candidateLimit || scanned >= maximumScanCount) {
                    break;
                }
            }
            if (inputs.size() >= candidateLimit || scanned >= maximumScanCount
                    || page.size() < Math.min(CANDIDATE_SCAN_PAGE_SIZE, candidateLimit)) {
                break;
            }
            page = pendingLabelCandidatesPage(tradeDate, page.get(page.size() - 1), candidateLimit);
        }
        if (inputs.isEmpty()) {
            return new VerificationResult(0, 0, unavailableStocks.size(), errors,
                    sha256("MATURE_EMPTY|" + tradeDate + "|" + errors));
        }

        Map<Long, Set<Integer>> existingHorizons = existingLabelHorizons(inputs);
        Map<List<Integer>, List<AiSampleLabelService.SampleInput>> byMissingHorizons = new LinkedHashMap<>();
        for (AiSampleLabelService.SampleInput input : inputs) {
            Set<Integer> existing = existingHorizons.getOrDefault(input.sampleId(), Set.of());
            List<Integer> missing = HORIZONS.stream().filter(horizon -> !existing.contains(horizon)).toList();
            if (!missing.isEmpty()) {
                byMissingHorizons.computeIfAbsent(missing, ignored -> new ArrayList<>()).add(input);
            }
        }

        LocalDateTime evidenceVerifiedAt = LocalDateTime.now(clock);
        List<AiSampleLabel> labels = new ArrayList<>();
        List<AiSampleLabelService.TradingDay> tradingDays =
                calendars.stream().map(this::tradingDay).toList();
        for (Map.Entry<List<Integer>, List<AiSampleLabelService.SampleInput>> entry
                : byMissingHorizons.entrySet()) {
            List<AiSampleLabelService.SampleInput> missingInputs = entry.getValue();
            for (int offset = 0; offset < missingInputs.size(); offset += LABEL_BATCH_SIZE) {
                List<AiSampleLabelService.SampleInput> chunk = missingInputs.subList(
                        offset, Math.min(offset + LABEL_BATCH_SIZE, missingInputs.size()));
                labels.addAll(labelService.matureAndStore(new AiSampleLabelService.LabelBatch(
                        chunk,
                        tradingDays,
                        AiResearchContract.CALENDAR_VERSION,
                        AiResearchContract.LABEL_VERSION,
                        entry.getKey(),
                        evidenceVerifiedAt)));
            }
        }
        int matured = (int) labels.stream().filter(label -> "MATURED".equals(label.labelStatus)).count();
        int failed = unavailableStocks.size();
        return new VerificationResult(inputs.size(), matured, failed, errors,
                sha256("MATURE|" + tradeDate + "|" + labels.stream().map(label -> String.valueOf(label.id)).toList()
                        + "|" + errors));
    }

    private List<AiSample> pendingLabelCandidatesPage(
            LocalDate tradeDate,
            AiSample cursor,
            int candidateLimit
    ) {
        int pageSize = Math.min(CANDIDATE_SCAN_PAGE_SIZE, candidateLimit);
        List<AiSample> page = sampleMapper.selectPendingLabelCandidatesPage(
                tradeDate,
                AiResearchContract.LABEL_VERSION,
                cursor == null ? null : cursor.tradeDate,
                cursor == null ? null : cursor.stockCode,
                cursor == null ? null : cursor.id,
                pageSize);
        return page == null ? List.of() : page;
    }

    private Map<Long, Set<Integer>> existingLabelHorizons(
            List<AiSampleLabelService.SampleInput> inputs
    ) {
        List<Long> sampleIds = inputs.stream().map(AiSampleLabelService.SampleInput::sampleId)
                .filter(Objects::nonNull).distinct().toList();
        if (sampleIds.isEmpty()) {
            return Map.of();
        }
        List<AiSampleLabel> existing = labelMapper.selectForSamplesAndVersion(
                sampleIds, AiResearchContract.LABEL_VERSION);
        Map<Long, Set<Integer>> result = new HashMap<>();
        if (existing != null) {
            for (AiSampleLabel label : existing) {
                if (label != null && label.sampleId != null && label.horizonTradingDays != null) {
                    result.computeIfAbsent(label.sampleId, ignored -> new LinkedHashSet<>())
                            .add(label.horizonTradingDays);
                }
            }
        }
        return result;
    }

    @Override
    public VerificationResult evaluatePredictions(LocalDate tradeDate, LocalDateTime evaluatedAt) {
        return evaluatePredictions(tradeDate, evaluatedAt, CANDIDATE_LIMIT);
    }

    @Override
    public VerificationResult evaluatePredictions(
            LocalDate tradeDate,
            LocalDateTime evaluatedAt,
            int candidateLimit
    ) {
        requireTime(tradeDate, evaluatedAt, "预测评价");
        requireLimit(candidateLimit);
        int processed = 0;
        List<AiPredictionEvaluation> evaluations = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        while (processed < candidateLimit) {
            int limit = Math.min(EVALUATION_BATCH_SIZE, candidateLimit - processed);
            List<AiPrediction> predictions = predictionMapper.selectUnevaluatedCandidates(
                    tradeDate, AiResearchContract.LABEL_VERSION,
                    AiPredictionEvaluationServiceImpl.VERSION, limit);
            if (predictions == null || predictions.isEmpty()) {
                break;
            }
            List<Long> sampleIds = predictions.stream().map(prediction -> prediction.sampleId)
                    .filter(Objects::nonNull).distinct().toList();
            List<AiSampleLabel> labels = sampleIds.isEmpty() ? List.of()
                    : labelMapper.selectMaturedForSamples(sampleIds, AiResearchContract.LABEL_VERSION);
            List<AiPredictionEvaluation> stored = evaluationService.evaluateAndStore(
                    new AiPredictionEvaluationService.EvaluationBatch(
                            predictions.stream().map(this::predictionInput).toList(),
                            labels == null ? List.of() : labels,
                            AiPredictionEvaluationServiceImpl.VERSION,
                            evaluatedAt));
            processed += predictions.size();
            if (stored == null || stored.isEmpty()) {
                errors.add("成熟标签存在但没有生成预测评价，已停止本轮批量评价");
                break;
            }
            evaluations.addAll(stored);
        }
        if (processed == 0) {
            return empty("EVALUATE_PREDICTIONS", tradeDate);
        }
        return new VerificationResult(processed, evaluations.size(), errors.size(), errors,
                sha256("EVALUATE|" + tradeDate + "|"
                        + evaluations.stream().map(value -> String.valueOf(value.id)).toList()
                        + "|" + errors));
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

    private static void requireLimit(int candidateLimit) {
        if (candidateLimit <= 0 || candidateLimit > 200_000) {
            throw new IllegalArgumentException("候选样本上限必须在 1 到 200000 之间");
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
