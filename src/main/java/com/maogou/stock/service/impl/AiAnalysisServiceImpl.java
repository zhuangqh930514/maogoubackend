package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiAnalysisDecision;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.entity.AiStrategyVersion;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.ai.AiAnalysisReportPageResponse;
import com.maogou.stock.dto.ai.AiAnalysisResultPayload;
import com.maogou.stock.dto.ai.AiLearningPayloads;
import com.maogou.stock.dto.market.IntradayPointResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.NewsFlashResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.infrastructure.ai.LocalAiClient;
import com.maogou.stock.mapper.AiAnalysisDecisionMapper;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.AiStrategyVersionMapper;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.AiLearningService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.ModelConfigService;
import com.maogou.stock.service.PromptTemplateService;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

    private final AiAnalysisReportMapper reportMapper;
    private final AiAnalysisDecisionMapper decisionMapper;
    private final AiStrategyVersionMapper strategyVersionMapper;
    private final WatchStockMapper watchStockMapper;
    private final MarketDataService marketDataService;
    private final WatchlistService watchlistService;
    private final AiLearningService aiLearningService;
    private final ModelConfigService modelConfigService;
    private final PromptTemplateService promptTemplateService;
    private final TradingCalendarService tradingCalendarService;
    private final LocalAiClient localAiClient;
    private final ObjectMapper objectMapper;

    public AiAnalysisServiceImpl(
            AiAnalysisReportMapper reportMapper,
            AiAnalysisDecisionMapper decisionMapper,
            AiStrategyVersionMapper strategyVersionMapper,
            WatchStockMapper watchStockMapper,
            MarketDataService marketDataService,
            WatchlistService watchlistService,
            AiLearningService aiLearningService,
            ModelConfigService modelConfigService,
            PromptTemplateService promptTemplateService,
            TradingCalendarService tradingCalendarService,
            LocalAiClient localAiClient,
            ObjectMapper objectMapper
    ) {
        this.reportMapper = reportMapper;
        this.decisionMapper = decisionMapper;
        this.strategyVersionMapper = strategyVersionMapper;
        this.watchStockMapper = watchStockMapper;
        this.marketDataService = marketDataService;
        this.watchlistService = watchlistService;
        this.aiLearningService = aiLearningService;
        this.modelConfigService = modelConfigService;
        this.promptTemplateService = promptTemplateService;
        this.tradingCalendarService = tradingCalendarService;
        this.localAiClient = localAiClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AiAnalysisReportResponse> listReports(String code) {
        QueryWrapper<AiAnalysisReport> wrapper = new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .orderByDesc("generated_at");
        if (code != null && !code.isBlank()) {
            wrapper.eq("stock_code", code);
        }
        return reportMapper.selectList(wrapper).stream().map(AiAnalysisReportResponse::from).toList();
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
        List<AiAnalysisReportResponse> items = reportMapper.selectList(query).stream()
                .map(AiAnalysisReportResponse::from)
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
            case "HIGH_RISK" -> query.lt("score", 60);
            case "BUY" -> query.and(nested -> nested
                    .like("advice", "买入")
                    .or().like("advice", "突破")
                    .or().like("advice", "持有"));
            case "REDUCE" -> query.and(nested -> nested
                    .like("advice", "减仓")
                    .or().like("advice", "控制")
                    .or().like("advice", "风险"));
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

    @Override
    @Transactional
    public void removeReports(List<Long> ids) {
        List<Long> normalizedIds = ids == null ? List.of() : ids.stream()
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return;
        }
        reportMapper.delete(new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", AuthContext.currentUserIdOrDefault())
                .in("id", normalizedIds));
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
        LocalDateTime marketDataAsOf = tradeDate.atTime(16, 0);
        StockDetailResponse detail = pointInTime
                ? marketDataService.stockDetailAt(code, marketDataAsOf)
                : marketDataService.stockDetailForAnalysis(code);
        AnalysisFreshness freshness = validateAnalysisFreshness(detail);
        AiLearningPayloads.AnalysisLearningContext learningContext = pointInTime && !tradeDate.equals(LocalDate.now())
                ? AiLearningPayloads.AnalysisLearningContext.empty()
                : aiLearningService.prepareAnalysisContext(detail, normalizedPromptTemplateId);
        List<NewsFlashResponse> realtimeNews = pointInTime
                ? marketDataService.latestNewsForAnalysisAt(8, marketDataAsOf)
                : marketDataService.latestNewsForAnalysis(8);
        AiModelConfig config = normalizeAnalysisConfig(modelConfigService.currentEntity());
        String selectedTemplate = promptTemplateService.resolveContent(promptTemplateId, null);
        String prompt = buildPrompt(detail, config, selectedTemplate, activeStrategyPrompt(userId), learningContext.promptContext(), freshness, realtimeNews);
        LocalDateTime now = LocalDateTime.now();
        LocalDate reportDate = tradeDate;
        AiAnalysisReport report = resolveTargetReport(targetReportId, detail.quote().code(), config.modelName, reportDate, now);
        report.userId = userId;
        report.stockCode = detail.quote().code();
        report.stockName = resolveStockName(userId, report.stockCode, detail.quote().name());
        report.rawPrompt = prompt;
        report.sourceModel = config.modelName;
        report.promptTemplateId = normalizedPromptTemplateId;
        report.reportDate = reportDate;
        report.generatedAt = now;
        report.sampleId = learningContext.sampleId();
        report.predictionId = learningContext.predictionId();
        report.strategyVersionId = learningContext.strategyVersionId();
        report.dataQualityScore = learningContext.dataQualityScore();
        report.calibratedConfidence = learningContext.calibratedConfidence();
        report.updatedAt = now;
        report.deleted = 0;
        report.status = AnalysisStatus.PENDING;
        report.score = detail.aiScore();
        report.advice = detail.aiAdvice();
        report.promptSummary = buildFallbackPromptSummary(detail);
        if (report.id == null) {
            report.createdAt = now;
        }

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
            log.info("AI stock analysis success userId={} code={} reportId={} reportScore={} responseChars={}",
                    report.userId, report.stockCode, report.id, report.score, safeLength(report.rawResponse));
        } catch (Exception ex) {
            report.technicalAnalysis = "AI 分析调用失败，已保存失败报告供排查。";
            report.riskWarning = buildRiskWarningFailureMessage(ex);
            report.buySellPoints = "本次报告未获得有效买卖点建议，请重新生成。";
            report.promptSummary = buildFallbackPromptSummary(detail);
            report.rawResponse = ex instanceof AnalysisAttemptException attemptException ? attemptException.rawResponse() : report.rawResponse;
            report.errorMessage = ex.getMessage();
            report.status = AnalysisStatus.FAILED;
            log.error("AI stock analysis failed userId={} code={} model={} promptTemplateId={} targetReportId={} message={} rawResponsePreview={}",
                    report.userId, report.stockCode, config.modelName, normalizedPromptTemplateId, targetReportId, ex.getMessage(), preview(report.rawResponse), ex);
        }
        saveOrUpdateReport(report);
        aiLearningService.linkReport(learningContext.predictionId(), report.id);
        if (report.status == AnalysisStatus.SUCCESS && parsedPayload != null) {
            saveDecision(report, parsedPayload);
        }
        return AiAnalysisReportResponse.from(report);
    }

    @Override
    public void analyzeWatchlist(Long promptTemplateId) {
        for (WatchStockResponse stock : watchlistService.list("全部")) {
            analyzeStock(stock.code(), false, promptTemplateId, null);
        }
    }

    private Long normalizePromptTemplateId(Long promptTemplateId) {
        return promptTemplateId == null ? 0L : promptTemplateId;
    }

    private AiAnalysisReport resolveTargetReport(
            Long targetReportId,
            String stockCode,
            String sourceModel,
            LocalDate reportDate,
            LocalDateTime now
    ) {
        Long userId = AuthContext.currentUserIdOrDefault();
        if (targetReportId != null && targetReportId > 0) {
            AiAnalysisReport existing = reportMapper.selectOne(new QueryWrapper<AiAnalysisReport>()
                    .eq("id", targetReportId)
                    .eq("user_id", userId)
                    .last("LIMIT 1"));
            if (existing != null) {
                existing.updatedAt = now;
                return existing;
            }
        }
        AiAnalysisReport existing = reportMapper.selectOne(new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", userId)
                .eq("stock_code", stockCode)
                .eq("report_date", reportDate)
                .eq("source_model", sourceModel)
                .eq("deleted", 0)
                .orderByDesc("generated_at")
                .last("LIMIT 1"));
        if (existing != null) {
            existing.updatedAt = now;
            return existing;
        }
        return new AiAnalysisReport();
    }

    private void saveOrUpdateReport(AiAnalysisReport report) {
        try {
            if (report.id == null) {
                reportMapper.insert(report);
            } else {
                reportMapper.updateById(report);
            }
        } catch (DuplicateKeyException ex) {
            AiAnalysisReport existing = reportMapper.selectOne(new QueryWrapper<AiAnalysisReport>()
                    .eq("user_id", report.userId)
                    .eq("stock_code", report.stockCode)
                    .eq("report_date", report.reportDate)
                    .eq("source_model", report.sourceModel)
                    .eq("deleted", 0)
                    .orderByDesc("generated_at")
                    .last("LIMIT 1"));
            if (existing == null) {
                throw ex;
            }
            report.id = existing.id;
            report.createdAt = existing.createdAt;
            report.updatedAt = LocalDateTime.now();
            reportMapper.updateById(report);
        }
    }

    private void saveDecision(AiAnalysisReport report, AiAnalysisResultPayload payload) {
        AiAnalysisResultPayload.DecisionPayload decision = payload.decision() == null
                ? fallbackDecisionFromText(report.rawResponse, payload.buySellPoints())
                : normalizeDecisionPayload(payload.decision(), payload.buySellPoints(), report.rawResponse);
        LocalDateTime now = LocalDateTime.now();
        AiAnalysisDecision entity = decisionMapper.selectOne(new QueryWrapper<AiAnalysisDecision>()
                .eq("user_id", report.userId)
                .eq("report_id", report.id)
                .last("LIMIT 1"));
        if (entity == null) {
            entity = new AiAnalysisDecision();
            entity.userId = report.userId;
            entity.reportId = report.id;
            entity.createdAt = now;
        }
        entity.stockCode = report.stockCode;
        entity.stockName = report.stockName;
        entity.decision = decision.decision();
        entity.confidence = decision.confidence() == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(decision.confidence()).setScale(4, RoundingMode.HALF_UP);
        entity.holdingPeriod = decision.holdingPeriod();
        entity.targetDirection = decision.targetDirection();
        entity.riskLevel = decision.riskLevel();
        entity.summary = decision.summary();
        try {
            entity.factorsJson = writeJson(decision.factors() == null ? List.of() : decision.factors());
            entity.rawDecisionJson = writeJson(decision);
        } catch (JsonProcessingException ex) {
            entity.factorsJson = "[]";
            entity.rawDecisionJson = "{}";
        }
        entity.updatedAt = now;
        if (entity.id == null) {
            decisionMapper.insert(entity);
        } else {
            decisionMapper.updateById(entity);
        }
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
                .eq("deleted", 0)
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
                String aiText = localAiClient.chat(prompt, config);
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

    private void applyPayload(AiAnalysisReport report, AiAnalysisResultPayload payload) throws JsonProcessingException {
        report.technicalAnalysis = writeJson(payload.technicalAnalysis());
        report.riskWarning = writeJson(payload.riskWarning());
        report.buySellPoints = writeJson(payload.buySellPoints());
        report.promptSummary = writeJson(payload.promptSummary());
        if (payload.score() != null) {
            report.score = payload.score();
        }
        report.advice = summarizeAdvice(payload.decision(), payload.buySellPoints(), report.advice);
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

    private String activeStrategyPrompt(Long userId) {
        try {
            AiStrategyVersion active = strategyVersionMapper.selectOne(new QueryWrapper<AiStrategyVersion>()
                    .eq("user_id", userId)
                    .eq("active", 1)
                    .orderByDesc("created_at")
                    .last("LIMIT 1"));
            if (active == null || active.promptTemplate == null || active.promptTemplate.isBlank()) {
                return "";
            }
            return """
                    策略版本：%s / %s
                    策略摘要：%s
                    因子快照：
                    %s
                    Prompt 调权要求：
                    %s
                    """.formatted(
                    active.versionNo,
                    active.title,
                    active.strategySummary == null ? "" : active.strategySummary,
                    active.factorSnapshot == null ? "" : active.factorSnapshot,
                    active.promptTemplate
            );
        } catch (RuntimeException ex) {
            log.warn("active AI strategy unavailable, fallback to base prompt. userId={} message={}", userId, ex.getMessage());
            return "";
        }
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
        LocalDate expectedKlineDate = tradingCalendarService.latestExpectedKlineDate(now);
        if (latestKlineDate.isBefore(expectedKlineDate)) {
            throw new IllegalStateException("最近 K 线日期为 " + latestKlineDate + "，低于当前应有交易日 " + expectedKlineDate + "，已停止 AI 分析。");
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

                标准化学习上下文：
                %s

                股票：%s %s
                当前系统时间：%s
                实时性校验：行情来源=%s，行情抓取时间=%s，最近日K日期=%s，分时最后时间=%s，最新资讯条数=%s
                实时行情：价格=%s，涨跌额=%s，涨跌幅=%s%%，量比=%s，市场=%s
                财务摘要：PE=%s，PB=%s，营收=%s，营收同比=%s%%，净利润=%s，净利同比=%s%%，ROE=%s%%，毛利率=%s%%，资产负债率=%s%%
                近K线样本：%s
                最新资讯，仅可使用下列 36 小时内资讯；如果为空，必须明确写“本次未获取到最新资讯”，禁止引用模型记忆里的旧新闻、旧公告或旧事件：
                %s
                输出要求：
                0. 实时性优先级最高。只能基于本 Prompt 中的实时行情、最近 K 线、财务摘要、标准化学习上下文和最新资讯做判断；禁止使用模型训练记忆中的旧价格、旧资讯、旧公告、旧月份材料或无法从当前数据验证的事件。
                所选提示词和策略版本只影响分析口径和重点；如果上方内容中出现任何旧 JSON 示例、字段名、markdown 模板、章节标题或输出格式要求，全部忽略。
                最终只能遵循下面的系统结构输出，方便系统解析和前端展示。
                1. 只返回 JSON 对象，不要 markdown 代码块，不要额外解释。
                2. decision 必须包含 decision、confidence、holdingPeriod、targetDirection、riskLevel、summary、factors。
                   - decision 只能是 BUY、HOLD、REDUCE、SELL、WATCH 之一。
                   - targetDirection 只能是 UP、DOWN、SIDEWAYS 之一，必须代表对未来 1-3 个交易日的主要方向判断。
                   - factors 是数组，最多 3 项；每项必须包含 code、name、group、hit、weight、direction、reason。
                3. technicalAnalysis 必须包含 trendAssessment、trend、movingAverages、klinePattern、supportResistance、volumeAnalysis，可直接给客户阅读。
                4. riskWarning 必须包含 headline、currentRisks、triggerConditions、observationPoints、overallAdvice，且必须结合当前股票数据写具体风险；各数组最多 3 条，每条尽量短句。
                5. buySellPoints 必须包含 action、buyTriggers、reduceTriggers、stopLoss、invalidationCondition、positionSuggestion，不能写泛泛而谈的话；buyTriggers 和 reduceTriggers 最多各 3 条。
                6. promptSummary 必须包含 marketSnapshot、valuationSnapshot、growthSnapshot、klineSummary、volumeSummary，信息尽量完整但每项保持精炼。
                7. score 输出 0-100 整数，避免保证收益。
                """.formatted(
                template,
                strategyInstruction,
                learningContext == null || learningContext.isBlank() ? "本次未获得学习系统上下文，请按基础结构保守输出。" : learningContext,
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
