package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiTradePlanReview;
import com.maogou.stock.domain.entity.AiTradeRuleConfig;
import com.maogou.stock.domain.entity.AiTradeRulePerformance;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.research.AiAnalysisReportPrediction;
import com.maogou.stock.domain.entity.research.AiFactorPerformance;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.domain.enums.TradeSide;
import com.maogou.stock.dto.ai.AiConditionalStrategyPayload;
import com.maogou.stock.dto.ai.AiLearningPayloads;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.AiTradePlanReviewMapper;
import com.maogou.stock.mapper.AiTradeRuleConfigMapper;
import com.maogou.stock.mapper.AiTradeRulePerformanceMapper;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.research.AiAnalysisReportPredictionMapper;
import com.maogou.stock.mapper.research.AiFactorPerformanceMapper;
import com.maogou.stock.mapper.research.AiFactorValueMapper;
import com.maogou.stock.mapper.research.AiPredictionEvaluationMapper;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.service.AiConditionalTradeStrategyService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiResearchContract;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AiConditionalTradeStrategyServiceImpl implements AiConditionalTradeStrategyService {

    private static final String DEFAULT_RULE_RESOURCE = "ai/conditional-trade-rules-v1.json";
    private static final int REVIEW_REPORT_LIMIT = 180;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final AiTradeRuleConfigMapper ruleConfigMapper;
    private final AiTradePlanReviewMapper reviewMapper;
    private final AiTradeRulePerformanceMapper rulePerformanceMapper;
    private final AiAnalysisReportMapper reportMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final AiSampleMapper sampleMapper;
    private final AiFactorValueMapper factorValueMapper;
    private final AiFactorPerformanceMapper factorPerformanceMapper;
    private final AiStrategyReleaseMapper strategyReleaseMapper;
    private final AiAnalysisReportPredictionMapper reportPredictionMapper;
    private final AiSampleLabelMapper sampleLabelMapper;
    private final AiPredictionEvaluationMapper predictionEvaluationMapper;
    private final AiPredictionMapper predictionMapper;
    private final MarketDataService marketDataService;
    private final TradingCalendarService tradingCalendarService;
    private final ConditionalTradeRuleEngine ruleEngine;
    private final ObjectMapper objectMapper;

    public AiConditionalTradeStrategyServiceImpl(
            AiTradeRuleConfigMapper ruleConfigMapper,
            AiTradePlanReviewMapper reviewMapper,
            AiTradeRulePerformanceMapper rulePerformanceMapper,
            AiAnalysisReportMapper reportMapper,
            TradeRecordMapper tradeRecordMapper,
            AiSampleMapper sampleMapper,
            AiFactorValueMapper factorValueMapper,
            AiFactorPerformanceMapper factorPerformanceMapper,
            AiStrategyReleaseMapper strategyReleaseMapper,
            AiAnalysisReportPredictionMapper reportPredictionMapper,
            AiSampleLabelMapper sampleLabelMapper,
            AiPredictionEvaluationMapper predictionEvaluationMapper,
            AiPredictionMapper predictionMapper,
            MarketDataService marketDataService,
            TradingCalendarService tradingCalendarService,
            ConditionalTradeRuleEngine ruleEngine,
            ObjectMapper objectMapper
    ) {
        this.ruleConfigMapper = ruleConfigMapper;
        this.reviewMapper = reviewMapper;
        this.rulePerformanceMapper = rulePerformanceMapper;
        this.reportMapper = reportMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.sampleMapper = sampleMapper;
        this.factorValueMapper = factorValueMapper;
        this.factorPerformanceMapper = factorPerformanceMapper;
        this.strategyReleaseMapper = strategyReleaseMapper;
        this.reportPredictionMapper = reportPredictionMapper;
        this.sampleLabelMapper = sampleLabelMapper;
        this.predictionEvaluationMapper = predictionEvaluationMapper;
        this.predictionMapper = predictionMapper;
        this.marketDataService = marketDataService;
        this.tradingCalendarService = tradingCalendarService;
        this.ruleEngine = ruleEngine;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiConditionalStrategyPayload build(
            Long userId,
            StockDetailResponse detail,
            LocalDate tradeDate,
            AiLearningPayloads.AnalysisLearningContext learningContext
    ) {
        ResearchSnapshot research = researchSnapshot(userId, detail.quote().code(), tradeDate, learningContext);
        ResolvedConfiguration resolved = resolveConfiguration(userId, research.release);
        AiConditionalStrategyPayload.PositionContext position = position(userId, detail.quote().code(), detail.quote().price());
        return ruleEngine.evaluate(new ConditionalTradeRuleEngine.EngineInput(
                tradeDate,
                detail.quote().fetchedAt() == null ? LocalDateTime.now() : detail.quote().fetchedAt(),
                detail,
                position,
                research.market,
                lineage(research, resolved, learningContext),
                resolved.configuration,
                research.factorEvidence,
                research.ruleWeights,
                research.limitations
        ));
    }

    @Override
    @Transactional
    public void initializeReviews(AiAnalysisReport report, AiConditionalStrategyPayload payload) {
        if (report == null || report.id == null || report.status != AnalysisStatus.SUCCESS
                || payload == null || payload.tradingPlans() == null) {
            return;
        }
        Long ruleConfigId = payload.lineage() == null ? null : payload.lineage().tradeRuleConfigId();
        if (ruleConfigId == null) {
            throw new IllegalStateException("条件策略快照缺少正式规则配置血缘");
        }
        Map<Integer, AiPrediction> predictions = reportPredictions(report.userId, report.id);
        LocalDateTime now = LocalDateTime.now();
        for (AiConditionalStrategyPayload.HorizonPlan plan : payload.tradingPlans()) {
            if (plan == null || plan.horizonDays() == null) {
                continue;
            }
            AiPrediction prediction = predictions.get(plan.horizonDays());
            if (prediction == null) {
                continue;
            }
            AiTradePlanReview review = reviewMapper.selectOne(new QueryWrapper<AiTradePlanReview>()
                    .eq("user_id", report.userId)
                    .eq("report_id", report.id)
                    .eq("horizon_trading_days", plan.horizonDays())
                    .last("LIMIT 1"));
            if (review != null && !"PENDING".equals(review.status)) {
                continue;
            }
            if (review == null) {
                review = new AiTradePlanReview();
                review.userId = report.userId;
                review.reportId = report.id;
                review.stockCode = report.stockCode;
                review.reportDate = report.reportDate;
                review.horizonDays = plan.horizonDays();
                review.status = "PENDING";
                review.createdAt = now;
            }
            review.tradeRuleConfigId = ruleConfigId;
            attachReviewLineage(review, prediction);
            review.targetTradeDate = tradingDateOffset(report.reportDate, plan.horizonDays());
            review.outcomeTradeDate = tradingDateOffset(report.reportDate, plan.horizonDays() + 1);
            review.ruleType = "HORIZON_PLAN";
            review.marketRegime = payload.market() == null ? "UNKNOWN" : normalize(payload.market().marketRegime(), "UNKNOWN");
            review.updatedAt = now;
            if (review.id == null) {
                reviewMapper.insert(review);
            } else {
                reviewMapper.updateById(review);
            }
        }
    }

    private Map<Integer, AiPrediction> reportPredictions(Long userId, Long reportId) {
        List<Long> predictionIds = reportPredictionMapper.selectByReport(userId, reportId).stream()
                .map(item -> item.predictionId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (predictionIds.isEmpty()) {
            return Map.of();
        }
        return predictionMapper.selectBatchIds(predictionIds).stream()
                .filter(item -> item.horizonDays != null)
                .collect(Collectors.toMap(
                        item -> item.horizonDays,
                        item -> item,
                        AiConditionalTradeStrategyServiceImpl::latestPrediction,
                        LinkedHashMap::new));
    }

    private void attachReviewLineage(AiTradePlanReview review, AiPrediction prediction) {
        review.predictionId = prediction.id;
        AiSampleLabel label = sampleLabelMapper.selectForReview(prediction.sampleId, prediction.horizonDays);
        if (label != null && (!Objects.equals(label.sampleId, prediction.sampleId)
                || !Objects.equals(label.horizonTradingDays, prediction.horizonDays))) {
            throw new IllegalStateException("条件计划标签与预测周期不一致");
        }
        review.sampleLabelId = label == null ? null : label.id;
        AiPredictionEvaluation evaluation = label == null ? null
                : predictionEvaluationMapper.selectForReview(prediction.id, label.id);
        if (evaluation != null
                && ((evaluation.predictionId != null && !Objects.equals(evaluation.predictionId, prediction.id))
                || (evaluation.sampleLabelId != null && !Objects.equals(evaluation.sampleLabelId, label.id)))) {
            throw new IllegalStateException("条件计划预测评价血缘不一致");
        }
        review.predictionEvaluationId = evaluation == null ? null : evaluation.id;
    }

    @Override
    public Map<Long, List<AiConditionalStrategyPayload.ReviewResult>> reviewsByReportIds(Long userId, List<Long> reportIds) {
        List<Long> ids = reportIds == null ? List.of() : reportIds.stream()
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return reviewMapper.selectList(new QueryWrapper<AiTradePlanReview>()
                        .eq("user_id", userId)
                        .in("report_id", ids)
                        .orderByAsc("horizon_trading_days"))
                .stream()
                .collect(Collectors.groupingBy(item -> item.reportId, LinkedHashMap::new,
                        Collectors.mapping(this::reviewResult, Collectors.toList())));
    }

    @Override
    public ReviewRunResult verifyMatured(Long userId, LocalDate asOfDate) {
        List<Long> pendingReportIds = reviewMapper.selectList(new QueryWrapper<AiTradePlanReview>()
                        .eq("user_id", userId)
                        .eq("status", "PENDING")
                        .isNotNull("outcome_trade_date")
                        .le("outcome_trade_date", asOfDate)
                        .orderByAsc("outcome_trade_date")
                        .orderByAsc("id")
                        .last("LIMIT " + (REVIEW_REPORT_LIMIT * 3)))
                .stream()
                .map(item -> item.reportId)
                .filter(Objects::nonNull)
                .distinct()
                .limit(REVIEW_REPORT_LIMIT)
                .toList();
        if (pendingReportIds.isEmpty()) {
            return new ReviewRunResult(0, 0, 0, 0, 0, List.of());
        }
        List<AiAnalysisReport> reports = reportMapper.selectList(new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", userId)
                .eq("status", AnalysisStatus.SUCCESS.name())
                .isNotNull("conditional_strategy")
                .in("id", pendingReportIds)
                .orderByAsc("report_date")
                .orderByAsc("id"));
        int processed = 0;
        int verified = 0;
        int noTrigger = 0;
        int pending = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (AiAnalysisReport report : reports) {
            try {
                ReviewCounts counts = verifyReport(report, asOfDate);
                processed += counts.processed;
                verified += counts.verified;
                noTrigger += counts.noTrigger;
                pending += counts.pending;
            } catch (RuntimeException exception) {
                failed++;
                errors.add(report.stockCode + ": " + rootMessage(exception));
            }
        }
        if (verified > 0 || noTrigger > 0) {
            refreshLearningFeedback(userId);
        }
        return new ReviewRunResult(processed, verified, noTrigger, pending, failed, List.copyOf(errors));
    }

    private ReviewCounts verifyReport(AiAnalysisReport report, LocalDate asOfDate) {
        AiConditionalStrategyPayload original = readStrategy(report.conditionalStrategy);
        KlineSeriesSnapshot snapshot = marketDataService.klineAt(
                report.stockCode, "day", 180, asOfDate.atTime(16, 0));
        List<KlinePointResponse> klines = snapshot == null || snapshot.points() == null ? List.of()
                : snapshot.points().stream()
                .filter(item -> item != null && item.tradeDate() != null && item.close() != null)
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                .toList();
        int entryIndex = indexOnOrBefore(klines, report.reportDate);
        if (entryIndex < 0) {
            return new ReviewCounts(0, 0, 0, original.tradingPlans().size());
        }
        int processed = 0;
        int verified = 0;
        int noTrigger = 0;
        int pending = 0;
        Map<Integer, AiPrediction> predictions = reportPredictions(report.userId, report.id);
        for (AiConditionalStrategyPayload.HorizonPlan originalPlan : original.tradingPlans()) {
            int horizon = originalPlan.horizonDays();
            int triggerIndex = entryIndex + horizon;
            int outcomeIndex = triggerIndex + 1;
            if (outcomeIndex >= klines.size()) {
                pending++;
                continue;
            }
            AiTradePlanReview review = ownedReview(report.userId, report.id, horizon);
            if (review != null && ("VERIFIED".equals(review.status) || "NO_TRIGGER".equals(review.status))) {
                continue;
            }
            processed++;
            KlinePointResponse trigger = klines.get(triggerIndex);
            KlinePointResponse outcome = klines.get(outcomeIndex);
            StockDetailResponse triggerDetail = detailAt(report, klines.subList(0, triggerIndex + 1), trigger, triggerIndex == 0 ? null : klines.get(triggerIndex - 1));
            ResearchSnapshot research = researchSnapshot(report.userId, report.stockCode, trigger.tradeDate(), AiLearningPayloads.AnalysisLearningContext.empty());
            AiConditionalStrategyPayload current = ruleEngine.evaluate(new ConditionalTradeRuleEngine.EngineInput(
                    trigger.tradeDate(), trigger.tradeDate().atTime(15, 0), triggerDetail,
                    original.position(), research.market, original.lineage(), original.ruleConfiguration(),
                    research.factorEvidence, research.ruleWeights, research.limitations));
            AiConditionalStrategyPayload.HorizonPlan actualPlan = current.tradingPlans().stream()
                    .filter(item -> Objects.equals(item.horizonDays(), horizon)).findFirst().orElse(null);
            AiConditionalStrategyPayload.ConditionalRule triggeredRule = actualPlan == null ? null
                    : actualPlan.rules().stream()
                    .filter(AiConditionalStrategyPayload.ConditionalRule::matched)
                    .filter(item -> Objects.equals(item.state(), actualPlan.currentState()))
                    .findFirst()
                    .orElseGet(() -> actualPlan.rules().stream()
                            .filter(AiConditionalStrategyPayload.ConditionalRule::matched).findFirst().orElse(null));
            if (review == null) {
                review = pendingReview(report, horizon, original);
            }
            AiPrediction matchingPrediction = predictions.get(horizon);
            if (matchingPrediction == null) {
                throw new IllegalStateException("条件计划缺少 T+" + horizon + " 正式预测血缘");
            }
            attachReviewLineage(review, matchingPrediction);
            if (review.sampleLabelId == null || review.predictionEvaluationId == null) {
                pending++;
                continue;
            }
            review.targetTradeDate = trigger.tradeDate();
            review.outcomeTradeDate = outcome.tradeDate();
            review.triggerPrice = trigger.close();
            review.outcomePrice = outcome.close();
            review.marketRegime = normalize(current.market().marketRegime(), "UNKNOWN");
            review.evaluatedAt = LocalDateTime.now();
            review.updatedAt = review.evaluatedAt;
            if (triggeredRule == null) {
                review.status = "NO_TRIGGER";
                review.feedbackSummary = "T+" + horizon + " 未满足任何完整条件组合，系统保持观察且不产生学习收益样本";
                Map<String, Object> noTriggerMetrics = new LinkedHashMap<>();
                noTriggerMetrics.put("plan", actualPlan);
                noTriggerMetrics.put("reason", "NO_COMPLETE_RULE_MATCH");
                review.actualMetricsJson = writeJson(noTriggerMetrics);
                saveReview(review);
                noTrigger++;
                continue;
            }
            OutcomeMetrics metrics = outcomeMetrics(trigger, outcome);
            Boolean effective = actionEffective(triggeredRule.action(), metrics.postTriggerReturn,
                    threshold(original.ruleConfiguration(), "actionEffectivenessBufferPct"));
            BigDecimal score = reviewScore(triggeredRule.action(), metrics, effective);
            review.status = "VERIFIED";
            review.triggeredRuleCode = triggeredRule.ruleCode();
            review.triggeredState = triggeredRule.state();
            review.suggestedAction = triggeredRule.action();
            review.postTriggerReturn = metrics.postTriggerReturn;
            review.maxFavorableReturn = metrics.maxFavorableReturn;
            review.maxAdverseReturn = metrics.maxAdverseReturn;
            review.actionEffective = effective == null ? null : effective ? 1 : 0;
            review.reviewScore = score;
            review.actualMetricsJson = writeJson(Map.of(
                    "triggerConditions", triggeredRule.triggerConditions(),
                    "triggerDate", trigger.tradeDate(),
                    "outcomeDate", outcome.tradeDate(),
                    "postTriggerReturn", metrics.postTriggerReturn,
                    "maxFavorableReturn", metrics.maxFavorableReturn,
                    "maxAdverseReturn", metrics.maxAdverseReturn
            ));
            Map<String, Object> feedback = new LinkedHashMap<>();
            feedback.put("ruleCode", triggeredRule.ruleCode());
            feedback.put("factorEvidence", triggeredRule.factorEvidence());
            feedback.put("actionEffective", effective);
            feedback.put("reviewScore", score);
            review.feedbackJson = writeJson(feedback);
            review.feedbackSummary = feedbackSummary(horizon, triggeredRule, metrics, effective);
            saveReview(review);
            verified++;
        }
        return new ReviewCounts(processed, verified, noTrigger, pending);
    }

    private ResearchSnapshot researchSnapshot(
            Long userId,
            String stockCode,
            LocalDate tradeDate,
            AiLearningPayloads.AnalysisLearningContext learningContext
    ) {
        List<String> limitations = new ArrayList<>();
        AiSample sample = sampleMapper.selectLatestForAnalysis(stockCode, tradeDate);
        if (sample != null && !tradeDate.equals(sample.tradeDate)) {
            limitations.add("研究实验室最新样本日期为 " + sample.tradeDate + "，与本次报告日期不一致，仅用于历史权重参考");
        }
        AiPrediction prediction = null;
        List<AiFactorValue> factors = List.of();
        List<AiFactorPerformance> performances = List.of();
        Long requestedReleaseId = learningContext == null ? null : learningContext.strategyVersionId();
        if (sample != null) {
            factors = factorValueMapper.selectBySamples(
                    List.of(sample.id), AiResearchContract.FACTOR_VERSION);
            performances = factorPerformanceMapper.selectForSamplesBefore(List.of(sample.id), tradeDate);
            prediction = predictionMapper.selectForAnalysis(sample.id).stream()
                    .filter(item -> Objects.equals(item.horizonDays, 3))
                    .filter(item -> requestedReleaseId == null
                            || Objects.equals(item.strategyReleaseId, requestedReleaseId))
                    .max(Comparator.comparing((AiPrediction item) -> item.predictedAt,
                                    Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(item -> item.id, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .orElse(null);
        }
        AiStrategyRelease release = requestedReleaseId == null
                ? null : strategyReleaseMapper.selectById(requestedReleaseId);
        if (release == null && prediction != null && prediction.strategyReleaseId != null) {
            release = strategyReleaseMapper.selectById(prediction.strategyReleaseId);
        }
        if (release == null) {
            release = activeRelease();
        }
        Map<String, AiConditionalStrategyPayload.FactorEvidence> evidence =
                formalEvidence(sample, factors, performances);
        if (evidence.isEmpty()) {
            limitations.add("未找到可关联的因子快照，规则仍可计算，但信号强度按低样本处理");
        }
        String regime = sample != null && sample.marketRegime != null ? sample.marketRegime
                : learningContext == null ? "UNKNOWN" : normalize(learningContext.marketRegime(), "UNKNOWN");
        String sectorName = sample == null || unusableSector(sample.sectorName) ? "未接入可靠板块" : sample.sectorName;
        AiConditionalStrategyPayload.MarketContext market = new AiConditionalStrategyPayload.MarketContext(
                normalize(regime, "UNKNOWN"), null, sectorName, null, "UNAVAILABLE", null,
                "UNKNOWN".equals(normalize(regime, "UNKNOWN")) ? "PARTIAL" : "AVAILABLE"
        );
        if (sample == null) {
            limitations.add("研究实验室尚无该股票的正式时点样本");
        }
        if (release == null) {
            limitations.add("当前没有 ACTIVE Champion 策略，使用全局条件规则版本");
        }
        return new ResearchSnapshot(sample, prediction, release, market, evidence,
                learnedRuleWeights(userId, regime), limitations);
    }

    private Map<String, AiConditionalStrategyPayload.FactorEvidence> formalEvidence(
            AiSample sample,
            List<AiFactorValue> factors,
            List<AiFactorPerformance> performances
    ) {
        if (factors == null || factors.isEmpty()) {
            return Map.of();
        }
        Map<String, AiFactorPerformance> performanceByCode = new LinkedHashMap<>();
        String regime = sample == null ? "UNKNOWN" : normalize(sample.marketRegime, "UNKNOWN");
        safeList(performances).stream().filter(item -> regime.equals(item.marketRegime))
                .forEach(item -> performanceByCode.putIfAbsent(item.factorCode, item));
        safeList(performances).forEach(item -> performanceByCode.putIfAbsent(item.factorCode, item));
        Map<String, AiConditionalStrategyPayload.FactorEvidence> result = new LinkedHashMap<>();
        for (AiFactorValue factor : factors) {
            AiFactorPerformance performance = performanceByCode.get(factor.factorCode);
            result.put(factor.factorCode, new AiConditionalStrategyPayload.FactorEvidence(
                    factor.factorCode,
                    factor.factorName == null ? factor.factorCode : factor.factorName,
                    factor.factorGroup,
                    factor.hit == null ? null : factor.hit == 1,
                    factor.rawValue,
                    factor.normalizedValue,
                    performance == null ? null : learnedFactorWeight(performance),
                    performance == null ? null : performance.successRate,
                    performance == null ? 0 : value(performance.sampleCount),
                    performance == null ? "LOW_SAMPLE" : performance.confidenceLevel,
                    factor.evidenceJson,
                    "AI_RESEARCH"
            ));
        }
        return result;
    }

    private ResolvedConfiguration resolveConfiguration(Long userId, AiStrategyRelease release) {
        JsonNode base = defaultConfigurationNode();
        AiTradeRuleConfig entity;
        String configuredVersion = null;
        try {
            QueryWrapper<AiTradeRuleConfig> query = new QueryWrapper<AiTradeRuleConfig>()
                    .eq("user_id", userId)
                    .eq("status", "ACTIVE")
                    .orderByDesc("updated_at")
                    .last("LIMIT 1");
            if (release != null && release.id != null) {
                query.eq("strategy_release_id", release.id);
            }
            entity = ruleConfigMapper.selectOne(query);
            if (entity != null && entity.configJson != null && !entity.configJson.isBlank()) {
                JsonNode configured = objectMapper.readTree(entity.configJson);
                if (configured.isObject()) {
                    deepMerge((ObjectNode) base, normalizedRuleOverrides(configured));
                }
                configuredVersion = entity.versionNo;
            }
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("用户条件策略规则配置无法解析", exception);
        }
        if (entity == null || entity.id == null) {
            throw new IllegalStateException("当前用户尚未初始化正式条件策略规则配置");
        }
        if (release != null && release.configJson != null && !release.configJson.isBlank()) {
            try {
                JsonNode releaseConfig = objectMapper.readTree(release.configJson);
                JsonNode overrides = releaseConfig.path("conditionalTradeRules");
                if (overrides.isObject()) {
                    deepMerge((ObjectNode) base, overrides);
                }
            } catch (JsonProcessingException ignored) {
                // Invalid optional overrides must not disable the base risk rules.
            }
        }
        try {
            AiConditionalStrategyPayload.RuleConfiguration parsed = objectMapper.treeToValue(
                    base, AiConditionalStrategyPayload.RuleConfiguration.class);
            String version = configuredVersion == null || configuredVersion.isBlank() ? parsed.version() : configuredVersion;
            if (release != null && release.versionNo != null) {
                version += "@" + release.versionNo;
            }
            AiConditionalStrategyPayload.RuleConfiguration configuration = new AiConditionalStrategyPayload.RuleConfiguration(
                    version, parsed.thresholds(), parsed.riskWeights(), parsed.positions(),
                    parsed.minimumConditions(), parsed.factorMappings());
            validateConfiguration(configuration);
            return new ResolvedConfiguration(
                    entity.id, configuration, sha256(objectMapper.writeValueAsString(configuration)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("条件策略规则配置无法解析", exception);
        }
    }

    private JsonNode defaultConfigurationNode() {
        try (var input = new ClassPathResource(DEFAULT_RULE_RESOURCE).getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("默认条件策略规则资源不可用", exception);
        }
    }

    private static void deepMerge(ObjectNode target, JsonNode overrides) {
        overrides.fields().forEachRemaining(entry -> {
            JsonNode current = target.get(entry.getKey());
            if (current != null && current.isObject() && entry.getValue().isObject()) {
                deepMerge((ObjectNode) current, entry.getValue());
            } else {
                target.set(entry.getKey(), entry.getValue());
            }
        });
    }

    ObjectNode normalizedRuleOverrides(JsonNode configured) {
        ObjectNode normalized = objectMapper.createObjectNode();
        for (String key : List.of(
                "version", "thresholds", "riskWeights", "positions",
                "minimumConditions", "factorMappings")) {
            JsonNode value = configured.get(key);
            if (value != null && !value.isNull()) {
                normalized.set(key, value.deepCopy());
            }
        }
        JsonNode riskWeights = normalized.get("riskWeights");
        if (riskWeights instanceof ObjectNode weights && weights.has("capital")) {
            if (!weights.has("fund")) {
                weights.set("fund", weights.get("capital"));
            }
            weights.remove("capital");
        }
        return normalized;
    }

    private static void validateConfiguration(AiConditionalStrategyPayload.RuleConfiguration configuration) {
        if (configuration == null || configuration.thresholds() == null || configuration.riskWeights() == null
                || configuration.positions() == null || configuration.minimumConditions() == null
                || configuration.factorMappings() == null) {
            throw new IllegalArgumentException("条件策略规则配置结构不完整");
        }
        BigDecimal riskWeight = configuration.riskWeights().values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (riskWeight.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.0001")) > 0) {
            throw new IllegalArgumentException("条件策略风险权重合计必须为1");
        }
    }

    private AiConditionalStrategyPayload.ResearchLineage lineage(
            ResearchSnapshot research,
            ResolvedConfiguration resolved,
            AiLearningPayloads.AnalysisLearningContext learningContext
    ) {
        BigDecimal quality = research.sample == null ? learningContext == null ? BigDecimal.ZERO : learningContext.dataQualityScore()
                : research.sample.dataQualityScore;
        Long predictionId = research.prediction == null ? learningContext == null ? null : learningContext.predictionId()
                : research.prediction.id;
        return new AiConditionalStrategyPayload.ResearchLineage(
                research.sample == null ? learningContext == null ? null : learningContext.sampleId() : research.sample.id,
                predictionId,
                research.release == null ? null : research.release.id,
                research.release == null ? null : research.release.versionNo,
                AiResearchContract.FACTOR_VERSION,
                resolved.configId,
                resolved.configuration.version(),
                resolved.fingerprint,
                quality == null ? BigDecimal.ZERO : quality,
                strategyValidationScore(research.release)
        );
    }

    private AiConditionalStrategyPayload.PositionContext position(Long userId, String stockCode, BigDecimal currentPrice) {
        List<TradeRecord> records = tradeRecordMapper.selectList(new QueryWrapper<TradeRecord>()
                .eq("user_id", userId)
                .eq("stock_code", stockCode)
                .eq("deleted", 0)
                .orderByAsc("traded_at")
                .orderByAsc("id"));
        int quantity = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (TradeRecord record : records) {
            int recordQuantity = record.quantity == null ? 0 : record.quantity;
            BigDecimal recordPrice = record.price == null ? BigDecimal.ZERO : record.price;
            if (record.side == TradeSide.SELL) {
                int sellQuantity = Math.min(quantity, Math.max(0, recordQuantity));
                BigDecimal average = quantity == 0 ? BigDecimal.ZERO
                        : cost.divide(BigDecimal.valueOf(quantity), 8, RoundingMode.HALF_UP);
                cost = cost.subtract(average.multiply(BigDecimal.valueOf(sellQuantity)));
                quantity -= sellQuantity;
            } else {
                cost = cost.add(recordPrice.multiply(BigDecimal.valueOf(recordQuantity)));
                quantity += recordQuantity;
            }
        }
        BigDecimal averageCost = quantity <= 0 ? null
                : cost.divide(BigDecimal.valueOf(quantity), 4, RoundingMode.HALF_UP);
        BigDecimal profitRate = averageCost == null || currentPrice == null || averageCost.signum() == 0 ? null
                : currentPrice.subtract(averageCost).multiply(ONE_HUNDRED)
                .divide(averageCost, 4, RoundingMode.HALF_UP);
        return new AiConditionalStrategyPayload.PositionContext(
                quantity > 0, Math.max(0, quantity), averageCost, currentPrice, profitRate);
    }

    private AiStrategyRelease activeRelease() {
        return strategyReleaseMapper.selectGlobalActiveChampion(
                AiResearchContract.SYSTEM_UNIVERSE_CODE, AiResearchContract.MODEL_FAMILY);
    }

    private Map<String, BigDecimal> learnedRuleWeights(Long userId, String regime) {
        List<AiTradeRulePerformance> rows = rulePerformanceMapper.selectList(
                new QueryWrapper<AiTradeRulePerformance>()
                        .eq("user_id", userId)
                        .orderByDesc("window_end_date")
                        .orderByDesc("sample_count")
                        .orderByDesc("last_evaluated_at"));
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        String normalizedRegime = normalize(regime, "UNKNOWN");
        rows.stream().filter(item -> normalizedRegime.equals(item.marketRegime))
                .forEach(item -> result.putIfAbsent(item.ruleCode, item.learnedWeight));
        rows.forEach(item -> result.putIfAbsent(item.ruleCode, item.learnedWeight));
        return result;
    }

    private void refreshLearningFeedback(Long userId) {
        List<AiTradePlanReview> rows = reviewMapper.selectList(new QueryWrapper<AiTradePlanReview>()
                .eq("user_id", userId)
                .eq("status", "VERIFIED")
                .isNotNull("action_effective")
                .orderByAsc("evaluated_at")).stream()
                .filter(AiConditionalTradeStrategyServiceImpl::entersActionPerformance)
                .toList();
        Map<PerformanceKey, List<AiTradePlanReview>> groups = rows.stream()
                .filter(item -> item.triggeredRuleCode != null && item.tradeRuleConfigId != null)
                .collect(Collectors.groupingBy(item -> new PerformanceKey(
                        item.tradeRuleConfigId,
                        item.triggeredRuleCode,
                        normalize(item.ruleType, "HORIZON_PLAN"),
                        item.horizonDays,
                        normalize(item.marketRegime, "UNKNOWN"))));
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<PerformanceKey, List<AiTradePlanReview>> entry : groups.entrySet()) {
            List<AiTradePlanReview> samples = entry.getValue();
            int effective = (int) samples.stream().filter(item -> item.actionEffective != null && item.actionEffective == 1).count();
            BigDecimal rate = percentage(effective, samples.size());
            BigDecimal confidence = BigDecimal.valueOf(Math.min(1d, samples.size() / 20d));
            AiTradeRulePerformance performance = new AiTradeRulePerformance();
            performance.userId = userId;
            performance.tradeRuleConfigId = entry.getKey().tradeRuleConfigId;
            performance.ruleCode = entry.getKey().ruleCode;
            performance.ruleType = entry.getKey().ruleType;
            performance.horizonDays = entry.getKey().horizonDays;
            performance.marketRegime = entry.getKey().marketRegime;
            performance.windowStartDate = samples.stream().map(item -> item.reportDate)
                    .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElseThrow();
            performance.windowEndDate = samples.stream().map(item -> item.outcomeTradeDate)
                    .filter(Objects::nonNull).max(Comparator.naturalOrder())
                    .orElse(performance.windowStartDate);
            performance.sampleCount = samples.size();
            performance.effectiveCount = effective;
            performance.effectivenessRate = rate;
            performance.avgPostTriggerReturn = average(samples.stream().map(item -> item.postTriggerReturn).toList());
            performance.avgAdverseReturn = average(samples.stream().map(item -> item.maxAdverseReturn).toList());
            performance.learnedWeight = clamp(new BigDecimal("50")
                    .add(rate.subtract(new BigDecimal("50")).multiply(confidence)), BigDecimal.ZERO, ONE_HUNDRED);
            performance.confidenceLevel = samples.size() < 10 ? "LOW_SAMPLE" : samples.size() < 30 ? "MEDIUM" : "HIGH";
            performance.inputFingerprint = sha256(samples.stream()
                    .map(item -> String.valueOf(item.id))
                    .sorted()
                    .collect(Collectors.joining(",", entry.getKey().toString() + ":", "")));
            performance.lastEvaluatedAt = now;
            performance.createdAt = now;
            performance.updatedAt = now;
            rulePerformanceMapper.upsert(performance);
        }
    }

    private AiTradePlanReview pendingReview(AiAnalysisReport report, int horizon, AiConditionalStrategyPayload payload) {
        AiTradePlanReview review = new AiTradePlanReview();
        review.userId = report.userId;
        review.reportId = report.id;
        review.stockCode = report.stockCode;
        review.reportDate = report.reportDate;
        review.horizonDays = horizon;
        review.tradeRuleConfigId = payload.lineage() == null ? null : payload.lineage().tradeRuleConfigId();
        if (review.tradeRuleConfigId == null) {
            throw new IllegalStateException("历史条件策略缺少正式规则配置血缘");
        }
        review.status = "PENDING";
        review.ruleType = "HORIZON_PLAN";
        review.marketRegime = payload.market() == null ? "UNKNOWN" : normalize(payload.market().marketRegime(), "UNKNOWN");
        review.createdAt = LocalDateTime.now();
        return review;
    }

    private AiTradePlanReview ownedReview(Long userId, Long reportId, int horizon) {
        return reviewMapper.selectOne(new QueryWrapper<AiTradePlanReview>()
                .eq("user_id", userId).eq("report_id", reportId)
                .eq("horizon_trading_days", horizon).last("LIMIT 1"));
    }

    private void saveReview(AiTradePlanReview review) {
        if (review.id == null) {
            reviewMapper.insert(review);
        } else {
            reviewMapper.updateById(review);
        }
    }

    private StockDetailResponse detailAt(
            AiAnalysisReport report,
            List<KlinePointResponse> history,
            KlinePointResponse trigger,
            KlinePointResponse previous
    ) {
        BigDecimal change = previous == null ? BigDecimal.ZERO : trigger.close().subtract(previous.close());
        BigDecimal percent = previous == null || previous.close().signum() == 0 ? BigDecimal.ZERO
                : change.multiply(ONE_HUNDRED).divide(previous.close(), 4, RoundingMode.HALF_UP);
        StockQuoteResponse quote = new StockQuoteResponse(
                report.stockCode, report.stockName, trigger.close(), change, percent, null,
                "CN-A", "PERSISTED_KLINE_REVIEW", trigger.tradeDate().atTime(15, 0));
        return new StockDetailResponse(quote, null, List.of(), List.copyOf(history), null, null);
    }

    static OutcomeMetrics outcomeMetrics(KlinePointResponse trigger, KlinePointResponse outcome) {
        if (trigger == null || outcome == null || trigger.tradeDate() == null || outcome.tradeDate() == null
                || !outcome.tradeDate().isAfter(trigger.tradeDate())) {
            throw new IllegalArgumentException("条件计划结果必须使用触发日后的下一可交易日数据");
        }
        BigDecimal post = returnPct(trigger.close(), outcome.close());
        BigDecimal favorable = returnPct(trigger.close(), outcome.high());
        BigDecimal adverse = returnPct(trigger.close(), outcome.low());
        return new OutcomeMetrics(post, favorable, adverse);
    }

    static boolean entersActionPerformance(AiTradePlanReview review) {
        return review != null
                && "VERIFIED".equals(review.status)
                && review.actionEffective != null
                && review.triggeredRuleCode != null
                && review.tradeRuleConfigId != null;
    }

    private static Boolean actionEffective(String action, BigDecimal postReturn, BigDecimal buffer) {
        if (action == null || postReturn == null) {
            return null;
        }
        return switch (action) {
            case "BUY", "ADD", "HOLD" -> postReturn.compareTo(buffer) > 0;
            case "REDUCE", "SELL", "STOP_LOSS", "TAKE_PROFIT" -> postReturn.compareTo(buffer.negate()) < 0;
            default -> null;
        };
    }

    private static BigDecimal reviewScore(String action, OutcomeMetrics metrics, Boolean effective) {
        if (effective == null) {
            return new BigDecimal("50.0");
        }
        BigDecimal oriented = switch (action) {
            case "REDUCE", "SELL", "STOP_LOSS", "TAKE_PROFIT" -> metrics.postTriggerReturn.negate();
            default -> metrics.postTriggerReturn;
        };
        BigDecimal score = new BigDecimal("50").add(oriented.multiply(new BigDecimal("7")));
        if (metrics.maxAdverseReturn != null && !List.of("REDUCE", "SELL", "STOP_LOSS", "TAKE_PROFIT").contains(action)) {
            score = score.add(metrics.maxAdverseReturn.multiply(new BigDecimal("2")));
        }
        return clamp(score, BigDecimal.ZERO, ONE_HUNDRED).setScale(1, RoundingMode.HALF_UP);
    }

    private static String feedbackSummary(
            int horizon,
            AiConditionalStrategyPayload.ConditionalRule rule,
            OutcomeMetrics metrics,
            Boolean effective
    ) {
        String result = effective == null ? "仅记录条件触发，不纳入动作胜率"
                : effective ? "动作在下一交易日验证有效" : "动作在下一交易日未验证有效";
        return "T+" + horizon + " 触发“" + rule.state() + "”，执行 " + rule.action()
                + "；触发后收益 " + signed(metrics.postTriggerReturn) + "，" + result;
    }

    private AiConditionalStrategyPayload.ReviewResult reviewResult(AiTradePlanReview item) {
        return new AiConditionalStrategyPayload.ReviewResult(
                item.id, item.reportId, item.horizonDays, item.targetTradeDate, item.outcomeTradeDate,
                item.status, item.triggeredRuleCode, item.triggeredState, item.suggestedAction,
                item.triggerPrice, item.outcomePrice, item.postTriggerReturn,
                item.maxFavorableReturn, item.maxAdverseReturn,
                item.actionEffective == null ? null : item.actionEffective == 1,
                item.reviewScore, item.feedbackSummary, item.evaluatedAt
        );
    }

    private AiConditionalStrategyPayload readStrategy(String json) {
        try {
            return objectMapper.readValue(json, AiConditionalStrategyPayload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("历史条件策略快照无法解析", exception);
        }
    }

    private static int indexOnOrBefore(List<KlinePointResponse> klines, LocalDate date) {
        for (int index = klines.size() - 1; index >= 0; index--) {
            if (!klines.get(index).tradeDate().isAfter(date)) {
                return index;
            }
        }
        return -1;
    }

    private LocalDate tradingDateOffset(LocalDate start, int offset) {
        LocalDate cursor = start;
        int remaining = offset;
        while (remaining > 0) {
            cursor = cursor.plusDays(1);
            if (tradingCalendarService.isTradingDay(cursor)) {
                remaining--;
            }
        }
        return cursor;
    }

    private BigDecimal strategyValidationScore(AiStrategyRelease release) {
        if (release == null || release.validationMetricsJson == null || release.validationMetricsJson.isBlank()) {
            return new BigDecimal("50");
        }
        try {
            JsonNode metrics = objectMapper.readTree(release.validationMetricsJson);
            for (String key : List.of("score", "winRate", "hitRate", "successRate", "directionHitRate")) {
                JsonNode value = metrics.path(key);
                if (value.isNumber()) {
                    BigDecimal parsed = value.decimalValue();
                    return parsed.compareTo(BigDecimal.ONE) <= 0 ? parsed.multiply(ONE_HUNDRED) : parsed;
                }
            }
        } catch (JsonProcessingException ignored) {
            return new BigDecimal("50");
        }
        return new BigDecimal("50");
    }

    private static BigDecimal learnedFactorWeight(AiFactorPerformance performance) {
        if (performance == null || performance.successRate == null) {
            return null;
        }
        BigDecimal stability = performance.stabilityScore == null ? new BigDecimal("50") : performance.stabilityScore;
        return performance.successRate.multiply(new BigDecimal("0.7"))
                .add(stability.multiply(new BigDecimal("0.3"))).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal threshold(AiConditionalStrategyPayload.RuleConfiguration configuration, String key) {
        BigDecimal value = configuration.thresholds().get(key);
        if (value == null) {
            throw new IllegalStateException("历史条件策略缺少阈值：" + key);
        }
        return value;
    }

    private static BigDecimal returnPct(BigDecimal base, BigDecimal value) {
        return base == null || value == null || base.signum() == 0 ? BigDecimal.ZERO
                : value.subtract(base).multiply(ONE_HUNDRED).divide(base, 4, RoundingMode.HALF_UP);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("条件策略复盘数据序列化失败", exception);
        }
    }

    private static BigDecimal percentage(int numerator, int denominator) {
        return denominator == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(numerator)
                .multiply(ONE_HUNDRED).divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> filtered = values.stream().filter(Objects::nonNull).toList();
        return filtered.isEmpty() ? BigDecimal.ZERO : filtered.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(filtered.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.max(min).min(max);
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean unusableSector(String value) {
        String normalized = normalize(value, "UNKNOWN");
        return "UNKNOWN".equals(normalized) || "全部".equals(value) || "WATCHLIST".equals(normalized);
    }

    private static String signed(BigDecimal value) {
        if (value == null) {
            return "-";
        }
        return (value.signum() > 0 ? "+" : "") + value.setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static AiPrediction latestPrediction(AiPrediction left, AiPrediction right) {
        if (left.predictedAt == null) {
            return right;
        }
        if (right.predictedAt == null) {
            return left;
        }
        return right.predictedAt.isAfter(left.predictedAt) ? right : left;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record ResearchSnapshot(
            AiSample sample,
            AiPrediction prediction,
            AiStrategyRelease release,
            AiConditionalStrategyPayload.MarketContext market,
            Map<String, AiConditionalStrategyPayload.FactorEvidence> factorEvidence,
            Map<String, BigDecimal> ruleWeights,
            List<String> limitations
    ) {
    }

    private record ResolvedConfiguration(
            Long configId,
            AiConditionalStrategyPayload.RuleConfiguration configuration,
            String fingerprint
    ) {
    }

    private record ReviewCounts(int processed, int verified, int noTrigger, int pending) {
    }

    static record OutcomeMetrics(
            BigDecimal postTriggerReturn,
            BigDecimal maxFavorableReturn,
            BigDecimal maxAdverseReturn
    ) {
    }

    private record PerformanceKey(
            Long tradeRuleConfigId,
            String ruleCode,
            String ruleType,
            Integer horizonDays,
            String marketRegime
    ) {
    }
}
