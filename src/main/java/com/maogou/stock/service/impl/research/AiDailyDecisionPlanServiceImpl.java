package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiDailyDecisionPlan;
import com.maogou.stock.domain.entity.research.AiDailyDecisionPlanReview;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.AiTradeFactorFeedback;
import com.maogou.stock.domain.entity.AiTradeRulePerformance;
import com.maogou.stock.dto.ai.AiConditionalStrategyPayload;
import com.maogou.stock.dto.ai.AiDailyDecisionPlanPayload;
import com.maogou.stock.dto.ai.AiLearningPayloads;
import com.maogou.stock.dto.ai.AiResearchDailyReportPayloads;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.KlineSeriesSnapshot;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.mapper.research.AiDailyDecisionPlanMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionPlanReviewMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionItemMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.AiTradeFactorFeedbackMapper;
import com.maogou.stock.mapper.AiTradeRulePerformanceMapper;
import com.maogou.stock.service.AiConditionalTradeStrategyService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiDailyDecisionPlanService;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.impl.ConditionalTradeRuleEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Covers the formal-decision path that has no linked AI report. A plan uses an
 * immutable AFTER_CLOSE sample, not a later quote, and records source failures
 * as unavailable rather than creating synthetic review facts.
 */
@Service
public class AiDailyDecisionPlanServiceImpl implements AiDailyDecisionPlanService {

    private static final int REVIEW_LIMIT = 360;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");
    private static final BigDecimal DEFAULT_COST_BPS = new BigDecimal("20");

    private final AiDailyDecisionPlanMapper planMapper;
    private final AiDailyDecisionPlanReviewMapper reviewMapper;
    private final AiDailyDecisionItemMapper itemMapper;
    private final AiSampleMapper sampleMapper;
    private final AiTradeRulePerformanceMapper rulePerformanceMapper;
    private final AiTradeFactorFeedbackMapper factorFeedbackMapper;
    private final AiConditionalTradeStrategyService conditionalStrategyService;
    private final ConditionalTradeRuleEngine ruleEngine;
    private final MarketDataService marketDataService;
    private final TradingCalendarService tradingCalendarService;
    private final ObjectMapper objectMapper;

    public AiDailyDecisionPlanServiceImpl(
            AiDailyDecisionPlanMapper planMapper,
            AiDailyDecisionPlanReviewMapper reviewMapper,
            AiDailyDecisionItemMapper itemMapper,
            AiSampleMapper sampleMapper,
            AiTradeRulePerformanceMapper rulePerformanceMapper,
            AiTradeFactorFeedbackMapper factorFeedbackMapper,
            AiConditionalTradeStrategyService conditionalStrategyService,
            ConditionalTradeRuleEngine ruleEngine,
            MarketDataService marketDataService,
            TradingCalendarService tradingCalendarService,
            ObjectMapper objectMapper
    ) {
        this.planMapper = planMapper;
        this.reviewMapper = reviewMapper;
        this.itemMapper = itemMapper;
        this.sampleMapper = sampleMapper;
        this.rulePerformanceMapper = rulePerformanceMapper;
        this.factorFeedbackMapper = factorFeedbackMapper;
        this.conditionalStrategyService = conditionalStrategyService;
        this.ruleEngine = ruleEngine;
        this.marketDataService = marketDataService;
        this.tradingCalendarService = tradingCalendarService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public PlanBuildResult initializeDeterministicPlans(
            Long userId,
            LocalDate tradeDate,
            List<AiDailyDecisionItem> items
    ) {
        if (userId == null || userId <= 0 || tradeDate == null || items == null || items.isEmpty()) {
            return new PlanBuildResult(0, 0, 0, List.of());
        }
        int created = 0;
        int unavailable = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (AiDailyDecisionItem item : items) {
            if (!requiresDeterministicPlan(item)) {
                continue;
            }
            try {
                PlanBuildResult itemResult = initializeItemPlans(userId, tradeDate, item);
                created += itemResult.createdCount();
                unavailable += itemResult.unavailableCount();
                failed += itemResult.failedCount();
                errors.addAll(itemResult.errors());
            } catch (RuntimeException exception) {
                String issue = error("INITIALIZE_DAILY_DECISION_PLAN", item.stockCode,
                        "正式样本快照/条件规则", rootMessage(exception), "已降级为条件计划不可用，下一次日报自动重试");
                try {
                    PlanBuildResult fallback = persistUnavailablePlans(userId, tradeDate, item, issue);
                    created += fallback.createdCount();
                    unavailable += fallback.unavailableCount();
                    errors.add(issue);
                } catch (RuntimeException persistenceException) {
                    failed++;
                    errors.add(error("INITIALIZE_DAILY_DECISION_PLAN", item.stockCode,
                            "日报条件计划数据库", rootMessage(persistenceException), "下一次日报自动重试"));
                }
            }
        }
        return new PlanBuildResult(created, unavailable, failed, List.copyOf(errors));
    }

    private PlanBuildResult initializeItemPlans(Long userId, LocalDate tradeDate, AiDailyDecisionItem item) {
        AiSample sample = item.sampleId == null ? null : sampleMapper.selectById(item.sampleId);
        if (sample == null || !Objects.equals(sample.tradeDate, tradeDate)
                || !Objects.equals(sample.stockCode, item.stockCode)) {
            return persistUnavailablePlans(userId, tradeDate, item,
                    "该正式日报结论缺少同日不可变研究样本，不能生成可验证条件计划");
        }
        StockDetailResponse detail = readDetail(sample);
        if (!isRealQuote(detail == null ? null : detail.quote())) {
            return persistUnavailablePlans(userId, tradeDate, item,
                    "正式样本的行情来源或有效价格不可验证，条件计划保持不可用");
        }
        AiConditionalStrategyPayload strategy = conditionalStrategyService.build(
                userId, detail, tradeDate, AiLearningPayloads.AnalysisLearningContext.empty());
        if (strategy.lineage() == null || strategy.lineage().tradeRuleConfigId() == null
                || strategy.tradingPlans() == null || strategy.tradingPlans().isEmpty()) {
            return persistUnavailablePlans(userId, tradeDate, item,
                    "正式条件规则配置血缘不完整，禁止生成不可追溯条件计划");
        }
        int created = 0;
        for (AiConditionalStrategyPayload.HorizonPlan horizon : strategy.tradingPlans()) {
            if (horizon == null || horizon.horizonDays() == null) {
                continue;
            }
            AiDailyDecisionPlan existing = ownedPlan(item.id, userId, horizon.horizonDays());
            if (existing != null) {
                continue;
            }
            AiDailyDecisionPlan plan = new AiDailyDecisionPlan();
            plan.userId = userId;
            plan.decisionItemId = item.id;
            plan.sampleId = sample.id;
            plan.tradeRuleConfigId = strategy.lineage().tradeRuleConfigId();
            plan.stockCode = item.stockCode;
            plan.tradeDate = tradeDate;
            plan.horizonDays = horizon.horizonDays();
            plan.planSource = "DETERMINISTIC_POLICY";
            plan.officialAction = normalizeAction(item.finalAction);
            plan.status = "PENDING";
            plan.targetTradeDate = tradingDateOffset(tradeDate, horizon.horizonDays());
            plan.outcomeTradeDate = tradingDateOffset(tradeDate, horizon.horizonDays() + 1);
            plan.planJson = write(new AiDailyDecisionPlanPayload(
                    AiDailyDecisionPlanPayload.SCHEMA_VERSION,
                    plan.planSource,
                    item.id,
                    sample.id,
                    item.stockCode,
                    tradeDate,
                    horizon.horizonDays(),
                    plan.officialAction,
                    item.reasonSummary,
                    strategy));
            plan.inputFingerprint = fingerprint(item.inputFingerprint, sample.sourceFingerprint,
                    strategy.lineage().configFingerprint(), horizon.horizonDays(), plan.officialAction);
            plan.sourceProvider = detail.quote().source();
            plan.sourceAsOf = sample.asOfTime;
            plan.createdAt = LocalDateTime.now();
            plan.updatedAt = plan.createdAt;
            planMapper.insert(plan);
            created++;
        }
        return new PlanBuildResult(created, 0, 0, List.of());
    }

    private PlanBuildResult persistUnavailablePlans(
            Long userId,
            LocalDate tradeDate,
            AiDailyDecisionItem item,
            String reason
    ) {
        int created = 0;
        for (int horizon : List.of(1, 2, 3)) {
            if (ownedPlan(item.id, userId, horizon) != null) {
                continue;
            }
            AiDailyDecisionPlan plan = new AiDailyDecisionPlan();
            plan.userId = userId;
            plan.decisionItemId = item.id;
            plan.sampleId = item.sampleId;
            plan.stockCode = item.stockCode;
            plan.tradeDate = tradeDate;
            plan.horizonDays = horizon;
            plan.planSource = "DETERMINISTIC_POLICY";
            plan.officialAction = normalizeAction(item.finalAction);
            plan.status = "UNAVAILABLE";
            plan.targetTradeDate = tradingDateOffset(tradeDate, horizon);
            plan.outcomeTradeDate = tradingDateOffset(tradeDate, horizon + 1);
            plan.inputFingerprint = fingerprint(item.inputFingerprint, "UNAVAILABLE", horizon, reason);
            plan.unavailableReason = reason;
            plan.createdAt = LocalDateTime.now();
            plan.updatedAt = plan.createdAt;
            planMapper.insert(plan);
            created++;
        }
        return new PlanBuildResult(created, created, 0, List.of());
    }

    @Override
    @Transactional
    public PlanReviewRunResult verifyMatured(Long userId, LocalDate asOfDate) {
        if (userId == null || userId <= 0 || asOfDate == null) {
            return new PlanReviewRunResult(0, 0, 0, 0, 0, List.of());
        }
        List<AiDailyDecisionPlan> plans = planMapper.selectList(new QueryWrapper<AiDailyDecisionPlan>()
                .eq("user_id", userId)
                .in("status", List.of("PENDING", "PENDING_TRIGGER", "TRIGGERED_WAIT_OUTCOME", "FAILED_RETRYABLE"))
                .isNotNull("target_trade_date").le("target_trade_date", asOfDate)
                .orderByAsc("target_trade_date", "id").last("LIMIT " + REVIEW_LIMIT));
        if (plans == null || plans.isEmpty()) {
            return new PlanReviewRunResult(0, 0, 0, 0, 0, List.of());
        }
        int processed = 0;
        int verified = 0;
        int noAction = 0;
        int pending = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        Map<String, KlineSeriesSnapshot> stockCache = new HashMap<>();
        KlineSeriesSnapshot benchmark = benchmark(asOfDate, errors);
        for (AiDailyDecisionPlan plan : plans) {
            try {
                ReviewOutcome outcome = review(plan, asOfDate, stockCache, benchmark);
                processed += outcome.processed;
                verified += outcome.verified;
                noAction += outcome.noAction;
                pending += outcome.pending;
            } catch (RuntimeException exception) {
                failed++;
                plan.status = "FAILED_RETRYABLE";
                plan.retryCount = (plan.retryCount == null ? 0 : plan.retryCount) + 1;
                plan.nextRetryAt = LocalDateTime.now().plusMinutes(Math.min(60, 5 * plan.retryCount));
                plan.updatedAt = LocalDateTime.now();
                planMapper.updateById(plan);
                errors.add(error("VERIFY_DAILY_DECISION_PLAN", plan.stockCode,
                        "正式日K/条件规则快照", rootMessage(exception), "下一次自动流水线继续验证"));
            }
        }
        if (verified > 0) {
            refreshCandidateFeedback(userId);
        }
        return new PlanReviewRunResult(processed, verified, noAction, pending, failed, List.copyOf(errors));
    }

    /**
     * Daily-policy reviews are evidence only. Their aggregate is intentionally
     * stored in the candidate namespace and cannot be read by the active rule
     * evaluator as a live weight.
     */
    private void refreshCandidateFeedback(Long userId) {
        List<AiDailyDecisionPlanReview> reviews = reviewMapper.selectList(
                        new QueryWrapper<AiDailyDecisionPlanReview>().eq("user_id", userId)
                                .in("status", List.of("VERIFIED", "VERIFIED_EFFECTIVE", "VERIFIED_INEFFECTIVE"))
                                .isNotNull("action_effective")
                                .orderByAsc("evaluated_at"));
        if (reviews == null || reviews.isEmpty()) {
            return;
        }
        List<Long> planIds = reviews.stream().map(item -> item.decisionPlanId)
                .filter(Objects::nonNull).distinct().toList();
        List<AiDailyDecisionPlan> plans = planIds.isEmpty() ? List.of() : planMapper.selectBatchIds(planIds);
        Map<Long, AiDailyDecisionPlan> planById = plans.stream().collect(Collectors.toMap(item -> item.id, item -> item));
        List<Long> itemIds = plans.stream().map(item -> item.decisionItemId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, AiDailyDecisionItem> itemById = itemIds.isEmpty() ? Map.of()
                : itemMapper.selectBatchIds(itemIds).stream().collect(Collectors.toMap(item -> item.id, item -> item));
        List<CandidateObservation> observations = new ArrayList<>();
        for (AiDailyDecisionPlanReview review : reviews) {
            AiDailyDecisionPlan plan = planById.get(review.decisionPlanId);
            if (plan == null || plan.tradeRuleConfigId == null || review.triggeredRuleCode == null) {
                continue;
            }
            AiDailyDecisionItem item = itemById.get(plan.decisionItemId);
            AiDailyDecisionPlanPayload snapshot = readPlan(plan.planJson);
            String regime = snapshot.conditionalStrategy() == null || snapshot.conditionalStrategy().market() == null
                    ? "UNKNOWN" : normalize(snapshot.conditionalStrategy().market().marketRegime(), "UNKNOWN");
            observations.add(new CandidateObservation(plan, review, item, regime));
        }
        if (observations.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Map<CandidateRuleKey, List<CandidateObservation>> byRule = observations.stream()
                .collect(Collectors.groupingBy(value -> new CandidateRuleKey(value.plan.tradeRuleConfigId,
                        value.review.triggeredRuleCode, value.plan.horizonDays, value.marketRegime), LinkedHashMap::new,
                        Collectors.toList()));
        for (Map.Entry<CandidateRuleKey, List<CandidateObservation>> entry : byRule.entrySet()) {
            List<CandidateObservation> values = entry.getValue();
            int effective = (int) values.stream().filter(value -> Integer.valueOf(1).equals(value.review.actionEffective)).count();
            AiTradeRulePerformance performance = new AiTradeRulePerformance();
            performance.userId = userId;
            performance.tradeRuleConfigId = entry.getKey().configId;
            performance.ruleCode = entry.getKey().ruleCode;
            performance.ruleType = "DAILY_DECISION_POLICY";
            performance.horizonDays = entry.getKey().horizonDays;
            performance.marketRegime = entry.getKey().marketRegime;
            performance.windowStartDate = values.stream().map(value -> value.plan.tradeDate)
                    .filter(Objects::nonNull).min(LocalDate::compareTo).orElseThrow();
            performance.windowEndDate = values.stream().map(value -> value.review.outcomeTradeDate)
                    .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(performance.windowStartDate);
            performance.sampleCount = values.size();
            performance.effectiveCount = effective;
            performance.effectivenessRate = percentage(effective, values.size());
            performance.avgPostTriggerReturn = average(values.stream().map(value -> value.review.postTriggerReturn).toList());
            performance.avgAdverseReturn = average(values.stream().map(value -> value.review.maxAdverseReturn).toList());
            performance.avgNetActionReturn = average(values.stream().map(value -> value.review.netActionReturn).toList());
            performance.avgExcessReturn = average(values.stream().map(value -> value.review.excessReturn).toList());
            performance.avgTransactionCostBps = average(values.stream().map(value -> value.review.transactionCostBps).toList());
            performance.wilsonLowerBound = wilsonLowerBound(effective, values.size());
            performance.learnedWeight = BigDecimal.valueOf(50);
            performance.confidenceLevel = confidence(values.size());
            performance.feedbackScope = "CANDIDATE_ONLY";
            performance.inputFingerprint = fingerprint(values.stream().map(value -> String.valueOf(value.review.id))
                    .sorted().collect(Collectors.joining(",", entry.getKey().toString() + ":", "")));
            performance.lastEvaluatedAt = now;
            performance.createdAt = now;
            performance.updatedAt = now;
            rulePerformanceMapper.upsert(performance);
        }
        refreshFactorFeedback(userId, observations, now);
    }

    private void refreshFactorFeedback(Long userId, List<CandidateObservation> observations, LocalDateTime now) {
        Map<CandidateFactorKey, List<CandidateObservation>> byFactor = new LinkedHashMap<>();
        for (CandidateObservation observation : observations) {
            for (FactorSnapshot factor : factors(observation.item)) {
                CandidateFactorKey key = new CandidateFactorKey(observation.plan.tradeRuleConfigId,
                        factor.code, factor.name, observation.review.triggeredRuleCode, observation.plan.horizonDays,
                        observation.marketRegime);
                byFactor.computeIfAbsent(key, ignored -> new ArrayList<>()).add(observation);
            }
        }
        for (Map.Entry<CandidateFactorKey, List<CandidateObservation>> entry : byFactor.entrySet()) {
            List<CandidateObservation> values = entry.getValue();
            int effective = (int) values.stream().filter(value -> Integer.valueOf(1).equals(value.review.actionEffective)).count();
            AiTradeFactorFeedback feedback = new AiTradeFactorFeedback();
            feedback.userId = userId;
            feedback.tradeRuleConfigId = entry.getKey().configId;
            feedback.factorCode = entry.getKey().factorCode;
            feedback.factorName = entry.getKey().factorName;
            feedback.factorGroup = "DAILY_TRIGGER";
            feedback.ruleCode = entry.getKey().ruleCode;
            feedback.ruleType = "DAILY_DECISION_POLICY";
            feedback.horizonDays = entry.getKey().horizonDays;
            feedback.marketRegime = entry.getKey().marketRegime;
            feedback.windowStartDate = values.stream().map(value -> value.plan.tradeDate)
                    .filter(Objects::nonNull).min(LocalDate::compareTo).orElseThrow();
            feedback.windowEndDate = values.stream().map(value -> value.review.outcomeTradeDate)
                    .filter(Objects::nonNull).max(LocalDate::compareTo).orElse(feedback.windowStartDate);
            feedback.sampleCount = values.size();
            feedback.effectiveCount = effective;
            feedback.effectivenessRate = percentage(effective, values.size());
            feedback.avgNetActionReturn = average(values.stream().map(value -> value.review.netActionReturn).toList());
            feedback.avgExcessReturn = average(values.stream().map(value -> value.review.excessReturn).toList());
            feedback.confidenceLevel = confidence(values.size());
            feedback.feedbackScope = "CANDIDATE_ONLY";
            feedback.inputFingerprint = fingerprint(values.stream().map(value -> String.valueOf(value.review.id))
                    .sorted().collect(Collectors.joining(",", entry.getKey().toString() + ":", "")));
            feedback.lastEvaluatedAt = now;
            feedback.createdAt = now;
            feedback.updatedAt = now;
            factorFeedbackMapper.upsert(feedback);
        }
    }

    private List<FactorSnapshot> factors(AiDailyDecisionItem item) {
        if (item == null || item.triggerFactorsJson == null || item.triggerFactorsJson.isBlank()) return List.of();
        try {
            JsonNode array = objectMapper.readTree(item.triggerFactorsJson);
            if (!array.isArray()) return List.of();
            List<FactorSnapshot> values = new ArrayList<>();
            for (JsonNode node : array) {
                String code = node.path("factorCode").asText("").trim();
                if (!code.isBlank()) values.add(new FactorSnapshot(code,
                        node.path("factorName").asText(code)));
            }
            return List.copyOf(values);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private ReviewOutcome review(
            AiDailyDecisionPlan plan,
            LocalDate asOfDate,
            Map<String, KlineSeriesSnapshot> stockCache,
            KlineSeriesSnapshot benchmark
    ) {
        AiDailyDecisionPlanReview existing = reviewMapper.selectOne(new QueryWrapper<AiDailyDecisionPlanReview>()
                .eq("user_id", plan.userId).eq("decision_plan_id", plan.id).last("LIMIT 1"));
        if (existing != null && List.of("VERIFIED", "VERIFIED_EFFECTIVE", "VERIFIED_INEFFECTIVE",
                "NO_ACTION", "NO_TRIGGER", "DATA_UNAVAILABLE", "FAILED_FINAL").contains(existing.status)) {
            return ReviewOutcome.empty();
        }
        AiDailyDecisionPlanPayload original = readPlan(plan.planJson);
        KlineSeriesSnapshot series = stockCache.computeIfAbsent(plan.stockCode,
                ignored -> marketDataService.klineAt(plan.stockCode, "day", 180, asOfDate.atTime(16, 0)));
        if (!isRealSeries(series)) {
            throw new IllegalStateException("行情源未返回可验证的真实日K数据");
        }
        List<KlinePointResponse> klines = normalized(series);
        int entry = indexOnOrBefore(klines, plan.tradeDate);
        int triggerIndex = entry < 0 ? -1 : entry + plan.horizonDays;
        int outcomeIndex = triggerIndex + 1;
        if (entry < 0 || triggerIndex >= klines.size()) {
            return new ReviewOutcome(0, 0, 0, 1);
        }
        KlinePointResponse trigger = klines.get(triggerIndex);
        AiConditionalStrategyPayload evaluated = evaluateAt(original, klines.subList(0, triggerIndex + 1), trigger,
                triggerIndex == 0 ? null : klines.get(triggerIndex - 1));
        AiConditionalStrategyPayload.HorizonPlan horizon = evaluated.tradingPlans().stream()
                .filter(item -> Objects.equals(item.horizonDays(), plan.horizonDays)).findFirst().orElse(null);
        AiConditionalStrategyPayload.ConditionalRule triggered = horizon == null ? null : horizon.rules().stream()
                .filter(AiConditionalStrategyPayload.ConditionalRule::matched).findFirst().orElse(null);
        AiDailyDecisionPlanReview review = existing == null ? newReview(plan) : existing;
        review.triggerTradeDate = trigger.tradeDate();
        review.triggerPrice = trigger.close();
        review.triggerCheckedAt = LocalDateTime.now();
        review.triggerSourceProvider = series.source();
        review.triggerSourceFingerprint = series.sourceFingerprint();
        review.updatedAt = review.triggerCheckedAt;
        if (triggered == null || !actionable(plan.officialAction)) {
            review.status = triggered == null ? "NO_TRIGGER" : "NO_ACTION";
            review.triggeredRuleCode = triggered == null ? null : triggered.ruleCode();
            review.triggeredState = triggered == null ? "NO_COMPLETE_RULE_MATCH" : triggered.state();
            review.suggestedAction = plan.officialAction;
            review.feedbackSummary = triggered == null
                    ? "T+" + plan.horizonDays + " 未满足完整条件组合，正式日报结论保持不执行"
                    : "T+" + plan.horizonDays + " 条件出现，但正式日报动作为" + actionLabel(plan.officialAction)
                    + "，不把观察结论伪造为交易收益样本";
            review.actualMetricsJson = write(Map.of(
                    "stockKlineSource", series.source(),
                    "stockKlineFingerprint", series.sourceFingerprint(),
                    "triggerDate", trigger.tradeDate(),
                    "officialAction", plan.officialAction,
                    "triggeredRule", triggered == null ? "NO_COMPLETE_RULE_MATCH" : triggered.ruleCode()));
            saveReview(review);
            plan.status = triggered == null ? "NO_TRIGGER" : "NO_ACTION";
            plan.triggerCheckedAt = review.triggerCheckedAt;
            plan.triggerSourceProvider = series.source();
            plan.triggerSourceFingerprint = series.sourceFingerprint();
            plan.updatedAt = review.updatedAt;
            planMapper.updateById(plan);
            return new ReviewOutcome(1, 0, 1, 0);
        }
        review.triggeredRuleCode = triggered.ruleCode();
        review.triggeredState = triggered.state();
        review.suggestedAction = plan.officialAction;
        plan.triggerCheckedAt = review.triggerCheckedAt;
        plan.triggerSourceProvider = series.source();
        plan.triggerSourceFingerprint = series.sourceFingerprint();
        if (outcomeIndex >= klines.size()) {
            review.status = "TRIGGERED_WAIT_OUTCOME";
            review.feedbackSummary = "T+" + plan.horizonDays + " 条件已触发，等待下一交易日验证动作效果";
            saveReview(review);
            plan.status = "TRIGGERED_WAIT_OUTCOME";
            plan.updatedAt = review.updatedAt;
            planMapper.updateById(plan);
            return new ReviewOutcome(1, 0, 0, 1);
        }
        KlinePointResponse outcome = klines.get(outcomeIndex);
        review.outcomeTradeDate = outcome.tradeDate();
        review.outcomePrice = outcome.close();
        review.outcomeCheckedAt = LocalDateTime.now();
        review.outcomeSourceProvider = series.source();
        review.outcomeSourceFingerprint = series.sourceFingerprint();
        review.evaluatedAt = review.outcomeCheckedAt;
        review.updatedAt = review.outcomeCheckedAt;
        ActionMetrics metrics = metrics(trigger, outcome, plan.officialAction, transactionCost(original));
        BigDecimal benchmarkReturn = benchmarkReturn(benchmark, trigger.tradeDate(), outcome.tradeDate(), plan.officialAction);
        BigDecimal excess = benchmarkReturn == null ? null
                : metrics.netActionReturn.subtract(benchmarkReturn).setScale(4, RoundingMode.HALF_UP);
        boolean effective = metrics.netActionReturn.compareTo(BigDecimal.ZERO) > 0;
        review.status = effective ? "VERIFIED_EFFECTIVE" : "VERIFIED_INEFFECTIVE";
        review.triggeredRuleCode = triggered.ruleCode();
        review.triggeredState = triggered.state();
        review.suggestedAction = plan.officialAction;
        review.postTriggerReturn = metrics.postTriggerReturn;
        review.maxFavorableReturn = metrics.maxFavorableReturn;
        review.maxAdverseReturn = metrics.maxAdverseReturn;
        review.transactionCostBps = metrics.transactionCostBps;
        review.netActionReturn = metrics.netActionReturn;
        review.benchmarkReturn = benchmarkReturn;
        review.excessReturn = excess;
        review.actionEffective = effective ? 1 : 0;
        review.reviewScore = score(metrics, effective);
        Map<String, Object> actualMetrics = new LinkedHashMap<>();
        actualMetrics.put("stockKlineSource", series.source());
        actualMetrics.put("stockKlineFingerprint", series.sourceFingerprint());
        actualMetrics.put("triggerDate", trigger.tradeDate());
        actualMetrics.put("outcomeDate", outcome.tradeDate());
        actualMetrics.put("officialAction", plan.officialAction);
        actualMetrics.put("triggeredRule", triggered.ruleCode());
        actualMetrics.put("postTriggerReturn", metrics.postTriggerReturn);
        actualMetrics.put("netActionReturn", metrics.netActionReturn);
        actualMetrics.put("benchmarkActionReturn", benchmarkReturn);
        actualMetrics.put("excessReturn", excess);
        review.actualMetricsJson = write(actualMetrics);
        review.feedbackJson = write(Map.of(
                "feedbackScope", "CANDIDATE_ONLY",
                "planSource", plan.planSource,
                "ruleCode", triggered.ruleCode(),
                "actionEffective", effective));
        review.feedbackSummary = "T+" + plan.horizonDays + " 触发“" + triggered.state() + "”，日报执行"
                + actionLabel(plan.officialAction) + "；策略净收益 " + signed(metrics.netActionReturn)
                + (excess == null ? "；基准不可用，未计算相对收益" : "；相对基准 " + signed(excess));
        saveReview(review);
        plan.status = review.status;
        plan.outcomeCheckedAt = review.outcomeCheckedAt;
        plan.outcomeSourceProvider = series.source();
        plan.outcomeSourceFingerprint = series.sourceFingerprint();
        plan.updatedAt = review.updatedAt;
        planMapper.updateById(plan);
        return new ReviewOutcome(1, 1, 0, 0);
    }

    @Override
    public Map<Long, List<AiResearchDailyReportPayloads.DecisionPlan>> plansByDecisionItemIds(
            Long userId,
            List<Long> decisionItemIds
    ) {
        if (userId == null || decisionItemIds == null || decisionItemIds.isEmpty()) {
            return Map.of();
        }
        List<AiDailyDecisionPlan> plans = planMapper.selectByDecisionItemIds(userId,
                decisionItemIds.stream().filter(Objects::nonNull).distinct().toList());
        if (plans == null || plans.isEmpty()) {
            return Map.of();
        }
        Map<Long, AiDailyDecisionPlanReview> reviews = reviewMapper.selectList(
                        new QueryWrapper<AiDailyDecisionPlanReview>().eq("user_id", userId)
                                .in("decision_plan_id", plans.stream().map(item -> item.id).toList()))
                .stream().collect(Collectors.toMap(item -> item.decisionPlanId, item -> item,
                        (left, right) -> right, LinkedHashMap::new));
        return plans.stream().collect(Collectors.groupingBy(item -> item.decisionItemId,
                LinkedHashMap::new, Collectors.mapping(item -> toView(item, reviews.get(item.id)), Collectors.toList())));
    }

    @Override
    public List<PriorReviewSummary> priorReviewSummaries(Long userId, LocalDate targetTradeDate) {
        if (userId == null || userId <= 0 || targetTradeDate == null) {
            return List.of();
        }
        List<AiDailyDecisionPlan> plans = planMapper.selectList(new QueryWrapper<AiDailyDecisionPlan>()
                .eq("user_id", userId).eq("target_trade_date", targetTradeDate)
                .in("horizon_days", List.of(1, 2, 3)));
        if (plans == null || plans.isEmpty()) {
            return List.of();
        }
        Map<Long, AiDailyDecisionPlanReview> reviews = reviewMapper.selectList(
                        new QueryWrapper<AiDailyDecisionPlanReview>().eq("user_id", userId)
                                .in("decision_plan_id", plans.stream().map(item -> item.id).toList()))
                .stream().collect(Collectors.toMap(item -> item.decisionPlanId, item -> item,
                        (left, right) -> right, LinkedHashMap::new));
        return plans.stream().collect(Collectors.groupingBy(item -> item.horizonDays,
                LinkedHashMap::new, Collectors.collectingAndThen(Collectors.toList(), values -> {
                    int triggerChecked = (int) values.stream().filter(item -> item.triggerCheckedAt != null
                            || List.of("NO_TRIGGER", "NO_ACTION", "TRIGGERED_WAIT_OUTCOME", "VERIFIED_EFFECTIVE",
                            "VERIFIED_INEFFECTIVE", "VERIFIED").contains(item.status)).count();
                    int effective = (int) values.stream().filter(item -> {
                        AiDailyDecisionPlanReview review = reviews.get(item.id);
                        return review != null && ("VERIFIED_EFFECTIVE".equals(review.status)
                                || ("VERIFIED".equals(review.status) && Integer.valueOf(1).equals(review.actionEffective)));
                    }).count();
                    int ineffective = (int) values.stream().filter(item -> {
                        AiDailyDecisionPlanReview review = reviews.get(item.id);
                        return review != null && ("VERIFIED_INEFFECTIVE".equals(review.status)
                                || ("VERIFIED".equals(review.status) && Integer.valueOf(0).equals(review.actionEffective)));
                    }).count();
                    int noTrigger = (int) values.stream().filter(item -> "NO_TRIGGER".equals(item.status)).count();
                    int unavailable = (int) values.stream().filter(item -> "UNAVAILABLE".equals(item.status)
                            || "DATA_UNAVAILABLE".equals(item.status)).count();
                    int retryable = (int) values.stream().filter(item -> "FAILED_RETRYABLE".equals(item.status)
                            || "PENDING_TRIGGER".equals(item.status) || "TRIGGERED_WAIT_OUTCOME".equals(item.status)).count();
                    return new PriorReviewSummary(values.get(0).horizonDays, values.size(), triggerChecked, effective,
                            ineffective, noTrigger, unavailable, retryable);
                }))).values().stream().sorted(java.util.Comparator.comparing(PriorReviewSummary::horizonDays)).toList();
    }

    private AiResearchDailyReportPayloads.DecisionPlan toView(
            AiDailyDecisionPlan plan,
            AiDailyDecisionPlanReview review
    ) {
        return new AiResearchDailyReportPayloads.DecisionPlan(
                plan.horizonDays, plan.planSource, plan.officialAction, plan.status,
                plan.targetTradeDate, plan.outcomeTradeDate,
                review == null ? null : review.triggeredState,
                review == null ? null : review.netActionReturn,
                review == null ? null : review.excessReturn,
                review == null || review.actionEffective == null ? null : review.actionEffective == 1,
                review == null ? plan.unavailableReason : review.feedbackSummary);
    }

    private AiConditionalStrategyPayload evaluateAt(
            AiDailyDecisionPlanPayload original,
            List<KlinePointResponse> history,
            KlinePointResponse trigger,
            KlinePointResponse previous
    ) {
        AiConditionalStrategyPayload strategy = original.conditionalStrategy();
        if (strategy == null || strategy.ruleConfiguration() == null || strategy.lineage() == null) {
            throw new IllegalStateException("日报条件计划快照不完整");
        }
        BigDecimal change = previous == null ? BigDecimal.ZERO : trigger.close().subtract(previous.close());
        BigDecimal percent = previous == null || previous.close() == null || previous.close().signum() == 0 ? BigDecimal.ZERO
                : change.multiply(ONE_HUNDRED).divide(previous.close(), 4, RoundingMode.HALF_UP);
        StockQuoteResponse quote = new StockQuoteResponse(original.stockCode(), original.stockCode(), trigger.close(),
                change, percent, null, "CN-A", "FORMAL_KLINE", trigger.tradeDate().atTime(15, 0));
        StockDetailResponse detail = new StockDetailResponse(quote, null, List.of(), List.copyOf(history), null, null);
        AiConditionalStrategyPayload.PositionContext originalPosition = strategy.position();
        BigDecimal profit = originalPosition == null || originalPosition.averageCost() == null
                || originalPosition.averageCost().signum() == 0 ? null
                : trigger.close().subtract(originalPosition.averageCost()).multiply(ONE_HUNDRED)
                .divide(originalPosition.averageCost(), 4, RoundingMode.HALF_UP);
        AiConditionalStrategyPayload.PositionContext position = new AiConditionalStrategyPayload.PositionContext(
                originalPosition != null && originalPosition.holding(),
                originalPosition == null ? 0 : originalPosition.quantity(),
                originalPosition == null ? null : originalPosition.averageCost(), trigger.close(), profit);
        return ruleEngine.evaluate(new ConditionalTradeRuleEngine.EngineInput(
                trigger.tradeDate(), trigger.tradeDate().atTime(15, 0), detail, position,
                strategy.market(), strategy.lineage(), strategy.ruleConfiguration(), Map.of(), Map.of(),
                strategy.dataLimitations()));
    }

    private StockDetailResponse readDetail(AiSample sample) {
        try {
            JsonNode root = objectMapper.readTree(sample.featureSnapshot);
            StockQuoteResponse quote = objectMapper.treeToValue(root.path("quote"), StockQuoteResponse.class);
            FinanceSnapshotResponse finance = root.path("finance").isMissingNode() || root.path("finance").isNull()
                    ? null : objectMapper.treeToValue(root.path("finance"), FinanceSnapshotResponse.class);
            KlinePointResponse[] klineArray = objectMapper.treeToValue(
                    root.path("kline"), KlinePointResponse[].class);
            List<KlinePointResponse> klines = klineArray == null ? List.of() : List.of(klineArray);
            return new StockDetailResponse(quote, finance, List.of(), klines, null, null);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法解析正式样本特征快照：" + sample.id, exception);
        }
    }

    private KlineSeriesSnapshot benchmark(LocalDate asOfDate, List<String> errors) {
        try {
            KlineSeriesSnapshot series = marketDataService.klineAt(AiResearchContract.BENCHMARK_SYMBOL,
                    "day", 240, asOfDate.atTime(16, 0));
            if (!isRealSeries(series)) {
                errors.add(error("VERIFY_DAILY_DECISION_PLAN", "不适用", "基准指数", "基准日K不可用", "本轮不计算相对收益"));
                return null;
            }
            return series;
        } catch (RuntimeException exception) {
            errors.add(error("VERIFY_DAILY_DECISION_PLAN", "不适用", "基准指数", rootMessage(exception), "本轮不计算相对收益"));
            return null;
        }
    }

    private static boolean requiresDeterministicPlan(AiDailyDecisionItem item) {
        return item != null && item.id != null && item.reportId == null && item.stockCode != null
                && !item.stockCode.isBlank();
    }

    private AiDailyDecisionPlan ownedPlan(Long decisionItemId, Long userId, Integer horizon) {
        return planMapper.selectOne(new QueryWrapper<AiDailyDecisionPlan>().eq("user_id", userId)
                .eq("decision_item_id", decisionItemId).eq("horizon_days", horizon).last("LIMIT 1"));
    }

    private AiDailyDecisionPlanReview newReview(AiDailyDecisionPlan plan) {
        AiDailyDecisionPlanReview review = new AiDailyDecisionPlanReview();
        review.userId = plan.userId;
        review.decisionPlanId = plan.id;
        review.createdAt = LocalDateTime.now();
        return review;
    }

    private void saveReview(AiDailyDecisionPlanReview review) {
        if (review.id == null) {
            reviewMapper.insert(review);
        } else {
            reviewMapper.updateById(review);
        }
    }

    private static boolean isRealQuote(StockQuoteResponse quote) {
        return quote != null && quote.price() != null && quote.price().signum() > 0
                && isRealSource(quote.source());
    }

    private static boolean isRealSeries(KlineSeriesSnapshot series) {
        return series != null && series.points() != null && !series.points().isEmpty() && isRealSource(series.source());
    }

    private static boolean isRealSource(String source) {
        String normalized = source == null ? "" : source.trim().toUpperCase();
        return !normalized.isBlank() && !normalized.contains("MOCK") && !normalized.contains("FALLBACK")
                && !normalized.contains("FIXTURE");
    }

    private static List<KlinePointResponse> normalized(KlineSeriesSnapshot series) {
        return series.points().stream().filter(item -> item != null && item.tradeDate() != null && item.close() != null)
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate)).toList();
    }

    private static int indexOnOrBefore(List<KlinePointResponse> points, LocalDate tradeDate) {
        for (int index = points.size() - 1; index >= 0; index--) {
            if (!points.get(index).tradeDate().isAfter(tradeDate)) return index;
        }
        return -1;
    }

    private LocalDate tradingDateOffset(LocalDate start, int offset) {
        LocalDate cursor = start;
        int remaining = offset;
        while (remaining-- > 0) {
            do cursor = cursor.plusDays(1); while (!tradingCalendarService.isTradingDay(cursor));
        }
        return cursor;
    }

    private static boolean actionable(String action) {
        return List.of("BUY", "ADD", "HOLD", "REDUCE", "SELL").contains(normalizeAction(action));
    }

    private static String normalizeAction(String action) {
        return action == null || action.isBlank() ? "WATCH" : action.trim().toUpperCase();
    }

    private static ActionMetrics metrics(KlinePointResponse trigger, KlinePointResponse outcome, String action, BigDecimal costBps) {
        BigDecimal raw = returnPct(trigger.close(), outcome.close());
        boolean defensive = List.of("REDUCE", "SELL").contains(normalizeAction(action));
        BigDecimal favorable = defensive ? returnPct(trigger.close(), outcome.low()).negate()
                : returnPct(trigger.close(), outcome.high());
        BigDecimal adverse = defensive ? returnPct(trigger.close(), outcome.high()).negate()
                : returnPct(trigger.close(), outcome.low());
        BigDecimal oriented = defensive ? raw.negate() : raw;
        BigDecimal net = oriented.subtract(costBps.divide(ONE_HUNDRED, 4, RoundingMode.HALF_UP));
        return new ActionMetrics(raw, favorable, adverse, costBps, net.setScale(4, RoundingMode.HALF_UP));
    }

    private static BigDecimal benchmarkReturn(KlineSeriesSnapshot series, LocalDate triggerDate, LocalDate outcomeDate, String action) {
        if (series == null) return null;
        KlinePointResponse trigger = series.points().stream().filter(item -> triggerDate.equals(item.tradeDate())).findFirst().orElse(null);
        KlinePointResponse outcome = series.points().stream().filter(item -> outcomeDate.equals(item.tradeDate())).findFirst().orElse(null);
        if (trigger == null || outcome == null) return null;
        BigDecimal raw = returnPct(trigger.close(), outcome.close());
        return List.of("REDUCE", "SELL").contains(normalizeAction(action)) ? raw.negate() : raw;
    }

    private static BigDecimal transactionCost(AiDailyDecisionPlanPayload payload) {
        AiConditionalStrategyPayload.RuleConfiguration config = payload.conditionalStrategy().ruleConfiguration();
        BigDecimal value = config.thresholds().get("evaluationTransactionCostBps");
        return value == null ? DEFAULT_COST_BPS : value;
    }

    private static BigDecimal score(ActionMetrics metrics, boolean effective) {
        BigDecimal value = new BigDecimal("50").add(metrics.netActionReturn.multiply(new BigDecimal("7")))
                .add(metrics.maxAdverseReturn.multiply(new BigDecimal("2")));
        return value.max(BigDecimal.ZERO).min(ONE_HUNDRED).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal returnPct(BigDecimal start, BigDecimal end) {
        return start == null || end == null || start.signum() == 0 ? BigDecimal.ZERO
                : end.subtract(start).multiply(ONE_HUNDRED).divide(start, 4, RoundingMode.HALF_UP);
    }

    private AiDailyDecisionPlanPayload readPlan(String json) {
        try {
            return objectMapper.readValue(json, AiDailyDecisionPlanPayload.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("日报条件计划快照无法解析", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("日报条件计划无法序列化", exception);
        }
    }

    private static String fingerprint(Object... parts) {
        String source = java.util.Arrays.stream(parts).map(value -> value == null ? "<null>" : String.valueOf(value))
                .collect(Collectors.joining("|"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String error(String step, String stockCode, String provider, String reason, String retry) {
        return "步骤=" + step + "；股票=" + (stockCode == null ? "不适用" : stockCode)
                + "；数据提供方=" + provider + "；原因=" + reason + "；重试状态=" + retry;
    }

    private static String rootMessage(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String actionLabel(String action) {
        return switch (normalizeAction(action)) {
            case "BUY" -> "买入";
            case "ADD" -> "加仓";
            case "HOLD" -> "持有";
            case "REDUCE" -> "减仓";
            case "SELL" -> "卖出";
            default -> "观察";
        };
    }

    private static String signed(BigDecimal value) {
        if (value == null) return "-";
        return (value.signum() > 0 ? "+" : "") + value.setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim().toUpperCase();
    }

    private static BigDecimal percentage(int numerator, int denominator) {
        return denominator <= 0 ? BigDecimal.ZERO : BigDecimal.valueOf(numerator).multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> valid = values.stream().filter(Objects::nonNull).toList();
        return valid.isEmpty() ? BigDecimal.ZERO : valid.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(valid.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal wilsonLowerBound(int success, int total) {
        if (total <= 0) return BigDecimal.ZERO;
        double z = 1.96d;
        double n = total;
        double p = (double) success / n;
        double denominator = 1d + z * z / n;
        double center = p + z * z / (2d * n);
        double margin = z * Math.sqrt((p * (1d - p) + z * z / (4d * n)) / n);
        return BigDecimal.valueOf(Math.max(0d, (center - margin) / denominator) * 100d)
                .setScale(4, RoundingMode.HALF_UP);
    }

    private static String confidence(int sampleCount) {
        return sampleCount < 10 ? "LOW_SAMPLE" : sampleCount < 30 ? "MEDIUM" : "HIGH";
    }

    private record ActionMetrics(BigDecimal postTriggerReturn, BigDecimal maxFavorableReturn,
                                 BigDecimal maxAdverseReturn, BigDecimal transactionCostBps,
                                 BigDecimal netActionReturn) {
    }

    private record ReviewOutcome(int processed, int verified, int noAction, int pending) {
        private static ReviewOutcome empty() {
            return new ReviewOutcome(0, 0, 0, 0);
        }
    }

    private record CandidateObservation(
            AiDailyDecisionPlan plan,
            AiDailyDecisionPlanReview review,
            AiDailyDecisionItem item,
            String marketRegime
    ) {
    }

    private record CandidateRuleKey(Long configId, String ruleCode, Integer horizonDays, String marketRegime) {
    }

    private record CandidateFactorKey(
            Long configId,
            String factorCode,
            String factorName,
            String ruleCode,
            Integer horizonDays,
            String marketRegime
    ) {
    }

    private record FactorSnapshot(String code, String name) {
    }
}
