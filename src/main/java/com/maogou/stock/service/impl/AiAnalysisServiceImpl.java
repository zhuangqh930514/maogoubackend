package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.ai.AiAnalysisReportPageResponse;
import com.maogou.stock.dto.ai.AiAnalysisReportSummaryResponse;
import com.maogou.stock.dto.ai.WatchlistAnalysisResult;
import com.maogou.stock.dto.ai.AiAnalysisResultPayload;
import com.maogou.stock.dto.ai.AiConditionalStrategyPayload;
import com.maogou.stock.dto.ai.AiLearningPayloads;
import com.maogou.stock.dto.market.IntradayPointResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.NewsFlashResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.infrastructure.ai.LocalAiClient;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.AiConditionalTradeStrategyService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.ModelConfigService;
import com.maogou.stock.service.PromptTemplateService;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.WatchlistService;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.ExternalIoTransactionGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiAnalysisServiceImpl implements AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisServiceImpl.class);
    private static final int MAX_ANALYSIS_ATTEMPTS = 3;
    private static final int MIN_ANALYSIS_MAX_TOKENS = 4096;
    private static final int MIN_ANALYSIS_TIMEOUT_MS = 90000;
    private static final int MAX_TEMPLATE_CHARS = 1800;
    private static final int MAX_ANALYSIS_QUOTE_AGE_SECONDS = 120;
    private static final int MAX_ANALYSIS_KLINE_AGE_DAYS = 7;
    private static final String UNKNOWN_STOCK_NAME = "未知股票";
    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?is)```(?:json)?\\s*([\\s\\S]*?)\\s*```");
    private static final String[] REPORT_SUMMARY_COLUMNS = {
            "id", "stock_code", "stock_name", "system_score", "advice", "generated_at",
            "source_model", "status", "error_message", "sample_id", "strategy_release_id",
            "report_version", "supersedes_report_id", "data_quality_score", "calibrated_confidence",
            "final_action", "risk_score", "risk_level"
    };
    private static final String[] REPORT_DETAIL_COLUMNS = {
            "id", "user_id", "stock_code", "stock_name", "sample_id", "strategy_release_id",
            "prompt_template_id", "report_date", "report_version", "supersedes_report_id",
            "idempotency_key", "status", "system_score", "final_action", "target_direction",
            "risk_score", "risk_level", "calibrated_confidence", "data_quality_score", "advice",
            "technical_analysis", "risk_warning", "buy_sell_points", "conditional_strategy",
            "prompt_summary", "source_model", "error_message", "generated_at", "created_at", "updated_at"
    };

    private final AiAnalysisReportMapper reportMapper;
    private final AiAnalysisReportLineageWriter reportLineageWriter;
    private final WatchStockMapper watchStockMapper;
    private final AiSampleMapper sampleMapper;
    private final AiPredictionMapper predictionMapper;
    private final AiStrategyReleaseMapper strategyReleaseMapper;
    private final MarketDataService marketDataService;
    private final WatchlistService watchlistService;
    private final AiConditionalTradeStrategyService conditionalTradeStrategyService;
    private final ModelConfigService modelConfigService;
    private final PromptTemplateService promptTemplateService;
    private final TradingCalendarService tradingCalendarService;
    private final LocalAiClient localAiClient;
    private final ObjectMapper objectMapper;

    public AiAnalysisServiceImpl(
            AiAnalysisReportMapper reportMapper,
            AiAnalysisReportLineageWriter reportLineageWriter,
            WatchStockMapper watchStockMapper,
            AiSampleMapper sampleMapper,
            AiPredictionMapper predictionMapper,
            AiStrategyReleaseMapper strategyReleaseMapper,
            MarketDataService marketDataService,
            WatchlistService watchlistService,
            AiConditionalTradeStrategyService conditionalTradeStrategyService,
            ModelConfigService modelConfigService,
            PromptTemplateService promptTemplateService,
            TradingCalendarService tradingCalendarService,
            LocalAiClient localAiClient,
            ObjectMapper objectMapper
    ) {
        this.reportMapper = reportMapper;
        this.reportLineageWriter = reportLineageWriter;
        this.watchStockMapper = watchStockMapper;
        this.sampleMapper = sampleMapper;
        this.predictionMapper = predictionMapper;
        this.strategyReleaseMapper = strategyReleaseMapper;
        this.marketDataService = marketDataService;
        this.watchlistService = watchlistService;
        this.conditionalTradeStrategyService = conditionalTradeStrategyService;
        this.modelConfigService = modelConfigService;
        this.promptTemplateService = promptTemplateService;
        this.tradingCalendarService = tradingCalendarService;
        this.localAiClient = localAiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AiAnalysisReportSummaryResponse> listReports(String code) {
        QueryWrapper<AiAnalysisReport> wrapper = new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .orderByDesc("generated_at");
        if (code != null && !code.isBlank()) {
            wrapper.eq("stock_code", code);
        }
        wrapper.select(REPORT_SUMMARY_COLUMNS);
        return reportMapper.selectList(wrapper).stream()
                .map(AiAnalysisReportSummaryResponse::from)
                .toList();
    }

    @Override
    public AiAnalysisReportResponse report(Long reportId) {
        if (reportId == null || reportId <= 0) {
            throw new IllegalArgumentException("报告 ID 无效");
        }
        AiAnalysisReport report = reportMapper.selectOwned(reportId, AuthContext.currentUserIdOrDefault());
        if (report == null) {
            throw new IllegalArgumentException("报告不存在");
        }
        return reportResponse(report);
    }

    @Override
    public AiAnalysisReportResponse latestReport(String code) {
        QueryWrapper<AiAnalysisReport> query = new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .orderByDesc("generated_at")
                .orderByDesc("id")
                .last("LIMIT 1");
        if (code != null && !code.isBlank()) {
            query.eq("stock_code", code.trim());
        }
        query.select(REPORT_DETAIL_COLUMNS);
        AiAnalysisReport report = reportMapper.selectOne(query);
        return report == null ? null : reportResponse(report);
    }

    @Override
    public AiAnalysisReportPageResponse pageReports(
            String code,
            LocalDate date,
            int page,
            int pageSize,
            String filter
    ) {
        int normalizedPageSize = Math.max(1, Math.min(pageSize, 50));
        LocalDate selectedDate = date == null ? latestReportDate(code) : date;
        if (selectedDate == null) {
            return AiAnalysisReportPageResponse.empty(normalizedPageSize);
        }

        long total = reportMapper.selectCount(reportPageQuery(code, selectedDate, filter));
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / normalizedPageSize);
        int normalizedPage = totalPages == 0 ? 1 : Math.min(Math.max(1, page), totalPages);
        if (total == 0) {
            return new AiAnalysisReportPageResponse(
                    List.of(), 0, normalizedPage, normalizedPageSize, 0,
                    selectedDate, date == null ? selectedDate : null);
        }

        long offset = (long) (normalizedPage - 1) * normalizedPageSize;
        QueryWrapper<AiAnalysisReport> query = reportPageQuery(code, selectedDate, filter)
                .orderByDesc("generated_at")
                .orderByDesc("id")
                .last("LIMIT " + normalizedPageSize + " OFFSET " + offset);
        query.select(REPORT_SUMMARY_COLUMNS);
        List<AiAnalysisReportSummaryResponse> items = reportMapper.selectList(query).stream()
                .map(AiAnalysisReportSummaryResponse::from)
                .toList();
        return new AiAnalysisReportPageResponse(
                items,
                total,
                normalizedPage,
                normalizedPageSize,
                totalPages,
                selectedDate,
                date == null ? selectedDate : null);
    }

    private LocalDate latestReportDate(String code) {
        QueryWrapper<AiAnalysisReport> query = new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .select("MAX(report_date)");
        if (code != null && !code.isBlank()) {
            query.eq("stock_code", code.trim());
        }
        return reportMapper.selectObjs(query).stream()
                .map(AiAnalysisServiceImpl::toLocalDate)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private QueryWrapper<AiAnalysisReport> reportPageQuery(String code, LocalDate date, String filter) {
        QueryWrapper<AiAnalysisReport> query = new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .eq("report_date", date);
        if (code != null && !code.isBlank()) {
            query.eq("stock_code", code.trim());
        }
        switch (filter == null ? "ALL" : filter.trim().toUpperCase(Locale.ROOT)) {
            case "HIGH_RISK" -> query.ge("risk_score", 60);
            case "BUY" -> query.in("final_action", "BUY", "HOLD");
            case "REDUCE" -> query.in("final_action", "REDUCE", "SELL");
            default -> {
            }
        }
        return query;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.length() < 10 ? null : LocalDate.parse(text.substring(0, 10));
    }

    private List<AiAnalysisReportResponse> reportResponses(List<AiAnalysisReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return List.of();
        }
        Long userId = AuthContext.currentUserIdOrDefault();
        Map<Long, List<AiConditionalStrategyPayload.ReviewResult>> reviews;
        try {
            reviews = conditionalTradeStrategyService.reviewsByReportIds(
                    userId, reports.stream().map(item -> item.id).filter(Objects::nonNull).toList());
        } catch (RuntimeException exception) {
            log.warn("load conditional trade reviews failed, userId={}, message={}", userId, exception.getMessage());
            reviews = Map.of();
        }
        Map<Long, List<AiConditionalStrategyPayload.ReviewResult>> reviewMap = reviews == null ? Map.of() : reviews;
        return reports.stream()
                .map(item -> AiAnalysisReportResponse.from(item, reviewMap.getOrDefault(item.id, List.of())))
                .toList();
    }

    private AiAnalysisReportResponse reportResponse(AiAnalysisReport report) {
        List<AiAnalysisReportResponse> responses = reportResponses(List.of(report));
        return responses.isEmpty() ? AiAnalysisReportResponse.from(report) : responses.get(0);
    }

    @Override
    public void removeReports(List<Long> ids) {
        List<Long> normalizedIds = ids == null ? List.of() : ids.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return;
        }
        throw new UnsupportedOperationException("正式个股报告为不可变研究证据，不支持删除");
    }

    @Override
    public AiAnalysisReportResponse analyzeStock(String code, boolean forceRefresh, Long promptTemplateId, Long targetReportId) {
        return analyzeStockInternal(code, forceRefresh, promptTemplateId, targetReportId, LocalDate.now(), false);
    }

    @Override
    public AiAnalysisReportResponse analyzeStockForTradeDate(
            String code,
            boolean forceRefresh,
            Long promptTemplateId,
            Long targetReportId,
            LocalDate tradeDate
    ) {
        return analyzeStockInternal(code, forceRefresh, promptTemplateId, targetReportId,
                Objects.requireNonNull(tradeDate, "tradeDate"), true);
    }

    private AiAnalysisReportResponse analyzeStockInternal(
            String code,
            boolean forceRefresh,
            Long promptTemplateId,
            Long targetReportId,
            LocalDate tradeDate,
            boolean pointInTime
    ) {
        Long userId = AuthContext.currentUserIdOrDefault();
        Long normalizedPromptTemplateId = normalizePromptTemplateId(promptTemplateId);
        validateTargetReport(userId, targetReportId, code);
        LocalDateTime marketDataAsOf = tradeDate.atTime(16, 0);
        StockDetailResponse detail = ExternalIoTransactionGuard.call(
                "股票分析行情调用",
                () -> pointInTime
                        ? marketDataService.stockDetailAt(code, marketDataAsOf)
                        : marketDataService.stockDetailForAnalysis(code));
        AnalysisFreshness freshness = validateAnalysisFreshness(detail);
        FormalAnalysisContext formalContext = loadFormalContext(detail.quote().code(), tradeDate);
        AiLearningPayloads.AnalysisLearningContext learningContext = formalContext.conditionalContext();
        List<NewsFlashResponse> realtimeNews = ExternalIoTransactionGuard.call(
                "股票分析资讯调用",
                () -> pointInTime
                        ? marketDataService.latestNewsForAnalysisAt(8, marketDataAsOf)
                        : marketDataService.latestNewsForAnalysis(8));
        AiConditionalStrategyPayload conditionalStrategy = conditionalTradeStrategyService.build(
                userId, detail, tradeDate, learningContext);
        String conditionalStrategyJson = writeRequiredJson(conditionalStrategy);
        AiModelConfig config = normalizeAnalysisConfig(modelConfigService.currentEntity());
        String selectedTemplate = promptTemplateService.resolveContent(promptTemplateId, null);
        String prompt = buildPrompt(detail, config, selectedTemplate, activeStrategyPrompt(formalContext.release()),
                formalContext.promptContext(), conditionalStrategyJson, freshness, realtimeNews);
        LocalDateTime now = LocalDateTime.now();
        LocalDate reportDate = tradeDate;
        FormalDecision formalDecision = formalContext.decision();
        AiAnalysisReport report = new AiAnalysisReport();
        report.userId = userId;
        report.stockCode = detail.quote().code();
        report.stockName = resolveStockName(userId, report.stockCode, detail.quote().name());
        report.rawPrompt = prompt;
        report.sourceModel = config.modelName;
        report.promptTemplateId = normalizedPromptTemplateId;
        report.reportDate = reportDate;
        report.generatedAt = now;
        report.sampleId = formalContext.sample().id;
        report.strategyReleaseId = formalContext.release().id;
        report.dataQualityScore = formalContext.sample().dataQualityScore == null
                ? BigDecimal.ZERO : formalContext.sample().dataQualityScore;
        report.calibratedConfidence = formalDecision.calibratedConfidence();
        report.updatedAt = now;
        report.status = AnalysisStatus.PENDING;
        report.systemScore = formalDecision.systemScore();
        report.finalAction = formalDecision.finalAction();
        report.targetDirection = formalDecision.targetDirection();
        report.riskScore = formalDecision.riskScore();
        report.riskLevel = formalDecision.riskLevel();
        report.advice = adviceFor(formalDecision);
        report.promptSummary = buildFallbackPromptSummary(detail);
        report.conditionalStrategy = conditionalStrategyJson;
        report.buySellPoints = deterministicTradePlanSummary(formalDecision, conditionalStrategy);
        report.createdAt = now;

        AiAnalysisResultPayload parsedPayload = null;
        try {
            log.info("start AI stock analysis userId={} code={} stock={} model={} promptTemplateId={} targetReportId={}",
                    report.userId, report.stockCode, report.stockName, config.modelName, normalizedPromptTemplateId, targetReportId);
            AnalysisAttemptResult attemptResult = executeAnalysisWithRetry(prompt, config);
            parsedPayload = attemptResult.payload();
            report.rawResponse = attemptResult.rawResponse();
            applyPayload(report, parsedPayload);
            report.status = AnalysisStatus.SUCCESS;
            report.errorMessage = null;
            log.info("AI stock analysis success userId={} code={} reportScore={} responseChars={}",
                    report.userId, report.stockCode, report.systemScore, safeLength(report.rawResponse));
        } catch (Exception ex) {
            report.technicalAnalysis = "AI 分析调用失败，已保存失败报告供排查。";
            report.riskWarning = buildRiskWarningFailureMessage(ex);
            report.promptSummary = buildFallbackPromptSummary(detail);
            report.rawResponse = ex instanceof AnalysisAttemptException attemptException ? attemptException.rawResponse() : report.rawResponse;
            report.errorMessage = ex.getMessage();
            report.status = AnalysisStatus.FAILED;
            log.error("AI stock analysis failed userId={} code={} model={} promptTemplateId={} targetReportId={} message={} rawResponsePreview={}",
                    report.userId, report.stockCode, config.modelName, normalizedPromptTemplateId, targetReportId, ex.getMessage(), preview(report.rawResponse), ex);
        }
        reportLineageWriter.persistVersion(report, formalContext.linkedPredictions());
        try {
            conditionalTradeStrategyService.initializeReviews(report, conditionalStrategy);
        } catch (RuntimeException exception) {
            log.warn("initialize conditional trade reviews failed, reportId={}, message={}", report.id, exception.getMessage());
        }
        return reportResponse(report);
    }

    @Override
    public WatchlistAnalysisResult analyzeWatchlist(Long promptTemplateId) {
        List<WatchStockResponse> watchlist = watchlistService.list("全部");
        List<WatchlistAnalysisResult.SkippedStock> skipped = new ArrayList<>();
        int analyzed = 0;
        for (WatchStockResponse stock : watchlist) {
            try {
                analyzeStock(stock.code(), false, promptTemplateId, null);
                analyzed++;
            } catch (FormalResearchSampleUnavailableException exception) {
                skipped.add(new WatchlistAnalysisResult.SkippedStock(
                        stock.code(), stock.name(), "尚未形成正式收盘研究样本，等待下一交易日收盘流水线"));
            }
        }
        return new WatchlistAnalysisResult(watchlist.size(), analyzed, skipped);
    }

    private Long normalizePromptTemplateId(Long promptTemplateId) {
        return promptTemplateId == null || promptTemplateId <= 0 ? null : promptTemplateId;
    }

    private void validateTargetReport(Long userId, Long targetReportId, String stockCode) {
        if (targetReportId == null || targetReportId <= 0) {
            return;
        }
        AiAnalysisReport target = reportMapper.selectOwned(targetReportId, userId);
        if (target == null) {
            throw new IllegalArgumentException("待重新分析的报告不存在或不属于当前用户");
        }
        if (stockCode != null && target.stockCode != null
                && stockCode.matches("\\d{6}") && !stockCode.equals(target.stockCode)) {
            throw new IllegalArgumentException("待重新分析报告与股票代码不一致");
        }
    }

    private FormalAnalysisContext loadFormalContext(String stockCode, LocalDate tradeDate) {
        AiSample sample = sampleMapper.selectLatestForAnalysis(stockCode, tradeDate);
        if (sample == null || sample.id == null) {
            throw new FormalResearchSampleUnavailableException("该股票尚无正式收盘研究样本，请等待收盘研究流水线完成");
        }
        AiStrategyRelease release = strategyReleaseMapper.selectGlobalActiveChampion(
                AiResearchContract.SYSTEM_UNIVERSE_CODE, AiResearchContract.MODEL_FAMILY);
        if (release == null || release.id == null) {
            throw new IllegalStateException("正式 Champion 策略不可用，无法生成可追溯报告");
        }
        List<AiPrediction> allPredictions = predictionMapper.selectForAnalysis(sample.id);
        List<AiPrediction> selected = selectCurrentReleasePredictions(allPredictions, release.id);
        Map<Integer, AiPrediction> byHorizon = new LinkedHashMap<>();
        selected.stream()
                .filter(item -> item.horizonDays != null)
                .forEach(item -> byHorizon.putIfAbsent(item.horizonDays, item));

        FormalDecision decision = deriveFormalDecision(sample, byHorizon);
        String promptContext = formalPromptContext(sample, release, byHorizon, decision);
        return new FormalAnalysisContext(
                sample,
                release,
                Map.copyOf(byHorizon),
                List.copyOf(byHorizon.values()),
                decision,
                promptContext);
    }

    private static final class FormalResearchSampleUnavailableException extends IllegalStateException {
        private FormalResearchSampleUnavailableException(String message) {
            super(message);
        }
    }

    static List<AiPrediction> selectCurrentReleasePredictions(
            List<AiPrediction> predictions,
            Long strategyReleaseId
    ) {
        if (predictions == null || predictions.isEmpty()) {
            return List.of();
        }
        return predictions.stream()
                .filter(Objects::nonNull)
                .filter(item -> Objects.equals(item.strategyReleaseId, strategyReleaseId))
                .sorted(Comparator.comparing((AiPrediction item) -> item.predictedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(item -> item.id, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    static FormalDecision deriveFormalDecision(AiSample sample, Map<Integer, AiPrediction> predictions) {
        if (sample == null) {
            return FormalDecision.unavailable("MISSING_FORMAL_SAMPLE");
        }
        if (sample.qualityStatus != null && !"READY".equals(sample.qualityStatus)) {
            return FormalDecision.unavailable("SAMPLE_QUALITY_" + sample.qualityStatus);
        }
        if (sample.tradableStatus != null && !"TRADABLE".equals(sample.tradableStatus)) {
            return FormalDecision.unavailable("SAMPLE_TRADABLE_" + sample.tradableStatus);
        }
        if (sample.dataQualityScore == null) {
            return FormalDecision.unavailable("MISSING_DATA_QUALITY");
        }
        Map<Integer, AiPrediction> values = predictions == null ? Map.of() : predictions;
        for (Integer horizon : List.of(1, 2, 3)) {
            AiPrediction prediction = values.get(horizon);
            if (prediction == null) {
                return FormalDecision.unavailable("MISSING_T" + horizon + "_PREDICTION");
            }
            if (prediction.score == null || prediction.riskScore == null
                    || prediction.calibratedConfidence == null || prediction.action == null) {
                return FormalDecision.unavailable("INVALID_T" + horizon + "_PREDICTION");
            }
        }
        AiPrediction t1 = values.get(1);
        AiPrediction t2 = values.get(2);
        AiPrediction t3 = values.get(3);
        BigDecimal score = t1.score.multiply(new BigDecimal("0.20"))
                .add(t2.score.multiply(new BigDecimal("0.30")))
                .add(t3.score.multiply(new BigDecimal("0.50")))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal confidence = t1.calibratedConfidence.multiply(new BigDecimal("0.20"))
                .add(t2.calibratedConfidence.multiply(new BigDecimal("0.30")))
                .add(t3.calibratedConfidence.multiply(new BigDecimal("0.50")))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal risk = t3.riskScore;
        String action = normalizeFormalAction(t3.action);
        if (risk.compareTo(new BigDecimal("70")) >= 0 && !List.of("REDUCE", "SELL").contains(action)) {
            action = "WATCH";
        }
        String direction = t3.targetDirection == null || t3.targetDirection.isBlank()
                ? "SIDEWAYS" : t3.targetDirection.trim().toUpperCase(Locale.ROOT);
        return new FormalDecision(score, action, direction, risk, formalRiskLevel(risk), confidence, null);
    }

    private String formalPromptContext(
            AiSample sample,
            AiStrategyRelease release,
            Map<Integer, AiPrediction> predictions,
            FormalDecision decision
    ) {
        List<Map<String, Object>> horizons = List.of(1, 2, 3, 5).stream()
                .map(predictions::get)
                .filter(Objects::nonNull)
                .map(item -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("horizonTradingDays", item.horizonDays);
                    value.put("predictionId", item.id);
                    value.put("action", item.action);
                    value.put("targetDirection", item.targetDirection);
                    value.put("score", item.score);
                    value.put("riskScore", item.riskScore);
                    value.put("probabilityUp", item.probabilityUp);
                    value.put("probabilityDown", item.probabilityDown);
                    value.put("expectedExcessReturn", item.expectedExcessReturn);
                    value.put("calibratedConfidence", item.calibratedConfidence);
                    value.put("predictedAt", item.predictedAt);
                    value.put("inputFingerprint", item.inputFingerprint);
                    return value;
                }).toList();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sampleId", sample.id);
        context.put("sampleTradeDate", sample.tradeDate);
        context.put("sampleAsOfTime", sample.asOfTime);
        context.put("samplePhase", sample.samplePhase);
        context.put("dataQualityScore", sample.dataQualityScore);
        context.put("qualityStatus", sample.qualityStatus);
        context.put("marketRegime", sample.marketRegime);
        context.put("strategyReleaseId", release.id);
        context.put("strategyReleaseVersion", release.versionNo);
        context.put("formalPredictions", horizons);
        context.put("deterministicSystemScore", decision.systemScore());
        context.put("deterministicFinalAction", decision.finalAction());
        context.put("deterministicRiskScore", decision.riskScore());
        context.put("deterministicRiskLevel", decision.riskLevel());
        context.put("unavailableReason", decision.unavailableReason());
        context.put("llmConfidenceWeight", 0);
        return writeRequiredJson(context);
    }

    private static String adviceFor(FormalDecision decision) {
        if (decision.unavailableReason() != null) {
            return "谨慎观察：核心分周期预测不完整，模型仅提供解释，不生成推荐";
        }
        return switch (decision.finalAction()) {
            case "BUY" -> "满足正式策略条件后关注买入机会";
            case "HOLD" -> "按条件计划持有并跟踪风险触发线";
            case "REDUCE" -> "风险信号触发，按条件计划降低仓位";
            case "SELL" -> "退出条件触发，按风险规则处理";
            default -> "谨慎观察，等待条件触发";
        };
    }

    private String deterministicTradePlanSummary(
            FormalDecision decision,
            AiConditionalStrategyPayload strategy
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "SYSTEM_CONDITIONAL_RULES");
        result.put("action", decision.finalAction());
        result.put("systemScore", decision.systemScore());
        result.put("riskScore", decision.riskScore());
        result.put("riskLevel", decision.riskLevel());
        result.put("unavailableReason", decision.unavailableReason());
        result.put("tradingPlans", strategy == null ? List.of() : strategy.tradingPlans());
        result.put("buyModels", strategy == null ? List.of() : strategy.buyModels());
        result.put("sellModels", strategy == null ? List.of() : strategy.sellModels());
        return writeRequiredJson(result);
    }

    private static String normalizeFormalAction(String action) {
        String normalized = action == null ? "WATCH" : action.trim().toUpperCase(Locale.ROOT);
        return List.of("BUY", "HOLD", "REDUCE", "SELL", "WATCH").contains(normalized)
                ? normalized : "WATCH";
    }

    private static String formalRiskLevel(BigDecimal risk) {
        if (risk.compareTo(new BigDecimal("30")) < 0) {
            return "LOW";
        }
        if (risk.compareTo(new BigDecimal("60")) < 0) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    private String resolveStockName(Long userId, String stockCode, String suggestedName) {
        if (hasMeaningfulStockName(suggestedName)) {
            return suggestedName.trim();
        }
        String knownFromUserReports = findKnownStockName(userId, stockCode);
        if (knownFromUserReports != null) {
            return knownFromUserReports;
        }
        if (userId != null) {
            var watchStock = watchStockMapper.selectAnyByUserIdAndCode(userId, stockCode);
            if (watchStock != null && hasMeaningfulStockName(watchStock.stockName)) {
                return watchStock.stockName.trim();
            }
        }
        String knownFromGlobalReports = findKnownStockName(null, stockCode);
        if (knownFromGlobalReports != null) {
            return knownFromGlobalReports;
        }
        return stockCode;
    }

    private String findKnownStockName(Long userId, String stockCode) {
        if (stockCode == null || stockCode.isBlank()) {
            return null;
        }
        QueryWrapper<AiAnalysisReport> wrapper = new QueryWrapper<AiAnalysisReport>()
                .select("stock_name")
                .eq("stock_code", stockCode)
                .isNotNull("stock_name")
                .notIn("stock_name", UNKNOWN_STOCK_NAME, stockCode)
                .orderByDesc("generated_at")
                .last("LIMIT 1");
        if (userId != null) {
            wrapper.eq("user_id", userId);
        }
        AiAnalysisReport existing = reportMapper.selectOne(wrapper);
        if (existing == null || !hasMeaningfulStockName(existing.stockName)) {
            return null;
        }
        return existing.stockName.trim();
    }

    private boolean hasMeaningfulStockName(String stockName) {
        if (stockName == null) {
            return false;
        }
        String normalized = stockName.trim();
        return !normalized.isBlank() && !UNKNOWN_STOCK_NAME.equals(normalized);
    }

    private AnalysisAttemptResult executeAnalysisWithRetry(String prompt, AiModelConfig config) throws Exception {
        Exception lastException = null;
        String lastRawResponse = "";
        for (int attempt = 1; attempt <= MAX_ANALYSIS_ATTEMPTS; attempt++) {
            try {
                String aiText = ExternalIoTransactionGuard.call(
                        "大模型推理调用", () -> localAiClient.chat(prompt, config));
                lastRawResponse = aiText;
                AiAnalysisResultPayload payload = parseAiPayload(aiText);
                validatePayload(payload);
                return new AnalysisAttemptResult(aiText, payload);
            } catch (Exception ex) {
                lastException = wrapRetryableException(ex, lastRawResponse);
                log.warn("AI stock analysis attempt failed attempt={}/{} message={} rawResponsePreview={}",
                        attempt, MAX_ANALYSIS_ATTEMPTS, lastException.getMessage(), preview(lastRawResponse));
                if (!shouldRetry(lastException) || attempt >= MAX_ANALYSIS_ATTEMPTS) {
                    throw lastException;
                }
            }
        }
        throw lastException == null ? new IllegalStateException("AI 分析失败") : lastException;
    }

    void applyPayload(AiAnalysisReport report, AiAnalysisResultPayload payload) throws JsonProcessingException {
        report.technicalAnalysis = writeJson(payload.technicalAnalysis());
        report.riskWarning = writeJson(payload.riskWarning());
        report.promptSummary = writeJson(payload.promptSummary());
    }

    private void validatePayload(AiAnalysisResultPayload payload) {
        if (payload == null) {
            throw new IllegalStateException("模型返回为空，无法生成报告。") ;
        }
        if (payload.technicalAnalysis() == null || payload.riskWarning() == null || payload.buySellPoints() == null || payload.promptSummary() == null) {
            throw new IllegalStateException("模型返回缺少必要字段，无法生成完整报告。") ;
        }
    }

    private Exception wrapRetryableException(Exception ex, String rawResponse) {
        if (ex instanceof JsonProcessingException) {
            String normalized = stripCodeFence(rawResponse);
            if (looksTruncatedJson(normalized)) {
                return new AnalysisAttemptException("模型返回内容被截断，已重试仍失败。", rawResponse, ex);
            }
            return new AnalysisAttemptException("模型返回 JSON 格式无效，已重试仍失败。", rawResponse, ex);
        }
        if (ex instanceof ResourceAccessException) {
            return new AnalysisAttemptException("模型请求超时或网络不可达，已重试仍失败。", rawResponse, ex);
        }
        if (ex instanceof RestClientResponseException restEx) {
            if (restEx.getStatusCode().is4xxClientError()) {
                return new AnalysisAttemptException("模型配置或鉴权失败：" + restEx.getStatusCode().value(), rawResponse, restEx);
            }
            return new AnalysisAttemptException("模型服务异常：" + restEx.getStatusCode().value(), rawResponse, restEx);
        }
        return new AnalysisAttemptException(ex.getMessage() == null || ex.getMessage().isBlank() ? "AI 分析失败" : ex.getMessage(), rawResponse, ex);
    }

    private boolean shouldRetry(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("被截断")
                || message.contains("JSON 格式无效")
                || message.contains("未返回 JSON")
                || message.contains("返回为空")
                || message.contains("缺少必要字段")
                || message.contains("超时")
                || message.contains("网络不可达")
                || message.contains("模型服务异常");
    }

    private boolean looksTruncatedJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String normalized = raw.trim();
        if (!(normalized.startsWith("{") || normalized.startsWith("["))) {
            return false;
        }
        int openBraces = 0;
        int closeBraces = 0;
        int openBrackets = 0;
        int closeBrackets = 0;
        long quoteCount = normalized.chars().filter(ch -> ch == '"').count();
        for (char ch : normalized.toCharArray()) {
            if (ch == '{') {
                openBraces++;
            } else if (ch == '}') {
                closeBraces++;
            } else if (ch == '[') {
                openBrackets++;
            } else if (ch == ']') {
                closeBrackets++;
            }
        }
        return (quoteCount % 2 != 0) || openBraces != closeBraces || openBrackets != closeBrackets;
    }

    private String buildRiskWarningFailureMessage(Exception ex) {
        String message = ex == null ? "" : ex.getMessage();
        if (message == null || message.isBlank()) {
            return "本次报告未获得有效风险提示，请检查模型输出格式或重新生成。";
        }
        if (message.contains("被截断")) {
            return "模型返回内容被截断，风险提示未能完整生成，请重新生成。";
        }
        if (message.contains("JSON 格式无效")) {
            return "模型返回格式异常，风险提示未能正确解析，请重新生成。";
        }
        if (message.contains("超时") || message.contains("网络不可达")) {
            return "模型请求超时或网络异常，风险提示未能生成，请稍后重试。";
        }
        if (message.contains("鉴权失败") || message.contains("配置")) {
            return "当前模型配置不可用，请检查模型中心配置后重新生成。";
        }
        return "本次报告未获得有效风险提示，请检查模型输出格式或重新生成。";
    }

    private String activeStrategyPrompt(AiStrategyRelease release) {
        if (release == null) {
            return "当前没有可用的正式策略发布。";
        }
        return """
                正式策略发布：%s / %s
                发布角色：%s
                发布状态：%s
                配置快照：%s
                因子快照：%s
                验证指标：%s
                """.formatted(
                release.versionNo,
                release.title,
                release.releaseRole,
                release.status,
                release.configJson == null ? "{}" : release.configJson,
                release.factorSnapshotJson == null ? "{}" : release.factorSnapshotJson,
                release.validationMetricsJson == null ? "{}" : release.validationMetricsJson);
    }

    private AnalysisFreshness validateAnalysisFreshness(StockDetailResponse detail) {
        if (detail == null || detail.quote() == null) {
            throw new IllegalStateException("实时行情不可用，已停止 AI 分析。");
        }
        if (detail.quote().price() == null || detail.quote().price().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("实时行情价格不可用，已停止 AI 分析。");
        }
        String source = detail.quote().source() == null ? "" : detail.quote().source().trim().toUpperCase(Locale.ROOT);
        if (source.isBlank() || source.contains("MOCK") || "LOCAL_TEST_FIXTURE".equals(source)
                || "LOCAL_FALLBACK".equals(source)) {
            throw new IllegalStateException("当前行情来源不是实时数据源（source=" + (source.isBlank() ? "UNKNOWN" : source) + "），已停止 AI 分析。");
        }
        LocalDateTime fetchedAt = detail.quote().fetchedAt();
        if (fetchedAt == null) {
            throw new IllegalStateException("实时行情缺少抓取时间，已停止 AI 分析。");
        }
        LocalDateTime now = LocalDateTime.now();
        if (fetchedAt.isBefore(now.minusSeconds(MAX_ANALYSIS_QUOTE_AGE_SECONDS))) {
            long ageSeconds = Duration.between(fetchedAt, now).toSeconds();
            throw new IllegalStateException("实时行情已过期 " + ageSeconds + " 秒，已停止 AI 分析，请刷新后重试。");
        }
        LocalDate latestKlineDate = detail.kline() == null ? null : detail.kline().stream()
                .map(KlinePointResponse::tradeDate)
                .filter(item -> item != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (latestKlineDate == null) {
            throw new IllegalStateException("最近 K 线数据不可用，已停止 AI 分析。");
        }
        LocalDate minimumKlineDate = tradingCalendarService.minimumRequiredAnalysisKlineDate(now);
        if (latestKlineDate.isBefore(minimumKlineDate)) {
            throw new IllegalStateException("最近 K 线日期为 " + latestKlineDate + "，低于当前允许的最近完整交易日 " + minimumKlineDate + "，已停止 AI 分析。");
        }
        LocalDate staleBefore = now.toLocalDate().minusDays(MAX_ANALYSIS_KLINE_AGE_DAYS);
        if (latestKlineDate.isBefore(staleBefore)) {
            throw new IllegalStateException("最近 K 线日期为 " + latestKlineDate + "，超过 " + MAX_ANALYSIS_KLINE_AGE_DAYS + " 天未更新，已停止 AI 分析。");
        }
        String lastIntradayTime = detail.intraday() == null || detail.intraday().isEmpty()
                ? "未获取到分时数据"
                : detail.intraday().stream()
                .map(IntradayPointResponse::time)
                .filter(item -> item != null && !item.isBlank())
                .reduce((left, right) -> right)
                .orElse("未获取到分时数据");
        return new AnalysisFreshness(source, fetchedAt, latestKlineDate, lastIntradayTime);
    }

    private String formatRealtimeNews(List<NewsFlashResponse> news) {
        if (news == null || news.isEmpty()) {
            return "本次未获取到最新资讯。";
        }
        return news.stream()
                .filter(item -> item != null && item.publishedAt() != null && item.title() != null && !item.title().isBlank())
                .limit(8)
                .map(item -> "- " + item.publishedAt() + " [" + safeText(item.source()) + "] " + item.title())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("本次未获取到最新资讯。");
    }

    private String buildPrompt(
            StockDetailResponse detail,
            AiModelConfig config,
            String selectedTemplate,
            String activeStrategyPrompt,
            String learningContext,
            String conditionalStrategy,
            AnalysisFreshness freshness,
            List<NewsFlashResponse> realtimeNews
    ) {
        String configuredTemplate = config.promptTemplate == null || config.promptTemplate.isBlank()
                ? "请基于行情、K线、财务数据输出技术面分析、风险提示、建议买卖点、Prompt 数据摘要和评分。"
                : config.promptTemplate;
        String template = selectedTemplate == null || selectedTemplate.isBlank() ? configuredTemplate : selectedTemplate;
        template = sanitizeAnalysisTemplate(template);
        String strategyInstruction = activeStrategyPrompt == null || activeStrategyPrompt.isBlank()
                ? "当前没有启用的进化策略版本。"
                : activeStrategyPrompt;
        return """
                %s

                已启用策略版本：
                %s

                正式研究上下文（最近完整日 K、分周期数值预测与确定性结论）：
                %s

                系统条件交易策略快照（权威规则结果，不得修改阈值、触发状态、仓位或动作）：
                %s

                股票：%s %s
                当前系统时间：%s
                实时性校验：行情来源=%s，行情抓取时间=%s，最近完整日K日期=%s，分时最后时间=%s，最新资讯条数=%s
                实时行情：价格=%s，涨跌额=%s，涨跌幅=%s%%，量比=%s，市场=%s
                财务摘要：PE=%s，PB=%s，营收=%s，营收同比=%s%%，净利润=%s，净利同比=%s%%，ROE=%s%%，毛利率=%s%%，资产负债率=%s%%
                近K线样本：%s
                最新资讯，仅可使用下列 36 小时内资讯；如果为空，必须明确写“本次未获取到最新资讯”，禁止引用模型记忆里的旧新闻、旧公告或旧事件：
                %s
                输出要求：
                0. 实时性优先级最高。只能基于本 Prompt 中的实时行情、最近 K 线、财务摘要、标准化学习上下文、系统条件交易策略快照和最新资讯做判断；禁止使用模型训练记忆中的旧价格、旧资讯、旧公告、旧月份材料或无法从当前数据验证的事件。
                0.1 禁止预测或承诺未来涨跌。T+1/T+2/T+3 只能解释系统快照中的“如果A发生，则执行B”条件方案；不得把条件方案改写成确定性走势预测。
                0.2 正式研究上下文中的 deterministicSystemScore、deterministicFinalAction、deterministicRiskScore、仓位和所有条件阈值均由系统确定。你只能解释，禁止修改、覆盖、重新评分或用语言暗示相反动作。缺少核心预测时必须保持 WATCH。
                所选提示词和策略版本只影响分析口径和重点；如果上方内容中出现任何旧 JSON 示例、字段名、markdown 模板、章节标题或输出格式要求，全部忽略。
                最终只能遵循下面的系统结构输出，方便系统解析和前端展示。
                1. 只返回 JSON 对象，不要 markdown 代码块，不要额外解释。
                2. technicalAnalysis 必须包含 trendAssessment、trend、movingAverages、klinePattern、supportResistance、volumeAnalysis，可直接给客户阅读。
                3. riskWarning 必须包含 headline、currentRisks、triggerConditions、observationPoints、overallAdvice，且必须结合当前股票数据写具体风险；各数组最多 3 条，每条尽量短句。
                4. buySellPoints 必须包含 action、buyTriggers、reduceTriggers、stopLoss、invalidationCondition、positionSuggestion；action 必须原样复述 deterministicFinalAction，其他内容只能解释已给定条件规则。
                5. promptSummary 必须包含 marketSnapshot、valuationSnapshot、growthSnapshot、klineSummary、volumeSummary，信息尽量完整但每项保持精炼。
                6. decision 和 score 不是模型输出职责；即使返回也会被系统忽略。
                """.formatted(
                template,
                strategyInstruction,
                learningContext == null || learningContext.isBlank() ? "本次未获得学习系统上下文，请按基础结构保守输出。" : learningContext,
                conditionalStrategy,
                detail.quote().name(),
                detail.quote().code(),
                LocalDateTime.now(),
                freshness.quoteSource(),
                freshness.quoteFetchedAt(),
                freshness.latestKlineDate(),
                freshness.lastIntradayTime(),
                realtimeNews == null ? 0 : realtimeNews.size(),
                detail.quote().price(),
                detail.quote().change(),
                detail.quote().percent(),
                detail.quote().volumeRatio(),
                detail.quote().market(),
                detail.finance().pe(),
                detail.finance().pb(),
                detail.finance().revenue(),
                detail.finance().revenueGrowth(),
                detail.finance().netProfit(),
                detail.finance().profitGrowth(),
                detail.finance().roe(),
                detail.finance().grossMargin(),
                detail.finance().debtRatio(),
                latestKlineSample(detail),
                formatRealtimeNews(realtimeNews)
        );
    }

    private List<KlinePointResponse> latestKlineSample(StockDetailResponse detail) {
        if (detail == null || detail.kline() == null || detail.kline().isEmpty()) {
            return List.of();
        }
        List<KlinePointResponse> sorted = detail.kline().stream()
                .filter(item -> item != null && item.tradeDate() != null)
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                .toList();
        int fromIndex = Math.max(0, sorted.size() - 12);
        return sorted.subList(fromIndex, sorted.size());
    }

    private AiAnalysisResultPayload parseAiPayload(String aiText) throws Exception {
        String normalized = normalizeAiText(aiText);
        String jsonCandidate = extractFirstJsonObject(normalized);
        if (jsonCandidate == null) {
            return parseTextPayload(normalized);
        }
        JsonNode root = unwrapPayloadRoot(objectMapper.readTree(jsonCandidate));
        AiAnalysisResultPayload legacyPayload = parseLegacySchemaPayload(root);
        if (legacyPayload != null) {
            return legacyPayload;
        }
        if (!hasAnyReportField(root)) {
            return parseTextPayload(normalized);
        }
        return new AiAnalysisResultPayload(
                parseDecision(root),
                parseTechnicalAnalysis(field(root, "technicalAnalysis", "technical_analysis", "技术面分析")),
                parseRiskWarning(field(root, "riskWarning", "risk_warning", "风险提示")),
                parseBuySellPoints(field(root, "buySellPoints", "buy_sell_points", "建议买卖点")),
                parsePromptSummary(field(root, "promptSummary", "prompt_summary", "数据摘要", "Prompt 数据摘要")),
                parseScore(field(root, "score", "综合评分", "评分"))
        );
    }

    private String normalizeAiText(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = THINK_BLOCK.matcher(raw).replaceAll("").trim();
        Matcher matcher = FENCED_BLOCK.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return trimmed;
    }

    private String stripCodeFence(String raw) {
        return normalizeAiText(raw);
    }

    private String extractFirstJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < raw.length(); index++) {
            char ch = raw.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, index + 1);
                }
            }
        }
        return raw.substring(start);
    }

    private JsonNode unwrapPayloadRoot(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            return objectMapper.createObjectNode();
        }
        if (root.isArray() && !root.isEmpty()) {
            return unwrapPayloadRoot(root.get(0));
        }
        if (hasAnyReportField(root)) {
            return root;
        }
        for (String key : List.of("data", "result", "report", "analysis", "content")) {
            JsonNode nested = root.path(key);
            if (nested.isObject() && hasAnyReportField(nested)) {
                return nested;
            }
        }
        return root;
    }

    private boolean hasAnyReportField(JsonNode node) {
        return field(node, "technicalAnalysis", "technical_analysis", "技术面分析") != null
                || field(node, "riskWarning", "risk_warning", "风险提示") != null
                || field(node, "buySellPoints", "buy_sell_points", "建议买卖点") != null
                || field(node, "promptSummary", "prompt_summary", "数据摘要", "Prompt 数据摘要") != null;
    }

    private AiAnalysisResultPayload parseLegacySchemaPayload(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode policyNode = field(root, "policy_alignment", "policyAlignment", "政策与赛道定位");
        JsonNode valuationNode = field(root, "fundamental_valuation", "fundamentalValuation", "基本面与估值安全边际");
        JsonNode technicalNode = field(root, "technical_capital", "technicalCapital", "资金面与技术形态");
        JsonNode risksNode = field(root, "key_risks", "keyRisks", "核心风险提示");
        JsonNode ratingNode = field(root, "investment_rating", "investmentRating", "投资评级");
        JsonNode confidenceNode = field(root, "confidence_score", "confidenceScore", "置信度");
        JsonNode summaryNode = field(root, "summary_reasoning", "summaryReasoning", "核心逻辑", "总结");
        boolean matchesLegacy = policyNode != null
                || valuationNode != null
                || technicalNode != null
                || risksNode != null
                || ratingNode != null
                || confidenceNode != null
                || summaryNode != null;
        if (!matchesLegacy) {
            return null;
        }

        String policy = stringify(policyNode);
        String valuation = stringify(valuationNode);
        String technical = stringify(technicalNode);
        List<String> risks = stringList(risksNode);
        String rating = stringify(ratingNode);
        String summary = stringify(summaryNode);
        String technicalSummary = joinSections(
                "政策与赛道定位", policy,
                "基本面与估值", valuation,
                "资金面与技术形态", technical
        );
        String riskSummary = risks.isEmpty() ? "模型未返回独立风险数组，请结合原文复核。" : String.join("；", risks);
        String buySellSummary = joinSections(
                "投资评级", rating,
                "核心逻辑", summary
        );
        Integer score = parseScore(confidenceNode);
        AiAnalysisResultPayload.BuySellPointsPayload buySellPoints = new AiAnalysisResultPayload.BuySellPointsPayload(
                rating.isBlank() ? "继续观察" : rating,
                List.of(),
                List.of(),
                "",
                "",
                summary.isBlank() ? "模型使用旧结构输出，系统已兼容归档。" : summary
        );
        return new AiAnalysisResultPayload(
                normalizeDecisionPayload(new AiAnalysisResultPayload.DecisionPayload(
                        normalizeDecisionText(rating),
                        score == null ? null : score / 100.0,
                        "1-3个交易日",
                        directionFromDecision(normalizeDecisionText(rating)),
                        risks.isEmpty() ? "MEDIUM" : "HIGH",
                        summary.isBlank() ? "模型使用旧结构返回，系统已按兼容逻辑生成决策。" : summary,
                        List.of()
                ), buySellPoints, root.toString()),
                textTechnicalAnalysis(technicalSummary.isBlank() ? "模型返回旧结构，技术面摘要为空。" : technicalSummary),
                new AiAnalysisResultPayload.RiskWarningPayload(
                        risks.isEmpty() ? "模型返回旧结构，未拆出独立风险项。" : risks.get(0),
                        risks,
                        List.of(),
                        List.of(),
                        summary
                ),
                buySellPoints,
                textPromptSummary(summary.isBlank() ? technicalSummary : summary),
                score
        );
    }

    private JsonNode field(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private AiAnalysisResultPayload parseTextPayload(String text) {
        String technical = sectionText(text, "技术面分析", "technicalAnalysis", "Technical Analysis");
        String risk = sectionText(text, "风险提示", "riskWarning", "Risk Warning");
        String buySell = sectionText(text, "建议买卖点", "buySellPoints", "Buy Sell Points");
        String summary = sectionText(text, "Prompt 数据摘要", "数据摘要", "promptSummary", "Prompt Summary");
        if (technical.isBlank() && risk.isBlank() && buySell.isBlank() && summary.isBlank()) {
            throw new IllegalStateException("模型未返回 JSON 或可识别章节，无法生成完整报告。");
        }
        if (technical.isBlank()) {
            technical = "模型未单独给出技术面分析，已按原始返回内容归档。";
        }
        if (risk.isBlank()) {
            risk = "模型未单独给出风险提示，请结合仓位和市场波动复核。";
        }
        if (buySell.isBlank()) {
            buySell = "模型未单独给出买卖点建议，请重新生成或更换提示词。";
        }
        if (summary.isBlank()) {
            summary = "模型返回为分段文本，未提供独立数据摘要。";
        }
        return new AiAnalysisResultPayload(
                fallbackDecisionFromText(text, null),
                textTechnicalAnalysis(technical),
                textRiskWarning(risk),
                textBuySellPoints(buySell),
                textPromptSummary(summary),
                null
        );
    }

    private String joinSections(String title1, String content1, String title2, String content2, String title3, String content3) {
        List<String> sections = new ArrayList<>();
        appendSection(sections, title1, content1);
        appendSection(sections, title2, content2);
        appendSection(sections, title3, content3);
        return String.join("\n\n", sections);
    }

    private String joinSections(String title1, String content1, String title2, String content2) {
        List<String> sections = new ArrayList<>();
        appendSection(sections, title1, content1);
        appendSection(sections, title2, content2);
        return String.join("\n\n", sections);
    }

    private void appendSection(List<String> sections, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        sections.add(title + "：\n" + content.trim());
    }

    private String sectionText(String text, String... titles) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n");
        int start = -1;
        String matchedTitle = "";
        for (String title : titles) {
            int index = normalized.toLowerCase(Locale.ROOT).indexOf(title.toLowerCase(Locale.ROOT));
            if (index >= 0 && (start < 0 || index < start)) {
                start = index;
                matchedTitle = title;
            }
        }
        if (start < 0) {
            return "";
        }
        int contentStart = start + matchedTitle.length();
        while (contentStart < normalized.length() && " ：:：#*-.\n\t".indexOf(normalized.charAt(contentStart)) >= 0) {
            contentStart++;
        }
        int end = normalized.length();
        for (String title : List.of("技术面分析", "technicalAnalysis", "Technical Analysis", "风险提示", "riskWarning", "Risk Warning", "建议买卖点", "buySellPoints", "Buy Sell Points", "Prompt 数据摘要", "数据摘要", "promptSummary", "Prompt Summary")) {
            int index = normalized.toLowerCase(Locale.ROOT).indexOf(title.toLowerCase(Locale.ROOT), contentStart);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return normalized.substring(contentStart, end).trim();
    }

    private AiAnalysisResultPayload.TechnicalAnalysisPayload parseTechnicalAnalysis(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return textTechnicalAnalysis(node.asText());
        }
        return new AiAnalysisResultPayload.TechnicalAnalysisPayload(
                text(node, "trendAssessment", "trend_assessment", "趋势判断", "overall"),
                new AiAnalysisResultPayload.TrendPayload(
                        text(path(node, "trend"), "shortTerm", "short_term", "短线"),
                        text(path(node, "trend"), "mediumTerm", "medium_term", "中线")
                ),
                new AiAnalysisResultPayload.MovingAveragesPayload(
                        text(path(node, "movingAverages", "均线"), "currentPrice", "current_price", "当前价格"),
                        text(path(node, "movingAverages", "均线"), "ma5", "MA5"),
                        text(path(node, "movingAverages", "均线"), "ma10", "MA10"),
                        text(path(node, "movingAverages", "均线"), "ma20", "MA20"),
                        text(path(node, "movingAverages", "均线"), "ma30", "MA30"),
                        text(path(node, "movingAverages", "均线"), "ma60", "MA60"),
                        text(path(node, "movingAverages", "均线"), "bias", "偏离")
                ),
                new AiAnalysisResultPayload.KlinePatternPayload(
                        text(path(node, "klinePattern", "K线形态"), "patternName", "pattern_name", "形态"),
                        text(path(node, "klinePattern", "K线形态"), "description", "描述")
                ),
                new AiAnalysisResultPayload.SupportResistancePayload(
                        stringList(path(path(node, "supportResistance", "支撑压力"), "support", "支撑位")),
                        stringList(path(path(node, "supportResistance", "支撑压力"), "resistance", "压力位")),
                        text(path(node, "supportResistance", "支撑压力"), "nearestSupport", "nearest_support", "最近支撑"),
                        text(path(node, "supportResistance", "支撑压力"), "nearestResistance", "nearest_resistance", "最近压力")
                ),
                text(node, "volumeAnalysis", "volume_analysis", "量能表现"),
                text(node, "signal", "信号"),
                text(node, "description", "描述")
        );
    }

    private AiAnalysisResultPayload.RiskWarningPayload parseRiskWarning(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return textRiskWarning(node.asText());
        }
        return new AiAnalysisResultPayload.RiskWarningPayload(
                text(node, "headline", "核心风险", "summary"),
                stringList(path(node, "currentRisks", "current_risks", "当前风险")),
                stringList(path(node, "triggerConditions", "trigger_conditions", "触发条件")),
                stringList(path(node, "observationPoints", "observation_points", "观察点")),
                text(node, "overallAdvice", "overall_advice", "应对建议")
        );
    }

    private AiAnalysisResultPayload.BuySellPointsPayload parseBuySellPoints(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return textBuySellPoints(node.asText());
        }
        return new AiAnalysisResultPayload.BuySellPointsPayload(
                text(node, "action", "当前建议", "操作建议"),
                stringList(path(node, "buyTriggers", "buy_triggers", "买点")),
                stringList(path(node, "reduceTriggers", "reduce_triggers", "卖点", "减仓条件")),
                text(node, "stopLoss", "stop_loss", "止损"),
                text(node, "invalidationCondition", "invalidation_condition", "失效条件"),
                text(node, "positionSuggestion", "position_suggestion", "仓位建议")
        );
    }

    private AiAnalysisResultPayload.PromptSummaryPayload parsePromptSummary(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return textPromptSummary(node.asText());
        }
        return new AiAnalysisResultPayload.PromptSummaryPayload(
                text(node, "marketSnapshot", "market_snapshot", "行情摘要"),
                text(node, "valuationSnapshot", "valuation_snapshot", "估值摘要"),
                text(node, "growthSnapshot", "growth_snapshot", "成长摘要"),
                text(node, "klineSummary", "kline_summary", "K线摘要"),
                text(node, "volumeSummary", "volume_summary", "量能摘要")
        );
    }

    private Integer parseScore(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        String text = node.asText("");
        Matcher matcher = Pattern.compile("\\d+").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group()) : null;
    }

    private AiAnalysisResultPayload.DecisionPayload parseDecision(JsonNode root) {
        JsonNode node = field(root, "decision", "decisionSignal", "decision_signal", "决策", "交易决策");
        if (node == null || !node.isObject()) {
            node = root;
        }
        AiAnalysisResultPayload.DecisionPayload parsed = new AiAnalysisResultPayload.DecisionPayload(
                normalizeDecisionText(text(node, "decision", "action", "操作", "建议")),
                parseDouble(field(node, "confidence", "置信度")),
                text(node, "holdingPeriod", "holding_period", "持有周期"),
                normalizeDirection(text(node, "targetDirection", "target_direction", "direction", "方向")),
                normalizeRiskLevel(text(node, "riskLevel", "risk_level", "风险等级")),
                text(node, "summary", "决策摘要", "summaryText"),
                parseFactors(field(node, "factors", "因子", "factorList"))
        );
        return normalizeDecisionPayload(parsed, null, root == null ? "" : root.toString());
    }

    private List<AiAnalysisResultPayload.FactorPayload> parseFactors(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        List<AiAnalysisResultPayload.FactorPayload> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> {
                AiAnalysisResultPayload.FactorPayload factor = parseFactor(item);
                if (factor != null) {
                    values.add(factor);
                }
            });
        } else if (node.isObject()) {
            AiAnalysisResultPayload.FactorPayload factor = parseFactor(node);
            if (factor != null) {
                values.add(factor);
            }
        }
        return values;
    }

    private AiAnalysisResultPayload.FactorPayload parseFactor(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String name = text(node, "name", "factorName", "factor_name", "名称");
        String code = text(node, "code", "factorCode", "factor_code", "编码");
        if (code.isBlank() && name.isBlank()) {
            return null;
        }
        return new AiAnalysisResultPayload.FactorPayload(
                code.isBlank() ? normalizeFactorCode(name) : code,
                name.isBlank() ? code : name,
                normalizeFactorGroup(text(node, "group", "factorGroup", "factor_group", "类型")),
                parseBoolean(field(node, "hit", "命中")),
                parseDouble(field(node, "weight", "weightScore", "weight_score", "权重")),
                normalizeFactorDirection(text(node, "direction", "方向")),
                text(node, "reason", "理由", "原因")
        );
    }

    private AiAnalysisResultPayload.TechnicalAnalysisPayload textTechnicalAnalysis(String text) {
        return new AiAnalysisResultPayload.TechnicalAnalysisPayload(text, null, null, null, null, null, null, text);
    }

    private AiAnalysisResultPayload.RiskWarningPayload textRiskWarning(String text) {
        return new AiAnalysisResultPayload.RiskWarningPayload(text, List.of(text), List.of(), List.of(), "");
    }

    private AiAnalysisResultPayload.BuySellPointsPayload textBuySellPoints(String text) {
        return new AiAnalysisResultPayload.BuySellPointsPayload(text, List.of(), List.of(), "", "", "");
    }

    private AiAnalysisResultPayload.PromptSummaryPayload textPromptSummary(String text) {
        return new AiAnalysisResultPayload.PromptSummaryPayload(text, "", "", "", "");
    }

    private JsonNode path(JsonNode node, String... names) {
        JsonNode value = field(node, names);
        return value == null ? objectMapper.createObjectNode() : value;
    }

    private String text(JsonNode node, String... names) {
        JsonNode value = field(node, names);
        return value == null ? "" : stringify(value);
    }

    private String stringify(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                String value = stringify(item);
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
            return String.join("；", values);
        }
        return node.toString();
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                String value = stringify(item);
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
            return values;
        }
        String value = stringify(node);
        return value.isBlank() ? List.of() : List.of(value);
    }

    private String writeJson(Object value) throws JsonProcessingException {
        if (value == null) {
            return "";
        }
        return objectMapper.writeValueAsString(value);
    }

    private String writeRequiredJson(Object value) {
        try {
            return writeJson(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("条件交易策略无法序列化", exception);
        }
    }

    private String summarizeAdvice(AiAnalysisResultPayload.DecisionPayload decision, AiAnalysisResultPayload.BuySellPointsPayload buySellPoints, String fallback) {
        if (decision != null && decision.decision() != null && !decision.decision().isBlank()) {
            return switch (decision.decision()) {
                case "BUY" -> "建议买入";
                case "REDUCE" -> "建议减仓";
                case "SELL" -> "建议卖出";
                case "WATCH" -> "继续观察";
                case "HOLD" -> "稳健持有";
                default -> fallback;
            };
        }
        if (buySellPoints == null) {
            return fallback;
        }
        if (buySellPoints.action() != null && !buySellPoints.action().isBlank()) {
            return buySellPoints.action();
        }
        if (buySellPoints.positionSuggestion() != null && !buySellPoints.positionSuggestion().isBlank()) {
            return buySellPoints.positionSuggestion();
        }
        return fallback;
    }

    private AiAnalysisResultPayload.DecisionPayload normalizeDecisionPayload(
            AiAnalysisResultPayload.DecisionPayload decision,
            AiAnalysisResultPayload.BuySellPointsPayload buySellPoints,
            String rawText
    ) {
        if (decision == null) {
            return fallbackDecisionFromText(rawText, buySellPoints);
        }
        String normalizedDecision = normalizeDecisionText(decision.decision());
        if (normalizedDecision.isBlank()) {
            normalizedDecision = inferDecision(rawText, buySellPoints);
        }
        String direction = normalizeDirection(decision.targetDirection());
        if (direction.isBlank()) {
            direction = directionFromDecision(normalizedDecision);
        }
        String holdingPeriod = decision.holdingPeriod() == null || decision.holdingPeriod().isBlank()
                ? "1-3d"
                : decision.holdingPeriod();
        String riskLevel = normalizeRiskLevel(decision.riskLevel());
        String summary = decision.summary() == null || decision.summary().isBlank()
                ? "模型已输出结构化决策，按当前行情和策略权重归档。"
                : decision.summary();
        List<AiAnalysisResultPayload.FactorPayload> factors = decision.factors() == null || decision.factors().isEmpty()
                ? fallbackFactors(rawText)
                : decision.factors();
        return new AiAnalysisResultPayload.DecisionPayload(
                normalizedDecision,
                decision.confidence() == null ? 0.50 : Math.max(0.0, Math.min(1.0, decision.confidence())),
                holdingPeriod,
                direction,
                riskLevel.isBlank() ? "MEDIUM" : riskLevel,
                summary,
                factors
        );
    }

    private AiAnalysisResultPayload.DecisionPayload fallbackDecisionFromText(String rawText, AiAnalysisResultPayload.BuySellPointsPayload buySellPoints) {
        String decision = "WATCH";
        return new AiAnalysisResultPayload.DecisionPayload(
                decision,
                0.35,
                "1-3d",
                directionFromDecision(decision),
                "MEDIUM",
                "模型未完整输出 decision 字段，系统已降级为观察，不允许进入每日推荐关注。",
                fallbackFactors(rawText)
        );
    }

    private String inferDecision(String rawText, AiAnalysisResultPayload.BuySellPointsPayload buySellPoints) {
        String actionText = buySellPoints == null ? "" : normalizeText(buySellPoints.action(), buySellPoints.positionSuggestion());
        String text = normalizeText(actionText, rawText);
        if (containsAny(text, "卖出", "清仓")) {
            return "SELL";
        }
        if (containsAny(text, "减仓", "控制仓位", "降低仓位")) {
            return "REDUCE";
        }
        if (containsAny(text, "买入", "低吸", "加仓", "突破")) {
            return "BUY";
        }
        if (containsAny(text, "观察", "等待")) {
            return "WATCH";
        }
        return "HOLD";
    }

    private List<AiAnalysisResultPayload.FactorPayload> fallbackFactors(String rawText) {
        String text = normalizeText(rawText);
        List<AiAnalysisResultPayload.FactorPayload> factors = new ArrayList<>();
        if (containsAny(text, "突破", "放量", "均线", "支撑")) {
            factors.add(new AiAnalysisResultPayload.FactorPayload("TEXT_TECHNICAL_SIGNAL", "文本技术信号", "TECHNICAL", true, 0.35, "POSITIVE", "报告文本包含技术信号关键词"));
        }
        if (containsAny(text, "风险", "止损", "破位", "回撤", "高位")) {
            factors.add(new AiAnalysisResultPayload.FactorPayload("TEXT_RISK_SIGNAL", "文本风险信号", "RISK", true, -0.30, "NEGATIVE", "报告文本包含风险或失效条件"));
        }
        if (factors.isEmpty()) {
            factors.add(new AiAnalysisResultPayload.FactorPayload("GENERAL_AI_JUDGEMENT", "综合判断", "DECISION", true, 0.20, "NEUTRAL", "模型未输出明确因子，按综合判断归档"));
        }
        return factors;
    }

    private Double parseDouble(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        String value = node.asText("").replace("%", "").trim();
        if (value.isBlank()) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value);
            return parsed > 1 ? parsed / 100.0 : parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Boolean parseBoolean(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String value = node.asText("").trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) {
            return null;
        }
        return value.equals("true") || value.equals("yes") || value.equals("1") || value.equals("命中") || value.equals("是");
    }

    private String normalizeDecisionText(String value) {
        String text = normalizeText(value);
        if (text.contains("增持")) {
            return "BUY";
        }
        if (text.contains("减持")) {
            return "REDUCE";
        }
        if (text.contains("中性")) {
            return "WATCH";
        }
        if (text.contains("sell") || text.contains("卖出") || text.contains("清仓")) {
            return "SELL";
        }
        if (text.contains("reduce") || text.contains("减仓") || text.contains("降低仓位")) {
            return "REDUCE";
        }
        if (text.contains("buy") || text.contains("买入") || text.contains("加仓") || text.contains("低吸")) {
            return "BUY";
        }
        if (text.contains("watch") || text.contains("观察") || text.contains("等待")) {
            return "WATCH";
        }
        if (text.contains("hold") || text.contains("持有")) {
            return "HOLD";
        }
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private AiModelConfig normalizeAnalysisConfig(AiModelConfig source) {
        AiModelConfig normalized = new AiModelConfig();
        if (source != null) {
            normalized.id = source.id;
            normalized.userId = source.userId;
            normalized.apiBaseUrl = source.apiBaseUrl;
            normalized.modelName = source.modelName;
            normalized.apiKey = source.apiKey;
            normalized.timeoutMs = source.timeoutMs;
            normalized.temperature = source.temperature;
            normalized.maxTokens = source.maxTokens;
            normalized.intradayIntervalMinutes = source.intradayIntervalMinutes;
            normalized.closeAnalysisTime = source.closeAnalysisTime;
            normalized.analysisScope = source.analysisScope;
            normalized.promptTemplate = source.promptTemplate;
            normalized.deleted = source.deleted;
            normalized.createdAt = source.createdAt;
            normalized.updatedAt = source.updatedAt;
        }
        normalized.timeoutMs = normalized.timeoutMs == null
                ? MIN_ANALYSIS_TIMEOUT_MS
                : Math.max(normalized.timeoutMs, MIN_ANALYSIS_TIMEOUT_MS);
        normalized.maxTokens = normalized.maxTokens == null
                ? MIN_ANALYSIS_MAX_TOKENS
                : Math.max(normalized.maxTokens, MIN_ANALYSIS_MAX_TOKENS);
        return normalized;
    }

    private String sanitizeAnalysisTemplate(String template) {
        if (template == null || template.isBlank()) {
            return "";
        }
        String normalized = template.replace("\r\n", "\n").trim();
        int cut = normalized.length();
        for (String marker : List.of(
                "最终输出必须严格遵循以下JSON格式",
                "最终输出必须严格遵循以下 JSON 格式",
                "# Constraints & Output Format",
                "输出格式",
                "```json",
                "\"stock_code\"",
                "JSON格式"
        )) {
            int index = normalized.indexOf(marker);
            if (index >= 0) {
                cut = Math.min(cut, index);
            }
        }
        String cleaned = normalized.substring(0, cut).trim();
        if (cleaned.isBlank()) {
            cleaned = normalized;
        }
        if (cleaned.length() > MAX_TEMPLATE_CHARS) {
            cleaned = cleaned.substring(0, MAX_TEMPLATE_CHARS).trim();
        }
        return cleaned;
    }

    private String normalizeDirection(String value) {
        String text = normalizeText(value);
        if (text.contains("up") || text.contains("看涨") || text.contains("上行") || text.contains("上涨")) {
            return "UP";
        }
        if (text.contains("down") || text.contains("看跌") || text.contains("下行") || text.contains("下跌")) {
            return "DOWN";
        }
        if (text.contains("side") || text.contains("震荡") || text.contains("横盘")) {
            return "SIDEWAYS";
        }
        return "";
    }

    private String directionFromDecision(String decision) {
        return switch (decision == null ? "" : decision) {
            case "BUY" -> "UP";
            case "SELL", "REDUCE" -> "DOWN";
            default -> "SIDEWAYS";
        };
    }

    private String normalizeRiskLevel(String value) {
        String text = normalizeText(value);
        if (text.contains("high") || text.contains("高")) {
            return "HIGH";
        }
        if (text.contains("low") || text.contains("低")) {
            return "LOW";
        }
        if (text.contains("medium") || text.contains("中")) {
            return "MEDIUM";
        }
        return "";
    }

    private String normalizeFactorGroup(String value) {
        String text = normalizeText(value);
        if (text.contains("risk") || text.contains("风险")) {
            return "RISK";
        }
        if (text.contains("fundamental") || text.contains("基本面") || text.contains("财务")) {
            return "FUNDAMENTAL";
        }
        if (text.contains("decision") || text.contains("决策") || text.contains("买卖")) {
            return "DECISION";
        }
        return "TECHNICAL";
    }

    private String normalizeFactorDirection(String value) {
        String text = normalizeText(value);
        if (text.contains("negative") || text.contains("负") || text.contains("风险") || text.contains("抑制")) {
            return "NEGATIVE";
        }
        if (text.contains("neutral") || text.contains("中性")) {
            return "NEUTRAL";
        }
        return "POSITIVE";
    }

    private String normalizeFactorCode(String name) {
        String normalized = name == null ? "" : name.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9\\u4e00-\\u9fa5]+", "_");
        return normalized.isBlank() ? "AI_FACTOR" : normalized;
    }

    private String buildFallbackPromptSummary(StockDetailResponse detail) {
        return "实时价 " + detail.quote().price()
                + "，涨跌幅 " + detail.quote().percent() + "%"
                + "，量比 " + detail.quote().volumeRatio()
                + "，PE " + detail.finance().pe()
                + "，PB " + detail.finance().pb()
                + "，营收同比 " + detail.finance().revenueGrowth() + "%"
                + "，净利同比 " + detail.finance().profitGrowth() + "%";
    }

    private static String normalizeText(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value != null) {
                builder.append(value).append(' ');
            }
        }
        return builder.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... words) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "未知来源" : value.trim();
    }

    private static String preview(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    private record AnalysisAttemptResult(String rawResponse, AiAnalysisResultPayload payload) {
    }

    private record FormalAnalysisContext(
            AiSample sample,
            AiStrategyRelease release,
            Map<Integer, AiPrediction> predictionsByHorizon,
            List<AiPrediction> linkedPredictions,
            FormalDecision decision,
            String promptContext
    ) {
        private AiLearningPayloads.AnalysisLearningContext conditionalContext() {
            AiPrediction primary = predictionsByHorizon.get(3);
            return new AiLearningPayloads.AnalysisLearningContext(
                    sample.id,
                    primary == null ? null : primary.id,
                    release.id,
                    sample.dataQualityScore == null ? BigDecimal.ZERO : sample.dataQualityScore,
                    decision.calibratedConfidence() == null ? BigDecimal.ZERO : decision.calibratedConfidence(),
                    sample.marketRegime == null ? "UNKNOWN" : sample.marketRegime,
                    promptContext);
        }
    }

    static record FormalDecision(
            BigDecimal systemScore,
            String finalAction,
            String targetDirection,
            BigDecimal riskScore,
            String riskLevel,
            BigDecimal calibratedConfidence,
            String unavailableReason
    ) {
        private static FormalDecision unavailable(String reason) {
            return new FormalDecision(null, "WATCH", "SIDEWAYS", null, "UNKNOWN", BigDecimal.ZERO, reason);
        }
    }

    private record AnalysisFreshness(
            String quoteSource,
            LocalDateTime quoteFetchedAt,
            LocalDate latestKlineDate,
            String lastIntradayTime
    ) {
    }

    private static final class AnalysisAttemptException extends RuntimeException {
        private final String rawResponse;

        private AnalysisAttemptException(String message, String rawResponse, Throwable cause) {
            super(message, cause);
            this.rawResponse = rawResponse;
        }

        private String rawResponse() {
            return rawResponse;
        }
    }
}
