package com.maogou.stock.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.*;
import com.maogou.stock.domain.enums.AnalysisStatus;
import com.maogou.stock.dto.ai.AiLearningPayloads;
import com.maogou.stock.dto.market.*;
import com.maogou.stock.dto.watchlist.WatchStockResponse;
import com.maogou.stock.mapper.*;
import com.maogou.stock.security.AuthContext;
import com.maogou.stock.service.AiLearningService;
import com.maogou.stock.service.MarketDataService;
import com.maogou.stock.service.ModelConfigService;
import com.maogou.stock.service.WatchlistService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiLearningServiceImpl implements AiLearningService {

    private static final String SCHEMA_MESSAGE = "AI 学习系统表未初始化，请执行 backend/src/main/resources/db/20260606_ai_learning_system.sql。";
    private static final String DEFAULT_UNIVERSE = "WATCHLIST";
    private static final String DEFAULT_PHASE = "AFTER_CLOSE";
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private final AiPredictionSampleMapper sampleMapper;
    private final AiFactorDefinitionMapper factorDefinitionMapper;
    private final AiFactorValueMapper factorValueMapper;
    private final AiPredictionResultMapper predictionMapper;
    private final AiPredictionLabelMapper labelMapper;
    private final AiStrategyExperimentMapper experimentMapper;
    private final AiBacktestRunMapper backtestRunMapper;
    private final AiBacktestTradeMapper backtestTradeMapper;
    private final AiLearningJobLogMapper jobLogMapper;
    private final AiModelEvalRunMapper modelEvalRunMapper;
    private final AiFactorStatMapper factorStatMapper;
    private final AiStrategyVersionMapper strategyVersionMapper;
    private final AiAnalysisReportMapper reportMapper;
    private final MarketDataService marketDataService;
    private final WatchlistService watchlistService;
    private final ModelConfigService modelConfigService;
    private final ObjectMapper objectMapper;

    public AiLearningServiceImpl(
            AiPredictionSampleMapper sampleMapper,
            AiFactorDefinitionMapper factorDefinitionMapper,
            AiFactorValueMapper factorValueMapper,
            AiPredictionResultMapper predictionMapper,
            AiPredictionLabelMapper labelMapper,
            AiStrategyExperimentMapper experimentMapper,
            AiBacktestRunMapper backtestRunMapper,
            AiBacktestTradeMapper backtestTradeMapper,
            AiLearningJobLogMapper jobLogMapper,
            AiModelEvalRunMapper modelEvalRunMapper,
            AiFactorStatMapper factorStatMapper,
            AiStrategyVersionMapper strategyVersionMapper,
            AiAnalysisReportMapper reportMapper,
            MarketDataService marketDataService,
            WatchlistService watchlistService,
            ModelConfigService modelConfigService,
            ObjectMapper objectMapper
    ) {
        this.sampleMapper = sampleMapper;
        this.factorDefinitionMapper = factorDefinitionMapper;
        this.factorValueMapper = factorValueMapper;
        this.predictionMapper = predictionMapper;
        this.labelMapper = labelMapper;
        this.experimentMapper = experimentMapper;
        this.backtestRunMapper = backtestRunMapper;
        this.backtestTradeMapper = backtestTradeMapper;
        this.jobLogMapper = jobLogMapper;
        this.modelEvalRunMapper = modelEvalRunMapper;
        this.factorStatMapper = factorStatMapper;
        this.strategyVersionMapper = strategyVersionMapper;
        this.reportMapper = reportMapper;
        this.marketDataService = marketDataService;
        this.watchlistService = watchlistService;
        this.modelConfigService = modelConfigService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiLearningPayloads.LearningDashboardResponse dashboard() {
        try {
            ensureTablesReady();
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiPredictionSample> samples = sampleMapper.selectList(new QueryWrapper<AiPredictionSample>()
                    .eq("user_id", userId)
                    .orderByDesc("sample_time")
                    .last("LIMIT 200"));
            List<AiPredictionLabel> labels = labelMapper.selectList(new QueryWrapper<AiPredictionLabel>()
                    .eq("user_id", userId)
                    .orderByDesc("evaluated_at")
                    .last("LIMIT 200"));
            List<AiFactorStat> stats = factorStatMapper.selectList(new QueryWrapper<AiFactorStat>()
                    .eq("user_id", userId)
                    .orderByDesc("weight_score")
                    .last("LIMIT 8"));
            AiStrategyVersion active = activeStrategy(userId);
            BigDecimal hitRate = hitRate(labels);
            BigDecimal avgNetReturn = avg(labels.stream().map(item -> item.netReturn).toList());
            BigDecimal avgDrawdown = avg(labels.stream().map(item -> item.maxAdverseReturn).toList());
            List<AiLearningPayloads.Metric> metrics = List.of(
                    new AiLearningPayloads.Metric("样本数", String.valueOf(samples.size()), "已固化预测样本", "blue"),
                    new AiLearningPayloads.Metric("样本外胜率", percent(hitRate), "基于 T+N 标签", "green"),
                    new AiLearningPayloads.Metric("平均净收益", signedPercent(avgNetReturn), "扣除简化交易成本", "red"),
                    new AiLearningPayloads.Metric("平均最大浮亏", signedPercent(avgDrawdown), "风险控制核心指标", "yellow")
            );
            return new AiLearningPayloads.LearningDashboardResponse(
                    true,
                    "AI 学习系统已就绪",
                    metrics,
                    labelCurve(labels, true),
                    labelCurve(labels, false),
                    stats.stream().map(this::factorPerformanceItem).toList(),
                    buildAlerts(samples, labels),
                    active == null ? "暂无启用策略" : active.versionNo + " · " + active.title
            );
        } catch (DataAccessException ex) {
            return new AiLearningPayloads.LearningDashboardResponse(false, SCHEMA_MESSAGE, List.of(), List.of(), List.of(), List.of(), List.of(), "未初始化");
        }
    }

    @Override
    public AiLearningPayloads.SampleCenterResponse samples(String stockCode, int limit) {
        try {
            ensureTablesReady();
            Long userId = AuthContext.currentUserIdOrDefault();
            QueryWrapper<AiPredictionSample> wrapper = new QueryWrapper<AiPredictionSample>()
                    .eq("user_id", userId)
                    .orderByDesc("sample_time")
                    .last("LIMIT " + Math.max(1, Math.min(limit, 200)));
            if (stockCode != null && !stockCode.isBlank()) {
                wrapper.eq("stock_code", stockCode.trim());
            }
            List<AiPredictionSample> rows = sampleMapper.selectList(wrapper);
            return sampleCenterResponse("样本数据已加载", rows);
        } catch (DataAccessException ex) {
            return new AiLearningPayloads.SampleCenterResponse(false, SCHEMA_MESSAGE, 0, 0, BigDecimal.ZERO, List.of());
        }
    }

    @Override
    public AiLearningPayloads.SampleDetailResponse sampleDetail(Long sampleId) {
        try {
            ensureTablesReady();
            Long userId = AuthContext.currentUserIdOrDefault();
            AiPredictionSample sample = ownedSample(userId, sampleId);
            return sampleDetailResponse(sample, "样本详情已加载");
        } catch (DataAccessException ex) {
            return new AiLearningPayloads.SampleDetailResponse(false, SCHEMA_MESSAGE, null, null, List.of(), List.of(), List.of());
        }
    }

    @Override
    @Transactional
    public AiLearningPayloads.SampleCenterResponse buildWatchlistSamples(String universeCode, String samplePhase) {
        AiLearningJobLog job = startJob("构建自选股学习样本", "BUILD_SAMPLES");
        try {
            ensureTablesReady();
            ensureDefaultFactorDefinitions();
            Long userId = AuthContext.currentUserIdOrDefault();
            String normalizedUniverse = normalizeUniverse(universeCode);
            String normalizedPhase = normalizePhase(samplePhase);
            List<WatchStockResponse> watchlist = watchlistService.list("全部");
            int success = 0;
            int failed = 0;
            for (WatchStockResponse stock : watchlist) {
                try {
                    StockDetailResponse detail = marketDataService.stockDetail(stock.code());
                    AiPredictionSample sample = buildOrUpdateSample(userId, detail, normalizedUniverse, normalizedPhase);
                    computeFactors(sample, detail);
                    success++;
                } catch (RuntimeException ex) {
                    failed++;
                }
            }
            finishJob(job, "SUCCESS", watchlist.size(), success, failed, null);
            return samples(null, 100);
        } catch (RuntimeException ex) {
            finishJob(job, "FAILED", 0, 0, 1, ex.getMessage());
            throw ex;
        }
    }

    @Override
    @Transactional
    public AiLearningPayloads.SampleDetailResponse recomputeSampleFactors(Long sampleId) {
        ensureTablesReady();
        Long userId = AuthContext.currentUserIdOrDefault();
        AiPredictionSample sample = ownedSample(userId, sampleId);
        StockDetailResponse detail = marketDataService.stockDetail(sample.stockCode);
        computeFactors(sample, detail);
        return sampleDetailResponse(sample, "因子已重新计算");
    }

    @Override
    public AiLearningPayloads.FactorFactoryResponse factorFactory() {
        try {
            ensureTablesReady();
            ensureDefaultFactorDefinitions();
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiFactorDefinition> definitions = factorDefinitionMapper.selectList(new QueryWrapper<AiFactorDefinition>()
                    .orderByAsc("factor_group")
                    .orderByAsc("factor_code"));
            List<AiFactorStat> stats = factorStatMapper.selectList(new QueryWrapper<AiFactorStat>()
                    .eq("user_id", userId)
                    .orderByDesc("weight_score"));
            Long valueCount = factorValueMapper.selectCount(new QueryWrapper<AiFactorValue>().eq("user_id", userId));
            return new AiLearningPayloads.FactorFactoryResponse(
                    true,
                    "因子工厂数据已加载",
                    definitions.size(),
                    (int) definitions.stream().filter(item -> item.enabled != null && item.enabled == 1).count(),
                    valueCount == null ? 0 : valueCount.intValue(),
                    definitions.stream().map(this::factorDefinitionItem).toList(),
                    stats.stream().map(this::factorPerformanceItem).toList(),
                    factorCorrelations(userId)
            );
        } catch (DataAccessException ex) {
            return new AiLearningPayloads.FactorFactoryResponse(false, SCHEMA_MESSAGE, 0, 0, 0, List.of(), List.of(), List.of());
        }
    }

    @Override
    public AiLearningPayloads.PredictionCenterResponse predictions(int limit) {
        try {
            ensureTablesReady();
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiPredictionResult> rows = predictionMapper.selectList(new QueryWrapper<AiPredictionResult>()
                    .eq("user_id", userId)
                    .orderByDesc("created_at")
                    .last("LIMIT " + Math.max(1, Math.min(limit, 300))));
            return predictionCenterResponse("预测数据已加载", rows);
        } catch (DataAccessException ex) {
            return new AiLearningPayloads.PredictionCenterResponse(false, SCHEMA_MESSAGE, 0, 0, BigDecimal.ZERO, List.of());
        }
    }

    @Override
    @Transactional
    public AiLearningPayloads.PredictionRankResponse rankUniverse(String universeCode, Integer horizonDays, Integer topK) {
        ensureTablesReady();
        ensureDefaultFactorDefinitions();
        Long userId = AuthContext.currentUserIdOrDefault();
        String normalizedUniverse = normalizeUniverse(universeCode);
        int normalizedHorizon = normalizeHorizon(horizonDays);
        int normalizedTopK = Math.max(1, Math.min(topK == null ? 10 : topK, 50));
        buildWatchlistSamples(normalizedUniverse, DEFAULT_PHASE);
        List<AiPredictionSample> samples = latestSamplesForUniverse(userId, normalizedUniverse, 120);
        AiStrategyVersion active = activeStrategy(userId);
        List<AiPredictionResult> predictions = new ArrayList<>();
        for (AiPredictionSample sample : samples) {
            List<AiFactorValue> factors = factorValuesForSample(sample.id);
            predictions.add(savePrediction(userId, sample, factors, active == null ? 0L : active.id, 0L, normalizedHorizon, null));
        }
        predictions.sort(Comparator.comparing((AiPredictionResult item) -> safe(item.score)).reversed()
                .thenComparing(item -> safe(item.riskScore)));
        int rank = 1;
        for (AiPredictionResult prediction : predictions) {
            prediction.rankNo = rank++;
            prediction.updatedAt = LocalDateTime.now();
            predictionMapper.updateById(prediction);
        }
        return new AiLearningPayloads.PredictionRankResponse(
                true,
                "选股排序已生成",
                normalizedUniverse,
                normalizedHorizon,
                normalizedTopK,
                predictions.stream().limit(normalizedTopK).map(this::predictionItem).toList()
        );
    }

    @Override
    public AiLearningPayloads.LabelCenterResponse labels(int limit) {
        try {
            ensureTablesReady();
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiPredictionLabel> rows = labelMapper.selectList(new QueryWrapper<AiPredictionLabel>()
                    .eq("user_id", userId)
                    .orderByDesc("evaluated_at")
                    .last("LIMIT " + Math.max(1, Math.min(limit, 300))));
            return labelCenterResponse("标签数据已加载", rows);
        } catch (DataAccessException ex) {
            return new AiLearningPayloads.LabelCenterResponse(false, SCHEMA_MESSAGE, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
        }
    }

    @Override
    @Transactional
    public AiLearningPayloads.LabelCenterResponse verifyLabels() {
        AiLearningJobLog job = startJob("验证预测标签", "VERIFY_LABELS");
        try {
            ensureTablesReady();
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiPredictionResult> predictions = predictionMapper.selectList(new QueryWrapper<AiPredictionResult>()
                    .eq("user_id", userId)
                    .orderByDesc("created_at")
                    .last("LIMIT 800"));
            int success = 0;
            int failed = 0;
            for (AiPredictionResult prediction : predictions) {
                try {
                    if (buildOrUpdateLabel(userId, prediction) != null) {
                        success++;
                    }
                } catch (RuntimeException ex) {
                    failed++;
                }
            }
            refreshLearningFactorStats(userId);
            finishJob(job, "SUCCESS", predictions.size(), success, failed, null);
            return labels(300);
        } catch (RuntimeException ex) {
            finishJob(job, "FAILED", 0, 0, 1, ex.getMessage());
            throw ex;
        }
    }

    @Override
    public AiLearningPayloads.ExperimentCenterResponse experiments() {
        try {
            ensureTablesReady();
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiStrategyExperiment> rows = experimentMapper.selectList(new QueryWrapper<AiStrategyExperiment>()
                    .eq("user_id", userId)
                    .orderByDesc("created_at")
                    .last("LIMIT 50"));
            return new AiLearningPayloads.ExperimentCenterResponse(true, "策略实验已加载", rows.stream().map(this::experimentItem).toList());
        } catch (DataAccessException ex) {
            return new AiLearningPayloads.ExperimentCenterResponse(false, SCHEMA_MESSAGE, List.of());
        }
    }

    @Override
    @Transactional
    public AiLearningPayloads.ExperimentCenterResponse runExperiment(String title, String universeCode) {
        ensureTablesReady();
        verifyLabels();
        Long userId = AuthContext.currentUserIdOrDefault();
        List<AiPredictionLabel> labels = labelMapper.selectList(new QueryWrapper<AiPredictionLabel>()
                .eq("user_id", userId)
                .orderByAsc("evaluated_at")
                .last("LIMIT 1000"));
        Metrics metrics = metrics(labels);
        AiStrategyExperiment experiment = new AiStrategyExperiment();
        experiment.userId = userId;
        experiment.title = title == null || title.isBlank() ? "滚动样本外策略实验 " + LocalDate.now() : title.trim();
        experiment.status = "COMPLETED";
        experiment.universeCode = normalizeUniverse(universeCode);
        experiment.trainStartDate = labels.isEmpty() ? null : labels.get(0).evaluatedAt.toLocalDate();
        experiment.trainEndDate = labels.isEmpty() ? null : labels.get(Math.max(0, labels.size() / 2 - 1)).evaluatedAt.toLocalDate();
        experiment.validationStartDate = labels.isEmpty() ? null : labels.get(Math.max(0, labels.size() / 2)).evaluatedAt.toLocalDate();
        experiment.validationEndDate = labels.isEmpty() ? null : labels.get(Math.max(0, labels.size() - 1)).evaluatedAt.toLocalDate();
        experiment.testStartDate = experiment.validationStartDate;
        experiment.testEndDate = experiment.validationEndDate;
        experiment.configJson = writeJson(Map.of("split", "time", "baseline", "watchlist-random", "minSamples", 20));
        experiment.metricsJson = writeJson(metrics.asMap());
        experiment.baselineMetricsJson = writeJson(Map.of("hitRate", "50.00%", "avgReturn", "0.00%", "maxDrawdown", "0.00%"));
        experiment.canPromote = metrics.sampleCount >= 20 && metrics.hitRate.compareTo(new BigDecimal("50")) >= 0 && metrics.avgReturn.compareTo(BigDecimal.ZERO) > 0 ? 1 : 0;
        experiment.createdAt = LocalDateTime.now();
        experiment.updatedAt = experiment.createdAt;
        experimentMapper.insert(experiment);
        return experiments();
    }

    @Override
    public AiLearningPayloads.BacktestCenterResponse backtests() {
        try {
            ensureTablesReady();
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiBacktestRun> rows = backtestRunMapper.selectList(new QueryWrapper<AiBacktestRun>()
                    .eq("user_id", userId)
                    .orderByDesc("created_at")
                    .last("LIMIT 50"));
            return new AiLearningPayloads.BacktestCenterResponse(true, "回测记录已加载", rows.stream().map(this::backtestRunItem).toList());
        } catch (DataAccessException ex) {
            return new AiLearningPayloads.BacktestCenterResponse(false, SCHEMA_MESSAGE, List.of());
        }
    }

    @Override
    public AiLearningPayloads.BacktestDetailResponse backtestDetail(Long runId) {
        try {
            ensureTablesReady();
            Long userId = AuthContext.currentUserIdOrDefault();
            AiBacktestRun run = backtestRunMapper.selectOne(new QueryWrapper<AiBacktestRun>()
                    .eq("id", runId)
                    .eq("user_id", userId)
                    .last("LIMIT 1"));
            if (run == null) {
                throw new IllegalArgumentException("回测不存在或无权访问");
            }
            List<AiBacktestTrade> trades = backtestTradeMapper.selectList(new QueryWrapper<AiBacktestTrade>()
                    .eq("backtest_run_id", run.id)
                    .orderByAsc("entry_date")
                    .orderByAsc("rank_no"));
            return new AiLearningPayloads.BacktestDetailResponse(true, "回测详情已加载", backtestRunItem(run), run.metricsJson, run.equityCurveJson, trades.stream().map(this::backtestTradeItem).toList());
        } catch (DataAccessException ex) {
            return new AiLearningPayloads.BacktestDetailResponse(false, SCHEMA_MESSAGE, null, null, null, List.of());
        }
    }

    @Override
    @Transactional
    public AiLearningPayloads.BacktestDetailResponse runBacktest(String title, String universeCode, Integer horizonDays, Integer topK) {
        ensureTablesReady();
        verifyLabels();
        Long userId = AuthContext.currentUserIdOrDefault();
        int normalizedHorizon = normalizeHorizon(horizonDays);
        int normalizedTopK = Math.max(1, Math.min(topK == null ? 5 : topK, 20));
        List<AiPredictionLabel> candidateLabels = labelMapper.selectList(new QueryWrapper<AiPredictionLabel>()
                .eq("user_id", userId)
                .eq("horizon_days", normalizedHorizon)
                .orderByAsc("evaluated_at")
                .last("LIMIT 1000"));
        Map<Long, AiPredictionResult> predictionMap = predictionsByIds(candidateLabels.stream().map(item -> item.predictionId).toList());
        List<AiPredictionLabel> selected = candidateLabels.stream()
                .filter(label -> {
                    AiPredictionResult prediction = predictionMap.get(label.predictionId);
                    return prediction != null && (prediction.rankNo == null || prediction.rankNo <= normalizedTopK);
                })
                .toList();
        Metrics metrics = metrics(selected);
        AiBacktestRun run = new AiBacktestRun();
        run.userId = userId;
        run.strategyVersionId = activeStrategyId(userId);
        run.title = title == null || title.isBlank() ? "Top " + normalizedTopK + " 样本外回测 " + LocalDate.now() : title.trim();
        run.universeCode = normalizeUniverse(universeCode);
        run.horizonDays = normalizedHorizon;
        run.topK = normalizedTopK;
        run.startDate = selected.isEmpty() ? null : selected.get(0).evaluatedAt.toLocalDate();
        run.endDate = selected.isEmpty() ? null : selected.get(selected.size() - 1).evaluatedAt.toLocalDate();
        run.totalReturn = metrics.totalReturn;
        run.winRate = metrics.hitRate;
        run.avgReturn = metrics.avgReturn;
        run.maxDrawdown = metrics.maxDrawdown;
        run.benchmarkReturn = BigDecimal.ZERO;
        run.tradeCount = selected.size();
        run.metricsJson = writeJson(metrics.asMap());
        run.equityCurveJson = writeJson(equityCurve(selected));
        run.status = "COMPLETED";
        run.createdAt = LocalDateTime.now();
        run.updatedAt = run.createdAt;
        backtestRunMapper.insert(run);
        for (AiPredictionLabel label : selected) {
            AiPredictionResult prediction = predictionMap.get(label.predictionId);
            AiPredictionSample sample = prediction == null ? null : sampleMapper.selectById(prediction.sampleId);
            AiBacktestTrade trade = new AiBacktestTrade();
            trade.userId = userId;
            trade.backtestRunId = run.id;
            trade.predictionId = label.predictionId;
            trade.stockCode = label.stockCode;
            trade.stockName = sample == null ? label.stockCode : sample.stockName;
            trade.entryDate = label.evaluatedAt.toLocalDate();
            trade.exitDate = label.evaluatedAt.toLocalDate().plusDays(label.horizonDays == null ? normalizedHorizon : label.horizonDays);
            trade.entryPrice = label.entryPrice;
            trade.exitPrice = label.exitPrice;
            trade.netReturn = label.netReturn;
            trade.maxDrawdown = label.maxAdverseReturn;
            trade.rankNo = prediction == null ? null : prediction.rankNo;
            trade.createdAt = LocalDateTime.now();
            backtestTradeMapper.insert(trade);
        }
        return backtestDetail(run.id);
    }

    @Override
    public AiLearningPayloads.ModelEvalCenterResponse modelEvals() {
        try {
            ensureTablesReady();
            Long userId = AuthContext.currentUserIdOrDefault();
            List<AiModelEvalRun> rows = modelEvalRunMapper.selectList(new QueryWrapper<AiModelEvalRun>()
                    .eq("user_id", userId)
                    .orderByDesc("created_at")
                    .last("LIMIT 50"));
            return new AiLearningPayloads.ModelEvalCenterResponse(true, "模型评测已加载", rows.stream().map(this::modelEvalItem).toList());
        } catch (DataAccessException ex) {
            return new AiLearningPayloads.ModelEvalCenterResponse(false, SCHEMA_MESSAGE, List.of());
        }
    }

    @Override
    @Transactional
    public AiLearningPayloads.ModelEvalCenterResponse runModelEval(String evalType, Integer sampleCount) {
        ensureTablesReady();
        Long userId = AuthContext.currentUserIdOrDefault();
        AiModelConfig config = modelConfigService.currentEntity();
        int limit = Math.max(1, Math.min(sampleCount == null ? 30 : sampleCount, 200));
        List<AiAnalysisReport> reports = reportMapper.selectList(new QueryWrapper<AiAnalysisReport>()
                .eq("user_id", userId)
                .orderByDesc("generated_at")
                .last("LIMIT " + limit));
        long success = reports.stream().filter(item -> item.status == AnalysisStatus.SUCCESS).count();
        BigDecimal successRate = reports.isEmpty() ? BigDecimal.ZERO : divide(new BigDecimal(success * 100), new BigDecimal(reports.size()));
        AiModelEvalRun run = new AiModelEvalRun();
        run.userId = userId;
        run.modelName = config.modelName;
        run.provider = "openai-compatible";
        run.promptTemplateId = 0L;
        run.evalType = evalType == null || evalType.isBlank() ? "REPORT_JSON" : evalType.trim();
        run.jsonSuccessRate = successRate;
        run.avgLatencyMs = BigDecimal.ZERO;
        run.sampleCount = reports.size();
        run.score = successRate;
        run.metricsJson = writeJson(Map.of("jsonSuccessRate", percent(successRate), "reportCount", reports.size(), "successCount", success));
        run.status = "READY";
        run.createdAt = LocalDateTime.now();
        modelEvalRunMapper.insert(run);
        return modelEvals();
    }

    @Override
    @Transactional
    public AiLearningPayloads.AnalysisLearningContext prepareAnalysisContext(StockDetailResponse detail, Long promptTemplateId) {
        try {
            ensureTablesReady();
            ensureDefaultFactorDefinitions();
            Long userId = AuthContext.currentUserIdOrDefault();
            AiPredictionSample sample = buildOrUpdateSample(userId, detail, DEFAULT_UNIVERSE, DEFAULT_PHASE);
            List<AiFactorValue> factors = computeFactors(sample, detail);
            AiStrategyVersion active = activeStrategy(userId);
            AiPredictionResult prediction = savePrediction(userId, sample, factors, active == null ? 0L : active.id, promptTemplateId, 3, null);
            return new AiLearningPayloads.AnalysisLearningContext(
                    sample.id,
                    prediction.id,
                    active == null ? 0L : active.id,
                    sample.dataQualityScore,
                    prediction.confidence,
                    buildPromptContext(sample, factors, prediction)
            );
        } catch (RuntimeException ex) {
            return AiLearningPayloads.AnalysisLearningContext.empty();
        }
    }

    @Override
    @Transactional
    public void linkReport(Long predictionId, Long reportId) {
        if (predictionId == null || reportId == null) {
            return;
        }
        AiPredictionResult prediction = predictionMapper.selectById(predictionId);
        if (prediction == null) {
            return;
        }
        prediction.reportId = reportId;
        prediction.updatedAt = LocalDateTime.now();
        predictionMapper.updateById(prediction);
    }

    private void ensureTablesReady() {
        sampleMapper.selectCount(new QueryWrapper<AiPredictionSample>().last("LIMIT 1"));
        factorDefinitionMapper.selectCount(new QueryWrapper<AiFactorDefinition>().last("LIMIT 1"));
        factorValueMapper.selectCount(new QueryWrapper<AiFactorValue>().last("LIMIT 1"));
        predictionMapper.selectCount(new QueryWrapper<AiPredictionResult>().last("LIMIT 1"));
        labelMapper.selectCount(new QueryWrapper<AiPredictionLabel>().last("LIMIT 1"));
        experimentMapper.selectCount(new QueryWrapper<AiStrategyExperiment>().last("LIMIT 1"));
        backtestRunMapper.selectCount(new QueryWrapper<AiBacktestRun>().last("LIMIT 1"));
        modelEvalRunMapper.selectCount(new QueryWrapper<AiModelEvalRun>().last("LIMIT 1"));
    }

    private AiPredictionSample buildOrUpdateSample(Long userId, StockDetailResponse detail, String universeCode, String samplePhase) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate tradeDate = now.toLocalDate();
        String normalizedUniverse = normalizeUniverse(universeCode);
        String normalizedPhase = normalizePhase(samplePhase);
        StockQuoteResponse quote = detail.quote();
        AiPredictionSample sample = sampleMapper.selectOne(new QueryWrapper<AiPredictionSample>()
                .eq("user_id", userId)
                .eq("stock_code", quote.code())
                .eq("trade_date", tradeDate)
                .eq("sample_phase", normalizedPhase)
                .eq("universe_code", normalizedUniverse)
                .last("LIMIT 1"));
        if (sample == null) {
            sample = new AiPredictionSample();
            sample.userId = userId;
            sample.stockCode = quote.code();
            sample.tradeDate = tradeDate;
            sample.samplePhase = normalizedPhase;
            sample.universeCode = normalizedUniverse;
            sample.createdAt = now;
        }
        sample.stockName = quote.name();
        sample.sampleTime = now;
        sample.marketRegime = marketRegime();
        sample.sectorCode = "UNKNOWN";
        sample.sectorName = "未接入板块";
        sample.dataQualityScore = dataQualityScore(detail);
        sample.tradable = isTradable(detail) ? 1 : 0;
        sample.excludeReason = sample.tradable == 1 ? null : excludeReason(detail);
        sample.featureSnapshot = writeJson(featureSnapshot(detail, sample.marketRegime));
        sample.updatedAt = now;
        if (sample.id == null) {
            sampleMapper.insert(sample);
        } else {
            sampleMapper.updateById(sample);
        }
        return sample;
    }

    private List<AiFactorValue> computeFactors(AiPredictionSample sample, StockDetailResponse detail) {
        Map<String, FactorDefinitionSeed> definitionMap = defaultFactorSeeds().stream()
                .collect(Collectors.toMap(FactorDefinitionSeed::code, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        List<FactorSignal> signals = factorSignals(detail);
        factorValueMapper.delete(new QueryWrapper<AiFactorValue>().eq("sample_id", sample.id));
        LocalDateTime now = LocalDateTime.now();
        List<AiFactorValue> values = new ArrayList<>();
        for (FactorSignal signal : signals) {
            FactorDefinitionSeed definition = definitionMap.get(signal.code());
            if (definition == null) {
                continue;
            }
            AiFactorValue value = new AiFactorValue();
            value.userId = sample.userId;
            value.sampleId = sample.id;
            value.stockCode = sample.stockCode;
            value.factorCode = signal.code();
            value.factorValue = scale(signal.value());
            value.normalizedValue = clamp(scale(signal.normalized()), BigDecimal.ZERO, BigDecimal.ONE);
            value.hit = signal.hit() ? 1 : 0;
            value.direction = definition.direction();
            value.evidence = signal.evidence();
            value.calculatedAt = now;
            factorValueMapper.insert(value);
            values.add(value);
        }
        return values;
    }

    private AiPredictionResult savePrediction(
            Long userId,
            AiPredictionSample sample,
            List<AiFactorValue> factors,
            Long strategyVersionId,
            Long promptTemplateId,
            Integer horizonDays,
            Integer rankNo
    ) {
        PredictionScore score = predictionScore(sample, factors);
        Long normalizedStrategyId = strategyVersionId == null ? 0L : strategyVersionId;
        int normalizedHorizon = normalizeHorizon(horizonDays);
        AiPredictionResult prediction = predictionMapper.selectOne(new QueryWrapper<AiPredictionResult>()
                .eq("user_id", userId)
                .eq("sample_id", sample.id)
                .eq("strategy_version_id", normalizedStrategyId)
                .eq("horizon_days", normalizedHorizon)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (prediction == null) {
            prediction = new AiPredictionResult();
            prediction.userId = userId;
            prediction.sampleId = sample.id;
            prediction.strategyVersionId = normalizedStrategyId;
            prediction.modelVersionId = 0L;
            prediction.promptTemplateId = promptTemplateId == null ? 0L : promptTemplateId;
            prediction.horizonDays = normalizedHorizon;
            prediction.createdAt = now;
        }
        prediction.action = score.action();
        prediction.targetDirection = score.targetDirection();
        prediction.confidence = score.confidence();
        prediction.score = score.score();
        prediction.riskScore = score.riskScore();
        prediction.rankNo = rankNo;
        prediction.reasonJson = writeJson(score.reasons());
        prediction.updatedAt = now;
        if (prediction.id == null) {
            predictionMapper.insert(prediction);
        } else {
            predictionMapper.updateById(prediction);
        }
        return prediction;
    }

    private AiPredictionLabel buildOrUpdateLabel(Long userId, AiPredictionResult prediction) {
        AiPredictionSample sample = sampleMapper.selectById(prediction.sampleId);
        if (sample == null) {
            return null;
        }
        List<KlinePointResponse> klines = marketDataService.kline(predictionStockCode(prediction, sample), "day", 180).stream()
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                .toList();
        int baseIndex = entryIndex(klines, sample.tradeDate);
        if (baseIndex < 0) {
            return null;
        }
        int entryIndex = "AFTER_CLOSE".equals(sample.samplePhase) || "CLOSE".equals(sample.samplePhase)
                ? Math.min(baseIndex + 1, klines.size() - 1)
                : baseIndex;
        int horizon = normalizeHorizon(prediction.horizonDays);
        int exitIndex = entryIndex + horizon;
        if (exitIndex >= klines.size()) {
            return null;
        }
        KlinePointResponse entry = klines.get(entryIndex);
        KlinePointResponse exit = klines.get(exitIndex);
        BigDecimal entryPrice = "AFTER_CLOSE".equals(sample.samplePhase) || "CLOSE".equals(sample.samplePhase)
                ? safe(entry.open())
                : safe(entry.close());
        if (entryPrice.compareTo(BigDecimal.ZERO) <= 0) {
            entryPrice = safe(entry.close());
        }
        BigDecimal exitPrice = safe(exit.close());
        List<KlinePointResponse> range = klines.subList(entryIndex, exitIndex + 1);
        BigDecimal high = range.stream().map(KlinePointResponse::high).filter(Objects::nonNull).max(BigDecimal::compareTo).orElse(exitPrice);
        BigDecimal low = range.stream().map(KlinePointResponse::low).filter(Objects::nonNull).min(BigDecimal::compareTo).orElse(exitPrice);
        BigDecimal closeReturn = pct(exitPrice.subtract(entryPrice), entryPrice);
        BigDecimal mfe = pct(high.subtract(entryPrice), entryPrice);
        BigDecimal mae = pct(low.subtract(entryPrice), entryPrice);
        BigDecimal netReturn = closeReturn.subtract(new BigDecimal("0.15"));
        LabelResult result = labelResult(prediction, netReturn, mfe, mae);
        AiPredictionLabel label = labelMapper.selectOne(new QueryWrapper<AiPredictionLabel>()
                .eq("user_id", userId)
                .eq("prediction_id", prediction.id)
                .eq("horizon_days", horizon)
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        if (label == null) {
            label = new AiPredictionLabel();
            label.userId = userId;
            label.predictionId = prediction.id;
            label.sampleId = prediction.sampleId;
            label.stockCode = sample.stockCode;
            label.horizonDays = horizon;
            label.createdAt = now;
        }
        label.entryPrice = entryPrice;
        label.exitPrice = exitPrice;
        label.closeReturn = closeReturn;
        label.maxFavorableReturn = mfe;
        label.maxAdverseReturn = mae;
        label.benchmarkReturn = BigDecimal.ZERO;
        label.sectorReturn = BigDecimal.ZERO;
        label.excessReturn = netReturn;
        label.netReturn = netReturn;
        label.hitDirection = result.hitDirection() ? 1 : 0;
        label.hitTarget = result.hitTarget() ? 1 : 0;
        label.hitStopLoss = result.hitStopLoss() ? 1 : 0;
        label.tradable = sample.tradable;
        label.labelScore = result.score();
        label.labelStatus = sample.tradable != null && sample.tradable == 1 ? "READY" : "UNTRADABLE";
        label.evaluatedAt = now;
        label.updatedAt = now;
        if (label.id == null) {
            labelMapper.insert(label);
        } else {
            labelMapper.updateById(label);
        }
        return label;
    }

    private void refreshLearningFactorStats(Long userId) {
        List<AiPredictionLabel> labels = labelMapper.selectList(new QueryWrapper<AiPredictionLabel>()
                .eq("user_id", userId)
                .orderByDesc("evaluated_at")
                .last("LIMIT 1000"));
        Map<Long, AiPredictionSample> sampleMap = samplesByIds(labels.stream().map(item -> item.sampleId).toList());
        List<AiFactorValue> allValues = new ArrayList<>();
        for (AiPredictionLabel label : labels) {
            List<AiFactorValue> values = factorValueMapper.selectList(new QueryWrapper<AiFactorValue>()
                    .eq("sample_id", label.sampleId)
                    .eq("hit", 1));
            allValues.addAll(values);
        }
        Map<String, List<FactorOutcome>> grouped = new LinkedHashMap<>();
        Map<Long, AiPredictionLabel> labelBySample = labels.stream().collect(Collectors.toMap(item -> item.sampleId, Function.identity(), (left, right) -> left));
        for (AiFactorValue value : allValues) {
            AiPredictionLabel label = labelBySample.get(value.sampleId);
            AiPredictionSample sample = sampleMap.get(value.sampleId);
            if (label == null || sample == null) {
                continue;
            }
            String regime = sample.marketRegime == null ? "UNKNOWN" : sample.marketRegime;
            grouped.computeIfAbsent(value.factorCode + "|" + regime, key -> new ArrayList<>())
                    .add(new FactorOutcome(value, label, sample));
        }
        Map<String, AiFactorDefinition> definitions = factorDefinitionMapper.selectList(new QueryWrapper<AiFactorDefinition>())
                .stream()
                .collect(Collectors.toMap(item -> item.factorCode, Function.identity(), (left, right) -> left));
        for (Map.Entry<String, List<FactorOutcome>> entry : grouped.entrySet()) {
            List<FactorOutcome> outcomes = entry.getValue();
            if (outcomes.isEmpty()) {
                continue;
            }
            FactorOutcome first = outcomes.get(0);
            AiFactorDefinition definition = definitions.get(first.value.factorCode);
            int sampleCount = outcomes.size();
            int successCount = (int) outcomes.stream().filter(item -> safe(item.label.labelScore).compareTo(new BigDecimal("60")) >= 0).count();
            BigDecimal successRate = divide(new BigDecimal(successCount * 100), new BigDecimal(sampleCount));
            BigDecimal avgReturn = avg(outcomes.stream().map(item -> item.label.netReturn).toList());
            BigDecimal avgDrawdown = avg(outcomes.stream().map(item -> item.label.maxAdverseReturn).toList());
            BigDecimal lowerBound = wilsonLowerBound(successCount, sampleCount);
            BigDecimal weight = factorWeight(lowerBound, avgReturn, avgDrawdown, sampleCount);
            AiFactorStat stat = new AiFactorStat();
            stat.userId = userId;
            stat.factorCode = first.value.factorCode;
            stat.factorName = definition == null ? first.value.factorCode : definition.factorName;
            stat.factorGroup = definition == null ? "UNKNOWN" : definition.factorGroup;
            stat.marketRegime = first.sample.marketRegime == null ? "UNKNOWN" : first.sample.marketRegime;
            stat.sampleCount = sampleCount;
            stat.successCount = successCount;
            stat.successRate = successRate;
            stat.avgReturn = avgReturn;
            stat.avgDrawdown = avgDrawdown;
            stat.weightScore = weight;
            stat.lastEvaluatedAt = LocalDateTime.now();
            stat.createdAt = stat.lastEvaluatedAt;
            stat.updatedAt = stat.lastEvaluatedAt;
            factorStatMapper.upsert(stat);
        }
    }

    private void ensureDefaultFactorDefinitions() {
        for (FactorDefinitionSeed seed : defaultFactorSeeds()) {
            AiFactorDefinition existing = factorDefinitionMapper.selectOne(new QueryWrapper<AiFactorDefinition>()
                    .eq("factor_code", seed.code())
                    .eq("version_no", "v1")
                    .last("LIMIT 1"));
            LocalDateTime now = LocalDateTime.now();
            if (existing == null) {
                AiFactorDefinition definition = new AiFactorDefinition();
                definition.factorCode = seed.code();
                definition.factorName = seed.name();
                definition.factorGroup = seed.group();
                definition.direction = seed.direction();
                definition.formulaDesc = seed.formulaDesc();
                definition.requiredFieldsJson = "[\"quote\",\"kline\",\"finance\",\"market\"]";
                definition.defaultWeight = seed.weight();
                definition.enabled = 1;
                definition.versionNo = "v1";
                definition.createdAt = now;
                definition.updatedAt = now;
                factorDefinitionMapper.insert(definition);
            } else {
                existing.factorName = seed.name();
                existing.factorGroup = seed.group();
                existing.direction = seed.direction();
                existing.formulaDesc = seed.formulaDesc();
                existing.defaultWeight = seed.weight();
                existing.enabled = existing.enabled == null ? 1 : existing.enabled;
                existing.updatedAt = now;
                factorDefinitionMapper.updateById(existing);
            }
        }
    }

    private List<FactorDefinitionSeed> defaultFactorSeeds() {
        return List.of(
                seed("PRICE_ABOVE_MA5", "站上5日线", "TREND", "POSITIVE", "收盘价高于5日均线", "7"),
                seed("PRICE_ABOVE_MA20", "站上20日线", "TREND", "POSITIVE", "收盘价高于20日均线", "8"),
                seed("MA5_ABOVE_MA10", "5日均线强于10日线", "TREND", "POSITIVE", "MA5 高于 MA10", "6"),
                seed("MA10_ABOVE_MA20", "10日均线强于20日线", "TREND", "POSITIVE", "MA10 高于 MA20", "6"),
                seed("NEW_20D_HIGH", "接近20日新高", "MOMENTUM", "POSITIVE", "收盘价接近近20日最高价", "8"),
                seed("BREAK_20D_LOW", "接近20日新低", "RISK", "NEGATIVE", "收盘价接近近20日最低价", "-8"),
                seed("RETURN_3D_STRONG", "3日动量强", "MOMENTUM", "POSITIVE", "近3日收益超过3%", "7"),
                seed("RETURN_5D_STRONG", "5日动量强", "MOMENTUM", "POSITIVE", "近5日收益超过5%", "7"),
                seed("RETURN_10D_STRONG", "10日动量强", "MOMENTUM", "POSITIVE", "近10日收益超过8%", "6"),
                seed("GAP_UP", "向上跳空", "MOMENTUM", "POSITIVE", "开盘价相对前收盘上涨超过1%", "5"),
                seed("GAP_DOWN", "向下跳空", "RISK", "NEGATIVE", "开盘价相对前收盘下跌超过1%", "-5"),
                seed("VOLUME_EXPANSION_5D", "5日量能放大", "VOLUME_PRICE", "POSITIVE", "成交量高于5日均量1.5倍", "8"),
                seed("VOLUME_EXPANSION_20D", "20日量能放大", "VOLUME_PRICE", "POSITIVE", "成交量高于20日均量1.5倍", "7"),
                seed("AMOUNT_ABOVE_100M", "成交额过亿", "LIQUIDITY", "POSITIVE", "日成交额高于1亿元", "5"),
                seed("TURNOVER_HIGH", "量比活跃", "VOLUME_PRICE", "POSITIVE", "实时量比高于1.5", "5"),
                seed("PRICE_UP_VOLUME_UP", "价涨量增", "VOLUME_PRICE", "POSITIVE", "上涨同时放量", "7"),
                seed("PRICE_DOWN_VOLUME_UP", "放量下跌", "RISK", "NEGATIVE", "下跌同时放量", "-8"),
                seed("LONG_UPPER_SHADOW", "长上影风险", "RISK", "NEGATIVE", "上影线占全天振幅超过45%", "-6"),
                seed("ATR_HIGH", "波动率偏高", "RISK", "NEGATIVE", "ATR14 占价格比例超过5%", "-5"),
                seed("DRAWDOWN_5D_HIGH", "5日回撤偏大", "RISK", "NEGATIVE", "近5日从高点回撤超过5%", "-7"),
                seed("HIGH_POSITION_60D", "60日高位", "RISK", "NEGATIVE", "价格接近60日高点", "-4"),
                seed("NEAR_LIMIT_UP", "接近涨停", "TRADING", "NEUTRAL", "涨幅接近涨停，可能买入受限", "-3"),
                seed("NEAR_LIMIT_DOWN", "接近跌停", "TRADING", "NEGATIVE", "跌幅接近跌停，流动性风险高", "-8"),
                seed("SECTOR_TOP_10", "板块强势代理", "SECTOR", "POSITIVE", "个股涨幅显著强于市场环境", "5"),
                seed("SECTOR_MOMENTUM_UP", "板块动量上行代理", "SECTOR", "POSITIVE", "个股与市场同时走强", "5"),
                seed("SECTOR_MOMENTUM_DOWN", "板块动量走弱代理", "SECTOR", "NEGATIVE", "个股与市场同时走弱", "-5"),
                seed("STOCK_OUTPERFORM_SECTOR", "跑赢市场代理", "SECTOR", "POSITIVE", "个股涨跌幅跑赢核心指数均值", "6"),
                seed("STOCK_UNDERPERFORM_SECTOR", "跑输市场代理", "SECTOR", "NEGATIVE", "个股涨跌幅跑输核心指数均值", "-6"),
                seed("INDEX_ENV_STRONG", "指数环境强", "MARKET", "POSITIVE", "核心指数均值上涨超过0.5%", "6"),
                seed("INDEX_ENV_WEAK", "指数环境弱", "MARKET", "NEGATIVE", "核心指数均值下跌超过0.5%", "-8"),
                seed("BREADTH_STRONG", "市场宽度强", "MARKET", "POSITIVE", "上涨家数多于下跌家数", "5"),
                seed("BREADTH_WEAK", "市场宽度弱", "MARKET", "NEGATIVE", "下跌家数多于上涨家数", "-7"),
                seed("REVENUE_GROWTH_POSITIVE", "营收正增长", "FUNDAMENTAL", "POSITIVE", "营收同比为正", "4"),
                seed("PROFIT_GROWTH_POSITIVE", "利润正增长", "FUNDAMENTAL", "POSITIVE", "净利润同比为正", "5"),
                seed("ROE_ABOVE_10", "ROE高于10%", "FUNDAMENTAL", "POSITIVE", "ROE 高于10%", "4"),
                seed("VALUATION_PRESSURE", "估值压力", "FUNDAMENTAL", "NEGATIVE", "PE 高于80或PB高于10", "-5"),
                seed("LOW_LIQUIDITY", "低流动性", "TRADING", "NEGATIVE", "成交额低于5000万", "-8"),
                seed("ST_FLAG", "ST风险", "TRADING", "NEGATIVE", "名称包含 ST", "-10"),
                seed("SUSPENSION_RISK", "停牌或数据停滞风险", "TRADING", "NEGATIVE", "最近K线距离当前日期过久", "-8")
        );
    }

    private List<FactorSignal> factorSignals(StockDetailResponse detail) {
        List<KlinePointResponse> klines = detail.kline() == null ? List.of() : detail.kline().stream()
                .sorted(Comparator.comparing(KlinePointResponse::tradeDate))
                .toList();
        StockQuoteResponse quote = detail.quote();
        FinanceSnapshotResponse finance = detail.finance() == null ? FinanceSnapshotResponse.empty() : detail.finance();
        BigDecimal price = safe(quote.price());
        BigDecimal pct = safe(quote.percent());
        BigDecimal marketAvg = marketAveragePercent();
        MarketBreadthResponse breadth = safeMarketBreadth();
        KlinePointResponse last = klines.isEmpty() ? null : klines.get(klines.size() - 1);
        KlinePointResponse prev = klines.size() < 2 ? null : klines.get(klines.size() - 2);
        BigDecimal ma5 = ma(klines, 5);
        BigDecimal ma10 = ma(klines, 10);
        BigDecimal ma20 = ma(klines, 20);
        BigDecimal close = last == null ? price : safe(last.close());
        BigDecimal high20 = high(klines, 20);
        BigDecimal low20 = low(klines, 20);
        BigDecimal high60 = high(klines, 60);
        BigDecimal avgVol5 = avgVolume(klines, 5);
        BigDecimal avgVol20 = avgVolume(klines, 20);
        BigDecimal lastVol = last == null || last.volume() == null ? BigDecimal.ZERO : new BigDecimal(last.volume());
        BigDecimal amount = last == null ? BigDecimal.ZERO : safe(last.amount());
        BigDecimal volumeRatio = safe(quote.volumeRatio());
        BigDecimal ret3 = periodReturn(klines, 3);
        BigDecimal ret5 = periodReturn(klines, 5);
        BigDecimal ret10 = periodReturn(klines, 10);
        BigDecimal gap = prev == null || last == null ? BigDecimal.ZERO : pct(safe(last.open()).subtract(safe(prev.close())), safe(prev.close()));
        BigDecimal atrPct = atrPct(klines, 14);
        BigDecimal drawdown5 = drawdownFromHigh(klines, 5);
        BigDecimal upperShadowRatio = upperShadowRatio(last);
        BigDecimal nearHigh60Distance = high60.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO : pct(close.subtract(high60), high60);
        return List.of(
                signal("PRICE_ABOVE_MA5", pct(close.subtract(ma5), ma5), close.compareTo(ma5) > 0, "收盘价相对MA5偏离 " + signedPercent(pct(close.subtract(ma5), ma5))),
                signal("PRICE_ABOVE_MA20", pct(close.subtract(ma20), ma20), close.compareTo(ma20) > 0, "收盘价相对MA20偏离 " + signedPercent(pct(close.subtract(ma20), ma20))),
                signal("MA5_ABOVE_MA10", pct(ma5.subtract(ma10), ma10), ma5.compareTo(ma10) > 0, "MA5 与 MA10 差值 " + signedPercent(pct(ma5.subtract(ma10), ma10))),
                signal("MA10_ABOVE_MA20", pct(ma10.subtract(ma20), ma20), ma10.compareTo(ma20) > 0, "MA10 与 MA20 差值 " + signedPercent(pct(ma10.subtract(ma20), ma20))),
                signal("NEW_20D_HIGH", pct(close.subtract(high20), high20), close.compareTo(high20.multiply(new BigDecimal("0.995"))) >= 0, "收盘价接近20日高点"),
                signal("BREAK_20D_LOW", pct(close.subtract(low20), low20), close.compareTo(low20.multiply(new BigDecimal("1.005"))) <= 0, "收盘价接近20日低点"),
                signal("RETURN_3D_STRONG", ret3, ret3.compareTo(new BigDecimal("3")) >= 0, "近3日收益 " + signedPercent(ret3)),
                signal("RETURN_5D_STRONG", ret5, ret5.compareTo(new BigDecimal("5")) >= 0, "近5日收益 " + signedPercent(ret5)),
                signal("RETURN_10D_STRONG", ret10, ret10.compareTo(new BigDecimal("8")) >= 0, "近10日收益 " + signedPercent(ret10)),
                signal("GAP_UP", gap, gap.compareTo(BigDecimal.ONE) >= 0, "跳空幅度 " + signedPercent(gap)),
                signal("GAP_DOWN", gap, gap.compareTo(BigDecimal.ONE.negate()) <= 0, "跳空幅度 " + signedPercent(gap)),
                signal("VOLUME_EXPANSION_5D", ratio(lastVol, avgVol5), ratio(lastVol, avgVol5).compareTo(new BigDecimal("1.5")) >= 0, "成交量为5日均量 " + ratio(lastVol, avgVol5)),
                signal("VOLUME_EXPANSION_20D", ratio(lastVol, avgVol20), ratio(lastVol, avgVol20).compareTo(new BigDecimal("1.5")) >= 0, "成交量为20日均量 " + ratio(lastVol, avgVol20)),
                signal("AMOUNT_ABOVE_100M", amount.divide(new BigDecimal("100000000"), 4, RoundingMode.HALF_UP), amount.compareTo(new BigDecimal("100000000")) >= 0, "成交额 " + amount),
                signal("TURNOVER_HIGH", volumeRatio, volumeRatio.compareTo(new BigDecimal("1.5")) >= 0, "量比 " + volumeRatio),
                signal("PRICE_UP_VOLUME_UP", pct, pct.compareTo(BigDecimal.ZERO) > 0 && volumeRatio.compareTo(new BigDecimal("1.2")) >= 0, "涨跌幅 " + signedPercent(pct) + "，量比 " + volumeRatio),
                signal("PRICE_DOWN_VOLUME_UP", pct, pct.compareTo(BigDecimal.ZERO) < 0 && volumeRatio.compareTo(new BigDecimal("1.2")) >= 0, "涨跌幅 " + signedPercent(pct) + "，量比 " + volumeRatio),
                signal("LONG_UPPER_SHADOW", upperShadowRatio, upperShadowRatio.compareTo(new BigDecimal("0.45")) >= 0, "上影线占比 " + upperShadowRatio),
                signal("ATR_HIGH", atrPct, atrPct.compareTo(new BigDecimal("5")) >= 0, "ATR14占比 " + signedPercent(atrPct)),
                signal("DRAWDOWN_5D_HIGH", drawdown5, drawdown5.compareTo(new BigDecimal("-5")) <= 0, "近5日高点回撤 " + signedPercent(drawdown5)),
                signal("HIGH_POSITION_60D", nearHigh60Distance, nearHigh60Distance.compareTo(new BigDecimal("-8")) >= 0, "距离60日高点 " + signedPercent(nearHigh60Distance)),
                signal("NEAR_LIMIT_UP", pct, pct.compareTo(new BigDecimal("8.5")) >= 0, "涨幅接近涨停 " + signedPercent(pct)),
                signal("NEAR_LIMIT_DOWN", pct, pct.compareTo(new BigDecimal("-8.5")) <= 0, "跌幅接近跌停 " + signedPercent(pct)),
                signal("SECTOR_TOP_10", pct.subtract(marketAvg), pct.subtract(marketAvg).compareTo(new BigDecimal("3")) >= 0, "个股跑赢市场均值 " + signedPercent(pct.subtract(marketAvg))),
                signal("SECTOR_MOMENTUM_UP", pct.add(marketAvg), pct.compareTo(BigDecimal.ZERO) > 0 && marketAvg.compareTo(BigDecimal.ZERO) > 0, "个股与市场同步走强"),
                signal("SECTOR_MOMENTUM_DOWN", pct.add(marketAvg), pct.compareTo(BigDecimal.ZERO) < 0 && marketAvg.compareTo(BigDecimal.ZERO) < 0, "个股与市场同步走弱"),
                signal("STOCK_OUTPERFORM_SECTOR", pct.subtract(marketAvg), pct.compareTo(marketAvg) > 0, "跑赢核心指数均值 " + signedPercent(pct.subtract(marketAvg))),
                signal("STOCK_UNDERPERFORM_SECTOR", pct.subtract(marketAvg), pct.compareTo(marketAvg) < 0, "跑输核心指数均值 " + signedPercent(pct.subtract(marketAvg))),
                signal("INDEX_ENV_STRONG", marketAvg, marketAvg.compareTo(new BigDecimal("0.5")) >= 0, "核心指数均值 " + signedPercent(marketAvg)),
                signal("INDEX_ENV_WEAK", marketAvg, marketAvg.compareTo(new BigDecimal("-0.5")) <= 0, "核心指数均值 " + signedPercent(marketAvg)),
                signal("BREADTH_STRONG", new BigDecimal(breadth == null ? 0 : breadth.upCount() - breadth.downCount()), breadth != null && breadth.upCount() > breadth.downCount(), "上涨家数 " + (breadth == null ? 0 : breadth.upCount()) + "，下跌家数 " + (breadth == null ? 0 : breadth.downCount())),
                signal("BREADTH_WEAK", new BigDecimal(breadth == null ? 0 : breadth.downCount() - breadth.upCount()), breadth != null && breadth.downCount() > breadth.upCount(), "下跌家数 " + (breadth == null ? 0 : breadth.downCount()) + "，上涨家数 " + (breadth == null ? 0 : breadth.upCount())),
                signal("REVENUE_GROWTH_POSITIVE", safe(finance.revenueGrowth()), safe(finance.revenueGrowth()).compareTo(BigDecimal.ZERO) > 0, "营收同比 " + signedPercent(safe(finance.revenueGrowth()))),
                signal("PROFIT_GROWTH_POSITIVE", safe(finance.profitGrowth()), safe(finance.profitGrowth()).compareTo(BigDecimal.ZERO) > 0, "净利同比 " + signedPercent(safe(finance.profitGrowth()))),
                signal("ROE_ABOVE_10", safe(finance.roe()), safe(finance.roe()).compareTo(BigDecimal.TEN) >= 0, "ROE " + signedPercent(safe(finance.roe()))),
                signal("VALUATION_PRESSURE", safe(finance.pe()), safe(finance.pe()).compareTo(new BigDecimal("80")) > 0 || safe(finance.pb()).compareTo(BigDecimal.TEN) > 0, "PE " + safe(finance.pe()) + "，PB " + safe(finance.pb())),
                signal("LOW_LIQUIDITY", amount.divide(new BigDecimal("100000000"), 4, RoundingMode.HALF_UP), amount.compareTo(new BigDecimal("50000000")) < 0, "成交额低于5000万"),
                signal("ST_FLAG", BigDecimal.ZERO, quote.name() != null && quote.name().toUpperCase(Locale.ROOT).contains("ST"), "股票名称 " + quote.name()),
                signal("SUSPENSION_RISK", BigDecimal.ZERO, last != null && last.tradeDate().isBefore(LocalDate.now().minusDays(10)), "最近K线日期 " + (last == null ? "-" : last.tradeDate()))
        );
    }

    private PredictionScore predictionScore(AiPredictionSample sample, List<AiFactorValue> factors) {
        Map<String, FactorDefinitionSeed> definitions = defaultFactorSeeds().stream()
                .collect(Collectors.toMap(FactorDefinitionSeed::code, Function.identity(), (left, right) -> left));
        BigDecimal positive = BigDecimal.ZERO;
        BigDecimal negative = BigDecimal.ZERO;
        List<Map<String, Object>> reasons = new ArrayList<>();
        for (AiFactorValue factor : factors) {
            FactorDefinitionSeed definition = definitions.get(factor.factorCode);
            if (definition == null || factor.hit == null || factor.hit == 0) {
                continue;
            }
            BigDecimal contribution = definition.weight().abs().multiply(safe(factor.normalizedValue).max(new BigDecimal("0.20")));
            if ("NEGATIVE".equals(definition.direction())) {
                negative = negative.add(contribution);
            } else if ("POSITIVE".equals(definition.direction())) {
                positive = positive.add(contribution);
            }
            reasons.add(Map.of(
                    "factorCode", factor.factorCode,
                    "factorName", definition.name(),
                    "direction", definition.direction(),
                    "contribution", contribution.setScale(2, RoundingMode.HALF_UP),
                    "evidence", factor.evidence == null ? "" : factor.evidence
            ));
        }
        BigDecimal qualityBonus = safe(sample.dataQualityScore).multiply(new BigDecimal("0.08"));
        BigDecimal score = clamp(new BigDecimal("50").add(positive.multiply(new BigDecimal("1.25"))).subtract(negative.multiply(new BigDecimal("1.35"))).add(qualityBonus), BigDecimal.ZERO, ONE_HUNDRED);
        BigDecimal risk = clamp(negative.multiply(new BigDecimal("2.1")).add(ONE_HUNDRED.subtract(safe(sample.dataQualityScore)).multiply(new BigDecimal("0.25"))), BigDecimal.ZERO, ONE_HUNDRED);
        String action;
        if (sample.tradable != null && sample.tradable == 0) {
            action = "WATCH";
        } else if (score.compareTo(new BigDecimal("72")) >= 0 && risk.compareTo(new BigDecimal("55")) <= 0) {
            action = "BUY";
        } else if (risk.compareTo(new BigDecimal("72")) >= 0) {
            action = "REDUCE";
        } else if (score.compareTo(new BigDecimal("60")) >= 0) {
            action = "HOLD";
        } else {
            action = "WATCH";
        }
        String direction = switch (action) {
            case "BUY", "HOLD" -> "UP";
            case "REDUCE", "SELL" -> "DOWN";
            default -> "SIDEWAYS";
        };
        BigDecimal confidence = clamp(new BigDecimal("42").add(score.subtract(new BigDecimal("50")).abs().multiply(new BigDecimal("0.55"))).add(safe(sample.dataQualityScore).multiply(new BigDecimal("0.18"))), BigDecimal.ZERO, new BigDecimal("95"));
        return new PredictionScore(action, direction, score, confidence, risk, reasons.stream().limit(8).toList());
    }

    private LabelResult labelResult(AiPredictionResult prediction, BigDecimal netReturn, BigDecimal mfe, BigDecimal mae) {
        BigDecimal target = new BigDecimal("1.50");
        BigDecimal stopLoss = new BigDecimal("-3.00");
        boolean hitDirection = switch (prediction.targetDirection == null ? "" : prediction.targetDirection) {
            case "UP" -> netReturn.compareTo(BigDecimal.ZERO) > 0;
            case "DOWN" -> netReturn.compareTo(BigDecimal.ZERO) < 0;
            default -> netReturn.abs().compareTo(new BigDecimal("1.50")) <= 0;
        };
        boolean hitTarget = switch (prediction.action == null ? "" : prediction.action) {
            case "BUY", "HOLD" -> netReturn.compareTo(target) >= 0 || mfe.compareTo(new BigDecimal("2.50")) >= 0;
            case "REDUCE", "SELL" -> netReturn.compareTo(BigDecimal.ZERO) <= 0;
            default -> netReturn.abs().compareTo(new BigDecimal("1.50")) <= 0;
        };
        boolean hitStopLoss = mae.compareTo(stopLoss) <= 0;
        BigDecimal score = new BigDecimal("50")
                .add(netReturn.multiply(new BigDecimal("8")))
                .add(mfe.multiply(new BigDecimal("1.2")))
                .add(mae.multiply(new BigDecimal("2.0")))
                .add(hitDirection ? new BigDecimal("12") : BigDecimal.ZERO)
                .add(hitTarget ? new BigDecimal("12") : BigDecimal.ZERO)
                .subtract(hitStopLoss ? new BigDecimal("15") : BigDecimal.ZERO);
        return new LabelResult(hitDirection, hitTarget, hitStopLoss, clamp(score, BigDecimal.ZERO, ONE_HUNDRED));
    }

    private AiLearningPayloads.SampleCenterResponse sampleCenterResponse(String message, List<AiPredictionSample> rows) {
        int tradable = (int) rows.stream().filter(item -> item.tradable != null && item.tradable == 1).count();
        BigDecimal avgQuality = avg(rows.stream().map(item -> item.dataQualityScore).toList());
        return new AiLearningPayloads.SampleCenterResponse(true, message, rows.size(), tradable, avgQuality, rows.stream().map(this::sampleItem).toList());
    }

    private AiLearningPayloads.SampleDetailResponse sampleDetailResponse(AiPredictionSample sample, String message) {
        List<AiFactorValue> factors = factorValuesForSample(sample.id);
        List<AiPredictionResult> predictions = predictionMapper.selectList(new QueryWrapper<AiPredictionResult>()
                .eq("sample_id", sample.id)
                .orderByDesc("created_at"));
        List<AiPredictionLabel> labels = labelMapper.selectList(new QueryWrapper<AiPredictionLabel>()
                .eq("sample_id", sample.id)
                .orderByDesc("evaluated_at"));
        return new AiLearningPayloads.SampleDetailResponse(
                true,
                message,
                sampleItem(sample),
                sample.featureSnapshot,
                factors.stream().map(this::factorValueItem).toList(),
                predictions.stream().map(this::predictionItem).toList(),
                labels.stream().map(this::labelItem).toList()
        );
    }

    private AiLearningPayloads.PredictionCenterResponse predictionCenterResponse(String message, List<AiPredictionResult> rows) {
        int buyCount = (int) rows.stream().filter(item -> "BUY".equals(item.action)).count();
        BigDecimal avgScore = avg(rows.stream().map(item -> item.score).toList());
        return new AiLearningPayloads.PredictionCenterResponse(true, message, rows.size(), buyCount, avgScore, rows.stream().map(this::predictionItem).toList());
    }

    private AiLearningPayloads.LabelCenterResponse labelCenterResponse(String message, List<AiPredictionLabel> rows) {
        int hitCount = (int) rows.stream().filter(item -> item.hitTarget != null && item.hitTarget == 1).count();
        return new AiLearningPayloads.LabelCenterResponse(
                true,
                message,
                rows.size(),
                hitCount,
                rows.isEmpty() ? BigDecimal.ZERO : divide(new BigDecimal(hitCount * 100), new BigDecimal(rows.size())),
                avg(rows.stream().map(item -> item.netReturn).toList()),
                avg(rows.stream().map(item -> item.maxAdverseReturn).toList()),
                rows.stream().map(this::labelItem).toList()
        );
    }

    private AiLearningPayloads.SampleItem sampleItem(AiPredictionSample item) {
        return new AiLearningPayloads.SampleItem(
                item.id,
                item.stockCode,
                item.stockName,
                item.sampleTime,
                item.tradeDate,
                item.samplePhase,
                item.universeCode,
                item.marketRegime,
                item.dataQualityScore,
                item.tradable != null && item.tradable == 1,
                item.excludeReason,
                countInt(factorValueMapper.selectCount(new QueryWrapper<AiFactorValue>().eq("sample_id", item.id))),
                countInt(predictionMapper.selectCount(new QueryWrapper<AiPredictionResult>().eq("sample_id", item.id))),
                countInt(labelMapper.selectCount(new QueryWrapper<AiPredictionLabel>().eq("sample_id", item.id)))
        );
    }

    private AiLearningPayloads.FactorDefinitionItem factorDefinitionItem(AiFactorDefinition item) {
        return new AiLearningPayloads.FactorDefinitionItem(item.id, item.factorCode, item.factorName, item.factorGroup, item.direction, item.formulaDesc, item.defaultWeight, item.enabled != null && item.enabled == 1, item.versionNo);
    }

    private AiLearningPayloads.FactorValueItem factorValueItem(AiFactorValue item) {
        AiFactorDefinition definition = factorDefinitionMapper.selectOne(new QueryWrapper<AiFactorDefinition>()
                .eq("factor_code", item.factorCode)
                .eq("version_no", "v1")
                .last("LIMIT 1"));
        return new AiLearningPayloads.FactorValueItem(
                item.id,
                item.sampleId,
                item.stockCode,
                item.factorCode,
                definition == null ? item.factorCode : definition.factorName,
                definition == null ? "UNKNOWN" : definition.factorGroup,
                item.factorValue,
                item.normalizedValue,
                item.hit != null && item.hit == 1,
                item.direction,
                item.evidence,
                item.calculatedAt
        );
    }

    private AiLearningPayloads.FactorPerformanceItem factorPerformanceItem(AiFactorStat item) {
        return new AiLearningPayloads.FactorPerformanceItem(
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
                wilsonLowerBound(item.successCount == null ? 0 : item.successCount, item.sampleCount == null ? 0 : item.sampleCount),
                item.lastEvaluatedAt
        );
    }

    private AiLearningPayloads.PredictionItem predictionItem(AiPredictionResult item) {
        AiPredictionSample sample = sampleMapper.selectById(item.sampleId);
        return new AiLearningPayloads.PredictionItem(
                item.id,
                item.sampleId,
                item.reportId,
                sample == null ? "" : sample.stockCode,
                sample == null ? "" : sample.stockName,
                item.action,
                item.targetDirection,
                item.horizonDays,
                item.confidence,
                item.score,
                item.rankNo,
                item.riskScore,
                item.reasonJson,
                item.createdAt
        );
    }

    private AiLearningPayloads.LabelItem labelItem(AiPredictionLabel item) {
        AiPredictionSample sample = sampleMapper.selectById(item.sampleId);
        return new AiLearningPayloads.LabelItem(
                item.id,
                item.predictionId,
                item.sampleId,
                item.stockCode,
                sample == null ? item.stockCode : sample.stockName,
                item.horizonDays,
                item.entryPrice,
                item.exitPrice,
                item.closeReturn,
                item.maxFavorableReturn,
                item.maxAdverseReturn,
                item.excessReturn,
                item.netReturn,
                item.hitDirection != null && item.hitDirection == 1,
                item.hitTarget != null && item.hitTarget == 1,
                item.hitStopLoss != null && item.hitStopLoss == 1,
                item.tradable != null && item.tradable == 1,
                item.labelScore,
                item.labelStatus,
                item.evaluatedAt
        );
    }

    private AiLearningPayloads.ExperimentItem experimentItem(AiStrategyExperiment item) {
        return new AiLearningPayloads.ExperimentItem(item.id, item.title, item.status, item.universeCode, item.trainStartDate, item.trainEndDate, item.validationStartDate, item.validationEndDate, item.testStartDate, item.testEndDate, item.metricsJson, item.baselineMetricsJson, item.canPromote != null && item.canPromote == 1, item.promotedStrategyVersionId, item.createdAt);
    }

    private AiLearningPayloads.BacktestRunItem backtestRunItem(AiBacktestRun item) {
        return new AiLearningPayloads.BacktestRunItem(item.id, item.title, item.universeCode, item.horizonDays, item.topK, item.startDate, item.endDate, item.totalReturn, item.winRate, item.avgReturn, item.maxDrawdown, item.benchmarkReturn, item.tradeCount, item.status, item.createdAt);
    }

    private AiLearningPayloads.BacktestTradeItem backtestTradeItem(AiBacktestTrade item) {
        return new AiLearningPayloads.BacktestTradeItem(item.id, item.predictionId, item.stockCode, item.stockName, item.entryDate, item.exitDate, item.entryPrice, item.exitPrice, item.netReturn, item.maxDrawdown, item.rankNo);
    }

    private AiLearningPayloads.ModelEvalItem modelEvalItem(AiModelEvalRun item) {
        return new AiLearningPayloads.ModelEvalItem(item.id, item.modelName, item.provider, item.promptTemplateId, item.evalType, item.jsonSuccessRate, item.avgLatencyMs, item.sampleCount, item.score, item.metricsJson, item.status, item.createdAt);
    }

    private List<AiLearningPayloads.FactorCorrelationItem> factorCorrelations(Long userId) {
        List<AiFactorValue> hits = factorValueMapper.selectList(new QueryWrapper<AiFactorValue>()
                .eq("user_id", userId)
                .eq("hit", 1)
                .orderByDesc("calculated_at")
                .last("LIMIT 500"));
        Map<Long, List<AiFactorValue>> bySample = hits.stream().collect(Collectors.groupingBy(item -> item.sampleId));
        Map<String, Integer> pairCount = new LinkedHashMap<>();
        for (List<AiFactorValue> values : bySample.values()) {
            List<String> codes = values.stream().map(item -> item.factorCode).distinct().sorted().toList();
            for (int i = 0; i < codes.size(); i++) {
                for (int j = i + 1; j < codes.size(); j++) {
                    String key = codes.get(i) + "|" + codes.get(j);
                    pairCount.put(key, pairCount.getOrDefault(key, 0) + 1);
                }
            }
        }
        return pairCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(10)
                .map(entry -> {
                    String[] parts = entry.getKey().split("\\|", 2);
                    BigDecimal coHitRate = bySample.isEmpty() ? BigDecimal.ZERO : divide(new BigDecimal(entry.getValue() * 100), new BigDecimal(bySample.size()));
                    return new AiLearningPayloads.FactorCorrelationItem(parts[0], parts.length > 1 ? parts[1] : "", coHitRate, BigDecimal.ZERO, entry.getValue());
                })
                .toList();
    }

    private List<AiLearningPayloads.AlertItem> buildAlerts(List<AiPredictionSample> samples, List<AiPredictionLabel> labels) {
        List<AiLearningPayloads.AlertItem> alerts = new ArrayList<>();
        if (samples.stream().anyMatch(item -> safe(item.dataQualityScore).compareTo(new BigDecimal("60")) < 0)) {
            alerts.add(new AiLearningPayloads.AlertItem("数据质量偏低", "部分样本行情、财务或K线数据不完整，训练权重已降低。", "warning"));
        }
        if (labels.size() < 300) {
            alerts.add(new AiLearningPayloads.AlertItem("样本外数量不足", "开放智能选股前建议至少积累 300 条样本外标签。", "info"));
        }
        if (hitRate(labels).compareTo(new BigDecimal("45")) < 0 && !labels.isEmpty()) {
            alerts.add(new AiLearningPayloads.AlertItem("策略表现弱于预期", "近期命中率偏低，应观察或回滚策略版本。", "danger"));
        }
        return alerts;
    }

    private List<AiLearningPayloads.CurvePoint> labelCurve(List<AiPredictionLabel> labels, boolean hitRateMode) {
        Map<LocalDate, List<AiPredictionLabel>> grouped = labels.stream()
                .filter(item -> item.evaluatedAt != null)
                .collect(Collectors.groupingBy(item -> item.evaluatedAt.toLocalDate(), TreeMap::new, Collectors.toList()));
        return grouped.entrySet().stream()
                .map(entry -> new AiLearningPayloads.CurvePoint(
                        entry.getKey().toString(),
                        hitRateMode ? hitRate(entry.getValue()) : avg(entry.getValue().stream().map(item -> item.netReturn).toList()),
                        hitRateMode ? new BigDecimal("50") : BigDecimal.ZERO
                ))
                .toList();
    }

    private List<Map<String, Object>> equityCurve(List<AiPredictionLabel> labels) {
        BigDecimal equity = BigDecimal.ZERO;
        List<Map<String, Object>> points = new ArrayList<>();
        for (AiPredictionLabel label : labels) {
            equity = equity.add(safe(label.netReturn));
            points.add(Map.of("date", label.evaluatedAt == null ? "" : label.evaluatedAt.toLocalDate().toString(), "value", equity.setScale(4, RoundingMode.HALF_UP)));
        }
        return points;
    }

    private Map<String, Object> featureSnapshot(StockDetailResponse detail, String marketRegime) {
        return Map.of(
                "quote", detail.quote(),
                "finance", detail.finance(),
                "klineCount", detail.kline() == null ? 0 : detail.kline().size(),
                "lastKline", detail.kline() == null || detail.kline().isEmpty() ? "" : detail.kline().get(detail.kline().size() - 1),
                "marketRegime", marketRegime,
                "capturedAt", LocalDateTime.now().toString()
        );
    }

    private String buildPromptContext(AiPredictionSample sample, List<AiFactorValue> factors, AiPredictionResult prediction) {
        List<AiFactorValue> hits = factors.stream()
                .filter(item -> item.hit != null && item.hit == 1)
                .limit(10)
                .toList();
        String hitText = hits.stream()
                .map(item -> {
                    AiFactorDefinition definition = factorDefinitionMapper.selectOne(new QueryWrapper<AiFactorDefinition>()
                            .eq("factor_code", item.factorCode)
                            .eq("version_no", "v1")
                            .last("LIMIT 1"));
                    return "- %s/%s：%s，标准值=%s，证据=%s".formatted(
                            item.factorCode,
                            definition == null ? item.factorCode : definition.factorName,
                            item.direction,
                            safe(item.normalizedValue),
                            item.evidence == null ? "" : item.evidence
                    );
                })
                .collect(Collectors.joining("\n"));
        return """
                AI 学习系统样本：
                sampleId=%s，predictionId=%s，市场环境=%s，数据质量=%s，系统预评分=%s，风险分=%s，建议动作=%s，预测方向=%s。
                系统已计算的标准因子如下。模型只能引用这些因子 code，不要编造未计算因子：
                %s
                """.formatted(
                sample.id,
                prediction.id,
                sample.marketRegime,
                sample.dataQualityScore,
                prediction.score,
                prediction.riskScore,
                prediction.action,
                prediction.targetDirection,
                hitText.isBlank() ? "暂无命中因子，请保守输出。": hitText
        );
    }

    private AiPredictionSample ownedSample(Long userId, Long sampleId) {
        AiPredictionSample sample = sampleMapper.selectOne(new QueryWrapper<AiPredictionSample>()
                .eq("id", sampleId)
                .eq("user_id", userId)
                .last("LIMIT 1"));
        if (sample == null) {
            throw new IllegalArgumentException("样本不存在或无权访问");
        }
        return sample;
    }

    private List<AiPredictionSample> latestSamplesForUniverse(Long userId, String universeCode, int limit) {
        List<AiPredictionSample> rows = sampleMapper.selectList(new QueryWrapper<AiPredictionSample>()
                .eq("user_id", userId)
                .eq("universe_code", universeCode)
                .orderByDesc("sample_time")
                .last("LIMIT " + Math.max(1, limit)));
        Map<String, AiPredictionSample> deduped = new LinkedHashMap<>();
        for (AiPredictionSample row : rows) {
            deduped.putIfAbsent(row.stockCode, row);
        }
        return new ArrayList<>(deduped.values());
    }

    private Map<Long, AiPredictionSample> samplesByIds(List<Long> ids) {
        List<Long> clean = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) {
            return Map.of();
        }
        return sampleMapper.selectBatchIds(clean).stream().collect(Collectors.toMap(item -> item.id, Function.identity()));
    }

    private Map<Long, AiPredictionResult> predictionsByIds(List<Long> ids) {
        List<Long> clean = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (clean.isEmpty()) {
            return Map.of();
        }
        return predictionMapper.selectBatchIds(clean).stream().collect(Collectors.toMap(item -> item.id, Function.identity()));
    }

    private List<AiFactorValue> factorValuesForSample(Long sampleId) {
        return factorValueMapper.selectList(new QueryWrapper<AiFactorValue>()
                .eq("sample_id", sampleId)
                .orderByDesc("hit")
                .orderByDesc("normalized_value"));
    }

    private AiStrategyVersion activeStrategy(Long userId) {
        return strategyVersionMapper.selectOne(new QueryWrapper<AiStrategyVersion>()
                .eq("user_id", userId)
                .eq("active", 1)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
    }

    private Long activeStrategyId(Long userId) {
        AiStrategyVersion active = activeStrategy(userId);
        return active == null ? 0L : active.id;
    }

    private AiLearningJobLog startJob(String name, String type) {
        AiLearningJobLog job = new AiLearningJobLog();
        job.userId = AuthContext.currentUserIdOrDefault();
        job.jobName = name;
        job.jobType = type;
        job.status = "RUNNING";
        job.startedAt = LocalDateTime.now();
        job.processedCount = 0;
        job.successCount = 0;
        job.failedCount = 0;
        job.createdAt = job.startedAt;
        try {
            jobLogMapper.insert(job);
        } catch (RuntimeException ignored) {
            return job;
        }
        return job;
    }

    private void finishJob(AiLearningJobLog job, String status, int processed, int success, int failed, String error) {
        if (job == null || job.id == null) {
            return;
        }
        job.status = status;
        job.finishedAt = LocalDateTime.now();
        job.processedCount = processed;
        job.successCount = success;
        job.failedCount = failed;
        job.errorMessage = error;
        jobLogMapper.updateById(job);
    }

    private Metrics metrics(List<AiPredictionLabel> labels) {
        int sampleCount = labels.size();
        int hitCount = (int) labels.stream().filter(item -> item.hitTarget != null && item.hitTarget == 1).count();
        BigDecimal hitRate = sampleCount == 0 ? BigDecimal.ZERO : divide(new BigDecimal(hitCount * 100), new BigDecimal(sampleCount));
        BigDecimal avgReturn = avg(labels.stream().map(item -> item.netReturn).toList());
        BigDecimal maxDrawdown = labels.stream().map(item -> safe(item.maxAdverseReturn)).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal totalReturn = labels.stream().map(item -> safe(item.netReturn)).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Metrics(sampleCount, hitCount, hitRate, avgReturn, maxDrawdown, totalReturn);
    }

    private BigDecimal dataQualityScore(StockDetailResponse detail) {
        BigDecimal score = BigDecimal.ZERO;
        if (detail.quote() != null && safe(detail.quote().price()).compareTo(BigDecimal.ZERO) > 0) {
            score = score.add(new BigDecimal("30"));
        }
        if (detail.kline() != null && detail.kline().size() >= 20) {
            score = score.add(new BigDecimal("40"));
        } else if (detail.kline() != null && detail.kline().size() >= 5) {
            score = score.add(new BigDecimal("20"));
        }
        if (detail.finance() != null && safe(detail.finance().pe()).compareTo(BigDecimal.ZERO) > 0) {
            score = score.add(new BigDecimal("15"));
        }
        if (detail.intraday() != null && !detail.intraday().isEmpty()) {
            score = score.add(new BigDecimal("10"));
        }
        if (!"UNKNOWN".equals(marketRegime())) {
            score = score.add(new BigDecimal("5"));
        }
        return clamp(score, BigDecimal.ZERO, ONE_HUNDRED);
    }

    private boolean isTradable(StockDetailResponse detail) {
        String name = detail.quote() == null ? "" : detail.quote().name();
        return detail.quote() != null
                && safe(detail.quote().price()).compareTo(BigDecimal.ZERO) > 0
                && (name == null || !name.toUpperCase(Locale.ROOT).contains("ST"));
    }

    private String excludeReason(StockDetailResponse detail) {
        if (detail.quote() == null || safe(detail.quote().price()).compareTo(BigDecimal.ZERO) <= 0) {
            return "行情价格不可用";
        }
        if (detail.quote().name() != null && detail.quote().name().toUpperCase(Locale.ROOT).contains("ST")) {
            return "ST 风险股票";
        }
        return "不可交易";
    }

    private String marketRegime() {
        BigDecimal avg = marketAveragePercent();
        if (avg.compareTo(new BigDecimal("0.50")) >= 0) {
            return "STRONG";
        }
        if (avg.compareTo(new BigDecimal("-0.50")) <= 0) {
            return "WEAK";
        }
        return "RANGE";
    }

    private BigDecimal marketAveragePercent() {
        try {
            List<MarketIndexResponse> indexes = marketDataService.coreIndexes();
            return avg(indexes.stream().map(MarketIndexResponse::percent).toList());
        } catch (RuntimeException ex) {
            return BigDecimal.ZERO;
        }
    }

    private MarketBreadthResponse safeMarketBreadth() {
        try {
            return marketDataService.marketBreadth();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private int entryIndex(List<KlinePointResponse> klines, LocalDate date) {
        for (int i = 0; i < klines.size(); i++) {
            if (!klines.get(i).tradeDate().isBefore(date)) {
                return i;
            }
        }
        return -1;
    }

    private String predictionStockCode(AiPredictionResult prediction, AiPredictionSample sample) {
        return sample == null ? String.valueOf(prediction.sampleId) : sample.stockCode;
    }

    private String normalizeUniverse(String value) {
        return value == null || value.isBlank() ? DEFAULT_UNIVERSE : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizePhase(String value) {
        return value == null || value.isBlank() ? DEFAULT_PHASE : value.trim().toUpperCase(Locale.ROOT);
    }

    private int normalizeHorizon(Integer horizon) {
        return Math.max(1, Math.min(horizon == null ? 3 : horizon, 10));
    }

    private static FactorDefinitionSeed seed(String code, String name, String group, String direction, String formula, String weight) {
        return new FactorDefinitionSeed(code, name, group, direction, formula, new BigDecimal(weight));
    }

    private static FactorSignal signal(String code, BigDecimal value, boolean hit, String evidence) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value.abs().divide(new BigDecimal("10"), 6, RoundingMode.HALF_UP);
        return new FactorSignal(code, value == null ? BigDecimal.ZERO : value, normalized, hit, evidence);
    }

    private static BigDecimal ma(List<KlinePointResponse> klines, int window) {
        if (klines == null || klines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<KlinePointResponse> tail = klines.subList(Math.max(0, klines.size() - window), klines.size());
        return avg(tail.stream().map(KlinePointResponse::close).toList());
    }

    private static BigDecimal high(List<KlinePointResponse> klines, int window) {
        if (klines == null || klines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return klines.subList(Math.max(0, klines.size() - window), klines.size()).stream()
                .map(KlinePointResponse::high)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private static BigDecimal low(List<KlinePointResponse> klines, int window) {
        if (klines == null || klines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return klines.subList(Math.max(0, klines.size() - window), klines.size()).stream()
                .map(KlinePointResponse::low)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private static BigDecimal avgVolume(List<KlinePointResponse> klines, int window) {
        if (klines == null || klines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> values = klines.subList(Math.max(0, klines.size() - window), klines.size()).stream()
                .map(item -> item.volume() == null ? BigDecimal.ZERO : new BigDecimal(item.volume()))
                .toList();
        return avg(values);
    }

    private static BigDecimal periodReturn(List<KlinePointResponse> klines, int days) {
        if (klines == null || klines.size() <= days) {
            return BigDecimal.ZERO;
        }
        BigDecimal current = safe(klines.get(klines.size() - 1).close());
        BigDecimal base = safe(klines.get(klines.size() - 1 - days).close());
        return pct(current.subtract(base), base);
    }

    private static BigDecimal atrPct(List<KlinePointResponse> klines, int window) {
        if (klines == null || klines.size() < 2) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> ranges = new ArrayList<>();
        int start = Math.max(1, klines.size() - window);
        for (int i = start; i < klines.size(); i++) {
            KlinePointResponse item = klines.get(i);
            KlinePointResponse prev = klines.get(i - 1);
            BigDecimal highLow = safe(item.high()).subtract(safe(item.low())).abs();
            BigDecimal highClose = safe(item.high()).subtract(safe(prev.close())).abs();
            BigDecimal lowClose = safe(item.low()).subtract(safe(prev.close())).abs();
            ranges.add(highLow.max(highClose).max(lowClose));
        }
        BigDecimal atr = avg(ranges);
        BigDecimal close = safe(klines.get(klines.size() - 1).close());
        return pct(atr, close);
    }

    private static BigDecimal drawdownFromHigh(List<KlinePointResponse> klines, int window) {
        if (klines == null || klines.isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal high = high(klines, window);
        BigDecimal close = safe(klines.get(klines.size() - 1).close());
        return pct(close.subtract(high), high);
    }

    private static BigDecimal upperShadowRatio(KlinePointResponse item) {
        if (item == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal high = safe(item.high());
        BigDecimal low = safe(item.low());
        BigDecimal open = safe(item.open());
        BigDecimal close = safe(item.close());
        BigDecimal range = high.subtract(low);
        if (range.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal bodyTop = open.max(close);
        return high.subtract(bodyTop).divide(range, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return safe(numerator).divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal factorWeight(BigDecimal lowerBound, BigDecimal avgReturn, BigDecimal avgDrawdown, int sampleCount) {
        BigDecimal sampleBonus = new BigDecimal(Math.min(sampleCount, 100)).multiply(new BigDecimal("0.08"));
        return clamp(lowerBound.multiply(new BigDecimal("0.55"))
                .add(avgReturn.add(new BigDecimal("5")).multiply(new BigDecimal("2")))
                .add(avgDrawdown.multiply(new BigDecimal("0.9")))
                .add(sampleBonus), BigDecimal.ZERO, ONE_HUNDRED);
    }

    private static BigDecimal wilsonLowerBound(int success, int total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        double z = 1.96;
        double phat = success / (double) total;
        double denominator = 1 + z * z / total;
        double numerator = phat + z * z / (2 * total) - z * Math.sqrt((phat * (1 - phat) + z * z / (4 * total)) / total);
        return BigDecimal.valueOf((numerator / denominator) * 100).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal hitRate(List<AiPredictionLabel> labels) {
        if (labels == null || labels.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long hit = labels.stream().filter(item -> item.hitTarget != null && item.hitTarget == 1).count();
        return divide(new BigDecimal(hit * 100), new BigDecimal(labels.size()));
    }

    private static BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return safe(numerator).multiply(ONE_HUNDRED).divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal avg(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> clean = values.stream().filter(Objects::nonNull).toList();
        if (clean.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return clean.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(new BigDecimal(clean.size()), 4, RoundingMode.HALF_UP);
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

    private static BigDecimal scale(BigDecimal value) {
        return safe(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static BigDecimal clamp(BigDecimal value, BigDecimal min, BigDecimal max) {
        BigDecimal safe = safe(value);
        if (safe.compareTo(min) < 0) {
            return min.setScale(4, RoundingMode.HALF_UP);
        }
        if (safe.compareTo(max) > 0) {
            return max.setScale(4, RoundingMode.HALF_UP);
        }
        return safe.setScale(4, RoundingMode.HALF_UP);
    }

    private static int countInt(Long value) {
        return value == null ? 0 : value.intValue();
    }

    private static String percent(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private static String signedPercent(BigDecimal value) {
        BigDecimal safeValue = safe(value).setScale(2, RoundingMode.HALF_UP);
        return (safeValue.compareTo(BigDecimal.ZERO) > 0 ? "+" : "") + safeValue + "%";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    private record FactorDefinitionSeed(String code, String name, String group, String direction, String formulaDesc, BigDecimal weight) {
    }

    private record FactorSignal(String code, BigDecimal value, BigDecimal normalized, boolean hit, String evidence) {
    }

    private record PredictionScore(String action, String targetDirection, BigDecimal score, BigDecimal confidence, BigDecimal riskScore, List<Map<String, Object>> reasons) {
    }

    private record LabelResult(boolean hitDirection, boolean hitTarget, boolean hitStopLoss, BigDecimal score) {
    }

    private record FactorOutcome(AiFactorValue value, AiPredictionLabel label, AiPredictionSample sample) {
    }

    private record Metrics(int sampleCount, int hitCount, BigDecimal hitRate, BigDecimal avgReturn, BigDecimal maxDrawdown, BigDecimal totalReturn) {
        Map<String, Object> asMap() {
            return Map.of(
                    "sampleCount", sampleCount,
                    "hitCount", hitCount,
                    "hitRate", percent(hitRate),
                    "avgReturn", signedPercent(avgReturn),
                    "maxDrawdown", signedPercent(maxDrawdown),
                    "totalReturn", signedPercent(totalReturn)
            );
        }
    }
}
