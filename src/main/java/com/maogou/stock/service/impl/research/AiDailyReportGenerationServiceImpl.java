package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.domain.enums.TradeSide;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.research.AiDailyReportGenerationService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps daily model work bounded. A daily decision can still be produced from deterministic
 * evidence for ordinary watchlist entries, while holdings and the highest-impact candidates
 * receive real model reports first.
 */
@Service
public class AiDailyReportGenerationServiceImpl implements AiDailyReportGenerationService {

    private static final int TOP_CANDIDATE_LIMIT = 5;
    private static final Set<Integer> CORE_HORIZONS = Set.of(1, 2, 3);

    private final WatchStockMapper watchStockMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final AiSampleMapper sampleMapper;
    private final AiPredictionMapper predictionMapper;
    private final AiAnalysisReportMapper reportMapper;
    private final AiAnalysisService analysisService;

    public AiDailyReportGenerationServiceImpl(
            WatchStockMapper watchStockMapper,
            TradeRecordMapper tradeRecordMapper,
            AiSampleMapper sampleMapper,
            AiPredictionMapper predictionMapper,
            AiAnalysisReportMapper reportMapper,
            AiAnalysisService analysisService
    ) {
        this.watchStockMapper = watchStockMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.sampleMapper = sampleMapper;
        this.predictionMapper = predictionMapper;
        this.reportMapper = reportMapper;
        this.analysisService = analysisService;
    }

    @Override
    public GenerationResult generate(GenerationRequest request) {
        validate(request);
        UserUniverse universe = loadUniverse(request.userId());
        if (universe.stockCodes().isEmpty()) {
            return new GenerationResult(0, 0, 0, 0, List.of(), List.of());
        }

        List<AiSample> samples = safeList(sampleMapper.selectLatestForDecision(
                request.dataBatchId(), request.tradeDate(), universe.stockCodes()));
        Map<String, AiSample> samplesByCode = samples.stream()
                .filter(Objects::nonNull)
                .filter(sample -> sample.stockCode != null && !sample.stockCode.isBlank())
                .collect(Collectors.toMap(sample -> sample.stockCode, sample -> sample,
                        AiDailyReportGenerationServiceImpl::latestSample, LinkedHashMap::new));
        List<Long> sampleIds = samplesByCode.values().stream()
                .map(sample -> sample.id)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, Map<Integer, AiPrediction>> predictionsBySample = loadPredictions(sampleIds, request.strategyReleaseId());

        List<StockIssue> skipped = new ArrayList<>();
        Map<String, Candidate> eligible = new LinkedHashMap<>();
        for (String stockCode : universe.stockCodes()) {
            AiSample sample = samplesByCode.get(stockCode);
            Candidate candidate = candidate(stockCode, sample,
                    sample == null ? Map.of() : predictionsBySample.getOrDefault(sample.id, Map.of()),
                    universe.holdingCodes().contains(stockCode));
            if (candidate == null) {
                skipped.add(new StockIssue(stockCode, unavailableReason(sample,
                        sample == null ? Map.of() : predictionsBySample.getOrDefault(sample.id, Map.of()))));
                continue;
            }
            eligible.put(stockCode, candidate);
        }

        LinkedHashSet<String> selectedCodes = new LinkedHashSet<>();
        eligible.values().stream().filter(Candidate::holding).map(Candidate::stockCode)
                .sorted().forEach(selectedCodes::add);
        eligible.values().stream().filter(Candidate::riskAction).map(Candidate::stockCode)
                .sorted().forEach(selectedCodes::add);
        eligible.values().stream()
                .sorted(Comparator.comparing(Candidate::primaryScore, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Candidate::stockCode))
                .limit(TOP_CANDIDATE_LIMIT)
                .map(Candidate::stockCode)
                .forEach(selectedCodes::add);

        List<String> selected = List.copyOf(selectedCodes);
        Map<String, AiAnalysisReport> reusableReports = loadReusableReports(request, selected);
        int generated = 0;
        int reused = 0;
        List<StockIssue> failed = new ArrayList<>();
        for (String stockCode : selected) {
            Candidate candidate = eligible.get(stockCode);
            AiAnalysisReport reusable = reusableReports.get(stockCode);
            if (matches(reusable, candidate, request)) {
                reused++;
                continue;
            }
            try {
                AiAnalysisReportResponse response = AuthContext.callAs(request.userId(), () ->
                        analysisService.analyzeStockForTradeDate(stockCode, false, null, null, request.tradeDate()));
                if (response != null && "SUCCESS".equals(response.status())) {
                    generated++;
                } else {
                    failed.add(new StockIssue(stockCode, "步骤=GENERATE_STOCK_REPORTS；股票=" + stockCode
                            + "；数据提供方=本地/第三方大模型；原因=" + responseMessage(response)));
                }
            } catch (RuntimeException exception) {
                failed.add(new StockIssue(stockCode, "步骤=GENERATE_STOCK_REPORTS；股票=" + stockCode
                        + "；数据提供方=本地/第三方大模型；原因=" + rootMessage(exception)));
            }
        }
        return new GenerationResult(eligible.size(), selected.size(), generated, reused, skipped, failed);
    }

    private Map<Long, Map<Integer, AiPrediction>> loadPredictions(List<Long> sampleIds, Long strategyReleaseId) {
        if (sampleIds.isEmpty()) {
            return Map.of();
        }
        return safeList(predictionMapper.selectForDailyDecision(sampleIds, strategyReleaseId)).stream()
                .filter(prediction -> prediction != null && prediction.sampleId != null
                        && prediction.horizonDays != null)
                .collect(Collectors.groupingBy(
                        prediction -> prediction.sampleId,
                        LinkedHashMap::new,
                        Collectors.toMap(prediction -> prediction.horizonDays, prediction -> prediction,
                                AiDailyReportGenerationServiceImpl::latestPrediction, LinkedHashMap::new)));
    }

    private Map<String, AiAnalysisReport> loadReusableReports(GenerationRequest request, List<String> stockCodes) {
        if (stockCodes.isEmpty()) {
            return Map.of();
        }
        return safeList(reportMapper.selectLatestSuccessfulForDailyDecision(
                request.userId(), request.tradeDate(), stockCodes)).stream()
                .filter(report -> report != null && report.stockCode != null)
                .collect(Collectors.toMap(report -> report.stockCode, report -> report,
                        (left, right) -> reportVersion(left) >= reportVersion(right) ? left : right,
                        LinkedHashMap::new));
    }

    private Candidate candidate(
            String stockCode,
            AiSample sample,
            Map<Integer, AiPrediction> predictions,
            boolean holding
    ) {
        if (sample == null || sample.id == null || !"READY".equals(sample.qualityStatus)
                || !"TRADABLE".equals(sample.tradableStatus) || sample.dataQualityScore == null) {
            return null;
        }
        if (!predictions.keySet().containsAll(CORE_HORIZONS)) {
            return null;
        }
        AiPrediction primary = predictions.get(3);
        if (primary == null || primary.score == null || primary.action == null) {
            return null;
        }
        boolean riskAction = "SELL".equals(primary.action) || "REDUCE".equals(primary.action);
        return new Candidate(stockCode, sample, primary.score, holding, riskAction);
    }

    private static boolean matches(AiAnalysisReport report, Candidate candidate, GenerationRequest request) {
        return report != null && report.status == AnalysisStatus.SUCCESS
                && Objects.equals(report.userId, request.userId())
                && Objects.equals(report.stockCode, candidate.stockCode())
                && Objects.equals(report.reportDate, request.tradeDate())
                && Objects.equals(report.sampleId, candidate.sample().id)
                && Objects.equals(report.strategyReleaseId, request.strategyReleaseId());
    }

    private UserUniverse loadUniverse(Long userId) {
        List<WatchStock> watches = safeList(watchStockMapper.selectList(new QueryWrapper<WatchStock>()
                .eq("user_id", userId).eq("deleted", 0).orderByAsc("priority").orderByAsc("stock_code")));
        List<TradeRecord> trades = safeList(tradeRecordMapper.selectList(new QueryWrapper<TradeRecord>()
                .eq("user_id", userId).eq("deleted", 0).orderByAsc("traded_at").orderByAsc("id")));
        LinkedHashSet<String> stockCodes = new LinkedHashSet<>();
        for (WatchStock watch : watches) {
            if (watch != null && watch.stockCode != null && !watch.stockCode.isBlank()) {
                stockCodes.add(watch.stockCode);
            }
        }
        Map<String, Integer> positions = new LinkedHashMap<>();
        for (TradeRecord trade : trades) {
            if (trade == null || trade.stockCode == null || trade.stockCode.isBlank() || trade.quantity == null) {
                continue;
            }
            int signed = trade.side == TradeSide.SELL ? -trade.quantity : trade.quantity;
            positions.merge(trade.stockCode, signed, Integer::sum);
            if (signed > 0) {
                stockCodes.add(trade.stockCode);
            }
        }
        Set<String> holdings = positions.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return new UserUniverse(List.copyOf(stockCodes), Set.copyOf(holdings));
    }

    private static String unavailableReason(AiSample sample, Map<Integer, AiPrediction> predictions) {
        if (sample == null) {
            return "步骤=GENERATE_STOCK_REPORTS；数据提供方=正式研究样本；原因=未找到当前交易日正式样本";
        }
        if (!"READY".equals(sample.qualityStatus)) {
            return "步骤=GENERATE_STOCK_REPORTS；数据提供方=正式研究样本；原因=样本质量状态=" + sample.qualityStatus;
        }
        if (!"TRADABLE".equals(sample.tradableStatus)) {
            return "步骤=GENERATE_STOCK_REPORTS；数据提供方=正式研究样本；原因=不可交易状态=" + sample.tradableStatus;
        }
        if (!predictions.keySet().containsAll(CORE_HORIZONS)) {
            return "步骤=GENERATE_STOCK_REPORTS；数据提供方=正式预测；原因=缺少 T+1/T+2/T+3 完整预测";
        }
        return "步骤=GENERATE_STOCK_REPORTS；数据提供方=正式研究样本；原因=缺少数据质量或主要预测动作";
    }

    private static String responseMessage(AiAnalysisReportResponse response) {
        if (response == null) {
            return "模型未返回报告";
        }
        if (response.errorMessage() != null && !response.errorMessage().isBlank()) {
            return response.errorMessage();
        }
        return "报告状态=" + (response.status() == null ? "UNKNOWN" : response.status());
    }

    private static AiSample latestSample(AiSample left, AiSample right) {
        if (left.asOfTime == null) {
            return right;
        }
        if (right.asOfTime == null) {
            return left;
        }
        return left.asOfTime.isAfter(right.asOfTime) ? left : right;
    }

    private static AiPrediction latestPrediction(AiPrediction left, AiPrediction right) {
        if (left.predictedAt == null) {
            return right;
        }
        if (right.predictedAt == null) {
            return left;
        }
        return left.predictedAt.isAfter(right.predictedAt) ? left : right;
    }

    private static int reportVersion(AiAnalysisReport report) {
        return report.reportVersion == null ? 0 : report.reportVersion;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static void validate(GenerationRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0
                || request.tradeDate() == null || request.dataBatchId() == null || request.dataBatchId() <= 0
                || request.strategyReleaseId() == null || request.strategyReleaseId() <= 0) {
            throw new IllegalArgumentException("日报报告生成请求缺少正式用户、交易日、数据批次或策略版本");
        }
    }

    private record Candidate(
            String stockCode,
            AiSample sample,
            BigDecimal primaryScore,
            boolean holding,
            boolean riskAction
    ) {
    }

    private record UserUniverse(List<String> stockCodes, Set<String> holdingCodes) {
    }
}
