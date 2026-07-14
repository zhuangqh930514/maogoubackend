package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiAnalysisDecision;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiDailyInsightItem;
import com.maogou.stock.domain.entity.research.AiFactorPerformance;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.mapper.AiAnalysisDecisionMapper;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.research.AiFactorPerformanceMapper;
import com.maogou.stock.mapper.research.AiFactorValueMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.service.research.AiDailyDecisionProjector;
import com.maogou.stock.service.research.AiResearchContract;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiDailyDecisionProjectorImpl implements AiDailyDecisionProjector {

    private static final int PRIMARY_HORIZON_DAYS = 3;
    private static final List<String> USER_FACING_INFERENCE_MODES = List.of("RULE_BASELINE", "CHAMPION");

    private final AiSampleMapper sampleMapper;
    private final AiPredictionMapper predictionMapper;
    private final AiSampleLabelMapper labelMapper;
    private final AiFactorValueMapper factorMapper;
    private final AiFactorPerformanceMapper performanceMapper;
    private final AiAnalysisReportMapper reportMapper;
    private final AiAnalysisDecisionMapper decisionMapper;
    private final ObjectMapper objectMapper;

    public AiDailyDecisionProjectorImpl(
            AiSampleMapper sampleMapper,
            AiPredictionMapper predictionMapper,
            AiSampleLabelMapper labelMapper,
            AiFactorValueMapper factorMapper,
            AiFactorPerformanceMapper performanceMapper,
            AiAnalysisReportMapper reportMapper,
            AiAnalysisDecisionMapper decisionMapper,
            ObjectMapper objectMapper
    ) {
        this.sampleMapper = sampleMapper;
        this.predictionMapper = predictionMapper;
        this.labelMapper = labelMapper;
        this.factorMapper = factorMapper;
        this.performanceMapper = performanceMapper;
        this.reportMapper = reportMapper;
        this.decisionMapper = decisionMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AiDailyInsightItem> project(
            Long userId,
            LocalDate tradeDate,
            Long snapshotId,
            LocalDateTime now
    ) {
        List<AiSample> samples = sampleMapper.selectList(new QueryWrapper<AiSample>()
                .eq("user_id", userId)
                .eq("trade_date", tradeDate)
                .eq("universe_code", "WATCHLIST")
                .orderByDesc("as_of_time")
                .last("LIMIT 300"));
        if (samples == null || samples.isEmpty()) {
            return List.of();
        }
        List<Long> sampleIds = samples.stream().map(item -> item.id).filter(Objects::nonNull).toList();
        Map<Long, AiPrediction> predictions = latestPredictions(userId, tradeDate, sampleIds);
        if (predictions.isEmpty()) {
            return List.of();
        }
        Map<String, AiAnalysisReport> reports = latestReports(userId, tradeDate);
        Map<Long, AiAnalysisDecision> decisions = decisions(reports.values().stream()
                .map(item -> item.id).filter(Objects::nonNull).toList());
        Map<Long, List<FactorReason>> reasonsBySample = factorReasons(userId, sampleIds, predictions);
        Map<String, History> stockHistory = stockHistory(userId,
                samples.stream().map(item -> item.stockCode).filter(Objects::nonNull).distinct().toList());
        Map<String, History> factorHistory = factorHistory(userId,
                reasonsBySample.values().stream().flatMap(List::stream)
                        .map(FactorReason::factorCode).filter(Objects::nonNull).distinct().toList());

        List<AiDailyInsightItem> items = new ArrayList<>();
        for (AiSample sample : samples) {
            AiPrediction prediction = predictions.get(sample.id);
            if (prediction == null) {
                continue;
            }
            AiAnalysisReport report = reports.get(sample.stockCode);
            AiAnalysisDecision aiDecision = report == null ? null : decisions.get(report.id);
            Freshness freshness = freshness(sample, report, tradeDate, now);
            List<FactorReason> reasons = reasonsBySample.getOrDefault(sample.id, List.of());
            History stock = stockHistory.getOrDefault(sample.stockCode, History.empty());
            History factor = effectiveFactorHistory(reasons, factorHistory);
            AiDailyInsightScoring.Decision classified = applyAvailabilityGate(sample, prediction,
                    AiDailyInsightScoring.classify(
                    new AiDailyInsightScoring.Input(
                            prediction.score,
                            prediction.riskScore,
                            sample.dataQualityScore,
                            freshness.score,
                            aiDecision == null ? "" : aiDecision.decision,
                            aiDecision == null ? BigDecimal.ZERO : aiDecision.confidence,
                            stock.hitRate,
                            stock.sampleCount,
                            factor.hitRate,
                            factor.sampleCount)));
            items.add(item(snapshotId, userId, tradeDate, sample, prediction, report, aiDecision,
                    reasons, freshness, classified, now));
        }
        return items.stream()
                .sorted(AiDailyInsightOrdering.comparator())
                .toList();
    }

    static AiDailyInsightScoring.Decision applyAvailabilityGate(
            AiSample sample,
            AiPrediction prediction,
            AiDailyInsightScoring.Decision classified
    ) {
        boolean unavailable = prediction != null && "UNAVAILABLE".equalsIgnoreCase(prediction.action)
                || sample != null && ("UNAVAILABLE".equalsIgnoreCase(sample.qualityStatus)
                || "UNAVAILABLE".equalsIgnoreCase(sample.tradableStatus));
        if (!unavailable) {
            return classified;
        }
        BigDecimal score = prediction == null || prediction.score == null
                ? BigDecimal.ZERO : prediction.score.setScale(2, RoundingMode.HALF_UP);
        return new AiDailyInsightScoring.Decision(
                "UNAVAILABLE", "WATCH", score, "DATA_UNAVAILABLE",
                classified == null ? BigDecimal.ZERO : classified.effectiveHitRate(),
                classified == null ? 0 : classified.effectiveSampleCount(),
                classified == null ? "LOW_SAMPLE" : classified.historySource());
    }

    private Map<Long, AiPrediction> latestPredictions(Long userId, LocalDate tradeDate, List<Long> sampleIds) {
        List<AiPrediction> values = predictionMapper.selectList(new QueryWrapper<AiPrediction>()
                .eq("user_id", userId)
                .eq("trade_date", tradeDate)
                .in("sample_id", sampleIds)
                .eq("horizon_days", PRIMARY_HORIZON_DAYS)
                .in("inference_mode", USER_FACING_INFERENCE_MODES)
                .orderByDesc("predicted_at")
                .orderByAsc("rank_no"));
        Map<Long, AiPrediction> result = new LinkedHashMap<>();
        for (AiPrediction value : values) {
            if (!Objects.equals(value.horizonDays, PRIMARY_HORIZON_DAYS)
                    || !USER_FACING_INFERENCE_MODES.contains(value.inferenceMode)) {
                continue;
            }
            result.putIfAbsent(value.sampleId, value);
        }
        return result;
    }

    private Map<String, AiAnalysisReport> latestReports(Long userId, LocalDate tradeDate) {
        List<AiAnalysisReport> values = reportMapper.selectList(new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", userId)
                .eq("report_date", tradeDate)
                .eq("deleted", 0)
                .orderByDesc("generated_at"));
        Map<String, AiAnalysisReport> result = new LinkedHashMap<>();
        for (AiAnalysisReport value : values) {
            result.putIfAbsent(value.stockCode, value);
        }
        return result;
    }

    private Map<Long, AiAnalysisDecision> decisions(List<Long> reportIds) {
        if (reportIds.isEmpty()) {
            return Map.of();
        }
        return decisionMapper.selectList(new QueryWrapper<AiAnalysisDecision>().in("report_id", reportIds))
                .stream().collect(Collectors.toMap(item -> item.reportId, Function.identity(), (left, right) -> left));
    }

    private Map<Long, List<FactorReason>> factorReasons(
            Long userId,
            List<Long> sampleIds,
            Map<Long, AiPrediction> predictions
    ) {
        Map<Long, Map<String, AiFactorValue>> evidence = factorMapper.selectList(
                        new QueryWrapper<AiFactorValue>()
                                .eq("user_id", userId)
                                .eq("factor_version", AiResearchContract.FACTOR_VERSION)
                                .in("sample_id", sampleIds))
                .stream()
                .filter(item -> Objects.equals(item.factorVersion, AiResearchContract.FACTOR_VERSION))
                .collect(Collectors.groupingBy(
                        item -> item.sampleId,
                        Collectors.toMap(item -> item.factorCode, Function.identity(), (left, right) -> left)));
        Map<Long, List<FactorReason>> result = new LinkedHashMap<>();
        for (Map.Entry<Long, AiPrediction> entry : predictions.entrySet()) {
            List<FactorReason> parsed = parseReasons(entry.getValue().reasonJson);
            Map<String, AiFactorValue> byCode = evidence.getOrDefault(entry.getKey(), Map.of());
            result.put(entry.getKey(), parsed.stream().map(reason -> {
                AiFactorValue value = byCode.get(reason.factorCode);
                return new FactorReason(
                        reason.factorCode,
                        reason.factorName,
                        value == null ? reason.direction : value.direction,
                        reason.contribution,
                        value == null || value.evidence == null ? "" : value.evidence);
            }).limit(5).toList());
        }
        return result;
    }

    private Map<String, History> stockHistory(Long userId, List<String> stockCodes) {
        if (stockCodes.isEmpty()) {
            return Map.of();
        }
        List<AiSampleLabel> labels = labelMapper.selectList(new QueryWrapper<AiSampleLabel>()
                .eq("user_id", userId)
                .eq("label_status", "VERIFIED")
                .eq("label_version", AiResearchContract.LABEL_VERSION)
                .eq("horizon_days", PRIMARY_HORIZON_DAYS)
                .in("stock_code", stockCodes)
                .orderByDesc("verified_at")
                .last("LIMIT 1200"));
        Map<String, List<AiSampleLabel>> grouped = labels.stream()
                .collect(Collectors.groupingBy(item -> item.stockCode));
        Map<String, History> result = new LinkedHashMap<>();
        grouped.forEach((code, values) -> {
            int count = values.size();
            long hits = values.stream().filter(item -> item.netReturn != null && item.netReturn.signum() > 0).count();
            result.put(code, new History(percent(hits, count), count));
        });
        return result;
    }

    private Map<String, History> factorHistory(Long userId, List<String> factorCodes) {
        if (factorCodes.isEmpty()) {
            return Map.of();
        }
        List<AiFactorPerformance> values = performanceMapper.selectList(
                new QueryWrapper<AiFactorPerformance>()
                        .eq("user_id", userId)
                        .eq("factor_version", AiResearchContract.FACTOR_VERSION)
                        .eq("horizon_days", PRIMARY_HORIZON_DAYS)
                        .in("factor_code", factorCodes)
                        .orderByDesc("evaluated_at"));
        Map<String, History> result = new LinkedHashMap<>();
        for (AiFactorPerformance value : values) {
            result.putIfAbsent(value.factorCode, new History(
                    normalizeRate(value.successRate), value.sampleCount == null ? 0 : value.sampleCount));
        }
        return result;
    }

    private static History effectiveFactorHistory(List<FactorReason> reasons, Map<String, History> byCode) {
        List<History> histories = reasons.stream().map(item -> byCode.get(item.factorCode))
                .filter(Objects::nonNull).toList();
        int count = histories.stream().mapToInt(item -> item.sampleCount).sum();
        if (count == 0) {
            return History.empty();
        }
        BigDecimal weighted = histories.stream()
                .map(item -> item.hitRate.multiply(BigDecimal.valueOf(item.sampleCount)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new History(weighted.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP), count);
    }

    private AiDailyInsightItem item(
            Long snapshotId,
            Long userId,
            LocalDate tradeDate,
            AiSample sample,
            AiPrediction prediction,
            AiAnalysisReport report,
            AiAnalysisDecision aiDecision,
            List<FactorReason> reasons,
            Freshness freshness,
            AiDailyInsightScoring.Decision classified,
            LocalDateTime now
    ) {
        AiDailyInsightItem item = new AiDailyInsightItem();
        item.snapshotId = snapshotId;
        item.userId = userId;
        item.tradeDate = tradeDate;
        item.stockCode = sample.stockCode;
        item.stockName = sample.stockName == null || sample.stockName.isBlank() ? sample.stockCode : sample.stockName;
        item.finalAction = classified.finalAction();
        item.actionBucket = classified.actionBucket();
        item.compositeScore = classified.compositeScore();
        item.systemScore = scale(prediction.score);
        item.aiDecision = aiDecision == null ? "WATCH" : aiDecision.decision;
        item.aiConfidence = scale(aiDecision == null ? BigDecimal.ZERO : aiDecision.confidence);
        item.targetDirection = aiDecision == null || aiDecision.targetDirection == null
                ? prediction.targetDirection : aiDecision.targetDirection;
        item.riskLevel = aiDecision == null || aiDecision.riskLevel == null ? "UNKNOWN" : aiDecision.riskLevel;
        item.riskScore = scale(prediction.riskScore);
        item.dataQualityScore = scale(sample.dataQualityScore);
        item.freshnessScore = freshness.score;
        item.freshnessStatus = freshness.status;
        item.freshnessMessage = freshness.message;
        item.historicalHitRate = classified.effectiveHitRate();
        item.historicalSampleCount = classified.effectiveSampleCount();
        item.confidenceLevel = classified.confidenceLevel();
        item.triggerFactorsJson = writeJson(reasons);
        item.reasonSummary = reasonSummary(reasons, classified, freshness);
        item.reportId = report == null ? null : report.id;
        item.predictionId = prediction.id;
        item.sampleId = sample.id;
        item.reportGeneratedAt = report == null ? null : report.generatedAt;
        item.sampleTime = sample.asOfTime;
        item.createdAt = now;
        item.updatedAt = now;
        return item;
    }

    private Freshness freshness(AiSample sample, AiAnalysisReport report, LocalDate tradeDate, LocalDateTime now) {
        LocalDate lastKline = lastKlineDate(sample.featureSnapshot);
        boolean sampleFresh = sample.asOfTime != null && !sample.asOfTime.isBefore(now.minusHours(30));
        boolean reportFresh = report != null && report.generatedAt != null
                && !report.generatedAt.toLocalDate().isBefore(tradeDate);
        boolean klineFresh = lastKline != null && !lastKline.isBefore(tradeDate);
        if (sampleFresh && reportFresh && klineFresh && safe(sample.dataQualityScore).compareTo(new BigDecimal("70")) >= 0) {
            return new Freshness(new BigDecimal("100"), "FRESH", "V2 样本、预测、报告和K线均为当前交易日数据");
        }
        if ((sampleFresh || reportFresh) && safe(sample.dataQualityScore).compareTo(new BigDecimal("60")) >= 0) {
            return new Freshness(new BigDecimal("70"), "PARTIAL", "V2 数据部分新鲜，最近K线 " + lastKline);
        }
        return new Freshness(new BigDecimal("30"), "STALE", "V2 数据新鲜度不足，结果已降级为观察");
    }

    private LocalDate lastKlineDate(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        try {
            JsonNode kline = objectMapper.readTree(snapshot).path("kline");
            LocalDate latest = null;
            if (kline.isArray()) {
                for (JsonNode point : kline) {
                    String raw = point.path("tradeDate").asText("");
                    if (!raw.isBlank()) {
                        LocalDate value = LocalDate.parse(raw);
                        if (latest == null || value.isAfter(latest)) {
                            latest = value;
                        }
                    }
                }
            }
            return latest;
        } catch (Exception exception) {
            return null;
        }
    }

    private List<FactorReason> parseReasons(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode factors = root.isArray() ? root : root.path("factors");
            if (!factors.isArray()) {
                return List.of();
            }
            List<FactorReason> result = new ArrayList<>();
            for (JsonNode factor : factors) {
                String code = factor.path("factorCode").asText("");
                if (!code.isBlank()) {
                    result.add(new FactorReason(
                            code,
                            factor.path("factorName").asText(code),
                            factor.path("direction").asText(""),
                            decimal(factor, "contribution"),
                            factor.path("evidence").asText("")));
                }
            }
            return result;
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String writeJson(List<FactorReason> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons);
        } catch (JsonProcessingException exception) {
            return "[]";
        }
    }

    private static String reasonSummary(
            List<FactorReason> reasons,
            AiDailyInsightScoring.Decision decision,
            Freshness freshness
    ) {
        String factors = reasons.stream().map(FactorReason::factorName).limit(3)
                .collect(Collectors.joining("、"));
        if (factors.isBlank()) {
            factors = "暂无有效触发因子";
        }
        return "%s；V2历史命中率 %s%%，样本 %s；%s。".formatted(
                factors, decision.effectiveHitRate(), decision.effectiveSampleCount(), freshness.message);
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        try {
            return new BigDecimal(node.path(field).asText("0"));
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal percent(long hits, int count) {
        return count == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(hits * 100L)
                .divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal normalizeRate(BigDecimal value) {
        BigDecimal safe = safe(value);
        return safe.compareTo(BigDecimal.ONE) <= 0 ? safe.multiply(new BigDecimal("100")) : safe;
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private record History(BigDecimal hitRate, int sampleCount) {
        private static History empty() {
            return new History(BigDecimal.ZERO, 0);
        }
    }

    private record Freshness(BigDecimal score, String status, String message) {
    }

    private record FactorReason(
            String factorCode,
            String factorName,
            String direction,
            BigDecimal contribution,
            String evidence
    ) {
    }
}
