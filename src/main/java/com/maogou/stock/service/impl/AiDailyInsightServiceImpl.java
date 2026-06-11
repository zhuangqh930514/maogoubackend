package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiAnalysisDecision;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.domain.entity.AiDailyInsightItem;
import com.maogou.stock.domain.entity.AiDailyInsightSnapshot;
import com.maogou.stock.domain.entity.AiFactorStat;
import com.maogou.stock.domain.entity.AiFactorValue;
import com.maogou.stock.domain.entity.AiLearningJobLog;
import com.maogou.stock.domain.entity.AiPredictionLabel;
import com.maogou.stock.domain.entity.AiPredictionResult;
import com.maogou.stock.domain.entity.AiPredictionSample;
import com.maogou.stock.dto.ai.AiDailyInsightPayloads;
import com.maogou.stock.mapper.AiAnalysisDecisionMapper;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.AiDailyInsightItemMapper;
import com.maogou.stock.mapper.AiDailyInsightSnapshotMapper;
import com.maogou.stock.mapper.AiFactorStatMapper;
import com.maogou.stock.mapper.AiFactorValueMapper;
import com.maogou.stock.mapper.AiLearningJobLogMapper;
import com.maogou.stock.mapper.AiPredictionLabelMapper;
import com.maogou.stock.mapper.AiPredictionResultMapper;
import com.maogou.stock.mapper.AiPredictionSampleMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiDailyInsightService;
import com.maogou.stock.service.TradingCalendarService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiDailyInsightServiceImpl implements AiDailyInsightService {

    private static final String SCHEMA_MESSAGE = "每日 AI 投研结果中心表未初始化，请先执行 backend/src/main/resources/db/20260611_daily_insight_center.sql。";
    private static final String JOB_TYPE_AUTO_CLOSE = "AUTO_CLOSE_PIPELINE";
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final AiDailyInsightSnapshotMapper snapshotMapper;
    private final AiDailyInsightItemMapper itemMapper;
    private final AiPredictionSampleMapper sampleMapper;
    private final AiPredictionResultMapper predictionMapper;
    private final AiPredictionLabelMapper labelMapper;
    private final AiAnalysisReportMapper reportMapper;
    private final AiAnalysisDecisionMapper decisionMapper;
    private final AiFactorValueMapper factorValueMapper;
    private final AiFactorStatMapper factorStatMapper;
    private final AiLearningJobLogMapper jobLogMapper;
    private final TradingCalendarService tradingCalendarService;
    private final ObjectMapper objectMapper;

    public AiDailyInsightServiceImpl(
            AiDailyInsightSnapshotMapper snapshotMapper,
            AiDailyInsightItemMapper itemMapper,
            AiPredictionSampleMapper sampleMapper,
            AiPredictionResultMapper predictionMapper,
            AiPredictionLabelMapper labelMapper,
            AiAnalysisReportMapper reportMapper,
            AiAnalysisDecisionMapper decisionMapper,
            AiFactorValueMapper factorValueMapper,
            AiFactorStatMapper factorStatMapper,
            AiLearningJobLogMapper jobLogMapper,
            TradingCalendarService tradingCalendarService,
            ObjectMapper objectMapper
    ) {
        this.snapshotMapper = snapshotMapper;
        this.itemMapper = itemMapper;
        this.sampleMapper = sampleMapper;
        this.predictionMapper = predictionMapper;
        this.labelMapper = labelMapper;
        this.reportMapper = reportMapper;
        this.decisionMapper = decisionMapper;
        this.factorValueMapper = factorValueMapper;
        this.factorStatMapper = factorStatMapper;
        this.jobLogMapper = jobLogMapper;
        this.tradingCalendarService = tradingCalendarService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiDailyInsightPayloads.DailyInsightResponse today() {
        try {
            ensureTablesReady();
            Long userId = AuthContext.currentUserIdOrDefault();
            LocalDate tradeDate = targetTradeDate();
            AiDailyInsightSnapshot snapshot = findSnapshot(userId, tradeDate);
            if (snapshot == null) {
                return emptyResponse(tradeDate, "今日尚未生成每日 AI 投研结果，请等待自动收盘流水线或手动重建。");
            }
            List<AiDailyInsightItem> items = itemMapper.selectList(new QueryWrapper<AiDailyInsightItem>()
                    .eq("snapshot_id", snapshot.id)
                    .orderByDesc("composite_score")
                    .orderByAsc("risk_score"));
            return response(snapshot, items, "每日 AI 投研结果已加载");
        } catch (DataAccessException ex) {
            return new AiDailyInsightPayloads.DailyInsightResponse(
                    false,
                    false,
                    SCHEMA_MESSAGE,
                    AiDailyInsightPayloads.SnapshotSummary.empty(targetTradeDate(), SCHEMA_MESSAGE),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }
    }

    @Override
    @Transactional
    public AiDailyInsightPayloads.DailyInsightResponse rebuildToday() {
        return rebuildForCurrentUser("MANUAL", "用户手动重建每日 AI 投研结果");
    }

    @Override
    @Transactional
    public AiDailyInsightPayloads.DailyInsightResponse rebuildForCurrentUser(String pipelineStatus, String pipelineMessage) {
        ensureTablesReady();
        Long userId = AuthContext.currentUserIdOrDefault();
        LocalDate tradeDate = targetTradeDate();
        AiDailyInsightSnapshot snapshot = findSnapshot(userId, tradeDate);
        LocalDateTime now = LocalDateTime.now();
        if (snapshot == null) {
            snapshot = new AiDailyInsightSnapshot();
            snapshot.userId = userId;
            snapshot.tradeDate = tradeDate;
            snapshot.createdAt = now;
        }
        snapshot.generatedAt = now;
        snapshot.pipelineStatus = blankToDefault(pipelineStatus, "MANUAL");
        snapshot.pipelineMessage = blankToDefault(pipelineMessage, "每日 AI 投研结果已重建");
        snapshot.updatedAt = now;
        saveSnapshot(snapshot);

        itemMapper.delete(new QueryWrapper<AiDailyInsightItem>().eq("snapshot_id", snapshot.id));
        List<AiDailyInsightItem> items = buildItems(userId, tradeDate, snapshot.id, now);
        for (AiDailyInsightItem item : items) {
            itemMapper.insert(item);
        }
        updateSnapshotMetrics(snapshot, items);
        snapshotMapper.updateById(snapshot);
        return response(snapshot, items, items.isEmpty() ? "未找到今日可汇总的 AI 样本，请先执行收盘学习流水线。" : "每日 AI 投研结果已重建");
    }

    private List<AiDailyInsightItem> buildItems(Long userId, LocalDate tradeDate, Long snapshotId, LocalDateTime now) {
        List<AiPredictionSample> samples = sampleMapper.selectList(new QueryWrapper<AiPredictionSample>()
                .eq("user_id", userId)
                .eq("trade_date", tradeDate)
                .eq("universe_code", "WATCHLIST")
                .orderByDesc("sample_time")
                .last("LIMIT 300"));
        if (samples.isEmpty()) {
            return List.of();
        }
        Map<Long, AiPredictionResult> predictionsBySampleId = latestPredictions(samples);
        Map<Long, AiAnalysisReport> reportsById = reportsByIds(predictionsBySampleId.values().stream()
                .map(item -> item.reportId)
                .filter(Objects::nonNull)
                .toList());
        Map<Long, AiAnalysisDecision> decisionsByReportId = decisionsByReportIds(reportsById.keySet().stream().toList());
        List<AiFactorStat> factorStats = factorStatMapper.selectList(new QueryWrapper<AiFactorStat>()
                .eq("user_id", userId)
                .orderByDesc("sample_count")
                .orderByDesc("weight_score"));
        Map<String, List<AiFactorStat>> factorStatsByCode = factorStats.stream()
                .collect(Collectors.groupingBy(item -> item.factorCode, LinkedHashMap::new, Collectors.toList()));
        Map<String, StockHistory> historyByStock = stockHistory(userId, samples.stream().map(item -> item.stockCode).distinct().toList());
        List<AiDailyInsightItem> items = new ArrayList<>();
        for (AiPredictionSample sample : samples) {
            AiPredictionResult prediction = predictionsBySampleId.get(sample.id);
            if (prediction == null) {
                continue;
            }
            AiAnalysisReport report = resolveReport(userId, tradeDate, sample, prediction, reportsById);
            AiAnalysisDecision decision = report == null ? null : decisionsByReportId.get(report.id);
            List<FactorReason> factors = triggerFactors(sample.id, prediction.reasonJson);
            FactorHistory factorHistory = factorHistory(factors, factorStatsByCode);
            StockHistory stockHistory = historyByStock.getOrDefault(sample.stockCode, StockHistory.empty());
            Freshness freshness = freshness(sample, report, tradeDate, now);
            AiDailyInsightScoring.Decision classified = AiDailyInsightScoring.classify(new AiDailyInsightScoring.Input(
                    prediction.score,
                    prediction.riskScore,
                    sample.dataQualityScore,
                    freshness.score(),
                    decision == null ? "" : decision.decision,
                    decision == null ? BigDecimal.ZERO : decision.confidence,
                    stockHistory.hitRate(),
                    stockHistory.sampleCount(),
                    factorHistory.hitRate(),
                    factorHistory.sampleCount()
            ));
            items.add(buildItem(snapshotId, userId, tradeDate, sample, prediction, report, decision, factors, freshness, classified, now));
        }
        return items.stream()
                .sorted(Comparator.comparing((AiDailyInsightItem item) -> bucketRank(item.actionBucket))
                        .thenComparing((AiDailyInsightItem item) -> safe(item.compositeScore)).reversed()
                        .thenComparing(item -> safe(item.riskScore)))
                .toList();
    }

    private AiDailyInsightItem buildItem(
            Long snapshotId,
            Long userId,
            LocalDate tradeDate,
            AiPredictionSample sample,
            AiPredictionResult prediction,
            AiAnalysisReport report,
            AiAnalysisDecision decision,
            List<FactorReason> factors,
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
        item.aiDecision = decision == null || decision.decision == null || decision.decision.isBlank() ? "WATCH" : decision.decision;
        item.aiConfidence = scale(decision == null ? BigDecimal.ZERO : decision.confidence);
        item.targetDirection = decision == null || decision.targetDirection == null || decision.targetDirection.isBlank()
                ? prediction.targetDirection
                : decision.targetDirection;
        item.riskLevel = decision == null || decision.riskLevel == null || decision.riskLevel.isBlank() ? "UNKNOWN" : decision.riskLevel;
        item.riskScore = scale(prediction.riskScore);
        item.dataQualityScore = scale(sample.dataQualityScore);
        item.freshnessScore = freshness.score();
        item.freshnessStatus = freshness.status();
        item.freshnessMessage = freshness.message();
        item.historicalHitRate = classified.effectiveHitRate();
        item.historicalSampleCount = classified.effectiveSampleCount();
        item.confidenceLevel = classified.confidenceLevel();
        item.triggerFactorsJson = writeJson(factors);
        item.reasonSummary = reasonSummary(item, factors);
        item.reportId = report == null ? null : report.id;
        item.predictionId = prediction.id;
        item.sampleId = sample.id;
        item.reportGeneratedAt = report == null ? null : report.generatedAt;
        item.sampleTime = sample.sampleTime;
        item.createdAt = now;
        item.updatedAt = now;
        return item;
    }

    private void updateSnapshotMetrics(AiDailyInsightSnapshot snapshot, List<AiDailyInsightItem> items) {
        snapshot.itemCount = items.size();
        snapshot.recommendationCount = (int) items.stream().filter(item -> "RECOMMEND".equals(item.actionBucket)).count();
        snapshot.avoidCount = (int) items.stream().filter(item -> "AVOID".equals(item.actionBucket)).count();
        snapshot.watchCount = (int) items.stream().filter(item -> "WATCH".equals(item.actionBucket)).count();
        snapshot.lowSampleCount = (int) items.stream().filter(item -> !"READY".equals(item.confidenceLevel) && !"FACTOR_PROXY".equals(item.confidenceLevel)).count();
        snapshot.dataQualityScore = avg(items.stream().map(item -> item.dataQualityScore).toList());
        snapshot.overallHitRate = avg(items.stream().map(item -> item.historicalHitRate).toList());
        snapshot.freshnessStatus = aggregateFreshness(items);
        snapshot.latestReportAt = items.stream()
                .map(item -> item.reportGeneratedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        snapshot.latestSampleAt = items.stream()
                .map(item -> item.sampleTime)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        AiLearningJobLog latestJob = latestAutoCloseJobLog(snapshot.userId);
        snapshot.latestJobLogId = latestJob == null ? null : latestJob.id;
        if (items.isEmpty()) {
            snapshot.freshnessStatus = "EMPTY";
        }
    }

    private AiDailyInsightPayloads.DailyInsightResponse response(AiDailyInsightSnapshot snapshot, List<AiDailyInsightItem> items, String message) {
        List<AiDailyInsightPayloads.InsightItem> recommendations = items.stream()
                .filter(item -> "RECOMMEND".equals(item.actionBucket))
                .sorted(Comparator.comparing((AiDailyInsightItem item) -> safe(item.compositeScore)).reversed())
                .map(AiDailyInsightPayloads.InsightItem::from)
                .toList();
        List<AiDailyInsightPayloads.InsightItem> watches = items.stream()
                .filter(item -> "WATCH".equals(item.actionBucket))
                .sorted(Comparator.comparing((AiDailyInsightItem item) -> safe(item.compositeScore)).reversed())
                .map(AiDailyInsightPayloads.InsightItem::from)
                .toList();
        List<AiDailyInsightPayloads.InsightItem> avoids = items.stream()
                .filter(item -> "AVOID".equals(item.actionBucket))
                .sorted(Comparator.comparing((AiDailyInsightItem item) -> safe(item.riskScore)).reversed())
                .map(AiDailyInsightPayloads.InsightItem::from)
                .toList();
        return new AiDailyInsightPayloads.DailyInsightResponse(
                true,
                true,
                message,
                AiDailyInsightPayloads.SnapshotSummary.from(snapshot),
                recommendations,
                watches,
                avoids,
                latestJobLogs(snapshot.userId, 8)
        );
    }

    private AiDailyInsightPayloads.DailyInsightResponse emptyResponse(LocalDate tradeDate, String message) {
        return new AiDailyInsightPayloads.DailyInsightResponse(
                true,
                false,
                message,
                AiDailyInsightPayloads.SnapshotSummary.empty(tradeDate, message),
                List.of(),
                List.of(),
                List.of(),
                latestJobLogs(AuthContext.currentUserIdOrDefault(), 8)
        );
    }

    private Map<Long, AiPredictionResult> latestPredictions(List<AiPredictionSample> samples) {
        List<Long> sampleIds = samples.stream().map(item -> item.id).filter(Objects::nonNull).toList();
        if (sampleIds.isEmpty()) {
            return Map.of();
        }
        List<AiPredictionResult> rows = predictionMapper.selectList(new QueryWrapper<AiPredictionResult>()
                .in("sample_id", sampleIds)
                .orderByDesc("updated_at")
                .orderByDesc("score"));
        Map<Long, AiPredictionResult> result = new LinkedHashMap<>();
        for (AiPredictionResult row : rows) {
            result.putIfAbsent(row.sampleId, row);
        }
        return result;
    }

    private Map<Long, AiAnalysisReport> reportsByIds(List<Long> ids) {
        List<Long> clean = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) {
            return Map.of();
        }
        return reportMapper.selectBatchIds(clean).stream()
                .collect(Collectors.toMap(item -> item.id, Function.identity(), (left, right) -> left));
    }

    private Map<Long, AiAnalysisDecision> decisionsByReportIds(List<Long> reportIds) {
        List<Long> clean = reportIds.stream().filter(Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) {
            return Map.of();
        }
        return decisionMapper.selectList(new QueryWrapper<AiAnalysisDecision>()
                        .in("report_id", clean))
                .stream()
                .collect(Collectors.toMap(item -> item.reportId, Function.identity(), (left, right) -> left));
    }

    private AiAnalysisReport resolveReport(Long userId, LocalDate tradeDate, AiPredictionSample sample, AiPredictionResult prediction, Map<Long, AiAnalysisReport> reportsById) {
        if (prediction.reportId != null && reportsById.containsKey(prediction.reportId)) {
            return reportsById.get(prediction.reportId);
        }
        return reportMapper.selectOne(new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", userId)
                .eq("stock_code", sample.stockCode)
                .eq("report_date", tradeDate)
                .eq("deleted", 0)
                .orderByDesc("generated_at")
                .last("LIMIT 1"));
    }

    private Map<String, StockHistory> stockHistory(Long userId, List<String> stockCodes) {
        if (stockCodes.isEmpty()) {
            return Map.of();
        }
        List<AiPredictionLabel> labels = labelMapper.selectList(new QueryWrapper<AiPredictionLabel>()
                .eq("user_id", userId)
                .in("stock_code", stockCodes)
                .orderByDesc("evaluated_at")
                .last("LIMIT 1200"));
        Map<String, List<AiPredictionLabel>> grouped = labels.stream().collect(Collectors.groupingBy(item -> item.stockCode));
        Map<String, StockHistory> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<AiPredictionLabel>> entry : grouped.entrySet()) {
            List<AiPredictionLabel> rows = entry.getValue();
            int count = rows.size();
            int hitCount = (int) rows.stream().filter(item -> item.hitTarget != null && item.hitTarget == 1).count();
            result.put(entry.getKey(), new StockHistory(count == 0 ? ZERO : divide(new BigDecimal(hitCount * 100), new BigDecimal(count)), count));
        }
        return result;
    }

    private List<FactorReason> triggerFactors(Long sampleId, String reasonJson) {
        List<FactorReason> parsed = parseReasonFactors(reasonJson);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        return factorValueMapper.selectList(new QueryWrapper<AiFactorValue>()
                        .eq("sample_id", sampleId)
                        .eq("hit", 1)
                        .orderByDesc("normalized_value")
                        .last("LIMIT 5"))
                .stream()
                .map(item -> new FactorReason(
                        item.factorCode,
                        item.factorCode,
                        item.direction,
                        item.normalizedValue,
                        item.evidence == null ? "" : item.evidence
                ))
                .toList();
    }

    private List<FactorReason> parseReasonFactors(String reasonJson) {
        if (reasonJson == null || reasonJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(reasonJson);
            if (!root.isArray()) {
                return List.of();
            }
            List<FactorReason> reasons = new ArrayList<>();
            for (JsonNode node : root) {
                String code = text(node, "factorCode");
                if (code.isBlank()) {
                    continue;
                }
                reasons.add(new FactorReason(
                        code,
                        text(node, "factorName"),
                        text(node, "direction"),
                        decimal(node, "contribution"),
                        text(node, "evidence")
                ));
            }
            return reasons.stream().limit(5).toList();
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private FactorHistory factorHistory(List<FactorReason> factors, Map<String, List<AiFactorStat>> factorStatsByCode) {
        List<AiFactorStat> matched = factors.stream()
                .flatMap(item -> factorStatsByCode.getOrDefault(item.factorCode(), List.of()).stream().limit(1))
                .toList();
        if (matched.isEmpty()) {
            return new FactorHistory(ZERO, 0);
        }
        int sampleCount = matched.stream().map(item -> item.sampleCount == null ? 0 : item.sampleCount).reduce(0, Integer::sum);
        BigDecimal weightedHit = ZERO;
        for (AiFactorStat stat : matched) {
            weightedHit = weightedHit.add(safe(stat.successRate).multiply(new BigDecimal(stat.sampleCount == null ? 0 : stat.sampleCount)));
        }
        return new FactorHistory(sampleCount == 0 ? ZERO : divide(weightedHit, new BigDecimal(sampleCount)), sampleCount);
    }

    private Freshness freshness(AiPredictionSample sample, AiAnalysisReport report, LocalDate tradeDate, LocalDateTime now) {
        LocalDate lastKlineDate = lastKlineDate(sample.featureSnapshot);
        boolean sampleFresh = sample.sampleTime != null && !sample.sampleTime.isBefore(now.minusHours(30));
        boolean reportFresh = report != null && report.generatedAt != null && !report.generatedAt.toLocalDate().isBefore(tradeDate);
        boolean klineFresh = lastKlineDate != null && !lastKlineDate.isBefore(tradeDate);
        BigDecimal dataQuality = safe(sample.dataQualityScore);
        if (sampleFresh && reportFresh && klineFresh && dataQuality.compareTo(new BigDecimal("70")) >= 0) {
            return new Freshness(new BigDecimal("100"), "FRESH", "行情、报告、K线和样本均为当前交易日数据");
        }
        if ((sampleFresh || reportFresh) && dataQuality.compareTo(new BigDecimal("60")) >= 0) {
            String klineMessage = lastKlineDate == null ? "未识别K线日期" : "最近K线 " + lastKlineDate;
            return new Freshness(new BigDecimal("70"), "PARTIAL", "数据部分新鲜：" + klineMessage);
        }
        return new Freshness(new BigDecimal("30"), "STALE", "数据新鲜度不足，禁止把该结果当作强推荐");
    }

    private LocalDate lastKlineDate(String featureSnapshot) {
        if (featureSnapshot == null || featureSnapshot.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(featureSnapshot);
            JsonNode value = root.path("lastKline").path("tradeDate");
            if (!value.isTextual()) {
                return null;
            }
            return LocalDate.parse(value.asText());
        } catch (Exception ex) {
            return null;
        }
    }

    private String reasonSummary(AiDailyInsightItem item, List<FactorReason> factors) {
        String factorText = factors.stream()
                .map(factor -> factor.factorName().isBlank() ? factor.factorCode() : factor.factorName())
                .limit(3)
                .collect(Collectors.joining("、"));
        if (factorText.isBlank()) {
            factorText = "暂无有效触发因子";
        }
        return "%s；历史命中率 %s%%，样本 %s；数据状态 %s。".formatted(
                factorText,
                scale(item.historicalHitRate),
                item.historicalSampleCount == null ? 0 : item.historicalSampleCount,
                item.freshnessStatus
        );
    }

    private void saveSnapshot(AiDailyInsightSnapshot snapshot) {
        if (snapshot.id == null) {
            snapshotMapper.insert(snapshot);
        } else {
            snapshotMapper.updateById(snapshot);
        }
    }

    private AiDailyInsightSnapshot findSnapshot(Long userId, LocalDate tradeDate) {
        return snapshotMapper.selectOne(new QueryWrapper<AiDailyInsightSnapshot>()
                .eq("user_id", userId)
                .eq("trade_date", tradeDate)
                .last("LIMIT 1"));
    }

    private LocalDate targetTradeDate() {
        return tradingCalendarService.latestExpectedKlineDate(LocalDateTime.now());
    }

    private AiLearningJobLog latestAutoCloseJobLog(Long userId) {
        return jobLogMapper.selectOne(new QueryWrapper<AiLearningJobLog>()
                .eq("user_id", userId)
                .eq("job_type", JOB_TYPE_AUTO_CLOSE)
                .orderByDesc("started_at")
                .last("LIMIT 1"));
    }

    private List<AiDailyInsightPayloads.JobLogItem> latestJobLogs(Long userId, int limit) {
        return jobLogMapper.selectList(new QueryWrapper<AiLearningJobLog>()
                        .eq("user_id", userId)
                        .orderByDesc("started_at")
                        .last("LIMIT " + Math.max(1, Math.min(limit, 30))))
                .stream()
                .map(item -> new AiDailyInsightPayloads.JobLogItem(
                        item.id,
                        item.jobName,
                        item.jobType,
                        item.status,
                        item.startedAt,
                        item.finishedAt,
                        item.processedCount,
                        item.successCount,
                        item.failedCount,
                        item.errorMessage
                ))
                .toList();
    }

    private void ensureTablesReady() {
        snapshotMapper.selectCount(new QueryWrapper<AiDailyInsightSnapshot>().last("LIMIT 1"));
        itemMapper.selectCount(new QueryWrapper<AiDailyInsightItem>().last("LIMIT 1"));
    }

    private String aggregateFreshness(List<AiDailyInsightItem> items) {
        Set<String> statuses = items.stream().map(item -> item.freshnessStatus).collect(Collectors.toSet());
        if (statuses.contains("STALE")) {
            return "STALE";
        }
        if (statuses.contains("PARTIAL")) {
            return "PARTIAL";
        }
        return items.isEmpty() ? "EMPTY" : "FRESH";
    }

    private int bucketRank(String bucket) {
        return switch (bucket == null ? "" : bucket) {
            case "RECOMMEND" -> 3;
            case "WATCH" -> 2;
            case "AVOID" -> 1;
            default -> 0;
        };
    }

    private BigDecimal avg(List<BigDecimal> values) {
        List<BigDecimal> clean = values.stream().filter(Objects::nonNull).toList();
        if (clean.isEmpty()) {
            return ZERO;
        }
        BigDecimal total = clean.stream().reduce(ZERO, BigDecimal::add);
        return scale(total.divide(new BigDecimal(clean.size()), 6, RoundingMode.HALF_UP));
    }

    private BigDecimal divide(BigDecimal left, BigDecimal right) {
        if (right == null || right.compareTo(ZERO) == 0) {
            return ZERO;
        }
        return left.divide(right, 6, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return ZERO;
        }
        try {
            return new BigDecimal(value.asText("0"));
        } catch (NumberFormatException ex) {
            return ZERO;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "[]";
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record StockHistory(BigDecimal hitRate, int sampleCount) {
        static StockHistory empty() {
            return new StockHistory(ZERO, 0);
        }
    }

    private record FactorHistory(BigDecimal hitRate, int sampleCount) {
    }

    private record FactorReason(String factorCode, String factorName, String direction, BigDecimal contribution, String evidence) {
    }

    private record Freshness(BigDecimal score, String status, String message) {
    }
}
