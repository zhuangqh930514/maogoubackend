package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiAnalysisDecision;
import com.maogou.stock.domain.entity.AiAnalysisFactorHit;
import com.maogou.stock.domain.entity.AiAnalysisOutcome;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiFactorStat;
import com.maogou.stock.domain.entity.AiStrategyEvolutionLog;
import com.maogou.stock.domain.entity.AiStrategyVersion;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.dto.ai.AiEvolutionDashboardResponse;
import com.maogou.stock.dto.ai.AiEvolutionReviewResponse;
import com.maogou.stock.dto.ai.AiFactorCenterResponse;
import com.maogou.stock.dto.ai.AiStrategyEvolutionResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.mapper.AiAnalysisFactorHitMapper;
import com.maogou.stock.mapper.AiAnalysisDecisionMapper;
import com.maogou.stock.mapper.AiAnalysisOutcomeMapper;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.AiFactorStatMapper;
import com.maogou.stock.mapper.AiStrategyEvolutionLogMapper;
import com.maogou.stock.mapper.AiStrategyVersionMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiEvolutionService;
import com.maogou.stock.service.MarketDataService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AiEvolutionServiceImpl implements AiEvolutionService {

    private static final String SCHEMA_MESSAGE = "AI 进化模块表未初始化，请先执行 backend/src/main/resources/db/20260601_ai_evolution_module.sql。";
    private static final List<Integer> VERIFY_HORIZONS = List.of(1, 2, 3);
    private static final List<FactorRule> FACTOR_RULES = List.of(
            new FactorRule("BREAKOUT_MOMENTUM", "突破动能", "TECHNICAL", "POSITIVE", new BigDecimal("0.78"), List.of("突破", "创新高", "放量突破", "上穿"), "出现突破或上穿描述"),
            new FactorRule("VOLUME_EXPANSION", "量能确认", "TECHNICAL", "POSITIVE", new BigDecimal("0.64"), List.of("放量", "量能", "量比", "成交量"), "量能被报告重点提及"),
            new FactorRule("MA_BULLISH", "均线多头", "TECHNICAL", "POSITIVE", new BigDecimal("0.62"), List.of("均线多头", "多头排列", "站上", "MA5", "MA10", "MA20"), "均线结构支撑多头判断"),
            new FactorRule("SUPPORT_HOLD", "支撑有效", "TECHNICAL", "POSITIVE", new BigDecimal("0.52"), List.of("支撑", "企稳", "止跌"), "报告提到支撑或企稳"),
            new FactorRule("BUY_TRIGGER_CLEAR", "买点明确", "DECISION", "POSITIVE", new BigDecimal("0.58"), List.of("买点", "买入", "低吸", "加仓"), "报告给出明确买入触发条件"),
            new FactorRule("FUNDAMENTAL_GROWTH", "基本面成长", "FUNDAMENTAL", "POSITIVE", new BigDecimal("0.48"), List.of("净利同比", "营收同比", "ROE", "毛利率", "成长"), "基本面成长指标被引用"),
            new FactorRule("RISK_OVERHEAT", "过热风险", "RISK", "NEGATIVE", new BigDecimal("-0.50"), List.of("高位", "超买", "冲高回落", "追高", "回撤"), "报告提示高位或回撤风险"),
            new FactorRule("WEAK_TREND", "趋势偏弱", "RISK", "NEGATIVE", new BigDecimal("-0.62"), List.of("破位", "跌破", "走弱", "缩量下跌", "趋势转弱"), "趋势弱化信号被报告识别"),
            new FactorRule("VALUATION_PRESSURE", "估值压力", "FUNDAMENTAL", "NEGATIVE", new BigDecimal("-0.36"), List.of("估值偏高", "PE", "PB", "市盈率", "市净率"), "估值压力被纳入风险判断"),
            new FactorRule("REDUCE_TRIGGER_CLEAR", "减仓条件明确", "DECISION", "NEGATIVE", new BigDecimal("-0.42"), List.of("减仓", "卖出", "止损", "风控"), "报告给出明确减仓或止损条件")
    );

    private final AiAnalysisReportMapper reportMapper;
    private final AiAnalysisDecisionMapper decisionMapper;
    private final AiAnalysisOutcomeMapper outcomeMapper;
    private final AiAnalysisFactorHitMapper factorHitMapper;
    private final AiFactorStatMapper factorStatMapper;
    private final AiStrategyVersionMapper strategyVersionMapper;
    private final AiStrategyEvolutionLogMapper strategyLogMapper;
    private final MarketDataService marketDataService;
    private final ObjectMapper objectMapper;

    public AiEvolutionServiceImpl(
            AiAnalysisReportMapper reportMapper,
            AiAnalysisDecisionMapper decisionMapper,
            AiAnalysisOutcomeMapper outcomeMapper,
            AiAnalysisFactorHitMapper factorHitMapper,
            AiFactorStatMapper factorStatMapper,
            AiStrategyVersionMapper strategyVersionMapper,
            AiStrategyEvolutionLogMapper strategyLogMapper,
            MarketDataService marketDataService,
            ObjectMapper objectMapper
    ) {
        this.reportMapper = reportMapper;
        this.decisionMapper = decisionMapper;
        this.outcomeMapper = outcomeMapper;
        this.factorHitMapper = factorHitMapper;
        this.factorStatMapper = factorStatMapper;
        this.strategyVersionMapper = strategyVersionMapper;
        this.strategyLogMapper = strategyLogMapper;
        this.marketDataService = marketDataService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiEvolutionDashboardResponse dashboard() {
        try {
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiAnalysisOutcome> outcomes = outcomeMapper.selectList(new QueryWrapper<AiAnalysisOutcome>()
                    .eq("user_id", userId)
                    .orderByDesc("evaluated_at")
                    .last("LIMIT 80"));
            List<AiFactorStat> stats = factorStatMapper.selectList(new QueryWrapper<AiFactorStat>()
                    .eq("user_id", userId)
                    .orderByDesc("weight_score")
                    .last("LIMIT 8"));
            Long reportCount = reportMapper.selectCount(new QueryWrapper<AiAnalysisReport>()
                    .eq("user_id", userId)
                    .eq("deleted", 0));
            AiStrategyVersion active = activeStrategy(userId);
            List<AiEvolutionDashboardResponse.Metric> metrics = List.of(
                    new AiEvolutionDashboardResponse.Metric("AI 报告数", String.valueOf(reportCount), "已有可复盘样本", "blue"),
                    new AiEvolutionDashboardResponse.Metric("验证样本", String.valueOf(outcomes.size()), "T+1/T+2/T+3 结果", "green"),
                    new AiEvolutionDashboardResponse.Metric("方向命中率", percent(directionHitRate(outcomes)), "基于收盘方向验证", "red"),
                    new AiEvolutionDashboardResponse.Metric("平均收益", signedPercent(avg(outcomes.stream().map(item -> item.pctChange).toList())), "验证样本均值", "yellow")
            );
            List<AiEvolutionDashboardResponse.RecentActivity> activities = outcomes.stream()
                    .limit(8)
                    .map(item -> new AiEvolutionDashboardResponse.RecentActivity(
                            item.stockName + " " + item.horizonDays + "日复盘",
                            "预测 " + directionText(item.predictionDirection) + "，实际 " + directionText(item.actualDirection) + "，收益 " + signedPercent(item.pctChange),
                            item.evaluatedAt,
                            item.success != null && item.success == 1 ? "SUCCESS" : "WATCH"
                    ))
                    .toList();
            List<AiEvolutionDashboardResponse.FactorSnapshot> factorSnapshots = stats.stream()
                    .map(item -> new AiEvolutionDashboardResponse.FactorSnapshot(
                            item.factorCode,
                            item.factorName,
                            item.factorGroup,
                            item.sampleCount,
                            item.successRate,
                            item.avgReturn,
                            item.weightScore
                    ))
                    .toList();
            return new AiEvolutionDashboardResponse(true, "AI 进化模块已就绪", metrics, activities, factorSnapshots, active == null ? "暂无已启用策略" : active.versionNo + " · " + active.title);
        } catch (DataAccessException ex) {
            return new AiEvolutionDashboardResponse(false, SCHEMA_MESSAGE, List.of(), List.of(), List.of(), "未初始化");
        }
    }

    @Override
    public AiEvolutionReviewResponse reviews() {
        try {
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiAnalysisOutcome> outcomes = outcomeMapper.selectList(new QueryWrapper<AiAnalysisOutcome>()
                    .eq("user_id", userId)
                    .orderByDesc("evaluated_at")
                    .last("LIMIT 100"));
            Long reportCount = reportMapper.selectCount(new QueryWrapper<AiAnalysisReport>()
                    .eq("user_id", userId)
                    .eq("status", AnalysisStatus.SUCCESS.name())
                    .eq("deleted", 0));
            Set<Long> verifiedReportIds = outcomes.stream()
                    .map(item -> item.reportId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            return new AiEvolutionReviewResponse(
                    true,
                    "复盘验证数据已加载",
                    reportCount.intValue(),
                    verifiedReportIds.size(),
                    Math.max(0, reportCount.intValue() - verifiedReportIds.size()),
                    outcomes.stream().map(this::reviewItem).toList()
            );
        } catch (DataAccessException ex) {
            return new AiEvolutionReviewResponse(false, SCHEMA_MESSAGE, 0, 0, 0, List.of());
        }
    }

    @Override
    @Transactional
    public AiEvolutionReviewResponse verifyReviews() {
        ensureEvolutionTablesReady();
        Long userId = AuthContext.currentUserIdOrDefault();
        LocalDate today = LocalDate.now();
        List<AiAnalysisReport> reports = reportMapper.selectList(new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", userId)
                .eq("status", AnalysisStatus.SUCCESS.name())
                .eq("deleted", 0)
                .lt("report_date", today)
                .orderByDesc("generated_at")
                .last("LIMIT 120"));

        for (AiAnalysisReport report : reports) {
            List<KlinePointResponse> klines = marketDataService.kline(report.stockCode, "day", 120).stream()
                    .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                    .toList();
            int entryIndex = entryIndex(klines, report.reportDate);
            if (entryIndex < 0) {
                continue;
            }
            for (Integer horizon : VERIFY_HORIZONS) {
                if (entryIndex + horizon >= klines.size() || outcomeExists(userId, report.id, horizon)) {
                    continue;
                }
                AiAnalysisOutcome outcome = buildOutcome(userId, report, klines.get(entryIndex), klines.get(entryIndex + horizon), horizon);
                outcomeMapper.insert(outcome);
            }
        }
        return reviews();
    }

    @Override
    public AiFactorCenterResponse factors() {
        try {
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiFactorStat> stats = factorStatMapper.selectList(new QueryWrapper<AiFactorStat>()
                    .eq("user_id", userId)
                    .orderByDesc("weight_score"));
            int sampleCount = stats.stream()
                    .map(item -> item.sampleCount == null ? 0 : item.sampleCount)
                    .reduce(0, Integer::sum);
            return new AiFactorCenterResponse(true, "因子学习数据已加载", stats.size(), sampleCount, stats.stream().map(this::factorItem).toList());
        } catch (DataAccessException ex) {
            return new AiFactorCenterResponse(false, SCHEMA_MESSAGE, 0, 0, List.of());
        }
    }

    @Override
    public AiFactorCenterResponse refreshFactors() {
        AiFactorCenterResponse current = factors();
        return new AiFactorCenterResponse(
                current.schemaReady(),
                "旧版因子刷新已停用，当前页面为只读兼容；有效统计由新版标签学习链路维护。",
                current.factorCount(),
                current.sampleCount(),
                current.factors()
        );
    }

    @Override
    public AiStrategyEvolutionResponse strategies() {
        try {
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiStrategyVersion> versions = strategyVersionMapper.selectList(new QueryWrapper<AiStrategyVersion>()
                    .eq("user_id", userId)
                    .orderByDesc("created_at"));
            List<AiStrategyEvolutionLog> logs = strategyLogMapper.selectList(new QueryWrapper<AiStrategyEvolutionLog>()
                    .eq("user_id", userId)
                    .orderByDesc("created_at")
                    .last("LIMIT 50"));
            if (versions.isEmpty()) {
                return new AiStrategyEvolutionResponse(true, "暂无落库策略，已展示推荐演进路径", plannedStrategyVersions(), List.of());
            }
            return new AiStrategyEvolutionResponse(
                    true,
                    "策略版本已加载",
                    versions.stream().map(this::strategyVersion).toList(),
                    logs.stream().map(this::evolutionLog).toList()
            );
        } catch (DataAccessException ex) {
            return new AiStrategyEvolutionResponse(false, SCHEMA_MESSAGE, plannedStrategyVersions(), List.of());
        }
    }

    @Override
    @Transactional
    public AiStrategyEvolutionResponse evolveStrategy() {
        ensureEvolutionTablesReady();
        Long userId = AuthContext.currentUserIdOrDefault();
        refreshFactors();
        List<AiFactorStat> stats = factorStatMapper.selectList(new QueryWrapper<AiFactorStat>()
                .eq("user_id", userId)
                .orderByDesc("weight_score"));
        if (stats.isEmpty()) {
            throw new IllegalStateException("暂无可学习因子，请先执行复盘验证并刷新因子。");
        }
        AiStrategyVersion active = activeStrategy(userId);
        AiStrategyVersion version = new AiStrategyVersion();
        version.userId = userId;
        version.versionNo = nextStrategyVersionNo(userId);
        version.title = "因子调权策略 " + version.versionNo;
        version.factorSnapshot = buildFactorSnapshot(stats);
        version.strategySummary = buildStrategySummary(stats);
        version.promptTemplate = buildStrategyPrompt(stats);
        version.avgSuccessRate = avg(stats.stream().map(item -> item.successRate).toList());
        version.avgReturn = avg(stats.stream().map(item -> item.avgReturn).toList());
        version.maxDrawdown = stats.stream()
                .map(item -> item.avgDrawdown)
                .filter(item -> item != null)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        version.sampleCount = stats.stream().map(item -> item.sampleCount == null ? 0 : item.sampleCount).reduce(0, Integer::sum);
        version.active = active == null ? 1 : 0;
        version.createdAt = LocalDateTime.now();
        version.updatedAt = version.createdAt;
        strategyVersionMapper.insert(version);
        insertStrategyLog(userId, version.id, "EVOLVE", "基于最新复盘和因子统计生成新策略版本", active == null ? "" : active.strategySummary, version.strategySummary);
        return strategies();
    }

    @Override
    @Transactional
    public AiStrategyEvolutionResponse activateStrategy(Long strategyId) {
        ensureEvolutionTablesReady();
        Long userId = AuthContext.currentUserIdOrDefault();
        AiStrategyVersion selected = strategyVersionMapper.selectOne(new QueryWrapper<AiStrategyVersion>()
                .eq("id", strategyId)
                .eq("user_id", userId)
                .last("LIMIT 1"));
        if (selected == null) {
            throw new IllegalArgumentException("策略版本不存在或无权访问。");
        }
        AiStrategyVersion before = activeStrategy(userId);
        List<AiStrategyVersion> versions = strategyVersionMapper.selectList(new QueryWrapper<AiStrategyVersion>().eq("user_id", userId));
        for (AiStrategyVersion version : versions) {
            version.active = version.id.equals(selected.id) ? 1 : 0;
            version.updatedAt = LocalDateTime.now();
            strategyVersionMapper.updateById(version);
        }
        insertStrategyLog(userId, selected.id, "ACTIVATE", "启用或回滚到策略版本 " + selected.versionNo, before == null ? "" : before.versionNo + " · " + before.strategySummary, selected.versionNo + " · " + selected.strategySummary);
        return strategies();
    }

    private void ensureEvolutionTablesReady() {
        try {
            outcomeMapper.selectCount(new QueryWrapper<AiAnalysisOutcome>().last("LIMIT 1"));
            decisionMapper.selectCount(new QueryWrapper<AiAnalysisDecision>().last("LIMIT 1"));
            factorHitMapper.selectCount(new QueryWrapper<AiAnalysisFactorHit>().last("LIMIT 1"));
            factorStatMapper.selectCount(new QueryWrapper<AiFactorStat>().last("LIMIT 1"));
            strategyVersionMapper.selectCount(new QueryWrapper<AiStrategyVersion>().last("LIMIT 1"));
            strategyLogMapper.selectCount(new QueryWrapper<AiStrategyEvolutionLog>().last("LIMIT 1"));
        } catch (DataAccessException ex) {
            throw new IllegalStateException(SCHEMA_MESSAGE, ex);
        }
    }

    private boolean outcomeExists(Long userId, Long reportId, Integer horizonDays) {
        Long count = outcomeMapper.selectCount(new QueryWrapper<AiAnalysisOutcome>()
                .eq("user_id", userId)
                .eq("report_id", reportId)
                .eq("horizon_days", horizonDays));
        return count != null && count > 0;
    }

    private AiAnalysisOutcome buildOutcome(Long userId, AiAnalysisReport report, KlinePointResponse entry, KlinePointResponse actual, Integer horizonDays) {
        BigDecimal entryPrice = safe(entry.close());
        BigDecimal closePrice = safe(actual.close());
        BigDecimal highPrice = safe(actual.high());
        BigDecimal lowPrice = safe(actual.low());
        BigDecimal pctChange = pct(closePrice.subtract(entryPrice), entryPrice);
        BigDecimal highChange = pct(highPrice.subtract(entryPrice), entryPrice);
        BigDecimal maxDrawdown = pct(lowPrice.subtract(entryPrice), entryPrice);
        String prediction = predictionDirection(report);
        String actualDirection = actualDirection(pctChange);
        boolean directionCorrect = directionCorrect(prediction, pctChange);
        boolean success = success(prediction, pctChange, highChange, maxDrawdown);
        AiAnalysisOutcome outcome = new AiAnalysisOutcome();
        outcome.userId = userId;
        outcome.reportId = report.id;
        outcome.stockCode = report.stockCode;
        outcome.stockName = report.stockName;
        outcome.reportDate = report.reportDate;
        outcome.horizonDays = horizonDays;
        outcome.predictionDirection = prediction;
        outcome.actualDirection = actualDirection;
        outcome.entryPrice = entryPrice;
        outcome.closePrice = closePrice;
        outcome.highPrice = highPrice;
        outcome.lowPrice = lowPrice;
        outcome.pctChange = pctChange;
        outcome.maxDrawdown = maxDrawdown;
        outcome.directionCorrect = directionCorrect ? 1 : 0;
        outcome.success = success ? 1 : 0;
        outcome.successScore = successScore(prediction, pctChange, highChange, maxDrawdown);
        outcome.evaluatedAt = LocalDateTime.now();
        outcome.deleted = 0;
        outcome.createdAt = outcome.evaluatedAt;
        outcome.updatedAt = outcome.evaluatedAt;
        return outcome;
    }

    private int entryIndex(List<KlinePointResponse> klines, LocalDate reportDate) {
        for (int index = 0; index < klines.size(); index++) {
            if (!klines.get(index).tradeDate().isBefore(reportDate)) {
                return index;
            }
        }
        return -1;
    }

    private String predictionDirection(AiAnalysisReport report) {
        AiAnalysisDecision decision = decisionForReport(report);
        if (decision != null && decision.targetDirection != null && !decision.targetDirection.isBlank()) {
            return decision.targetDirection;
        }
        String text = normalizeText(report.advice, report.technicalAnalysis, report.riskWarning, report.buySellPoints, report.rawResponse);
        if (containsAny(text, "减仓", "卖出", "止损", "破位", "控制仓位", "风险偏高", "看空")) {
            return "DOWN";
        }
        if (containsAny(text, "买入", "低吸", "突破", "上行", "看多", "持有", "加仓")) {
            return "UP";
        }
        return "SIDEWAYS";
    }

    private String actualDirection(BigDecimal pctChange) {
        if (pctChange.compareTo(new BigDecimal("0.30")) > 0) {
            return "UP";
        }
        if (pctChange.compareTo(new BigDecimal("-0.30")) < 0) {
            return "DOWN";
        }
        return "SIDEWAYS";
    }

    private boolean directionCorrect(String prediction, BigDecimal pctChange) {
        if ("UP".equals(prediction)) {
            return pctChange.compareTo(BigDecimal.ZERO) > 0;
        }
        if ("DOWN".equals(prediction)) {
            return pctChange.compareTo(BigDecimal.ZERO) < 0;
        }
        return pctChange.abs().compareTo(new BigDecimal("1.50")) <= 0;
    }

    private boolean success(String prediction, BigDecimal pctChange, BigDecimal highChange, BigDecimal maxDrawdown) {
        if ("UP".equals(prediction)) {
            return (pctChange.compareTo(new BigDecimal("1.00")) >= 0 || highChange.compareTo(new BigDecimal("2.00")) >= 0)
                    && maxDrawdown.compareTo(new BigDecimal("-5.00")) >= 0;
        }
        if ("DOWN".equals(prediction)) {
            return pctChange.compareTo(new BigDecimal("-1.00")) <= 0;
        }
        return pctChange.abs().compareTo(new BigDecimal("1.50")) <= 0;
    }

    private BigDecimal successScore(String prediction, BigDecimal pctChange, BigDecimal highChange, BigDecimal maxDrawdown) {
        BigDecimal score;
        if ("UP".equals(prediction)) {
            score = new BigDecimal("50").add(pctChange.multiply(new BigDecimal("7"))).add(highChange.multiply(new BigDecimal("1.5"))).add(maxDrawdown.multiply(new BigDecimal("1.2")));
        } else if ("DOWN".equals(prediction)) {
            score = new BigDecimal("50").subtract(pctChange.multiply(new BigDecimal("7")));
        } else {
            score = new BigDecimal("82").subtract(pctChange.abs().multiply(new BigDecimal("8")));
        }
        return clamp(score, BigDecimal.ZERO, new BigDecimal("100"));
    }

    private List<FactorRule> extractFactors(AiAnalysisReport report) {
        List<FactorRule> structuredFactors = structuredFactors(report);
        if (!structuredFactors.isEmpty()) {
            return structuredFactors;
        }
        String text = normalizeText(report.advice, report.technicalAnalysis, report.riskWarning, report.buySellPoints, report.promptSummary, report.rawResponse);
        List<FactorRule> hits = FACTOR_RULES.stream()
                .filter(rule -> rule.keywords().stream().anyMatch(keyword -> text.contains(keyword.toLowerCase(Locale.ROOT))))
                .toList();
        if (!hits.isEmpty()) {
            return hits;
        }
        return List.of(new FactorRule("GENERAL_AI_JUDGEMENT", "综合判断", "DECISION", "NEUTRAL", new BigDecimal("0.20"), List.of(), "报告未命中预置因子，按综合判断归档"));
    }

    private AiAnalysisDecision decisionForReport(AiAnalysisReport report) {
        if (report == null || report.id == null) {
            return null;
        }
        try {
            return decisionMapper.selectOne(new QueryWrapper<AiAnalysisDecision>()
                    .eq("user_id", report.userId)
                    .eq("report_id", report.id)
                    .last("LIMIT 1"));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private List<FactorRule> structuredFactors(AiAnalysisReport report) {
        AiAnalysisDecision decision = decisionForReport(report);
        if (decision == null || decision.factorsJson == null || decision.factorsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(decision.factorsJson);
            if (!root.isArray()) {
                return List.of();
            }
            List<FactorRule> factors = new ArrayList<>();
            for (JsonNode item : root) {
                String code = text(item, "code", "factorCode", "factor_code");
                String name = text(item, "name", "factorName", "factor_name");
                if (code.isBlank() && name.isBlank()) {
                    continue;
                }
                factors.add(new FactorRule(
                        code.isBlank() ? normalizeFactorCode(name) : code,
                        name.isBlank() ? code : name,
                        normalizeFactorGroup(text(item, "group", "factorGroup", "factor_group")),
                        normalizeFactorDirection(text(item, "direction")),
                        decimal(item, "weight", "weightScore", "weight_score"),
                        List.of(),
                        text(item, "reason")
                ));
            }
            return factors;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private void rebuildFactorStats(Long userId, List<AiAnalysisFactorHit> hits) {
        Map<String, List<AiAnalysisFactorHit>> grouped = hits.stream()
                .collect(Collectors.groupingBy(hit -> hit.factorCode + "|" + hit.marketRegime, LinkedHashMap::new, Collectors.toList()));
        for (List<AiAnalysisFactorHit> group : grouped.values()) {
            AiAnalysisFactorHit first = group.get(0);
            int sampleCount = group.size();
            int successCount = (int) group.stream().filter(hit -> safe(hit.successScore).compareTo(new BigDecimal("60")) >= 0).count();
            BigDecimal successRate = divide(new BigDecimal(successCount * 100), new BigDecimal(sampleCount));
            BigDecimal avgReturn = avg(group.stream().map(hit -> hit.pctChange).toList());
            BigDecimal avgScore = avg(group.stream().map(hit -> hit.successScore).toList());
            BigDecimal avgDrawdown = avgDrawdownByOutcome(group);
            BigDecimal factorWeight = factorWeight(successRate, avgReturn, avgDrawdown, avgScore, first.direction);
            AiFactorStat stat = new AiFactorStat();
            stat.userId = userId;
            stat.factorCode = first.factorCode;
            stat.factorName = first.factorName;
            stat.factorGroup = first.factorGroup;
            stat.marketRegime = first.marketRegime;
            stat.sampleCount = sampleCount;
            stat.successCount = successCount;
            stat.successRate = successRate;
            stat.avgReturn = avgReturn;
            stat.avgDrawdown = avgDrawdown;
            stat.weightScore = factorWeight;
            stat.lastEvaluatedAt = LocalDateTime.now();
            stat.createdAt = stat.lastEvaluatedAt;
            stat.updatedAt = stat.lastEvaluatedAt;
            factorStatMapper.upsert(stat);
        }
    }

    private BigDecimal avgDrawdownByOutcome(List<AiAnalysisFactorHit> group) {
        List<Long> outcomeIds = group.stream().map(hit -> hit.outcomeId).distinct().toList();
        if (outcomeIds.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<AiAnalysisOutcome> outcomes = outcomeMapper.selectBatchIds(outcomeIds);
        return avg(outcomes.stream().map(item -> item.maxDrawdown).toList());
    }

    private BigDecimal factorWeight(BigDecimal successRate, BigDecimal avgReturn, BigDecimal avgDrawdown, BigDecimal avgScore, String direction) {
        BigDecimal score = successRate.multiply(new BigDecimal("0.45"))
                .add(avgScore.multiply(new BigDecimal("0.35")))
                .add(avgReturn.add(new BigDecimal("5")).multiply(new BigDecimal("2.0")))
                .add(avgDrawdown.multiply(new BigDecimal("0.8")));
        if ("NEGATIVE".equals(direction)) {
            score = score.subtract(new BigDecimal("8"));
        }
        return clamp(score, BigDecimal.ZERO, new BigDecimal("100"));
    }

    private AiEvolutionReviewResponse.ReviewItem reviewItem(AiAnalysisOutcome item) {
        return new AiEvolutionReviewResponse.ReviewItem(
                item.id,
                item.reportId,
                item.stockCode,
                item.stockName,
                item.reportDate,
                item.horizonDays,
                item.predictionDirection,
                item.actualDirection,
                item.entryPrice,
                item.closePrice,
                item.pctChange,
                item.maxDrawdown,
                item.directionCorrect != null && item.directionCorrect == 1,
                item.success != null && item.success == 1,
                item.successScore,
                item.evaluatedAt
        );
    }

    private AiFactorCenterResponse.FactorItem factorItem(AiFactorStat item) {
        return new AiFactorCenterResponse.FactorItem(
                item.id,
                item.factorCode,
                item.factorName,
                item.factorGroup,
                item.marketRegime,
                item.sampleCount,
                item.successCount,
                item.successRate,
                item.avgReturn,
                item.avgDrawdown,
                item.weightScore,
                item.lastEvaluatedAt
        );
    }

    private AiStrategyEvolutionResponse.StrategyVersion strategyVersion(AiStrategyVersion item) {
        return new AiStrategyEvolutionResponse.StrategyVersion(
                item.id,
                item.versionNo,
                item.title,
                item.active != null && item.active == 1 ? "ACTIVE" : "STANDBY",
                item.createdAt,
                item.avgSuccessRate,
                item.avgReturn,
                item.maxDrawdown,
                item.sampleCount,
                item.strategySummary,
                item.factorSnapshot,
                item.promptTemplate
        );
    }

    private AiStrategyEvolutionResponse.EvolutionLog evolutionLog(AiStrategyEvolutionLog item) {
        return new AiStrategyEvolutionResponse.EvolutionLog(
                item.id,
                item.strategyVersionId,
                item.actionType,
                item.actionSummary,
                item.beforeSnapshot,
                item.afterSnapshot,
                item.createdAt
        );
    }

    private List<AiStrategyEvolutionResponse.StrategyVersion> plannedStrategyVersions() {
        LocalDateTime now = LocalDateTime.now();
        return List.of(
                new AiStrategyEvolutionResponse.StrategyVersion(null, "PLAN-01", "结构化复盘引擎", "PLANNED", now, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, "验证 AI 报告 T+1/T+2/T+3 的方向、收益和回撤表现。", "等待复盘样本", "要求模型输出可验证的方向、买卖触发和风险条件。"),
                new AiStrategyEvolutionResponse.StrategyVersion(null, "PLAN-02", "因子归因引擎", "PLANNED", now, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, "把技术面、风险、基本面和买卖点拆成可统计因子。", "等待因子样本", "要求报告明确说明命中的因子和失效条件。"),
                new AiStrategyEvolutionResponse.StrategyVersion(null, "PLAN-03", "策略版本管理", "PLANNED", now, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, "根据因子胜率、收益、回撤生成可启用和可回滚的策略版本。", "等待策略生成", "根据高权重因子调整分析重点，保留风险抑制项。")
        );
    }

    private AiStrategyVersion activeStrategy(Long userId) {
        return strategyVersionMapper.selectOne(new QueryWrapper<AiStrategyVersion>()
                .eq("user_id", userId)
                .eq("active", 1)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
    }

    private String nextStrategyVersionNo(Long userId) {
        Long count = strategyVersionMapper.selectCount(new QueryWrapper<AiStrategyVersion>().eq("user_id", userId));
        return "MG-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + String.format("%03d", (count == null ? 0 : count) + 1);
    }

    private String buildFactorSnapshot(List<AiFactorStat> stats) {
        return stats.stream()
                .limit(10)
                .map(item -> "%s/%s：样本%s，胜率%s，均收%s，权重%s".formatted(item.factorName, item.marketRegime, item.sampleCount, percent(item.successRate), signedPercent(item.avgReturn), item.weightScore))
                .collect(Collectors.joining("\n"));
    }

    private String buildStrategySummary(List<AiFactorStat> stats) {
        List<AiFactorStat> top = stats.stream().limit(3).toList();
        List<AiFactorStat> risk = stats.stream()
                .filter(item -> "RISK".equals(item.factorGroup) || safe(item.avgDrawdown).compareTo(new BigDecimal("-3")) < 0)
                .limit(3)
                .toList();
        String topText = top.stream().map(item -> item.factorName + "(" + percent(item.successRate) + ")").collect(Collectors.joining("、"));
        String riskText = risk.isEmpty() ? "无显著新增风险抑制项" : risk.stream().map(item -> item.factorName).collect(Collectors.joining("、"));
        return "下一版策略将提高高胜率因子权重：" + topText + "；同时保留风险抑制项：" + riskText + "。";
    }

    private String buildStrategyPrompt(List<AiFactorStat> stats) {
        String factors = stats.stream()
                .limit(8)
                .map(item -> "- " + item.factorName + "：历史胜率 " + percent(item.successRate) + "，平均收益 " + signedPercent(item.avgReturn) + "，当前权重 " + item.weightScore)
                .collect(Collectors.joining("\n"));
        return """
                你是猫狗智投的 A 股短线分析模型。请优先参考以下经过复盘验证的因子权重，但不要忽略基本面和风险条件：
                %s

                输出仍必须严格为 JSON，包含 technicalAnalysis、riskWarning、buySellPoints、promptSummary、score。
                买卖点必须给出触发条件、失效条件、止损和仓位建议；风险提示必须说明哪些因子会使本次判断失效。
                """.formatted(factors);
    }

    private void insertStrategyLog(Long userId, Long strategyVersionId, String actionType, String actionSummary, String beforeSnapshot, String afterSnapshot) {
        AiStrategyEvolutionLog log = new AiStrategyEvolutionLog();
        log.userId = userId;
        log.strategyVersionId = strategyVersionId;
        log.actionType = actionType;
        log.actionSummary = actionSummary;
        log.beforeSnapshot = beforeSnapshot;
        log.afterSnapshot = afterSnapshot;
        log.createdAt = LocalDateTime.now();
        strategyLogMapper.insert(log);
    }

    private String marketRegime(AiAnalysisOutcome outcome) {
        if (safe(outcome.pctChange).compareTo(new BigDecimal("1")) >= 0) {
            return "强势验证";
        }
        if (safe(outcome.pctChange).compareTo(new BigDecimal("-1")) <= 0) {
            return "弱势验证";
        }
        return "震荡验证";
    }

    private BigDecimal directionHitRate(List<AiAnalysisOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long hit = outcomes.stream().filter(item -> item.directionCorrect != null && item.directionCorrect == 1).count();
        return divide(new BigDecimal(hit * 100), new BigDecimal(outcomes.size()));
    }

    private String text(JsonNode node, String... names) {
        if (node == null || !node.isObject()) {
            return "";
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                if (value.isTextual() || value.isNumber() || value.isBoolean()) {
                    return value.asText();
                }
                return value.toString();
            }
        }
        return "";
    }

    private BigDecimal decimal(JsonNode node, String... names) {
        String value = text(node, names);
        if (value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace("%", "").trim()).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
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

    private static String normalizeText(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value != null) {
                builder.append(value).append(' ');
            }
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(new BigDecimal("100")).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal avg(List<BigDecimal> values) {
        List<BigDecimal> clean = values.stream().filter(item -> item != null).toList();
        if (clean.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = clean.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(new BigDecimal(clean.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value.compareTo(min) < 0) {
            return min;
        }
        if (value.compareTo(max) > 0) {
            return max;
        }
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String percent(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private static String signedPercent(BigDecimal value) {
        BigDecimal safeValue = safe(value).setScale(2, RoundingMode.HALF_UP);
        return (safeValue.compareTo(BigDecimal.ZERO) > 0 ? "+" : "") + safeValue + "%";
    }

    private static String directionText(String direction) {
        return switch (direction == null ? "" : direction) {
            case "UP" -> "看涨";
            case "DOWN" -> "看跌";
            case "SIDEWAYS" -> "震荡";
            default -> "未知";
        };
    }

    private record FactorRule(
            String code,
            String name,
            String group,
            String direction,
            BigDecimal initialWeight,
            List<String> keywords,
            String reason
    ) {
    }
}
