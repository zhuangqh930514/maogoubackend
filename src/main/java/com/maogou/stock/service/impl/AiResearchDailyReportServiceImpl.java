package com.maogou.stock.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItemPrediction;
import com.maogou.stock.domain.entity.research.AiDailyDecisionSnapshot;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.domain.entity.research.AiResearchDailyReport;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.dto.ai.AiResearchDailyReportPayloads;
import com.maogou.stock.mapper.research.AiDailyDecisionItemMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionItemPredictionMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionSnapshotMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.mapper.research.AiResearchDailyReportMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.TradingCalendarService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
public class AiResearchDailyReportServiceImpl implements AiResearchDailyReportService {

    private final AiResearchDailyReportMapper reportMapper;
    private final AiDailyDecisionSnapshotMapper snapshotMapper;
    private final AiDailyDecisionItemMapper itemMapper;
    private final AiDailyDecisionItemPredictionMapper itemPredictionMapper;
    private final AiPipelineRunMapper pipelineRunMapper;
    private final AiPipelineStepMapper pipelineStepMapper;
    private final AiStrategyReleaseMapper strategyReleaseMapper;
    private final ObjectMapper objectMapper;
    private final TradingCalendarService tradingCalendarService;

    public AiResearchDailyReportServiceImpl(
            AiResearchDailyReportMapper reportMapper,
            AiDailyDecisionSnapshotMapper snapshotMapper,
            AiDailyDecisionItemMapper itemMapper,
            AiDailyDecisionItemPredictionMapper itemPredictionMapper,
            AiPipelineRunMapper pipelineRunMapper,
            AiPipelineStepMapper pipelineStepMapper,
            AiStrategyReleaseMapper strategyReleaseMapper,
            ObjectMapper objectMapper,
            TradingCalendarService tradingCalendarService
    ) {
        this.reportMapper = reportMapper;
        this.snapshotMapper = snapshotMapper;
        this.itemMapper = itemMapper;
        this.itemPredictionMapper = itemPredictionMapper;
        this.pipelineRunMapper = pipelineRunMapper;
        this.pipelineStepMapper = pipelineStepMapper;
        this.strategyReleaseMapper = strategyReleaseMapper;
        this.objectMapper = objectMapper;
        this.tradingCalendarService = tradingCalendarService;
    }

    @Override
    @Transactional
    public ReportView generate(GenerationRequest request) {
        validate(request);
        AiResearchDailyReport existing = reportMapper.selectByIdempotencyForShare(
                request.userId(), request.idempotencyKey());
        if (existing != null) {
            return ReportView.from(toView(existing));
        }

        reportMapper.lockUser(request.userId());
        existing = reportMapper.selectByIdempotencyForShare(request.userId(), request.idempotencyKey());
        if (existing != null) {
            return ReportView.from(toView(existing));
        }

        AiDailyDecisionSnapshot snapshot = requireSnapshot(request);
        List<AiDailyDecisionItem> items = safeList(itemMapper.selectBySnapshot(request.userId(), snapshot.id));
        AiResearchDailyReportPayloads.ReportContent content = buildContent(snapshot, items, request);
        AiResearchDailyReport current = reportMapper.selectCurrentForUpdate(request.userId(), request.tradeDate());
        int nextVersion = value(reportMapper.selectMaxVersionForUpdate(
                request.userId(), request.tradeDate())) + 1;
        AiResearchDailyReport entity = buildEntity(request, snapshot, current, nextVersion, content);
        if (current != null) {
            current.isCurrent = 0;
            current.updatedAt = request.generatedAt();
            reportMapper.updateById(current);
        }
        reportMapper.insert(entity);
        return ReportView.from(toView(entity));
    }

    @Override
    public ReportView latest() {
        AiResearchDailyReport entity = reportMapper.selectLatestCurrent(
                AuthContext.currentUserIdOrDefault(), latestExpectedTradeDate());
        if (entity == null) {
            throw new IllegalArgumentException("暂无投研日报");
        }
        return ReportView.from(toView(entity));
    }

    @Override
    public ReportView latestOrNull(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        AiResearchDailyReport entity = reportMapper.selectLatestCurrent(userId, latestExpectedTradeDate());
        return entity == null ? null : ReportView.from(toView(entity));
    }

    @Override
    public List<AiResearchDailyReportPayloads.ReportListItem> list(int limit) {
        int resolvedLimit = limit <= 0 ? 20 : Math.min(limit, 60);
        return reportMapper.selectRecent(AuthContext.currentUserIdOrDefault(), resolvedLimit).stream()
                .map(AiResearchDailyReportPayloads.ReportListItem::from)
                .toList();
    }

    @Override
    public ReportView detail(Long reportId) {
        if (reportId == null || reportId <= 0) {
            throw new IllegalArgumentException("日报 ID 无效");
        }
        AiResearchDailyReport entity = reportMapper.selectById(reportId);
        if (entity == null || !Objects.equals(entity.userId, AuthContext.currentUserIdOrDefault())) {
            throw new IllegalArgumentException("日报不存在");
        }
        return ReportView.from(toView(entity));
    }

    @Override
    public ReportView rebuildToday() {
        return rebuild(null);
    }

    @Override
    public ReportView rebuild(LocalDate requestedTradeDate) {
        long userId = AuthContext.currentUserIdOrDefault();
        LocalDate maxTradeDate = latestExpectedTradeDate();
        LocalDate tradeDate = requestedTradeDate == null ? maxTradeDate : requestedTradeDate;
        if (tradeDate.isAfter(maxTradeDate)) {
            throw new IllegalArgumentException("不能重建尚未结束的未来交易日报");
        }
        AiDailyDecisionSnapshot snapshot = snapshotMapper.selectCurrent(userId, tradeDate);
        if (snapshot == null) {
            throw new IllegalStateException("该交易日尚无每日决策快照，请先运行用户投影流水线");
        }
        return generate(new GenerationRequest(
                userId,
                tradeDate,
                snapshot.id,
                snapshot.pipelineRunId,
                snapshot.strategyReleaseId,
                snapshot.modelVersionId,
                "REPORT:MANUAL:" + snapshot.id + ":" + System.currentTimeMillis(),
                snapshot.snapshotStatus,
                null,
                "手动重新归档已固化的每日决策快照",
                LocalDateTime.now()));
    }

    private AiDailyDecisionSnapshot requireSnapshot(GenerationRequest request) {
        AiDailyDecisionSnapshot snapshot = request.decisionSnapshotId() == null
                ? snapshotMapper.selectCurrent(request.userId(), request.tradeDate())
                : snapshotMapper.selectById(request.decisionSnapshotId());
        if (snapshot == null) {
            throw new IllegalStateException("投研日报缺少已持久化的每日决策快照");
        }
        if (!Objects.equals(snapshot.userId, request.userId())
                || !Objects.equals(snapshot.tradeDate, request.tradeDate())) {
            throw new IllegalStateException("投研日报不得引用其他用户或其他交易日的决策快照");
        }
        return snapshot;
    }

    private AiResearchDailyReportPayloads.ReportContent buildContent(
            AiDailyDecisionSnapshot snapshot,
            List<AiDailyDecisionItem> items,
            GenerationRequest request
    ) {
        Map<Long, AiDailyDecisionItemPrediction> primaryPredictions = primaryPredictions(snapshot.userId, items);
        List<AiResearchDailyReportPayloads.StockCard> recommendations = mapItems(
                items, "RECOMMEND", primaryPredictions);
        List<AiResearchDailyReportPayloads.StockCard> watches = mapItems(
                items, "CAUTIOUS", primaryPredictions);
        List<AiResearchDailyReportPayloads.StockCard> avoids = mapItems(
                items, "AVOID", primaryPredictions);
        List<AiResearchDailyReportPayloads.StockCard> holdingRisks = mapItems(
                items, "HOLDING_RISK", primaryPredictions);
        List<AiResearchDailyReportPayloads.StockCard> unavailable = mapItems(
                items, "DATA_UNAVAILABLE", primaryPredictions);
        return new AiResearchDailyReportPayloads.ReportContent(
                new AiResearchDailyReportPayloads.Freshness(
                        snapshot.freshnessStatus,
                        zero(snapshot.dataQualityScore),
                        snapshot.generatedAt,
                        snapshot.generatedAt,
                        request.generatedAt()),
                pipelineSummary(snapshot, request),
                strategyPerformance(snapshot),
                recommendations,
                watches,
                avoids,
                holdingRisks,
                unavailable,
                aggregateFactors(items),
                new AiResearchDailyReportPayloads.InsightSummary(
                        snapshot.id,
                        snapshot.generatedAt,
                        snapshot.snapshotStatus,
                        request.pipelineMessage(),
                        snapshot.overallHitRate,
                        items.size(),
                        (int) items.stream().filter(item -> "LOW_SAMPLE".equals(item.confidenceLevel)).count(),
                        snapshot.globalPipelineRunId,
                        snapshot.marketRegime));
    }

    private Map<Long, AiDailyDecisionItemPrediction> primaryPredictions(
            Long userId,
            List<AiDailyDecisionItem> items
    ) {
        List<Long> itemIds = items.stream().map(item -> item.id).filter(Objects::nonNull).toList();
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return safeList(itemPredictionMapper.selectByItems(userId, itemIds)).stream()
                .filter(link -> "PRIMARY_RANKING".equals(link.purpose))
                .collect(Collectors.toMap(link -> link.decisionItemId, Function.identity(),
                        (left, right) -> left, LinkedHashMap::new));
    }

    private List<AiResearchDailyReportPayloads.StockCard> mapItems(
            List<AiDailyDecisionItem> items,
            String category,
            Map<Long, AiDailyDecisionItemPrediction> primaryPredictions
    ) {
        return items.stream()
                .filter(item -> category.equals(item.category))
                .sorted(Comparator.comparing(
                        (AiDailyDecisionItem item) -> zero(item.systemScore), Comparator.reverseOrder())
                        .thenComparing(item -> item.stockCode))
                .map(item -> stockCard(item,
                        item.id == null ? null : primaryPredictions.get(item.id)))
                .toList();
    }

    private AiResearchDailyReportPayloads.StockCard stockCard(
            AiDailyDecisionItem item,
            AiDailyDecisionItemPrediction primaryPrediction
    ) {
        return new AiResearchDailyReportPayloads.StockCard(
                item.stockCode,
                item.stockName,
                item.finalAction,
                item.category,
                item.systemScore,
                item.riskScore,
                item.historicalHitRate,
                item.outOfSampleCount,
                item.confidenceLevel,
                item.freshnessStatus,
                item.reasonSummary,
                item.reportId,
                primaryPrediction == null ? null : primaryPrediction.predictionId,
                item.sampleId,
                item.systemScore,
                item.finalAction,
                BigDecimal.ZERO,
                direction(item.finalAction),
                item.riskLevel,
                item.dataQualityComponent,
                freshnessScore(item.freshnessStatus),
                freshnessMessage(item),
                parseFactors(item.triggerFactorsJson),
                null,
                null,
                item.horizonSignalScore,
                item.factorReliabilityScore,
                item.strategyValidationScore,
                item.riskComponent,
                item.decisionSource,
                item.unavailableReason);
    }

    private AiResearchDailyReportPayloads.PipelineSummary pipelineSummary(
            AiDailyDecisionSnapshot snapshot,
            GenerationRequest request
    ) {
        Long runId = snapshot.globalPipelineRunId != null
                ? snapshot.globalPipelineRunId : request.pipelineRunId();
        AiPipelineRun run = runId == null ? null : pipelineRunMapper.selectById(runId);
        List<AiPipelineStep> steps = runId == null
                ? List.of() : safeList(pipelineStepMapper.selectByRunIdForUpdate(runId));
        return new AiResearchDailyReportPayloads.PipelineSummary(
                runId,
                run == null ? snapshot.snapshotStatus : run.status,
                run == null ? null : run.currentStep,
                request.failedStep(),
                run == null ? 0 : value(run.processedCount),
                run == null ? 0 : value(run.successCount),
                run == null ? 0 : value(run.failedCount),
                request.pipelineMessage() != null ? request.pipelineMessage()
                        : run == null ? null : run.errorMessage,
                steps.stream().map(step -> new AiResearchDailyReportPayloads.PipelineStep(
                        step.stepKey, step.status, value(step.inputCount), value(step.outputCount),
                        step.errorMessage)).toList());
    }

    private AiResearchDailyReportPayloads.StrategyPerformance strategyPerformance(
            AiDailyDecisionSnapshot snapshot
    ) {
        AiStrategyRelease release = strategyReleaseMapper.selectById(snapshot.strategyReleaseId);
        JsonNode metrics = parse(release == null ? null : release.validationMetricsJson);
        return new AiResearchDailyReportPayloads.StrategyPerformance(
                snapshot.strategyReleaseId,
                release == null ? null : release.versionNo,
                release == null ? "策略版本不可用" : release.title,
                snapshot.modelVersionId,
                decimal(metrics, "totalReturn"),
                decimal(metrics, "alpha"),
                decimal(metrics, "maxDrawdown"),
                decimal(metrics, "sharpeRatio"),
                integer(metrics, "sampleCount"),
                decimal(metrics, "hitRate"),
                text(metrics, "driftStatus", "UNASSESSED"));
    }

    private AiResearchDailyReport buildEntity(
            GenerationRequest request,
            AiDailyDecisionSnapshot snapshot,
            AiResearchDailyReport current,
            int nextVersion,
            AiResearchDailyReportPayloads.ReportContent content
    ) {
        AiResearchDailyReport entity = new AiResearchDailyReport();
        entity.userId = request.userId();
        entity.decisionSnapshotId = snapshot.id;
        entity.tradeDate = request.tradeDate();
        entity.reportVersion = nextVersion;
        entity.pipelineRunId = request.pipelineRunId() == null
                ? snapshot.pipelineRunId : request.pipelineRunId();
        entity.strategyReleaseId = snapshot.strategyReleaseId;
        entity.modelVersionId = snapshot.modelVersionId;
        entity.supersedesReportId = current == null ? null : current.id;
        entity.idempotencyKey = request.idempotencyKey();
        entity.isCurrent = 1;
        entity.reportStatus = reportStatus(snapshot, request);
        entity.title = buildTitle(snapshot, entity.reportStatus);
        entity.executiveSummary = buildExecutiveSummary(content, entity.reportStatus, request);
        entity.freshnessStatus = snapshot.freshnessStatus;
        entity.dataQualityScore = zero(snapshot.dataQualityScore);
        entity.contentJson = writeJson(content);
        entity.markdownContent = buildMarkdown(entity.title, entity.executiveSummary, content);
        entity.generatedAt = request.generatedAt();
        entity.createdAt = request.generatedAt();
        entity.updatedAt = request.generatedAt();
        return entity;
    }

    private AiResearchDailyReportPayloads.ReportView toView(AiResearchDailyReport entity) {
        try {
            AiResearchDailyReportPayloads.ReportContent content = objectMapper.readValue(
                    entity.contentJson, AiResearchDailyReportPayloads.ReportContent.class);
            return AiResearchDailyReportPayloads.ReportView.from(entity, content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("投研日报内容解析失败", exception);
        }
    }

    private List<AiResearchDailyReportPayloads.FactorCard> aggregateFactors(
            List<AiDailyDecisionItem> items
    ) {
        Map<String, FactorAggregate> aggregate = new LinkedHashMap<>();
        for (AiDailyDecisionItem item : items) {
            if ("DATA_UNAVAILABLE".equals(item.category)) {
                continue;
            }
            for (AiResearchDailyReportPayloads.TriggerFactor factor : parseFactors(item.triggerFactorsJson)) {
                aggregate.compute(factor.factorCode(), (key, current) -> current == null
                        ? new FactorAggregate(factor.factorCode(), factor.factorName(), factor.direction(),
                        zero(factor.contribution()), factor.evidence(), 1)
                        : new FactorAggregate(current.factorCode(), current.factorName(), current.direction(),
                        current.contribution().add(zero(factor.contribution())), current.evidence(), current.count() + 1));
            }
        }
        return aggregate.values().stream()
                .sorted(Comparator.comparing(FactorAggregate::contribution).reversed())
                .limit(6)
                .map(value -> new AiResearchDailyReportPayloads.FactorCard(
                        value.factorCode(), value.factorName(), value.direction(),
                        value.contribution(), value.evidence()))
                .toList();
    }

    private List<AiResearchDailyReportPayloads.TriggerFactor> parseFactors(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json,
                    new TypeReference<List<AiResearchDailyReportPayloads.TriggerFactor>>() {
                    });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String buildExecutiveSummary(
            AiResearchDailyReportPayloads.ReportContent content,
            String status,
            GenerationRequest request
    ) {
        if ("FAILED_PIPELINE".equals(status)) {
            return "今日流水线异常：" + safeText(request.pipelineMessage(), "未记录原因")
                    + "。日报仅归档已固化的每日决策，不补造结论。";
        }
        if ("DATA_UNAVAILABLE".equals(status)) {
            return "当前交易日核心研究数据不完整，共 " + content.unavailable().size()
                    + " 只股票无法形成决策，系统未生成伪推荐。";
        }
        if ("EMPTY_RESULT".equals(status)) {
            return "当前用户股票池为空，日报已归档但没有个股结论。";
        }
        return "今日推荐关注 " + content.recommendations().size()
                + " 只，谨慎观察 " + content.watches().size()
                + " 只，建议回避 " + content.avoids().size()
                + " 只，持仓风险 " + content.holdingRisks().size()
                + " 只；另有 " + content.unavailable().size() + " 只数据不可用。";
    }

    private String buildMarkdown(
            String title,
            String summary,
            AiResearchDailyReportPayloads.ReportContent content
    ) {
        StringBuilder builder = new StringBuilder("# ").append(title).append("\n\n")
                .append(summary).append("\n\n")
                .append("## 数据质量\n")
                .append("- 数据新鲜度：").append(content.freshness().status()).append('\n')
                .append("- 数据质量：").append(zero(content.freshness().dataQualityScore())).append('\n')
                .append("- 决策快照：").append(content.insightSummary().snapshotId()).append("\n\n");
        appendStocks(builder, "推荐关注", content.recommendations());
        appendStocks(builder, "谨慎观察", content.watches());
        appendStocks(builder, "建议回避", content.avoids());
        appendStocks(builder, "持仓风险", content.holdingRisks());
        appendStocks(builder, "数据不可用", content.unavailable());
        return builder.toString();
    }

    private static void appendStocks(
            StringBuilder builder,
            String title,
            List<AiResearchDailyReportPayloads.StockCard> items
    ) {
        builder.append("## ").append(title).append('\n');
        if (items.isEmpty()) {
            builder.append("- 暂无\n\n");
            return;
        }
        for (AiResearchDailyReportPayloads.StockCard item : items) {
            builder.append("- ").append(item.stockName()).append(' ').append(item.stockCode())
                    .append("，动作 ").append(safeText(item.action(), "不可用"))
                    .append("，系统分 ").append(item.systemScore())
                    .append("，风险 ").append(item.riskScore())
                    .append("，结论：").append(safeText(item.reasonSummary(), item.unavailableReason()))
                    .append('\n');
        }
        builder.append('\n');
    }

    private static String reportStatus(AiDailyDecisionSnapshot snapshot, GenerationRequest request) {
        if ("FAILED".equals(request.pipelineStatus())
                || request.failedStep() != null && !request.failedStep().isBlank()) {
            return "FAILED_PIPELINE";
        }
        return switch (safeText(snapshot.snapshotStatus, "DATA_UNAVAILABLE")) {
            case "EMPTY" -> "EMPTY_RESULT";
            case "DATA_UNAVAILABLE" -> "DATA_UNAVAILABLE";
            case "PARTIAL" -> "PARTIAL_READY";
            default -> "READY";
        };
    }

    private static String buildTitle(AiDailyDecisionSnapshot snapshot, String status) {
        return switch (status) {
            case "FAILED_PIPELINE" -> "猫狗智投投研日报 · " + snapshot.tradeDate + " · 流水线异常";
            case "DATA_UNAVAILABLE" -> "猫狗智投投研日报 · " + snapshot.tradeDate + " · 数据不可用";
            case "PARTIAL_READY" -> "猫狗智投投研日报 · " + snapshot.tradeDate + " · 部分完成";
            case "EMPTY_RESULT" -> "猫狗智投投研日报 · " + snapshot.tradeDate + " · 暂无结论";
            default -> "猫狗智投投研日报 · " + snapshot.tradeDate + " · " + snapshot.marketRegime;
        };
    }

    private static String direction(String action) {
        return switch (safeText(action, "WATCH")) {
            case "BUY" -> "UP";
            case "REDUCE", "SELL" -> "DOWN";
            default -> "SIDEWAYS";
        };
    }

    private static BigDecimal freshnessScore(String status) {
        return "CURRENT_CLOSE".equals(status) ? new BigDecimal("100") : BigDecimal.ZERO;
    }

    private static String freshnessMessage(AiDailyDecisionItem item) {
        return "DATA_UNAVAILABLE".equals(item.category)
                ? item.unavailableReason : "使用当日完整收盘研究快照";
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? BigDecimal.ZERO : value.decimalValue();
    }

    private static int integer(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() ? 0 : value.asInt();
    }

    private static String text(JsonNode node, String key, String fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value == null || value.isNull() || value.asText().isBlank() ? fallback : value.asText();
    }

    private String writeJson(AiResearchDailyReportPayloads.ReportContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("投研日报内容序列化失败", exception);
        }
    }

    private static void validate(GenerationRequest request) {
        if (request == null || request.userId() == null || request.userId() <= 0
                || request.tradeDate() == null || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank() || request.generatedAt() == null) {
            throw new IllegalArgumentException("投研日报生成请求不完整");
        }
    }

    private LocalDate latestExpectedTradeDate() {
        return tradingCalendarService.latestExpectedKlineDate(LocalDateTime.now());
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private record FactorAggregate(
            String factorCode,
            String factorName,
            String direction,
            BigDecimal contribution,
            String evidence,
            int count
    ) {
    }
}
