package com.maogou.stock.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiDailyInsightItem;
import com.maogou.stock.domain.entity.AiDailyInsightSnapshot;
import com.maogou.stock.domain.entity.TradeRecord;
import com.maogou.stock.domain.entity.v2.AiResearchDailyReport;
import com.maogou.stock.dto.ai.AiResearchDailyReportPayloads;
import com.maogou.stock.mapper.v2.AiResearchDailyReportMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.v2.AiResearchDailyReportSource;
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
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AiResearchDailyReportServiceImpl implements AiResearchDailyReportService {

    private final AiResearchDailyReportMapper reportMapper;
    private final AiResearchDailyReportSource source;
    private final ObjectMapper objectMapper;
    private final TradingCalendarService tradingCalendarService;

    public AiResearchDailyReportServiceImpl(
            AiResearchDailyReportMapper reportMapper,
            AiResearchDailyReportSource source,
            ObjectMapper objectMapper,
            TradingCalendarService tradingCalendarService
    ) {
        this.reportMapper = reportMapper;
        this.source = source;
        this.objectMapper = objectMapper;
        this.tradingCalendarService = tradingCalendarService;
    }

    @Override
    @Transactional
    public ReportView generate(GenerationRequest request) {
        validate(request);
        AiResearchDailyReport existing = reportMapper.selectByIdempotencyForShare(request.userId(), request.idempotencyKey());
        if (existing != null) {
            return ReportView.from(toView(existing));
        }

        reportMapper.lockUser(request.userId());
        existing = reportMapper.selectByIdempotencyForShare(request.userId(), request.idempotencyKey());
        if (existing != null) {
            return ReportView.from(toView(existing));
        }

        AiResearchDailyReport current = reportMapper.selectCurrentForUpdate(request.userId(), request.tradeDate());
        int nextVersion = Math.max(0, safe(reportMapper.selectMaxVersionForUpdate(request.userId(), request.tradeDate()))) + 1;
        AiResearchDailyReportPayloads.ReportContent content = buildContent(request);
        AiResearchDailyReport entity = buildEntity(request, current, nextVersion, content);
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
        LocalDate latestExpectedTradeDate = latestExpectedTradeDate();
        LocalDate tradeDate = requestedTradeDate == null ? latestExpectedTradeDate : requestedTradeDate;
        if (tradeDate.isAfter(latestExpectedTradeDate)) {
            throw new IllegalArgumentException("不能重建尚未结束的未来交易日报");
        }
        return generate(new GenerationRequest(
                userId,
                tradeDate,
                null,
                null,
                null,
                "MANUAL:" + tradeDate + ":" + System.currentTimeMillis(),
                "MANUAL",
                null,
                "手动重建 " + tradeDate + " 投研日报",
                LocalDateTime.now()));
    }

    private AiResearchDailyReportPayloads.ReportView toView(AiResearchDailyReport entity) {
        AiResearchDailyReportPayloads.ReportContent content;
        try {
            content = objectMapper.readValue(entity.contentJson, AiResearchDailyReportPayloads.ReportContent.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("投研日报内容解析失败", exception);
        }
        return AiResearchDailyReportPayloads.ReportView.from(entity, content);
    }

    private AiResearchDailyReportPayloads.ReportContent buildContent(GenerationRequest request) {
        AiResearchDailyReportSource.ReportSource loaded = source.load(
                request.userId(),
                request.tradeDate(),
                new AiResearchDailyReportSource.PipelineRequest(
                        request.pipelineRunId(),
                        request.strategyReleaseId(),
                        request.modelVersionId(),
                        request.pipelineStatus(),
                        request.failedStep(),
                        request.pipelineMessage()));
        AiDailyInsightSnapshot snapshot = loaded.snapshot();
        List<AiDailyInsightItem> items = loaded.items() == null ? List.of() : loaded.items();
        List<AiResearchDailyReportPayloads.StockCard> recommendations = mapItems(items, "RECOMMEND");
        List<AiResearchDailyReportPayloads.StockCard> watches = mapItems(items, "WATCH");
        List<AiResearchDailyReportPayloads.StockCard> avoids = mapItems(items, "AVOID");
        List<AiResearchDailyReportPayloads.StockCard> holdingRisks = mapHoldingRisks(items, loaded.holdings());
        List<AiResearchDailyReportPayloads.FactorCard> keyFactors = aggregateFactors(items);
        return new AiResearchDailyReportPayloads.ReportContent(
                new AiResearchDailyReportPayloads.Freshness(
                        snapshot == null ? "UNAVAILABLE" : snapshot.freshnessStatus,
                        snapshot == null ? BigDecimal.ZERO : zero(snapshot.dataQualityScore),
                        snapshot == null ? null : snapshot.latestSampleAt,
                        snapshot == null ? null : snapshot.latestReportAt,
                        request.generatedAt()),
                loaded.pipeline(),
                loaded.strategyPerformance(),
                recommendations,
                watches,
                avoids,
                holdingRisks,
                keyFactors,
                insightSummary(snapshot));
    }

    private AiResearchDailyReport buildEntity(
            GenerationRequest request,
            AiResearchDailyReport current,
            int nextVersion,
            AiResearchDailyReportPayloads.ReportContent content
    ) {
        LocalDateTime now = request.generatedAt();
        AiResearchDailyReport entity = new AiResearchDailyReport();
        entity.userId = request.userId();
        entity.tradeDate = request.tradeDate();
        entity.reportVersion = nextVersion;
        entity.pipelineRunId = request.pipelineRunId();
        entity.strategyReleaseId = request.strategyReleaseId();
        entity.modelVersionId = request.modelVersionId();
        entity.supersedesReportId = current == null ? null : current.id;
        entity.idempotencyKey = request.idempotencyKey();
        entity.isCurrent = 1;
        entity.reportStatus = resolveReportStatus(request.pipelineStatus(), request.failedStep(), content);
        entity.marketRegime = inferMarketRegime(content, request.pipelineStatus());
        entity.recommendationCount = content.recommendations().size();
        entity.watchCount = content.watches().size();
        entity.avoidCount = content.avoids().size();
        entity.holdingRiskCount = content.holdingRisks().size();
        entity.freshnessStatus = content.freshness().status();
        entity.dataQualityScore = zero(content.freshness().dataQualityScore());
        entity.title = buildTitle(request.tradeDate(), entity.reportStatus, entity.marketRegime);
        entity.executiveSummary = buildExecutiveSummary(entity, content, request);
        entity.markdownContent = buildMarkdown(entity, content, request);
        entity.contentJson = writeJson(content);
        entity.generatedAt = request.generatedAt();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    private List<AiResearchDailyReportPayloads.StockCard> mapItems(List<AiDailyInsightItem> items, String bucket) {
        return items.stream()
                .filter(item -> bucket.equals(item.actionBucket))
                .sorted(Comparator.comparing((AiDailyInsightItem item) -> zero(item.compositeScore)).reversed())
                .map(item -> new AiResearchDailyReportPayloads.StockCard(
                        item.stockCode,
                        item.stockName,
                        item.finalAction,
                        item.actionBucket,
                        zero(item.compositeScore),
                        zero(item.riskScore),
                        zero(item.historicalHitRate),
                        safe(item.historicalSampleCount),
                        item.confidenceLevel,
                        item.freshnessStatus,
                        item.reasonSummary,
                        item.reportId,
                        item.predictionId,
                        item.sampleId,
                        zero(item.systemScore),
                        item.aiDecision,
                        zero(item.aiConfidence),
                        item.targetDirection,
                        item.riskLevel,
                        zero(item.dataQualityScore),
                        zero(item.freshnessScore),
                        item.freshnessMessage,
                        parseFactors(item.triggerFactorsJson),
                        item.reportGeneratedAt,
                        item.sampleTime))
                .toList();
    }

    private List<AiResearchDailyReportPayloads.StockCard> mapHoldingRisks(
            List<AiDailyInsightItem> items,
            List<TradeRecord> holdings
    ) {
        Set<String> holdingCodes = holdings == null ? Set.of() : holdings.stream()
                .map(item -> item.stockCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return items.stream()
                .filter(item -> holdingCodes.contains(item.stockCode))
                .filter(item -> "AVOID".equals(item.actionBucket)
                        || zero(item.riskScore).compareTo(new BigDecimal("70")) >= 0)
                .sorted(Comparator.comparing((AiDailyInsightItem item) -> zero(item.riskScore)).reversed())
                .map(item -> new AiResearchDailyReportPayloads.StockCard(
                        item.stockCode,
                        item.stockName,
                        item.finalAction,
                        item.actionBucket,
                        zero(item.compositeScore),
                        zero(item.riskScore),
                        zero(item.historicalHitRate),
                        safe(item.historicalSampleCount),
                        item.confidenceLevel,
                        item.freshnessStatus,
                        item.reasonSummary,
                        item.reportId,
                        item.predictionId,
                        item.sampleId,
                        zero(item.systemScore),
                        item.aiDecision,
                        zero(item.aiConfidence),
                        item.targetDirection,
                        item.riskLevel,
                        zero(item.dataQualityScore),
                        zero(item.freshnessScore),
                        item.freshnessMessage,
                        parseFactors(item.triggerFactorsJson),
                        item.reportGeneratedAt,
                        item.sampleTime))
                .toList();
    }

    private AiResearchDailyReportPayloads.InsightSummary insightSummary(AiDailyInsightSnapshot snapshot) {
        if (snapshot == null) {
            return new AiResearchDailyReportPayloads.InsightSummary(
                    null, null, "UNAVAILABLE", "每日投研快照不可用",
                    BigDecimal.ZERO, 0, 0, null);
        }
        return new AiResearchDailyReportPayloads.InsightSummary(
                snapshot.id,
                snapshot.generatedAt,
                snapshot.pipelineStatus,
                snapshot.pipelineMessage,
                zero(snapshot.overallHitRate),
                safe(snapshot.itemCount),
                safe(snapshot.lowSampleCount),
                snapshot.latestJobLogId);
    }

    private List<AiResearchDailyReportPayloads.FactorCard> aggregateFactors(List<AiDailyInsightItem> items) {
        Map<String, FactorAggregate> aggregate = new LinkedHashMap<>();
        for (AiDailyInsightItem item : items) {
            for (AiResearchDailyReportPayloads.TriggerFactor factor : parseFactors(item.triggerFactorsJson)) {
                aggregate.compute(factor.factorCode(), (key, existing) -> {
                    if (existing == null) {
                        return new FactorAggregate(
                                factor.factorCode(), factor.factorName(), factor.direction(),
                                zero(factor.contribution()), factor.evidence(), 1);
                    }
                    return new FactorAggregate(
                            existing.factorCode,
                            existing.factorName,
                            existing.direction,
                            existing.contribution.add(zero(factor.contribution())),
                            existing.evidence,
                            existing.count + 1);
                });
            }
        }
        return aggregate.values().stream()
                .sorted(Comparator.comparing((FactorAggregate item) -> item.contribution).reversed())
                .limit(6)
                .map(item -> new AiResearchDailyReportPayloads.FactorCard(
                        item.factorCode,
                        item.factorName,
                        item.direction,
                        item.contribution,
                        item.evidence))
                .toList();
    }

    private List<AiResearchDailyReportPayloads.TriggerFactor> parseFactors(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawJson,
                    new TypeReference<List<AiResearchDailyReportPayloads.TriggerFactor>>() {
                    });
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private String buildTitle(LocalDate tradeDate, String reportStatus, String marketRegime) {
        return switch (reportStatus) {
            case "FAILED_PIPELINE" -> "猫狗智投投研日报 · " + tradeDate + " · 流水线异常";
            case "PARTIAL_READY" -> "猫狗智投投研日报 · " + tradeDate + " · 部分完成";
            case "EMPTY_RESULT" -> "猫狗智投投研日报 · " + tradeDate + " · 暂无有效结论";
            default -> "猫狗智投投研日报 · " + tradeDate + " · " + marketRegime;
        };
    }

    private String buildExecutiveSummary(
            AiResearchDailyReport entity,
            AiResearchDailyReportPayloads.ReportContent content,
            GenerationRequest request
    ) {
        if ("FAILED_PIPELINE".equals(entity.reportStatus)) {
            return "今日自动化流水线在 " + safeText(request.failedStep(), "未知步骤")
                    + " 失败，原因：" + safeText(request.pipelineMessage(), "未记录")
                    + "。本日报仅展示已固化数据，不扩展新的 AI 推理结论。";
        }
        if ("EMPTY_RESULT".equals(entity.reportStatus)) {
            return "流水线已完成，但当前交易日没有可用投研结论。请检查自选股、数据质量和模型配置；系统未生成任何伪推荐。";
        }
        return "今日推荐关注 " + entity.recommendationCount + " 只，谨慎观察 " + entity.watchCount
                + " 只，建议回避 " + entity.avoidCount + " 只，持仓风险提示 " + entity.holdingRiskCount
                + " 只。数据新鲜度为 " + entity.freshnessStatus + "，市场状态判断为 " + entity.marketRegime + "。";
    }

    private String buildMarkdown(
            AiResearchDailyReport entity,
            AiResearchDailyReportPayloads.ReportContent content,
            GenerationRequest request
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(entity.title).append('\n').append('\n');
        builder.append(entity.executiveSummary).append('\n').append('\n');
        builder.append("## 数据质量\n");
        AiResearchDailyReportPayloads.InsightSummary insight = content.insightSummary();
        builder.append("- 平均命中率 ").append(percent(insight.overallHitRate())).append('\n');
        builder.append("- 数据质量 ").append(percent(content.freshness().dataQualityScore())).append('\n');
        builder.append("- 低样本结论 ").append(safe(insight.lowSampleCount())).append(" 只\n");
        builder.append("- 快照时间 ").append(insight.generatedAt()).append('\n').append('\n');
        builder.append("## 推荐关注\n");
        appendStocks(builder, content.recommendations());
        builder.append("\n## 谨慎观察\n");
        appendStocks(builder, content.watches());
        builder.append("\n## 建议回避\n");
        appendStocks(builder, content.avoids());
        builder.append("\n## 持仓风险\n");
        appendStocks(builder, content.holdingRisks());
        builder.append("\n## 关键因子\n");
        if (content.keyFactors().isEmpty()) {
            builder.append("- 暂无关键因子\n");
        } else {
            for (AiResearchDailyReportPayloads.FactorCard factor : content.keyFactors()) {
                builder.append("- ").append(factor.factorName())
                        .append("（").append(factor.factorCode()).append("）")
                        .append("，贡献度 ").append(factor.contribution())
                        .append("，证据：").append(safeText(factor.evidence(), "无")).append('\n');
            }
        }
        builder.append("\n## 流水线状态\n");
        builder.append("- 状态：").append(safeText(content.pipeline().status(), "UNKNOWN")).append('\n');
        if ("FAILED_PIPELINE".equals(entity.reportStatus)) {
            builder.append("- 流水线异常：")
                    .append(safeText(request.failedStep(), "未知步骤"))
                    .append("，仅展示已固化数据\n");
        }
        return builder.toString();
    }

    private void appendStocks(StringBuilder builder, List<AiResearchDailyReportPayloads.StockCard> items) {
        if (items.isEmpty()) {
            builder.append("- 暂无\n");
            return;
        }
        for (AiResearchDailyReportPayloads.StockCard item : items) {
            builder.append("- ").append(item.stockName()).append(' ').append(item.stockCode())
                    .append("，评分 ").append(item.compositeScore())
                    .append("，系统分 ").append(item.systemScore())
                    .append("，AI决策 ").append(safeText(item.aiDecision(), "未结构化"))
                    .append("（置信度 ").append(percent(item.aiConfidence())).append("）")
                    .append("，风险 ").append(item.riskScore())
                    .append("，命中率 ").append(percent(item.historicalHitRate()))
                    .append(" / ").append(safe(item.historicalSampleCount())).append(" 样本")
                    .append("，新鲜度 ").append(safeText(item.freshnessStatus(), "UNAVAILABLE"))
                    .append("，结论：").append(safeText(item.reasonSummary(), "无")).append('\n');
        }
    }

    private String percent(BigDecimal value) {
        return zero(value).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private String inferMarketRegime(AiResearchDailyReportPayloads.ReportContent content, String pipelineStatus) {
        if (content.recommendations().size() >= 3) {
            return "TRENDING";
        }
        if ("FAILED".equals(pipelineStatus)) {
            return "DEFENSIVE";
        }
        if (content.freshness().dataQualityScore().compareTo(new BigDecimal("80")) >= 0) {
            return "BALANCED";
        }
        return "DEFENSIVE";
    }

    private String resolveReportStatus(
            String pipelineStatus,
            String failedStep,
            AiResearchDailyReportPayloads.ReportContent content
    ) {
        if ("FAILED".equals(pipelineStatus) || failedStep != null && !failedStep.isBlank()) {
            return "FAILED_PIPELINE";
        }
        List<AiResearchDailyReportPayloads.StockCard> decisionItems = new ArrayList<>();
        decisionItems.addAll(content.recommendations());
        decisionItems.addAll(content.watches());
        decisionItems.addAll(content.avoids());
        if (!decisionItems.isEmpty() && decisionItems.stream().allMatch(item ->
                "UNAVAILABLE".equals(item.action()) || "DATA_UNAVAILABLE".equals(item.confidenceLevel()))) {
            return "DATA_UNAVAILABLE";
        }
        if ("PARTIAL_SUCCESS".equals(pipelineStatus)) {
            return "PARTIAL_READY";
        }
        if (content.recommendations().isEmpty()
                && content.watches().isEmpty()
                && content.avoids().isEmpty()) {
            return "EMPTY_RESULT";
        }
        return "READY";
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
                || request.tradeDate() == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                || request.pipelineStatus() == null || request.pipelineStatus().isBlank()
                || request.generatedAt() == null) {
            throw new IllegalArgumentException("投研日报生成请求不完整");
        }
    }

    private static int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String safeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private LocalDate latestExpectedTradeDate() {
        return tradingCalendarService.latestExpectedKlineDate(LocalDateTime.now());
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
