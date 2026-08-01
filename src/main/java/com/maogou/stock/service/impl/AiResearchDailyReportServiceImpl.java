package com.maogou.stock.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import com.maogou.stock.dto.portfolio.TradePositionAggregate;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItemPrediction;
import com.maogou.stock.domain.entity.research.AiDailyDecisionSnapshot;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.domain.entity.research.AiResearchDailyReport;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.dto.ai.AiConditionalStrategyPayload;
import com.maogou.stock.dto.ai.AiResearchDailyReportPayloads;
import com.maogou.stock.mapper.research.AiDailyDecisionItemMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionItemPredictionMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionSnapshotMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.mapper.research.AiResearchDailyReportMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.AiAnalysisReportMapper;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.AiUserNotificationService;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiDailyDecisionPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiResearchDailyReportServiceImpl implements AiResearchDailyReportService {

    private static final Logger log = LoggerFactory.getLogger(AiResearchDailyReportServiceImpl.class);

    private final AiResearchDailyReportMapper reportMapper;
    private final AiDailyDecisionSnapshotMapper snapshotMapper;
    private final AiDailyDecisionItemMapper itemMapper;
    private final AiDailyDecisionItemPredictionMapper itemPredictionMapper;
    private final AiPipelineRunMapper pipelineRunMapper;
    private final AiPipelineStepMapper pipelineStepMapper;
    private final AiStrategyReleaseMapper strategyReleaseMapper;
    private final AiSampleMapper sampleMapper;
    private final WatchStockMapper watchStockMapper;
    private final AiAnalysisReportMapper analysisReportMapper;
    private final TradeRecordMapper tradeRecordMapper;
    private final ObjectMapper objectMapper;
    private final TradingCalendarService tradingCalendarService;
    private final AiUserNotificationService notificationService;
    private final AiDailyDecisionPlanService dailyDecisionPlanService;

    public AiResearchDailyReportServiceImpl(
            AiResearchDailyReportMapper reportMapper,
            AiDailyDecisionSnapshotMapper snapshotMapper,
            AiDailyDecisionItemMapper itemMapper,
            AiDailyDecisionItemPredictionMapper itemPredictionMapper,
            AiPipelineRunMapper pipelineRunMapper,
            AiPipelineStepMapper pipelineStepMapper,
            AiStrategyReleaseMapper strategyReleaseMapper,
            AiSampleMapper sampleMapper,
            WatchStockMapper watchStockMapper,
            AiAnalysisReportMapper analysisReportMapper,
            TradeRecordMapper tradeRecordMapper,
            ObjectMapper objectMapper,
            TradingCalendarService tradingCalendarService,
            AiUserNotificationService notificationService,
            AiDailyDecisionPlanService dailyDecisionPlanService
    ) {
        this.reportMapper = reportMapper;
        this.snapshotMapper = snapshotMapper;
        this.itemMapper = itemMapper;
        this.itemPredictionMapper = itemPredictionMapper;
        this.pipelineRunMapper = pipelineRunMapper;
        this.pipelineStepMapper = pipelineStepMapper;
        this.strategyReleaseMapper = strategyReleaseMapper;
        this.sampleMapper = sampleMapper;
        this.watchStockMapper = watchStockMapper;
        this.analysisReportMapper = analysisReportMapper;
        this.tradeRecordMapper = tradeRecordMapper;
        this.objectMapper = objectMapper;
        this.tradingCalendarService = tradingCalendarService;
        this.notificationService = notificationService;
        this.dailyDecisionPlanService = dailyDecisionPlanService;
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
        ReportView view = ReportView.from(toView(entity));
        notificationService.publishDailyReport(request.userId(), view);
        return view;
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
    public ReportListPage pageHistory(ReportListQuery query) {
        ReportListQuery resolved = query == null ? new ReportListQuery(null, 1, 10) : query;
        long userId = AuthContext.currentUserIdOrDefault();
        QueryWrapper<AiResearchDailyReport> filter = historyQuery(userId, resolved);
        long total = reportMapper.selectCount(filter);
        if (total == 0) {
            return ReportListPage.empty(resolved.page(), resolved.pageSize());
        }
        int totalPages = (int) Math.ceil((double) total / resolved.pageSize());
        int page = Math.min(resolved.page(), totalPages);
        QueryWrapper<AiResearchDailyReport> pageQuery = historyQuery(userId, resolved)
                .orderByDesc("trade_date", "generated_at", "id")
                .last("LIMIT " + resolved.pageSize() + " OFFSET " + ((long) (page - 1) * resolved.pageSize()));
        List<AiResearchDailyReportPayloads.ReportListItem> items = safeList(reportMapper.selectList(pageQuery)).stream()
                .map(AiResearchDailyReportPayloads.ReportListItem::from)
                .toList();
        return new ReportListPage(items, total, page, resolved.pageSize(), totalPages);
    }

    @Override
    public DailyOverview overview(int historyLimit) {
        long userId = AuthContext.currentUserIdOrDefault();
        AiResearchDailyReport entity = reportMapper.selectLatestCurrent(userId, latestExpectedTradeDate());
        if (entity == null) {
            throw new IllegalArgumentException("暂无投研日报");
        }
        ReportView fullReport = ReportView.from(toView(entity));
        AiResearchDailyReport previous = reportMapper.selectPreviousCurrent(userId, entity.tradeDate);
        ReportView previousView = previous == null ? null : ReportView.from(toView(previous));
        AiDailyDecisionSnapshot snapshot = entity.decisionSnapshotId == null
                ? null : snapshotMapper.selectById(entity.decisionSnapshotId);
        Long globalRunId = snapshot == null ? null : snapshot.globalPipelineRunId;
        Long userRunId = snapshot == null ? entity.pipelineRunId : snapshot.pipelineRunId;
        AiPipelineRun globalRun = globalRunId == null
                ? pipelineRunMapper.selectLatestGlobalDailyByTradeDate(entity.tradeDate)
                : pipelineRunMapper.selectById(globalRunId);
        AiPipelineRun userRun = userRunId == null
                ? pipelineRunMapper.selectLatestUserProjectionByTradeDate(userId, entity.tradeDate)
                : pipelineRunMapper.selectById(userRunId);
        return new DailyOverview(
                trimPagedSections(fullReport),
                list(historyLimit),
                dailyChanges(fullReport, previousView),
                tradingCalendarService.nextTradingDateTime(LocalDateTime.now(), 16, 0),
                runSummary(globalRun),
                runSummary(userRun));
    }

    private static AiResearchDailyReportService.ResearchRunSummary runSummary(AiPipelineRun run) {
        if (run == null) {
            return null;
        }
        return new AiResearchDailyReportService.ResearchRunSummary(
                run.id, run.pipelineType, run.tradeDate, run.status, run.currentStep,
                value(run.processedCount), value(run.successCount), value(run.failedCount),
                value(run.retryCount), run.errorMessage, run.startedAt, run.finishedAt, run.nextRetryAt);
    }

    @Override
    public DecisionItemPage pageItems(Long reportId, DecisionItemQuery query) {
        if (reportId == null || reportId <= 0) {
            throw new IllegalArgumentException("日报 ID 无效");
        }
        DecisionItemQuery resolved = query == null
                ? new DecisionItemQuery("ALL", null, "ALL", null, "SYSTEM_SCORE_DESC", 1, 10)
                : query;
        long userId = AuthContext.currentUserIdOrDefault();
        AiResearchDailyReport report = reportMapper.selectById(reportId);
        if (report == null || !Objects.equals(report.userId, userId)) {
            throw new IllegalArgumentException("日报不存在");
        }
        if (report.decisionSnapshotId == null) {
            throw new IllegalStateException("该日报为字段升级前的历史版本，暂不支持按页读取决策明细");
        }
        QueryWrapper<AiDailyDecisionItem> filter = decisionItemQuery(userId, report.decisionSnapshotId, resolved);
        long total = itemMapper.selectCount(filter);
        if (total == 0) {
            return DecisionItemPage.empty(resolved.page(), resolved.pageSize());
        }
        int totalPages = (int) Math.ceil((double) total / resolved.pageSize());
        int page = Math.min(resolved.page(), totalPages);
        QueryWrapper<AiDailyDecisionItem> pageQuery = decisionItemQuery(userId, report.decisionSnapshotId, resolved);
        applyDecisionItemSort(pageQuery, resolved.sort());
        pageQuery.last("LIMIT " + resolved.pageSize() + " OFFSET " + ((long) (page - 1) * resolved.pageSize()));
        List<AiDailyDecisionItem> items = safeList(itemMapper.selectList(pageQuery));
        Map<Long, AiDailyDecisionItemPrediction> primaryPredictions = primaryPredictions(userId, items);
        Map<Long, AiAnalysisReport> reportsById = reportsById(userId, items);
        Map<String, HoldingSnapshot> holdings = holdings(userId);
        Map<Long, List<AiResearchDailyReportPayloads.DecisionPlan>> plans = decisionPlans(userId, items);
        List<AiResearchDailyReportPayloads.StockCard> cards = items.stream()
                .map(item -> stockCard(item,
                        item.id == null ? null : primaryPredictions.get(item.id),
                        item.reportId == null ? null : reportsById.get(item.reportId),
                        holdings.get(item.stockCode), decisionPlansForItem(plans, item.id)))
                .toList();
        return new DecisionItemPage(cards, total, page, resolved.pageSize(), totalPages);
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
        return trimPagedSections(ReportView.from(toView(entity)));
    }

    /**
     * Watch and unavailable groups can contain an entire user research pool. They have a
     * dedicated, ownership-scoped page endpoint, so do not transfer them on every page load.
     * Legacy reports cannot use that endpoint because they have no decision snapshot; retain
     * their archived content for read compatibility.
     */
    private static ReportView trimPagedSections(ReportView view) {
        if (view == null || view.decisionSnapshotId() == null || view.content() == null) {
            return view;
        }
        AiResearchDailyReportPayloads.ReportContent content = view.content();
        AiResearchDailyReportPayloads.ReportContent trimmed = new AiResearchDailyReportPayloads.ReportContent(
                content.freshness(), content.pipeline(), content.strategyPerformance(),
                content.recommendations(), holdingCards(content.watches()), content.avoids(), content.holdingRisks(),
                holdingCards(content.unavailable()), content.keyFactors(), content.insightSummary());
        return new ReportView(
                view.id(), view.decisionSnapshotId(), view.tradeDate(), view.reportVersion(), view.pipelineRunId(),
                view.strategyReleaseId(), view.modelVersionId(), view.supersedesReportId(), view.current(),
                view.reportStatus(), view.title(), view.executiveSummary(), view.marketRegime(),
                view.recommendationCount(), view.watchCount(), view.avoidCount(), view.holdingRiskCount(),
                view.freshnessStatus(), view.dataQualityScore(), trimmed, view.markdownContent(), view.generatedAt());
    }

    private static List<DailyChange> dailyChanges(ReportView current, ReportView previous) {
        if (current == null || current.content() == null) {
            return List.of();
        }
        Map<String, AiResearchDailyReportPayloads.StockCard> before = new LinkedHashMap<>(cardsByCode(
                previous == null ? null : previous.content()));
        Map<String, AiResearchDailyReportPayloads.StockCard> after = cardsByCode(current.content());
        List<DailyChange> changes = new ArrayList<>();
        for (AiResearchDailyReportPayloads.StockCard item : after.values()) {
            AiResearchDailyReportPayloads.StockCard old = before.remove(item.stockCode());
            if (isDataUnavailable(item)) {
                if (old == null || !isDataUnavailable(old)) {
                    changes.add(change(item, old, "DATA_UNAVAILABLE", "当日数据不可用，未形成正式结论"));
                }
                continue;
            }
            if (old == null) {
                changes.add(change(item, null, "NEW", "新增进入日报"));
                continue;
            }
            if (isDataUnavailable(old)) {
                changes.add(change(item, old, "DATA_RECOVERED", "数据已恢复，已形成正式结论"));
                continue;
            }
            if (!Objects.equals(old.action(), item.action()) || !Objects.equals(old.actionBucket(), item.actionBucket())) {
                changes.add(change(item, old, "ACTION_CHANGED", "最终动作或分类已调整"));
                continue;
            }
            if (!Objects.equals(old.riskLevel(), item.riskLevel())) {
                boolean holding = "HOLDING_RISK".equals(item.actionBucket())
                        || "HOLDING_RISK".equals(old.actionBucket());
                changes.add(change(item, old, holding ? "HOLDING_RISK_CHANGED" : "RISK_CHANGED",
                        holding ? "持仓风险等级已变化" : "风险等级已变化"));
                continue;
            }
            if (!Objects.equals(factorSignature(old), factorSignature(item))) {
                changes.add(change(item, old, "FACTORS_CHANGED", "触发因子已变化"));
                continue;
            }
            if (!Objects.equals(old.freshnessStatus(), item.freshnessStatus())) {
                changes.add(change(item, old, "FRESHNESS_CHANGED", "数据新鲜度已变化"));
            }
        }
        for (AiResearchDailyReportPayloads.StockCard old : before.values()) {
            changes.add(new DailyChange(
                    old.stockCode(), old.stockName(), "REMOVED", old.action(), null,
                    old.actionBucket(), null, "已移出当日投研范围"));
        }
        return changes.stream()
                .sorted(Comparator.<DailyChange>comparingInt(change -> changePriority(change.changeType()))
                        .thenComparing(DailyChange::stockCode, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private static boolean isDataUnavailable(AiResearchDailyReportPayloads.StockCard item) {
        return item != null && "DATA_UNAVAILABLE".equals(item.actionBucket());
    }

    private static List<AiResearchDailyReportPayloads.StockCard> holdingCards(
            List<AiResearchDailyReportPayloads.StockCard> items
    ) {
        return safeList(items).stream()
                .filter(Objects::nonNull)
                .filter(item -> item.positionPlan() != null)
                .toList();
    }

    private static String factorSignature(AiResearchDailyReportPayloads.StockCard item) {
        if (item == null || item.triggerFactors() == null || item.triggerFactors().isEmpty()) {
            return "";
        }
        return item.triggerFactors().stream()
                .filter(Objects::nonNull)
                .map(factor -> String.join(":",
                        normalizedFactorValue(factor.factorCode()),
                        normalizedFactorValue(factor.direction()),
                        normalizedFactorValue(factor.contribution())))
                .sorted()
                .collect(Collectors.joining("|"));
    }

    private static String normalizedFactorValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int changePriority(String changeType) {
        return switch (changeType == null ? "" : changeType) {
            case "HOLDING_RISK_CHANGED" -> 1;
            case "ACTION_CHANGED" -> 2;
            case "DATA_UNAVAILABLE", "DATA_RECOVERED" -> 3;
            case "RISK_CHANGED" -> 4;
            case "FACTORS_CHANGED" -> 5;
            case "FRESHNESS_CHANGED" -> 6;
            case "NEW" -> 7;
            case "REMOVED" -> 8;
            default -> 99;
        };
    }

    private static DailyChange change(
            AiResearchDailyReportPayloads.StockCard current,
            AiResearchDailyReportPayloads.StockCard previous,
            String type,
            String message
    ) {
        return new DailyChange(
                current.stockCode(), current.stockName(), type,
                previous == null ? null : previous.action(), current.action(),
                previous == null ? null : previous.actionBucket(), current.actionBucket(), message);
    }

    private static Map<String, AiResearchDailyReportPayloads.StockCard> cardsByCode(
            AiResearchDailyReportPayloads.ReportContent content
    ) {
        if (content == null) {
            return Map.of();
        }
        return java.util.stream.Stream.of(
                        content.recommendations(), content.watches(), content.avoids(),
                        content.holdingRisks(), content.unavailable())
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .filter(item -> item.stockCode() != null && !item.stockCode().isBlank())
                .collect(Collectors.toMap(
                        AiResearchDailyReportPayloads.StockCard::stockCode,
                        Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
    }

    private static QueryWrapper<AiDailyDecisionItem> decisionItemQuery(
            long userId,
            Long snapshotId,
            DecisionItemQuery query
    ) {
        QueryWrapper<AiDailyDecisionItem> wrapper = new QueryWrapper<AiDailyDecisionItem>()
                .eq("user_id", userId)
                .eq("decision_snapshot_id", snapshotId);
        String category = normalizedQueryValue(query.category());
        if (category != null && !"ALL".equals(category)) {
            if (!Set.of("RECOMMEND", "CAUTIOUS", "AVOID", "HOLDING_RISK", "DATA_UNAVAILABLE").contains(category)) {
                throw new IllegalArgumentException("不支持的日报分类：" + query.category());
            }
            wrapper.eq("category", category);
        }
        String action = normalizedQueryValue(query.action());
        if (action != null && !"ALL".equals(action)) {
            if (!Set.of("BUY", "HOLD", "WATCH", "REDUCE", "SELL").contains(action)) {
                throw new IllegalArgumentException("不支持的最终动作：" + query.action());
            }
            wrapper.eq("final_action", action);
        }
        String dataStatus = normalizedQueryValue(query.dataStatus());
        if ("AVAILABLE".equals(dataStatus)) {
            wrapper.ne("freshness_status", "UNAVAILABLE");
        } else if ("UNAVAILABLE".equals(dataStatus)) {
            wrapper.eq("freshness_status", "UNAVAILABLE");
        } else if (dataStatus != null && !"ALL".equals(dataStatus)) {
            throw new IllegalArgumentException("不支持的数据状态：" + query.dataStatus());
        }
        String keyword = query.keyword() == null ? null : query.keyword().trim();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(value -> value.like("stock_code", keyword).or().like("stock_name", keyword));
        }
        return wrapper;
    }

    private static QueryWrapper<AiResearchDailyReport> historyQuery(long userId, ReportListQuery query) {
        QueryWrapper<AiResearchDailyReport> wrapper = new QueryWrapper<AiResearchDailyReport>()
                .eq("user_id", userId)
                .eq("is_current", 1);
        if (query.tradeDate() != null) {
            wrapper.eq("trade_date", query.tradeDate());
        }
        return wrapper;
    }

    private static void applyDecisionItemSort(QueryWrapper<AiDailyDecisionItem> query, String requestedSort) {
        String sort = normalizedQueryValue(requestedSort);
        switch (sort == null ? "SYSTEM_SCORE_DESC" : sort) {
            case "RISK_DESC" -> query.orderByDesc("risk_score", "system_score").orderByAsc("stock_code");
            case "STOCK_ASC" -> query.orderByAsc("stock_code");
            case "FRESHNESS_ASC" -> query.orderByAsc("freshness_status").orderByDesc("system_score").orderByAsc("stock_code");
            case "SYSTEM_SCORE_DESC" -> query.orderByDesc("system_score", "risk_score").orderByAsc("stock_code");
            default -> throw new IllegalArgumentException("不支持的日报排序：" + requestedSort);
        }
    }

    private static String normalizedQueryValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(java.util.Locale.ROOT);
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
        Map<Long, AiAnalysisReport> reportsById = reportsById(snapshot.userId, items);
        Map<String, HoldingSnapshot> holdings = holdings(snapshot.userId);
        Map<Long, List<AiResearchDailyReportPayloads.DecisionPlan>> plans = decisionPlans(snapshot.userId, items);
        List<AiResearchDailyReportPayloads.StockCard> recommendations = mapItems(
                items, "RECOMMEND", primaryPredictions, reportsById, holdings, plans);
        List<AiResearchDailyReportPayloads.StockCard> watches = mapItems(
                items, "CAUTIOUS", primaryPredictions, reportsById, holdings, plans);
        List<AiResearchDailyReportPayloads.StockCard> avoids = mapItems(
                items, "AVOID", primaryPredictions, reportsById, holdings, plans);
        List<AiResearchDailyReportPayloads.StockCard> holdingRisks = mapItems(
                items, "HOLDING_RISK", primaryPredictions, reportsById, holdings, plans);
        List<AiResearchDailyReportPayloads.StockCard> unavailable = mapItems(
                items, "DATA_UNAVAILABLE", primaryPredictions, reportsById, holdings, plans);
        return new AiResearchDailyReportPayloads.ReportContent(
                new AiResearchDailyReportPayloads.Freshness(
                        snapshot.freshnessStatus,
                        zero(snapshot.dataQualityScore),
                        latestSampleAt(items),
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
                        snapshot.marketRegime),
                priorVerifications(request.userId(), request.tradeDate()),
                learningChanges(request.userId(), request.tradeDate()));
    }

    private List<AiResearchDailyReportPayloads.PriorVerification> priorVerifications(Long userId, LocalDate tradeDate) {
        List<AiDailyDecisionPlanService.PriorReviewSummary> values =
                dailyDecisionPlanService.priorReviewSummaries(userId, tradeDate);
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(value -> new AiResearchDailyReportPayloads.PriorVerification(
                value.horizonDays(), value.dueCount(), value.triggerCheckedCount(), value.effectiveCount(),
                value.ineffectiveCount(), value.noTriggerCount(), value.unavailableCount(), value.retryableCount())).toList();
    }

    private List<AiResearchDailyReportPayloads.LearningChange> learningChanges(Long userId, LocalDate tradeDate) {
        return priorVerifications(userId, tradeDate).stream().map(value -> {
            String message = "T+" + value.horizonDays() + " 到期 " + value.dueCount() + " 条，已检查触发 "
                    + value.triggerCheckedCount() + " 条；有效 " + value.effectiveCount() + "，无效 "
                    + value.ineffectiveCount() + "，未触发 " + value.noTriggerCount();
            String level = value.retryableCount() > 0 || value.unavailableCount() > 0 ? "DATA_LIMITED" : "CANDIDATE_EVIDENCE";
            return new AiResearchDailyReportPayloads.LearningChange("CONDITIONAL_PLAN_REVIEW", message, level);
        }).toList();
    }

    private LocalDateTime latestSampleAt(List<AiDailyDecisionItem> items) {
        List<Long> sampleIds = items.stream()
                .map(item -> item.sampleId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (sampleIds.isEmpty()) {
            return null;
        }
        return safeList(sampleMapper.selectList(new QueryWrapper<AiSample>().in("id", sampleIds))).stream()
                .map(sample -> sample.asOfTime)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
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

    private Map<Long, AiAnalysisReport> reportsById(Long userId, List<AiDailyDecisionItem> items) {
        List<Long> reportIds = items.stream().map(item -> item.reportId)
                .filter(Objects::nonNull).distinct().toList();
        if (reportIds.isEmpty()) {
            return Map.of();
        }
        return safeList(analysisReportMapper.selectOwnedByIds(userId, reportIds)).stream()
                .filter(report -> report != null && report.id != null)
                .collect(Collectors.toMap(report -> report.id, Function.identity(),
                        (left, right) -> right, LinkedHashMap::new));
    }

    private List<AiResearchDailyReportPayloads.StockCard> mapItems(
            List<AiDailyDecisionItem> items,
            String category,
            Map<Long, AiDailyDecisionItemPrediction> primaryPredictions,
            Map<Long, AiAnalysisReport> reportsById,
            Map<String, HoldingSnapshot> holdings,
            Map<Long, List<AiResearchDailyReportPayloads.DecisionPlan>> plans
    ) {
        return items.stream()
                .filter(item -> category.equals(item.category))
                .sorted(Comparator.comparing(
                        (AiDailyDecisionItem item) -> zero(item.systemScore), Comparator.reverseOrder())
                        .thenComparing(item -> item.stockCode))
                .map(item -> stockCard(item,
                        item.id == null ? null : primaryPredictions.get(item.id),
                        item.reportId == null ? null : reportsById.get(item.reportId),
                        holdings.get(item.stockCode), decisionPlansForItem(plans, item.id)))
                .toList();
    }

    private static List<AiResearchDailyReportPayloads.DecisionPlan> decisionPlansForItem(
            Map<Long, List<AiResearchDailyReportPayloads.DecisionPlan>> plans,
            Long itemId
    ) {
        if (plans == null || plans.isEmpty() || itemId == null) {
            return List.of();
        }
        List<AiResearchDailyReportPayloads.DecisionPlan> values = plans.get(itemId);
        return values == null ? List.of() : values;
    }

    private AiResearchDailyReportPayloads.StockCard stockCard(
            AiDailyDecisionItem item,
            AiDailyDecisionItemPrediction primaryPrediction,
            AiAnalysisReport report,
            HoldingSnapshot holding,
            List<AiResearchDailyReportPayloads.DecisionPlan> decisionPlans
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
                evidenceScope(item),
                wilsonLowerBound(item.historicalHitRate, item.outOfSampleCount),
                wilsonUpperBound(item.historicalHitRate, item.outOfSampleCount),
                item.confidenceLevel,
                item.freshnessStatus,
                item.reasonSummary,
                item.reportId,
                primaryPrediction == null ? null : primaryPrediction.predictionId,
                item.sampleId,
                item.systemScore,
                report == null ? null : report.finalAction,
                report == null ? null : report.calibratedConfidence,
                direction(item.finalAction),
                item.riskLevel,
                item.dataQualityComponent,
                freshnessScore(item.freshnessStatus),
                freshnessMessage(item),
                parseFactors(item.triggerFactorsJson),
                report == null ? null : report.generatedAt,
                null,
                item.horizonSignalScore,
                item.factorReliabilityScore,
                item.strategyValidationScore,
                item.riskComponent,
                item.decisionSource,
                item.decisionPolicyVersion,
                item.unavailableReason,
                positionPlan(report, holding, item),
                decisionPlans == null ? List.of() : decisionPlans);
    }

    private Map<Long, List<AiResearchDailyReportPayloads.DecisionPlan>> decisionPlans(
            Long userId,
            List<AiDailyDecisionItem> items
    ) {
        List<Long> ids = items.stream().map(item -> item.id).filter(Objects::nonNull).toList();
        return ids.isEmpty() ? Map.of() : dailyDecisionPlanService.plansByDecisionItemIds(userId, ids);
    }

    private static String evidenceScope(AiDailyDecisionItem item) {
        if (item == null || item.evidenceScope == null || item.evidenceScope.isBlank()) {
            return "UNKNOWN";
        }
        return item.evidenceScope;
    }

    private static BigDecimal wilsonLowerBound(BigDecimal hitRate, Integer sampleCount) {
        return wilsonInterval(hitRate, sampleCount, false);
    }

    private static BigDecimal wilsonUpperBound(BigDecimal hitRate, Integer sampleCount) {
        return wilsonInterval(hitRate, sampleCount, true);
    }

    private static BigDecimal wilsonInterval(BigDecimal hitRate, Integer sampleCount, boolean upper) {
        if (hitRate == null || sampleCount == null || sampleCount <= 0) {
            return null;
        }
        double count = sampleCount.doubleValue();
        double probability = hitRate.doubleValue() / 100d;
        if (!Double.isFinite(probability)) {
            return null;
        }
        probability = Math.max(0d, Math.min(1d, probability));
        double z = 1.959963984540054d;
        double z2 = z * z;
        double denominator = 1d + z2 / count;
        double center = (probability + z2 / (2d * count)) / denominator;
        double margin = z * Math.sqrt((probability * (1d - probability) + z2 / (4d * count)) / count)
                / denominator;
        double result = Math.max(0d, Math.min(1d, upper ? center + margin : center - margin));
        return BigDecimal.valueOf(result * 100d).setScale(2, RoundingMode.HALF_UP);
    }

    private Map<String, HoldingSnapshot> holdings(Long userId) {
        if (tradeRecordMapper == null || userId == null || userId <= 0) {
            return Map.of();
        }
        return safeList(tradeRecordMapper.selectActivePositions(userId)).stream()
                .filter(item -> item != null && item.stockCode != null && item.quantity != null && item.quantity > 0)
                .collect(Collectors.toMap(item -> item.stockCode,
                        item -> new HoldingSnapshot(averageCost(item), item.quantity),
                        (first, ignored) -> first, LinkedHashMap::new));
    }

    private AiResearchDailyReportPayloads.PositionPlan positionPlan(
            AiAnalysisReport report,
            HoldingSnapshot holding,
            AiDailyDecisionItem item
    ) {
        if (report == null || report.conditionalStrategy == null || report.conditionalStrategy.isBlank()) {
            return fallbackPositionPlan(holding, item);
        }
        try {
            AiConditionalStrategyPayload strategy = objectMapper.readValue(
                    report.conditionalStrategy, AiConditionalStrategyPayload.class);
            AiConditionalStrategyPayload.PositionContext position = strategy.position();
            if (position == null || !position.holding()) {
                return null;
            }
            AiConditionalStrategyPayload.SignalModel target = signal(strategy.sellModels(), "SELL_TARGET_PROFIT");
            AiConditionalStrategyPayload.SignalModel technicalStop = signal(strategy.sellModels(), "SELL_TECHNICAL_STOP");
            AiConditionalStrategyPayload.SignalModel logicStop = signal(strategy.sellModels(), "SELL_LOGIC_STOP");
            AiConditionalStrategyPayload.ConditionalRule reduce = planRule(strategy.tradingPlans(), 1, "T1_WEAK");
            return new AiResearchDailyReportPayloads.PositionPlan(
                    position.averageCost(),
                    position.currentPrice(),
                    position.profitRate(),
                    ifThen(technicalStop),
                    ifThen(reduce),
                    ifThen(target),
                    ifThen(logicStop),
                    strategy.riskScore() == null ? null : strategy.riskScore().advice());
        } catch (Exception exception) {
            log.warn("daily report skipped invalid holding strategy, reportId={}, stockCode={}, reason={}",
                    report.id, report.stockCode, exception.getMessage());
            return fallbackPositionPlan(holding, item);
        }
    }

    private static AiResearchDailyReportPayloads.PositionPlan fallbackPositionPlan(
            HoldingSnapshot holding,
            AiDailyDecisionItem item
    ) {
        if (holding == null) {
            return null;
        }
        String action = item == null || item.finalAction == null ? "WATCH" : item.finalAction;
        String risk = item == null || item.riskLevel == null ? "待确认" : item.riskLevel;
        return new AiResearchDailyReportPayloads.PositionPlan(
                holding.averageCost(),
                null,
                null,
                "如果任一交易日收盘价低于持仓成本的 92%，则执行止损或将仓位降至可承受范围",
                "如果日报最终动作变为减仓或卖出，且对应条件已满足，则按当前持仓分批降低仓位",
                "如果浮动收益达到 8% 且接近历史压力位，则分批止盈；未取得真实现价前不自动判定已触发",
                "如果正式数据质量降为不可用、关键支撑失守或行业风险持续恶化，则冻结加仓并重新评估",
                "当前正式动作为 " + action + "，风险等级为 " + risk
                        + "。AI 持仓报告暂未就绪，以上为真实成本和净持仓生成的保守兜底计划。");
    }

    private static BigDecimal averageCost(TradePositionAggregate position) {
        if (position.totalCost == null || position.quantity == null || position.quantity <= 0) {
            return null;
        }
        return position.totalCost.divide(BigDecimal.valueOf(position.quantity), 2, RoundingMode.HALF_UP);
    }

    private record HoldingSnapshot(BigDecimal averageCost, int quantity) {
    }

    private static AiConditionalStrategyPayload.SignalModel signal(
            List<AiConditionalStrategyPayload.SignalModel> models,
            String modelCode
    ) {
        if (models == null) {
            return null;
        }
        return models.stream()
                .filter(Objects::nonNull)
                .filter(model -> modelCode.equals(model.modelCode()))
                .findFirst()
                .orElse(null);
    }

    private static AiConditionalStrategyPayload.ConditionalRule planRule(
            List<AiConditionalStrategyPayload.HorizonPlan> plans,
            int horizonDays,
            String ruleCode
    ) {
        if (plans == null) {
            return null;
        }
        return plans.stream()
                .filter(Objects::nonNull)
                .filter(plan -> plan.horizonDays() != null && plan.horizonDays() == horizonDays)
                .flatMap(plan -> plan.rules() == null ? java.util.stream.Stream.empty() : plan.rules().stream())
                .filter(Objects::nonNull)
                .filter(rule -> ruleCode.equals(rule.ruleCode()))
                .findFirst()
                .orElse(null);
    }

    private static String ifThen(AiConditionalStrategyPayload.SignalModel model) {
        return model == null ? null : model.ifThen();
    }

    private static String ifThen(AiConditionalStrategyPayload.ConditionalRule rule) {
        return rule == null ? null : rule.ifThen();
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
            JsonNode contentNode = objectMapper.readTree(entity.contentJson);
            hydrateArchivedStockNames(entity, contentNode);
            AiResearchDailyReportPayloads.ReportContent content = objectMapper.treeToValue(
                    contentNode, AiResearchDailyReportPayloads.ReportContent.class);
            return AiResearchDailyReportPayloads.ReportView.from(entity, content);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("投研日报内容解析失败", exception);
        }
    }

    private void hydrateArchivedStockNames(AiResearchDailyReport entity, JsonNode contentNode) {
        if (!(contentNode instanceof ObjectNode root) || entity.decisionSnapshotId == null) {
            return;
        }
        List<ObjectNode> unresolvedCards = new ArrayList<>();
        for (String field : List.of("recommendations", "watches", "avoids", "holdingRisks", "unavailable")) {
            JsonNode value = root.get(field);
            if (!(value instanceof ArrayNode cards)) {
                continue;
            }
            for (JsonNode card : cards) {
                if (card instanceof ObjectNode object && !usableStockName(object.path("stockName").asText(null),
                        object.path("stockCode").asText(null))) {
                    unresolvedCards.add(object);
                }
            }
        }
        if (unresolvedCards.isEmpty()) {
            return;
        }

        Map<String, String> names = safeList(itemMapper.selectBySnapshot(entity.userId, entity.decisionSnapshotId)).stream()
                .filter(item -> usableStockName(item.stockName, item.stockCode))
                .collect(Collectors.toMap(
                        item -> item.stockCode,
                        item -> item.stockName,
                        (first, ignored) -> first,
                        LinkedHashMap::new));
        List<String> missingCodes = unresolvedCards.stream()
                .map(card -> card.path("stockCode").asText(null))
                .filter(code -> code != null && !names.containsKey(code))
                .distinct()
                .toList();
        if (!missingCodes.isEmpty()) {
            safeList(watchStockMapper.selectList(new QueryWrapper<WatchStock>()
                    .select("stock_code", "stock_name")
                    .eq("user_id", entity.userId)
                    .in("stock_code", missingCodes))).stream()
                    .filter(stock -> usableStockName(stock.stockName, stock.stockCode))
                    .forEach(stock -> names.putIfAbsent(stock.stockCode, stock.stockName));
        }
        for (ObjectNode card : unresolvedCards) {
            String code = card.path("stockCode").asText(null);
            String name = names.get(code);
            if (name != null) {
                card.put("stockName", name);
            }
        }
    }

    private static boolean usableStockName(String name, String code) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = name.trim();
        return !normalized.equals(code) && !"未知股票".equals(normalized);
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
