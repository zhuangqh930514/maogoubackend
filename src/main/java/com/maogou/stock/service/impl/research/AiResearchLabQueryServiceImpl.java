package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiFactorDefinition;
import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiFactorPerformance;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import com.maogou.stock.domain.entity.research.AiLabelCostEvidence;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.domain.entity.research.AiPortfolioBacktestDaily;
import com.maogou.stock.domain.entity.research.AiPortfolioBacktestPosition;
import com.maogou.stock.domain.entity.research.AiPortfolioBacktestRun;
import com.maogou.stock.domain.entity.research.AiPortfolioBacktestTrade;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiShadowEvaluation;
import com.maogou.stock.domain.entity.research.AiShadowEvaluationItem;
import com.maogou.stock.domain.entity.research.AiSourceHealth;
import com.maogou.stock.domain.entity.research.AiStrategyGovernanceEvent;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetItem;
import com.maogou.stock.domain.entity.research.AiTrainingReadinessMetric;
import com.maogou.stock.domain.entity.research.AiWalkForwardBaseline;
import com.maogou.stock.domain.entity.research.AiWalkForwardFold;
import com.maogou.stock.domain.entity.research.AiWalkForwardRun;
import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.mapper.AiFactorDefinitionMapper;
import com.maogou.stock.mapper.research.AiDataBatchMapper;
import com.maogou.stock.mapper.research.AiFactorPerformanceMapper;
import com.maogou.stock.mapper.research.AiFactorValueMapper;
import com.maogou.stock.mapper.research.AiLabelCostEvidenceMapper;
import com.maogou.stock.mapper.research.AiModelVersionMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.mapper.research.AiPortfolioBacktestDailyMapper;
import com.maogou.stock.mapper.research.AiPortfolioBacktestPositionMapper;
import com.maogou.stock.mapper.research.AiPortfolioBacktestRunMapper;
import com.maogou.stock.mapper.research.AiPortfolioBacktestTradeMapper;
import com.maogou.stock.mapper.research.AiPredictionEvaluationMapper;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiResearchUniverseItemMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiShadowEvaluationItemMapper;
import com.maogou.stock.mapper.research.AiShadowEvaluationMapper;
import com.maogou.stock.mapper.research.AiSourceHealthMapper;
import com.maogou.stock.mapper.research.AiStrategyGovernanceEventMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetItemMapper;
import com.maogou.stock.mapper.research.AiTrainingDatasetMapper;
import com.maogou.stock.mapper.research.AiWalkForwardBaselineMapper;
import com.maogou.stock.mapper.research.AiWalkForwardFoldMapper;
import com.maogou.stock.mapper.research.AiWalkForwardRunMapper;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.AiResearchLabQueryService;
import org.apache.ibatis.session.SqlSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AiResearchLabQueryServiceImpl implements AiResearchLabQueryService {

    private static final Logger log = LoggerFactory.getLogger(AiResearchLabQueryServiceImpl.class);
    private static final long OVERVIEW_CACHE_TTL_MILLIS = 30_000L;
    private static final long OVERVIEW_STALE_TTL_MILLIS = 300_000L;

    private static final Set<String> PRIVATE_FIELDS = Set.of(
            "userId", "ownerUserId", "actorUserId", "executionOwner", "leaseUntil", "nextRetryAt");

    private final SqlSession sqlSession;
    private volatile CachedOverview overviewCache;

    public AiResearchLabQueryServiceImpl(SqlSession sqlSession) {
        this.sqlSession = sqlSession;
    }

    @Override
    public ResearchLabPayloads.Overview overview() {
        CachedOverview cached = overviewCache;
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAtMillis > now) {
            return cached.value;
        }
        synchronized (this) {
            cached = overviewCache;
            now = System.currentTimeMillis();
            if (cached != null && cached.expiresAtMillis > now) {
                return cached.value;
            }
            try {
                ResearchLabPayloads.Overview overview = loadOverview();
                overviewCache = new CachedOverview(
                        overview,
                        now + OVERVIEW_CACHE_TTL_MILLIS,
                        now + OVERVIEW_STALE_TTL_MILLIS);
                return overview;
            } catch (RuntimeException ex) {
                if (cached != null && cached.staleUntilMillis > now) {
                    log.warn("research lab overview refresh failed, return stale cache", ex);
                    return cached.value;
                }
                throw ex;
            }
        }
    }

    private ResearchLabPayloads.Overview loadOverview() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("samples", mapper(AiSampleMapper.class).selectCount(new QueryWrapper<>()));
        counts.put("matureLabels", mapper(AiSampleLabelMapper.class).selectCount(
                new QueryWrapper<AiSampleLabel>().eq("label_status", "MATURED").eq("is_current", 1)));
        counts.put("predictions", mapper(AiPredictionMapper.class).selectCount(new QueryWrapper<>()));
        counts.put("datasets", mapper(AiTrainingDatasetMapper.class).selectCount(new QueryWrapper<>()));
        counts.put("shadowChallengers", mapper(AiStrategyReleaseMapper.class).selectCount(
                new QueryWrapper<AiStrategyRelease>().eq("release_role", "CHALLENGER").eq("status", "SHADOW")));

        AiStrategyRelease active = mapper(AiStrategyReleaseMapper.class).selectOne(
                new QueryWrapper<AiStrategyRelease>()
                        .eq("release_role", "CHAMPION").eq("status", "ACTIVE")
                        .orderByDesc("activated_at", "id").last("LIMIT 1"));
        AiPipelineRun latest = mapper(AiPipelineRunMapper.class).selectOne(
                new QueryWrapper<AiPipelineRun>().eq("scope_type", "GLOBAL")
                        .orderByDesc("created_at", "id").last("LIMIT 1"));

        Map<String, Object> readiness = new LinkedHashMap<>();
        List<AiTrainingReadinessMetric> metrics = mapper(AiTrainingDatasetItemMapper.class)
                .selectTrainingReadinessMetrics(AiResearchContract.LABEL_VERSION, LocalDateTime.now());
        for (AiTrainingReadinessMetric metric : safe(metrics)) {
            readiness.put(metric.dimensionType + ":" + metric.dimensionKey, metric.metricCount);
        }
        return new ResearchLabPayloads.Overview(
                counts, active == null ? Map.of() : evidenceFields(active),
                latest == null ? Map.of() : evidenceFields(latest), readiness);
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> universe(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiResearchUniverseItem> query = new QueryWrapper<>();
        stock(query, filter, "stock_code");
        dates(query, filter, "effective_from");
        status(query, filter, "listed_status");
        return page(mapper(AiResearchUniverseItemMapper.class), query, filter,
                "universeItem", "effective_from", "id");
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> dataBatches(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiDataBatch> query = new QueryWrapper<>();
        dates(query, filter, "trade_date");
        status(query, filter, "status");
        quality(query, filter, "quality_status");
        return page(mapper(AiDataBatchMapper.class), query, filter, "dataBatch", "trade_date", "id");
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> sourceHealth(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiSourceHealth> query = new QueryWrapper<>();
        dateTimes(query, filter, "last_attempt_at");
        status(query, filter, "source_status");
        return page(mapper(AiSourceHealthMapper.class), query, filter,
                "sourceHealth", "last_attempt_at", "id");
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> samples(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiSample> query = new QueryWrapper<>();
        stock(query, filter, "stock_code");
        dates(query, filter, "trade_date");
        status(query, filter, "tradable_status");
        quality(query, filter, "quality_status");
        AiSampleMapper sampleMapper = mapper(AiSampleMapper.class);
        long total = sampleMapper.selectCount(query);
        if (total == 0) {
            return ResearchLabPayloads.PageResult.empty(filter.page(), filter.pageSize());
        }
        query.select("id", "universe_item_id", "stock_code", "stock_name", "trade_date",
                "sample_phase", "as_of_time", "market_regime", "data_quality_score",
                "quality_status", "tradable_status");
        query.orderByDesc("trade_date", "id");
        query.last("LIMIT " + filter.offset() + ", " + filter.pageSize());
        List<AiSample> records = sampleMapper.selectList(query);
        hydrateSampleNames(records);
        return new ResearchLabPayloads.PageResult<>(items("sample", records),
                total, filter.page(), filter.pageSize());
    }

    @Override
    public ResearchLabPayloads.Detail sample(Long id) {
        AiSample sample = required(mapper(AiSampleMapper.class).selectById(id), "样本不存在");
        hydrateSampleNames(List.of(sample));
        Map<String, List<ResearchLabPayloads.EvidenceItem>> related = new LinkedHashMap<>();
        related.put("factors", items("factorValue", mapper(AiFactorValueMapper.class).selectList(
                new QueryWrapper<AiFactorValue>().eq("sample_id", id).orderByAsc("factor_definition_id"))));
        List<AiSampleLabel> labels = mapper(AiSampleLabelMapper.class).selectList(
                new QueryWrapper<AiSampleLabel>().eq("sample_id", id).eq("is_current", 1)
                        .orderByAsc("horizon_trading_days"));
        related.put("labels", items("sampleLabel", labels));
        List<Long> labelIds = labels.stream().map(label -> label.id).toList();
        related.put("labelCosts", items("labelCostEvidence", labelIds.isEmpty() ? List.of()
                : mapper(AiLabelCostEvidenceMapper.class).selectList(
                        new QueryWrapper<AiLabelCostEvidence>().in("sample_label_id", labelIds)
                                .orderByAsc("sample_label_id", "id"))));
        related.put("predictions", items("prediction", mapper(AiPredictionMapper.class).selectList(
                new QueryWrapper<AiPrediction>().eq("sample_id", id)
                        .orderByAsc("horizon_trading_days").orderByDesc("predicted_at"))));
        return detail("sample", sample, related);
    }

    private void hydrateSampleNames(List<AiSample> samples) {
        List<Long> itemIds = safe(samples).stream()
                .map(sample -> sample.universeItemId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (itemIds.isEmpty()) {
            return;
        }
        Map<Long, AiResearchUniverseItem> itemsById = new LinkedHashMap<>();
        for (AiResearchUniverseItem item : safe(mapper(AiResearchUniverseItemMapper.class)
                .selectBatchIds(itemIds))) {
            itemsById.put(item.id, item);
        }
        for (AiSample sample : safe(samples)) {
            sample.stockName = sampleDisplayName(sample, itemsById.get(sample.universeItemId));
        }
    }

    static String sampleDisplayName(AiSample sample, AiResearchUniverseItem universeItem) {
        if (sample == null) {
            return null;
        }
        if (isUsableStockName(sample.stockCode, sample.stockName)) {
            return sample.stockName.trim();
        }
        if (universeItem != null && isUsableStockName(sample.stockCode, universeItem.stockName)) {
            return universeItem.stockName.trim();
        }
        return sample.stockCode;
    }

    private static boolean isUsableStockName(String stockCode, String stockName) {
        return stockName != null
                && !stockName.isBlank()
                && !stockName.trim().equalsIgnoreCase(stockCode)
                && !"未知股票".equals(stockName.trim());
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> predictions(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiPrediction> query = new QueryWrapper<>();
        stock(query, filter, "stock_code");
        dates(query, filter, "trade_date");
        status(query, filter, "action");
        equal(query, filter.strategyReleaseId(), "strategy_release_id");
        equal(query, filter.modelVersionId(), "model_version_id");
        return page(mapper(AiPredictionMapper.class), query, filter, "prediction", "trade_date", "id");
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> labels(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiSampleLabel> query = new QueryWrapper<>();
        query.eq("is_current", 1);
        stock(query, filter, "stock_code");
        dates(query, filter, "entry_trade_date");
        status(query, filter, "label_status");
        return page(mapper(AiSampleLabelMapper.class), query, filter,
                "sampleLabel", "entry_trade_date", "id");
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> predictionEvaluations(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiPredictionEvaluation> query = new QueryWrapper<>();
        status(query, filter, "evaluation_status");
        predictionRelation(query, filter);
        return page(mapper(AiPredictionEvaluationMapper.class), query, filter,
                "predictionEvaluation", "evaluated_at", "id");
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> factors(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiFactorDefinition> query = new QueryWrapper<>();
        dateTimes(query, filter, "updated_at");
        if (filter.status() != null) {
            query.eq("enabled", "ENABLED".equals(filter.status()) ? 1 : 0);
        }
        return page(mapper(AiFactorDefinitionMapper.class), query, filter,
                "factorDefinition", "updated_at", "id");
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> factorPerformance(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiFactorPerformance> query = new QueryWrapper<>();
        query.eq("is_current", 1);
        dates(query, filter, "window_end_date");
        status(query, filter, "drift_status");
        quality(query, filter, "confidence_level");
        return page(mapper(AiFactorPerformanceMapper.class), query, filter,
                "factorPerformance", "window_end_date", "id");
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> datasets(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiTrainingDataset> query = new QueryWrapper<>();
        dates(query, filter, "train_end_date");
        status(query, filter, "status");
        return page(mapper(AiTrainingDatasetMapper.class), query, filter,
                "trainingDataset", "created_at", "id");
    }

    @Override
    public ResearchLabPayloads.Detail dataset(Long id) {
        AiTrainingDataset dataset = required(mapper(AiTrainingDatasetMapper.class).selectById(id), "训练数据集不存在");
        Map<String, List<ResearchLabPayloads.EvidenceItem>> related = Map.of(
                "items", items("trainingDatasetItem", mapper(AiTrainingDatasetItemMapper.class).selectList(
                        new QueryWrapper<AiTrainingDatasetItem>().eq("training_dataset_id", id)
                                .orderByAsc("sequence_no", "id").last("LIMIT 100"))));
        return detail("trainingDataset", dataset, related);
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> models(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiModelVersion> query = new QueryWrapper<>();
        dates(query, filter, "train_end_date");
        status(query, filter, "status");
        equal(query, filter.modelVersionId(), "id");
        return page(mapper(AiModelVersionMapper.class), query, filter,
                "modelVersion", "created_at", "id");
    }

    @Override
    public ResearchLabPayloads.Detail model(Long id) {
        AiModelVersion model = required(mapper(AiModelVersionMapper.class).selectById(id), "模型版本不存在");
        Map<String, List<ResearchLabPayloads.EvidenceItem>> related = Map.of(
                "strategies", items("strategyRelease", mapper(AiStrategyReleaseMapper.class).selectList(
                        new QueryWrapper<AiStrategyRelease>().eq("model_version_id", id)
                                .orderByDesc("created_at", "id"))));
        return detail("modelVersion", model, related);
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> walkForward(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiWalkForwardRun> query = new QueryWrapper<>();
        dateTimes(query, filter, "created_at");
        status(query, filter, "status");
        equal(query, filter.strategyReleaseId(), "strategy_release_id");
        equal(query, filter.modelVersionId(), "model_version_id");
        return page(mapper(AiWalkForwardRunMapper.class), query, filter,
                "walkForwardRun", "created_at", "id");
    }

    @Override
    public ResearchLabPayloads.Detail walkForward(Long id) {
        AiWalkForwardRun run = required(mapper(AiWalkForwardRunMapper.class).selectById(id), "Walk-forward 运行不存在");
        List<AiWalkForwardFold> folds = mapper(AiWalkForwardFoldMapper.class).selectList(
                new QueryWrapper<AiWalkForwardFold>().eq("walk_forward_run_id", id).orderByAsc("fold_no"));
        List<Long> foldIds = folds.stream().map(fold -> fold.id).toList();
        List<AiWalkForwardBaseline> baselines = foldIds.isEmpty() ? List.of()
                : mapper(AiWalkForwardBaselineMapper.class).selectList(
                        new QueryWrapper<AiWalkForwardBaseline>().in("walk_forward_fold_id", foldIds)
                                .orderByAsc("walk_forward_fold_id", "id"));
        return detail("walkForwardRun", run, Map.of(
                "folds", items("walkForwardFold", folds),
                "baselines", items("walkForwardBaseline", baselines)));
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> backtests(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiPortfolioBacktestRun> query = new QueryWrapper<>();
        dates(query, filter, "end_trade_date");
        status(query, filter, "status");
        equal(query, filter.strategyReleaseId(), "strategy_release_id");
        equal(query, filter.modelVersionId(), "model_version_id");
        return page(mapper(AiPortfolioBacktestRunMapper.class), query, filter,
                "backtestRun", "created_at", "id");
    }

    @Override
    public ResearchLabPayloads.Detail backtest(Long id) {
        AiPortfolioBacktestRun run = required(mapper(AiPortfolioBacktestRunMapper.class).selectById(id), "回测运行不存在");
        return detail("backtestRun", run, Map.of(
                "daily", items("backtestDaily", mapper(AiPortfolioBacktestDailyMapper.class).selectList(
                        new QueryWrapper<AiPortfolioBacktestDaily>().eq("backtest_run_id", id)
                                .orderByAsc("trade_date").last("LIMIT 500"))),
                "trades", items("backtestTrade", mapper(AiPortfolioBacktestTradeMapper.class).selectList(
                        new QueryWrapper<AiPortfolioBacktestTrade>().eq("backtest_run_id", id)
                                .orderByDesc("trade_date", "id").last("LIMIT 500"))),
                "positions", items("backtestPosition", mapper(AiPortfolioBacktestPositionMapper.class).selectList(
                        new QueryWrapper<AiPortfolioBacktestPosition>().eq("backtest_run_id", id)
                                .orderByDesc("trade_date", "weight").last("LIMIT 500")))));
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> strategies(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiStrategyRelease> query = new QueryWrapper<>();
        dateTimes(query, filter, "created_at");
        status(query, filter, "status");
        equal(query, filter.strategyReleaseId(), "id");
        equal(query, filter.modelVersionId(), "model_version_id");
        return page(mapper(AiStrategyReleaseMapper.class), query, filter,
                "strategyRelease", "created_at", "id");
    }

    @Override
    public ResearchLabPayloads.Detail strategy(Long id) {
        AiStrategyRelease strategy = required(mapper(AiStrategyReleaseMapper.class).selectById(id), "策略版本不存在");
        QueryWrapper<AiShadowEvaluation> shadowQuery = new QueryWrapper<>();
        shadowQuery.and(value -> value.eq("champion_release_id", id).or().eq("challenger_release_id", id));
        shadowQuery.orderByDesc("evaluated_at", "id");
        return detail("strategyRelease", strategy, Map.of(
                "shadowEvaluations", items("shadowEvaluation",
                        mapper(AiShadowEvaluationMapper.class).selectList(shadowQuery)),
                "governanceEvents", items("governanceEvent",
                        mapper(AiStrategyGovernanceEventMapper.class).selectList(
                                new QueryWrapper<AiStrategyGovernanceEvent>().eq("strategy_release_id", id)
                                        .orderByDesc("occurred_at", "id")))));
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> shadowEvaluations(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiShadowEvaluation> query = new QueryWrapper<>();
        dates(query, filter, "window_end_date");
        status(query, filter, "decision_status");
        if (filter.strategyReleaseId() != null) {
            query.and(value -> value.eq("champion_release_id", filter.strategyReleaseId())
                    .or().eq("challenger_release_id", filter.strategyReleaseId()));
        }
        if (filter.modelVersionId() != null) {
            query.and(value -> value.eq("champion_model_version_id", filter.modelVersionId())
                    .or().eq("challenger_model_version_id", filter.modelVersionId()));
        }
        return page(mapper(AiShadowEvaluationMapper.class), query, filter,
                "shadowEvaluation", "evaluated_at", "id");
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> governanceEvents(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiStrategyGovernanceEvent> query = new QueryWrapper<>();
        dateTimes(query, filter, "occurred_at");
        status(query, filter, "decision_status");
        equal(query, filter.strategyReleaseId(), "strategy_release_id");
        return page(mapper(AiStrategyGovernanceEventMapper.class), query, filter,
                "governanceEvent", "occurred_at", "id");
    }

    @Override
    public ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> pipelineRuns(
            ResearchLabPayloads.QueryFilter filter) {
        QueryWrapper<AiPipelineRun> query = new QueryWrapper<>();
        query.eq("scope_type", "GLOBAL");
        dates(query, filter, "trade_date");
        status(query, filter, "status");
        equal(query, filter.strategyReleaseId(), "strategy_release_id");
        equal(query, filter.modelVersionId(), "model_version_id");
        return page(mapper(AiPipelineRunMapper.class), query, filter,
                "pipelineRun", "created_at", "id");
    }

    @Override
    public ResearchLabPayloads.Detail pipelineRun(Long id, Long authenticatedUserId) {
        QueryWrapper<AiPipelineRun> query = pipelineRunScope(id, authenticatedUserId);
        AiPipelineRun run = required(mapper(AiPipelineRunMapper.class).selectOne(query), "流水线不存在或无权查看");
        List<AiPipelineStep> steps = mapper(AiPipelineStepMapper.class).selectList(
                new QueryWrapper<AiPipelineStep>().eq("pipeline_run_id", id).orderByAsc("step_order", "id"));
        return detail("pipelineRun", run, Map.of("steps", items("pipelineStep", steps)));
    }

    static QueryWrapper<AiPipelineRun> pipelineRunScope(Long id, Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new IllegalArgumentException("查询流水线缺少认证用户");
        }
        return new QueryWrapper<AiPipelineRun>().eq("id", id)
                .and(scope -> scope.eq("scope_type", "GLOBAL")
                        .or(user -> user.eq("scope_type", "USER")
                                .eq("owner_user_id", authenticatedUserId)));
    }

    private void predictionRelation(QueryWrapper<AiPredictionEvaluation> query, ResearchLabPayloads.QueryFilter filter) {
        if (filter.stockCode() != null) {
            query.apply("EXISTS (SELECT 1 FROM ai_prediction p WHERE p.id = ai_prediction_evaluation.prediction_id AND p.stock_code = {0})",
                    filter.stockCode());
        }
        if (filter.dateFrom() != null) {
            query.apply("EXISTS (SELECT 1 FROM ai_prediction p WHERE p.id = ai_prediction_evaluation.prediction_id AND p.trade_date >= {0})",
                    filter.dateFrom());
        }
        if (filter.dateTo() != null) {
            query.apply("EXISTS (SELECT 1 FROM ai_prediction p WHERE p.id = ai_prediction_evaluation.prediction_id AND p.trade_date <= {0})",
                    filter.dateTo());
        }
        if (filter.strategyReleaseId() != null) {
            query.apply("EXISTS (SELECT 1 FROM ai_prediction p WHERE p.id = ai_prediction_evaluation.prediction_id AND p.strategy_release_id = {0})",
                    filter.strategyReleaseId());
        }
        if (filter.modelVersionId() != null) {
            query.apply("EXISTS (SELECT 1 FROM ai_prediction p WHERE p.id = ai_prediction_evaluation.prediction_id AND p.model_version_id = {0})",
                    filter.modelVersionId());
        }
    }

    private <T> ResearchLabPayloads.PageResult<ResearchLabPayloads.EvidenceItem> page(
            BaseMapper<T> mapper,
            QueryWrapper<T> query,
            ResearchLabPayloads.QueryFilter filter,
            String type,
            String... orderColumns
    ) {
        long total = mapper.selectCount(query);
        if (total == 0) {
            return ResearchLabPayloads.PageResult.empty(filter.page(), filter.pageSize());
        }
        query.orderByDesc(Arrays.asList(orderColumns));
        query.last("LIMIT " + filter.offset() + ", " + filter.pageSize());
        return new ResearchLabPayloads.PageResult<>(items(type, mapper.selectList(query)),
                total, filter.page(), filter.pageSize());
    }

    private ResearchLabPayloads.Detail detail(
            String type,
            Object record,
            Map<String, List<ResearchLabPayloads.EvidenceItem>> related
    ) {
        return new ResearchLabPayloads.Detail(
                new ResearchLabPayloads.EvidenceItem(type, evidenceFields(record)), related);
    }

    private List<ResearchLabPayloads.EvidenceItem> items(String type, List<?> records) {
        List<ResearchLabPayloads.EvidenceItem> result = new ArrayList<>();
        for (Object record : safe(records)) {
            result.add(new ResearchLabPayloads.EvidenceItem(type, evidenceFields(record)));
        }
        return List.copyOf(result);
    }

    static Map<String, Object> evidenceFields(Object record) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Field field : record.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || PRIVATE_FIELDS.contains(field.getName())) {
                continue;
            }
            if (record instanceof AiPipelineStep step
                    && "REQUEST_ACCEPTED".equals(step.stepKey)
                    && "checkpointJson".equals(field.getName())) {
                continue;
            }
            try {
                Object value = field.get(record);
                if (value != null) {
                    result.put(field.getName(), value);
                }
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("无法读取研究证据字段：" + field.getName(), exception);
            }
        }
        return result;
    }

    private static <T> T required(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <T> void stock(QueryWrapper<T> query, ResearchLabPayloads.QueryFilter filter, String column) {
        if (filter.stockCode() != null) {
            query.eq(column, filter.stockCode());
        }
    }

    private static <T> void dates(QueryWrapper<T> query, ResearchLabPayloads.QueryFilter filter, String column) {
        if (filter.dateFrom() != null) {
            query.ge(column, filter.dateFrom());
        }
        if (filter.dateTo() != null) {
            query.le(column, filter.dateTo());
        }
    }

    private static <T> void dateTimes(
            QueryWrapper<T> query,
            ResearchLabPayloads.QueryFilter filter,
            String column
    ) {
        if (filter.dateFrom() != null) {
            query.ge(column, filter.dateFrom().atStartOfDay());
        }
        if (filter.dateTo() != null) {
            query.lt(column, filter.dateTo().plusDays(1).atStartOfDay());
        }
    }

    private static <T> void status(QueryWrapper<T> query, ResearchLabPayloads.QueryFilter filter, String column) {
        if (filter.status() != null) {
            query.eq(column, filter.status());
        }
    }

    private static <T> void quality(QueryWrapper<T> query, ResearchLabPayloads.QueryFilter filter, String column) {
        if (filter.qualityStatus() != null) {
            query.eq(column, filter.qualityStatus());
        }
    }

    private static <T> void equal(QueryWrapper<T> query, Object value, String column) {
        if (value != null) {
            query.eq(column, value);
        }
    }

    private <T> T mapper(Class<T> mapperType) {
        return sqlSession.getMapper(mapperType);
    }

    private record CachedOverview(
            ResearchLabPayloads.Overview value,
            long expiresAtMillis,
            long staleUntilMillis) {
    }
}
