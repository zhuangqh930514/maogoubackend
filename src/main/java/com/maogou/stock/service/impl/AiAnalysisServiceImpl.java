package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiModelConfig;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.dto.ai.AiAnalysisReportResponse;
import com.maogou.stock.dto.ai.AiAnalysisResultPayload;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.infrastructure.ai.LocalAiClient;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiAnalysisService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.ModelConfigService;
import com.maogou.stock.service.PromptTemplateService;
import com.maogou.stock.service.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiAnalysisServiceImpl implements AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisServiceImpl.class);
    private static final int MAX_ANALYSIS_ATTEMPTS = 3;
    private static final Pattern THINK_BLOCK = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern FENCED_BLOCK = Pattern.compile("(?is)```(?:json)?\\s*([\\s\\S]*?)\\s*```");

    private final AiAnalysisReportMapper reportMapper;
    private final MarketDataService marketDataService;
    private final WatchlistService watchlistService;
    private final ModelConfigService modelConfigService;
    private final PromptTemplateService promptTemplateService;
    private final LocalAiClient localAiClient;
    private final ObjectMapper objectMapper;

    public AiAnalysisServiceImpl(
            AiAnalysisReportMapper reportMapper,
            MarketDataService marketDataService,
            WatchlistService watchlistService,
            ModelConfigService modelConfigService,
            PromptTemplateService promptTemplateService,
            LocalAiClient localAiClient,
            ObjectMapper objectMapper
    ) {
        this.reportMapper = reportMapper;
        this.marketDataService = marketDataService;
        this.watchlistService = watchlistService;
        this.modelConfigService = modelConfigService;
        this.promptTemplateService = promptTemplateService;
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
    @Transactional
    public AiAnalysisReportResponse analyzeStock(String code, boolean forceRefresh, Long promptTemplateId, Long targetReportId) {
        Long normalizedPromptTemplateId = normalizePromptTemplateId(promptTemplateId);
        StockDetailResponse detail = marketDataService.stockDetail(code);
        AiModelConfig config = modelConfigService.currentEntity();
        String selectedTemplate = promptTemplateService.resolveContent(promptTemplateId, null);
        String prompt = buildPrompt(detail, config, selectedTemplate);
        LocalDateTime now = LocalDateTime.now();
        LocalDate reportDate = now.toLocalDate();
        AiAnalysisReport report = resolveTargetReport(targetReportId, detail.quote().code(), config.modelName, reportDate, now);
        report.userId = AuthContext.currentUserIdOrDefault();
        report.stockCode = detail.quote().code();
        report.stockName = detail.quote().name();
        report.rawPrompt = prompt;
        report.sourceModel = config.modelName;
        report.promptTemplateId = normalizedPromptTemplateId;
        report.reportDate = reportDate;
        report.generatedAt = now;
        report.updatedAt = now;
        report.deleted = 0;
        report.status = AnalysisStatus.PENDING;
        report.score = detail.aiScore();
        report.advice = detail.aiAdvice();
        report.promptSummary = buildFallbackPromptSummary(detail);
        if (report.id == null) {
            report.createdAt = now;
        }

        try {
            log.info("start AI stock analysis userId={} code={} stock={} model={} promptTemplateId={} targetReportId={}",
                    report.userId, report.stockCode, report.stockName, config.modelName, normalizedPromptTemplateId, targetReportId);
            AnalysisAttemptResult attemptResult = executeAnalysisWithRetry(prompt, config);
            report.rawResponse = attemptResult.rawResponse();
            applyPayload(report, attemptResult.payload());
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
        report.advice = summarizeAdvice(payload.buySellPoints(), report.advice);
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

    private String buildPrompt(StockDetailResponse detail, AiModelConfig config, String selectedTemplate) {
        String configuredTemplate = config.promptTemplate == null || config.promptTemplate.isBlank()
                ? "请基于行情、K线、财务数据输出技术面分析、风险提示、建议买卖点、Prompt 数据摘要和评分。"
                : config.promptTemplate;
        String template = selectedTemplate == null || selectedTemplate.isBlank() ? configuredTemplate : selectedTemplate;
        return """
                %s

                股票：%s %s
                实时行情：价格=%s，涨跌额=%s，涨跌幅=%s%%，量比=%s，市场=%s
                财务摘要：PE=%s，PB=%s，营收=%s，营收同比=%s%%，净利润=%s，净利同比=%s%%，ROE=%s%%，毛利率=%s%%，资产负债率=%s%%
                近K线样本：%s
                输出要求：
                所选提示词只影响分析口径和重点；无论提示词如何变化，最终必须严格映射到以下 JSON 报告结构，方便系统解析和前端展示。
                1. 只返回 JSON 对象，不要 markdown 代码块，不要额外解释。
                2. technicalAnalysis 必须包含 trendAssessment、trend、movingAverages、klinePattern、supportResistance、volumeAnalysis，可直接给客户阅读。
                3. riskWarning 必须包含 headline、currentRisks、triggerConditions、observationPoints、overallAdvice，且必须结合当前股票数据写具体风险。
                4. buySellPoints 必须包含 action、buyTriggers、reduceTriggers、stopLoss、invalidationCondition、positionSuggestion，不能写泛泛而谈的话。
                5. promptSummary 必须包含 marketSnapshot、valuationSnapshot、growthSnapshot、klineSummary、volumeSummary，信息尽量完整。
                6. score 输出 0-100 整数，避免保证收益。
                """.formatted(
                template,
                detail.quote().name(),
                detail.quote().code(),
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
                detail.kline().stream().limit(12).toList()
        );
    }

    private AiAnalysisResultPayload parseAiPayload(String aiText) throws Exception {
        String normalized = normalizeAiText(aiText);
        String jsonCandidate = extractFirstJsonObject(normalized);
        if (jsonCandidate == null) {
            return parseTextPayload(normalized);
        }
        JsonNode root = unwrapPayloadRoot(objectMapper.readTree(jsonCandidate));
        if (!hasAnyReportField(root)) {
            return parseTextPayload(normalized);
        }
        return new AiAnalysisResultPayload(
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
                textTechnicalAnalysis(technical),
                textRiskWarning(risk),
                textBuySellPoints(buySell),
                textPromptSummary(summary),
                null
        );
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

    private String summarizeAdvice(AiAnalysisResultPayload.BuySellPointsPayload buySellPoints, String fallback) {
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

    private String buildFallbackPromptSummary(StockDetailResponse detail) {
        return "实时价 " + detail.quote().price()
                + "，涨跌幅 " + detail.quote().percent() + "%"
                + "，量比 " + detail.quote().volumeRatio()
                + "，PE " + detail.finance().pe()
                + "，PB " + detail.finance().pb()
                + "，营收同比 " + detail.finance().revenueGrowth() + "%"
                + "，净利同比 " + detail.finance().profitGrowth() + "%";
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
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
