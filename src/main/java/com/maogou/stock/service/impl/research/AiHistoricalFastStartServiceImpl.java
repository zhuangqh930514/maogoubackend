package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiHistoricalBackfillRun;
import com.maogou.stock.domain.entity.research.AiHistoricalBackfillShard;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.domain.entity.research.AiTrainingReadinessSnapshot;
import com.maogou.stock.dto.research.HistoricalFastStartPayloads;
import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;
import com.maogou.stock.mapper.research.AiHistoricalBackfillRunMapper;
import com.maogou.stock.mapper.research.AiHistoricalBackfillShardMapper;
import com.maogou.stock.mapper.research.AiDataQuarantineMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.mapper.research.AiTrainingReadinessSnapshotMapper;
import com.maogou.stock.service.research.AiHistoricalBootstrapService;
import com.maogou.stock.service.research.AiHistoricalEvidenceImportService;
import com.maogou.stock.service.research.AiHistoricalFastStartService;
import com.maogou.stock.service.research.AiResearchContract;
import com.maogou.stock.service.research.HistoricalBackfillShardExecutor;
import com.maogou.stock.service.research.HistoricalProviderPreflightService;
import com.maogou.stock.service.research.HistoricalReadinessEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * First coordinator layer for historical fast-start.
 *
 * <p>This class owns run/shard lifecycle and operator controls. Business work
 * is delegated to one real shard executor at a time. The old bootstrap service
 * is retained only by the package-private compatibility constructor used by
 * legacy tests and migration callers; Spring production wiring never uses it.</p>
 */
@Service
public class AiHistoricalFastStartServiceImpl implements AiHistoricalFastStartService {

    static final String ENGINE_VERSION = "HISTORICAL_FAST_START/2.0.0";
    static final String DEFAULT_MODE = "REPAIR_AND_EXPAND";
    static final String DEFAULT_FEATURE_VERSION = "POINT_IN_TIME/1.1.0";
    static final String DEFAULT_FACTOR_VERSION = "FACTOR/1.1.0";
    static final int DEFAULT_TARGET_TRADING_DAYS = 180;
    static final int DEFAULT_TARGET_STOCKS_PER_DAY = 300;
    static final int MIN_TARGET_TRADING_DAYS = 120;
    static final int MAX_TARGET_TRADING_DAYS = 400;
    static final int MIN_TARGET_STOCKS = 200;
    static final int MAX_TARGET_STOCKS = 300;
    static final int REPLAY_BLOCK_DAYS = 20;
    static final Duration LEASE_DURATION = Duration.ofHours(6);
    static final Duration PREVIEW_TTL = Duration.ofMinutes(30);

    private final AiHistoricalBackfillRunMapper runMapper;
    private final AiHistoricalBackfillShardMapper shardMapper;
    private final AiDataQuarantineMapper quarantineMapper;
    private final AiTrainingReadinessSnapshotMapper readinessMapper;
    private final AiSampleMapper sampleMapper;
    private final AiSampleLabelMapper labelMapper;
    private final AiStrategyReleaseMapper strategyReleaseMapper;
    private final AiHistoricalEvidenceImportService evidenceImportService;
    private final HistoricalBackfillShardExecutor shardExecutor;
    private final AiHistoricalBootstrapService bootstrapService;
    private final List<HistoricalMarketDataProvider> providers;
    private final HistoricalProviderPreflightService providerPreflightService;
    private final HistoricalReadinessEvaluator readinessEvaluator;
    private final TaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;

    @Autowired
    public AiHistoricalFastStartServiceImpl(
            AiHistoricalBackfillRunMapper runMapper,
            AiHistoricalBackfillShardMapper shardMapper,
            AiDataQuarantineMapper quarantineMapper,
            AiTrainingReadinessSnapshotMapper readinessMapper,
            AiSampleMapper sampleMapper,
            AiSampleLabelMapper labelMapper,
            AiStrategyReleaseMapper strategyReleaseMapper,
            AiHistoricalEvidenceImportService evidenceImportService,
            HistoricalBackfillShardExecutor shardExecutor,
            List<HistoricalMarketDataProvider> providers,
            HistoricalProviderPreflightService providerPreflightService,
            HistoricalReadinessEvaluator readinessEvaluator,
            @Qualifier("researchTaskExecutor") TaskExecutor taskExecutor,
            ObjectMapper objectMapper
    ) {
        this(runMapper, shardMapper, quarantineMapper, readinessMapper, sampleMapper, labelMapper,
                strategyReleaseMapper, evidenceImportService, shardExecutor, null, providers,
                providerPreflightService, readinessEvaluator, taskExecutor, objectMapper);
    }

    private AiHistoricalFastStartServiceImpl(
            AiHistoricalBackfillRunMapper runMapper,
            AiHistoricalBackfillShardMapper shardMapper,
            AiDataQuarantineMapper quarantineMapper,
            AiTrainingReadinessSnapshotMapper readinessMapper,
            AiSampleMapper sampleMapper,
            AiSampleLabelMapper labelMapper,
            AiStrategyReleaseMapper strategyReleaseMapper,
            AiHistoricalEvidenceImportService evidenceImportService,
            HistoricalBackfillShardExecutor shardExecutor,
            AiHistoricalBootstrapService bootstrapService,
            List<HistoricalMarketDataProvider> providers,
            HistoricalProviderPreflightService providerPreflightService,
            HistoricalReadinessEvaluator readinessEvaluator,
            TaskExecutor taskExecutor,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.shardMapper = shardMapper;
        this.quarantineMapper = quarantineMapper;
        this.readinessMapper = readinessMapper;
        this.sampleMapper = sampleMapper;
        this.labelMapper = labelMapper;
        this.strategyReleaseMapper = strategyReleaseMapper;
        this.evidenceImportService = evidenceImportService;
        this.shardExecutor = shardExecutor;
        this.bootstrapService = bootstrapService;
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.providerPreflightService = providerPreflightService == null
                ? HistoricalProviderPreflightService.noop() : providerPreflightService;
        this.readinessEvaluator = readinessEvaluator == null
                ? HistoricalReadinessEvaluator.noop() : readinessEvaluator;
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
    }

    AiHistoricalFastStartServiceImpl(
            AiHistoricalBackfillRunMapper runMapper,
            AiHistoricalBackfillShardMapper shardMapper,
            AiDataQuarantineMapper quarantineMapper,
            AiTrainingReadinessSnapshotMapper readinessMapper,
            AiSampleMapper sampleMapper,
            AiSampleLabelMapper labelMapper,
            AiStrategyReleaseMapper strategyReleaseMapper,
            AiHistoricalEvidenceImportService evidenceImportService,
            AiHistoricalBootstrapService bootstrapService,
            List<HistoricalMarketDataProvider> providers,
            TaskExecutor taskExecutor,
            ObjectMapper objectMapper
    ) {
        this(runMapper, shardMapper, quarantineMapper, readinessMapper, sampleMapper, labelMapper,
                strategyReleaseMapper, evidenceImportService, null, bootstrapService, providers,
                HistoricalProviderPreflightService.noop(), HistoricalReadinessEvaluator.noop(),
                taskExecutor, objectMapper);
    }

    @Override
    public HistoricalFastStartPayloads.PreviewResult preview(
            HistoricalFastStartPayloads.PreviewRequest request,
            Long operatorUserId
    ) {
        NormalizedConfig config = normalize(request);
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> blockingIssues = new ArrayList<>();
        HistoricalProviderPreflightService.PreflightResult preflight = providerPreflightService.check(
                config.endDate().atTime(16, 0), AiResearchContract.BENCHMARK_SYMBOL);
        List<Map<String, Object>> capabilities = preflight.capabilities();
        blockingIssues.addAll(preflight.blockingIssues());
        AiHistoricalEvidenceImportService.ColdStartPlan plan = null;
        try {
            plan = evidenceImportService.plan(config.endDate(), config.targetTradingDays(),
                    config.targetStocksPerDay());
        } catch (RuntimeException exception) {
            blockingIssues.add(issue("MISSING_TRADE_CALENDAR", "交易日历或历史行情预检失败",
                    rootMessage(exception), true));
        }

        AiStrategyRelease champion = activeChampion();
        if (champion == null || champion.id == null) {
            blockingIssues.add(issue("ACTIVE_STRATEGY_REQUIRED", "没有可用于历史回放的正式策略",
                    "请先启用全局 Champion 策略，再创建历史补齐任务", false));
        }

        if (plan == null) {
            return new HistoricalFastStartPayloads.PreviewResult(
                    null, configFingerprint(config, null, champion), null, null, null, null,
                    config.targetTradingDays(), config.targetStocksPerDay(), null,
                    Map.of("samples", 0L, "labels", 0L), Map.of(), Map.of(), capabilities,
                    blockingIssues, now.plus(PREVIEW_TTL));
        }

        LocalDate sampleStart = plan.tradingDates().get(0);
        int sampleEndIndex = Math.min(plan.trainingTradingDays(), plan.tradingDates().size()) - 1;
        LocalDate sampleEnd = plan.tradingDates().get(Math.max(0, sampleEndIndex));
        long reusableSamples = countReusableSamples(sampleStart, sampleEnd);
        long reusableLabels = countReusableLabels(sampleStart, sampleEnd, config.labelVersion());
        String configFingerprint = configFingerprint(config, plan, champion);
        String previewFingerprint = sha256(configFingerprint + "|" + reusableSamples + "|" + reusableLabels);
        long plannedSamples = (long) plan.trainingTradingDays() * plan.targetStockCount();
        long plannedLabels = plannedSamples * 4L;
        long plannedRequests = Math.max(0L, plannedSamples - reusableSamples)
                + Math.max(0L, plannedLabels - reusableLabels);

        return new HistoricalFastStartPayloads.PreviewResult(
                previewFingerprint,
                configFingerprint,
                plan.startDate(),
                sampleStart,
                sampleEnd,
                plan.endDate(),
                plan.trainingTradingDays(),
                plan.targetStockCount(),
                plan.replayTradingDays(),
                Map.of("samples", reusableSamples, "labels", reusableLabels),
                Map.of("tradingDays", plan.trainingTradingDays(), "samples", plannedSamples,
                        "labels", plannedLabels),
                Map.of("requests", plannedRequests, "bytes", 0L, "diskBytes", 0L),
                capabilities,
                blockingIssues,
                now.plus(PREVIEW_TTL));
    }

    @Override
    public HistoricalFastStartPayloads.RunView create(
            HistoricalFastStartPayloads.CreateRequest request,
            String idempotencyHeader,
            Long operatorUserId
    ) {
        if (request == null) {
            throw new IllegalArgumentException("历史补齐请求不能为空");
        }
        String idempotencyKey = firstNonBlank(idempotencyHeader, request.idempotencyKey());
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("必须提供 Idempotency-Key");
        }
        HistoricalFastStartPayloads.PreviewResult preview = preview(request.previewRequest(), operatorUserId);
        if (preview.previewFingerprint() == null
                || !Objects.equals(preview.previewFingerprint(), request.previewFingerprint())) {
            throw new IllegalArgumentException("预览已过期或配置已变化，请重新预览");
        }
        if (!preview.blockingIssues().isEmpty()) {
            throw new IllegalStateException("历史补齐存在阻断项，不能创建运行："
                    + preview.blockingIssues().get(0).get("reasonCode"));
        }

        NormalizedConfig config = normalize(request.previewRequest());
        AiHistoricalEvidenceImportService.ColdStartPlan plan = evidenceImportService.plan(
                config.endDate(), config.targetTradingDays(), config.targetStocksPerDay());
        AiStrategyRelease champion = activeChampion();
        if (champion == null || champion.id == null) {
            throw new IllegalStateException("没有可用于历史回放的正式策略");
        }
        String inputFingerprint = sha256(preview.previewFingerprint() + "|" + champion.id
                + "|" + Objects.toString(champion.modelVersionId, ""));
        AiHistoricalBackfillRun existing = runMapper.selectByRunKey(idempotencyKey);
        if (existing != null) {
            if (!Objects.equals(existing.sourceManifestChecksum, inputFingerprint)
                    && !Objects.equals(existing.runConfigJson, runConfig(config, plan, champion,
                    preview.previewFingerprint()))) {
                throw new IllegalStateException("幂等键已绑定不同历史补齐配置，拒绝覆盖原运行");
            }
            return toRunView(existing);
        }

        LocalDateTime now = LocalDateTime.now();
        AiHistoricalBackfillRun run = new AiHistoricalBackfillRun();
        run.runKey = idempotencyKey;
        run.mode = config.mode();
        run.requestedStartDate = plan.startDate();
        run.requestedEndDate = plan.endDate();
        run.effectiveSampleStartDate = preview.effectiveSampleStartDate();
        run.effectiveSampleEndDate = preview.effectiveSampleEndDate();
        run.targetTradingDays = plan.trainingTradingDays();
        run.targetStocksPerDay = plan.targetStockCount();
        run.featureVersion = config.featureVersion();
        run.factorVersion = config.factorVersion();
        run.labelVersion = config.labelVersion();
        run.calendarVersion = config.calendarVersion();
        run.industryStandard = config.industryStandard();
        run.sourceManifestChecksum = inputFingerprint;
        run.runConfigJson = runConfig(config, plan, champion, preview.previewFingerprint());
        run.status = "PLANNED";
        run.currentStage = "PLANNED";
        run.totalShards = shardCount(plan);
        run.succeededShards = 0;
        run.quarantinedShards = 0;
        run.failedShards = 0;
        run.requestedBy = operatorUserId;
        run.createdAt = now;
        run.updatedAt = now;
        runMapper.insertIgnore(run);
        AiHistoricalBackfillRun stored = runMapper.selectByRunKey(idempotencyKey);
        if (stored == null || stored.id == null) {
            throw new IllegalStateException("历史补齐运行创建后无法读取记录");
        }
        if (!Objects.equals(stored.sourceManifestChecksum, inputFingerprint)) {
            throw new IllegalStateException("历史补齐幂等键已绑定不同输入");
        }
        createShards(stored.id, plan, inputFingerprint, now);
        stored.totalShards = shardCount(plan);
        runMapper.updateById(stored);
        submit(stored.id);
        return toRunView(runMapper.selectByRunId(stored.id));
    }

    @Override
    public HistoricalFastStartPayloads.RunView createLegacy(
            AiHistoricalFastStartService.LegacyCreateCommand command
    ) {
        if (command == null || command.plan() == null || command.strategyReleaseId() == null
                || command.idempotencyKey() == null || command.idempotencyKey().isBlank()
                || command.pipelineRunId() == null) {
            throw new IllegalArgumentException("兼容历史入口缺少计划、策略、流水线或幂等键");
        }
        AiHistoricalEvidenceImportService.ColdStartPlan plan = command.plan();
        LocalDate sampleStart = plan.tradingDates().get(0);
        LocalDate sampleEnd = plan.tradingDates().get(Math.min(
                plan.trainingTradingDays(), plan.tradingDates().size()) - 1);
        NormalizedConfig config = new NormalizedConfig(
                plan.endDate(), plan.trainingTradingDays(), plan.targetStockCount(),
                DEFAULT_MODE, DEFAULT_FEATURE_VERSION, DEFAULT_FACTOR_VERSION,
                AiResearchContract.LABEL_VERSION, AiResearchContract.CALENDAR_VERSION,
                "SW2021_L1", command.strategyReleaseId(), command.modelVersionId());
        AiStrategyRelease strategy = new AiStrategyRelease();
        strategy.id = command.strategyReleaseId();
        strategy.modelVersionId = command.modelVersionId();
        String inputFingerprint = sha256("COMPAT|" + command.idempotencyKey() + "|"
                + command.pipelineRunId() + "|" + plan.startDate() + "|" + plan.endDate()
                + "|" + plan.trainingTradingDays() + "|" + plan.targetStockCount());
        AiHistoricalBackfillRun existing = runMapper.selectByRunKey(command.idempotencyKey());
        if (existing != null) {
            if (!Objects.equals(existing.pipelineRunId, command.pipelineRunId())
                    || !Objects.equals(existing.sourceManifestChecksum, inputFingerprint)) {
                throw new IllegalStateException("兼容入口幂等键已绑定不同历史运行");
            }
            return toRunView(existing);
        }
        LocalDateTime now = command.requestedAt() == null ? LocalDateTime.now() : command.requestedAt();
        AiHistoricalBackfillRun run = new AiHistoricalBackfillRun();
        run.pipelineRunId = command.pipelineRunId();
        run.runKey = command.idempotencyKey();
        run.mode = config.mode();
        run.requestedStartDate = plan.startDate();
        run.requestedEndDate = plan.endDate();
        run.effectiveSampleStartDate = sampleStart;
        run.effectiveSampleEndDate = sampleEnd;
        run.targetTradingDays = plan.trainingTradingDays();
        run.targetStocksPerDay = plan.targetStockCount();
        run.featureVersion = config.featureVersion();
        run.factorVersion = config.factorVersion();
        run.labelVersion = config.labelVersion();
        run.calendarVersion = config.calendarVersion();
        run.industryStandard = config.industryStandard();
        run.sourceManifestChecksum = inputFingerprint;
        run.runConfigJson = runConfig(config, plan, strategy,
                "COMPAT_EXECUTOR:" + sha256(command.idempotencyKey()));
        run.status = "PLANNED";
        run.currentStage = "PLANNED";
        run.totalShards = shardCount(plan);
        run.succeededShards = 0;
        run.quarantinedShards = 0;
        run.failedShards = 0;
        run.requestedBy = command.operatorUserId();
        run.createdAt = now;
        run.updatedAt = now;
        runMapper.insertIgnore(run);
        AiHistoricalBackfillRun stored = runMapper.selectByRunKey(command.idempotencyKey());
        if (stored == null || stored.id == null) {
            throw new IllegalStateException("兼容历史入口创建后无法读取 backfill run");
        }
        createShards(stored.id, plan, inputFingerprint, now);
        submit(stored.id);
        return toRunView(runMapper.selectByRunId(stored.id));
    }

    @Override
    public HistoricalFastStartPayloads.RunView getRun(Long runId, Long operatorUserId) {
        return toRunView(requiredRun(runId));
    }

    @Override
    public HistoricalFastStartPayloads.PageResult<HistoricalFastStartPayloads.ShardView> listShards(
            Long runId,
            HistoricalFastStartPayloads.ShardQuery query,
            Long operatorUserId
    ) {
        requiredRun(runId);
        HistoricalFastStartPayloads.ShardQuery safe = query == null
                ? new HistoricalFastStartPayloads.ShardQuery(null, null, null, 1, 50) : query;
        int page = safe.safePage();
        int size = safe.safeSize();
        long total = shardMapper.countByRun(runId, safe.stageKey(), safe.status(), safe.tradeDate());
        List<HistoricalFastStartPayloads.ShardView> items = shardMapper.selectPageByRun(
                        runId, safe.stageKey(), safe.status(), safe.tradeDate(), (page - 1) * size, size)
                .stream().map(AiHistoricalFastStartServiceImpl::toShardView).toList();
        return new HistoricalFastStartPayloads.PageResult<>(items, total, page, size);
    }

    @Override
    public HistoricalFastStartPayloads.PageResult<HistoricalFastStartPayloads.IssueView> listIssues(
            Long runId,
            HistoricalFastStartPayloads.IssueQuery query,
            Long operatorUserId
    ) {
        requiredRun(runId);
        HistoricalFastStartPayloads.IssueQuery safe = query == null
                ? new HistoricalFastStartPayloads.IssueQuery(null, null, null, 1, 50) : query;
        int page = safe.safePage();
        int size = safe.safeSize();
        long total = quarantineMapper.countByRun(runId, safe.reasonCode(), safe.stockCode(), safe.tradeDate());
        List<HistoricalFastStartPayloads.IssueView> items = quarantineMapper.selectPageByRun(
                        runId, safe.reasonCode(), safe.stockCode(), safe.tradeDate(), (page - 1) * size, size)
                .stream().map(AiHistoricalFastStartServiceImpl::toIssueView).toList();
        return new HistoricalFastStartPayloads.PageResult<>(items, total, page, size);
    }

    @Override
    public HistoricalFastStartPayloads.RunView pause(Long runId, Long operatorUserId) {
        requiredRun(runId);
        int updated = runMapper.pause(runId, "操作员暂停历史补齐", LocalDateTime.now());
        if (updated != 1) {
            throw new IllegalStateException("当前运行状态不允许暂停");
        }
        return toRunView(requiredRun(runId));
    }

    @Override
    public HistoricalFastStartPayloads.RunView resume(Long runId, Long operatorUserId) {
        requiredRun(runId);
        int updated = runMapper.resume(runId, LocalDateTime.now());
        if (updated != 1) {
            throw new IllegalStateException("当前运行状态不允许恢复");
        }
        submit(runId);
        return toRunView(requiredRun(runId));
    }

    @Override
    public HistoricalFastStartPayloads.RunView retryFailed(Long runId, Long operatorUserId) {
        requiredRun(runId);
        LocalDateTime now = LocalDateTime.now();
        if (runMapper.retryFailed(runId, "操作员请求重试失败分片", now) != 1) {
            throw new IllegalStateException("当前运行没有可重试的失败状态");
        }
        shardMapper.retryFailedByRun(runId, "等待重试", now);
        submit(runId);
        return toRunView(requiredRun(runId));
    }

    @Override
    public HistoricalFastStartPayloads.RunView cancel(Long runId, Long operatorUserId) {
        requiredRun(runId);
        if (runMapper.cancel(runId, "操作员取消历史补齐；已落地事实保留", LocalDateTime.now()) != 1) {
            throw new IllegalStateException("当前运行不允许取消");
        }
        return toRunView(requiredRun(runId));
    }

    @Override
    public HistoricalFastStartPayloads.ReadinessView validate(Long runId, Long operatorUserId) {
        return persistReadiness(requiredRun(runId));
    }

    private HistoricalFastStartPayloads.ReadinessView persistReadiness(AiHistoricalBackfillRun run) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate startDate = run.effectiveSampleStartDate == null
                ? run.requestedStartDate : run.effectiveSampleStartDate;
        LocalDate endDate = run.effectiveSampleEndDate == null
                ? run.requestedEndDate : run.effectiveSampleEndDate;
        if (startDate == null || endDate == null) {
            throw new IllegalStateException("历史补齐运行缺少有效样本日期范围，不能执行 readiness 校验");
        }
        HistoricalReadinessEvaluator.Evaluation evaluation = readinessEvaluator.evaluate(
                new HistoricalReadinessEvaluator.Request(
                        run.id, run.featureVersion, run.factorVersion, run.labelVersion,
                        run.calendarVersion, startDate, endDate, now));
        AiTrainingReadinessSnapshot snapshot = new AiTrainingReadinessSnapshot();
        snapshot.backfillRunId = run.id;
        snapshot.pipelineRunId = run.pipelineRunId;
        snapshot.asOfTime = now;
        snapshot.featureVersion = run.featureVersion;
        snapshot.factorVersion = run.factorVersion;
        snapshot.labelVersion = run.labelVersion;
        snapshot.calendarVersion = run.calendarVersion;
        snapshot.tradingDays = evaluation.tradingDays();
        snapshot.stockCount = evaluation.stockCount();
        snapshot.horizonCountsJson = json(evaluation.horizonCounts());
        snapshot.regimeDaysJson = json(evaluation.regimeDays());
        snapshot.tradabilityEligible = evaluation.tradabilityEligible();
        snapshot.tradabilityReady = evaluation.tradabilityReady();
        snapshot.tradabilityCoverage = evaluation.tradabilityCoverage();
        snapshot.universeEligible = evaluation.universeEligible();
        snapshot.universeReady = evaluation.universeReady();
        snapshot.universeCoverage = evaluation.universeCoverage();
        snapshot.sectorEligible = evaluation.sectorEligible();
        snapshot.sectorReady = evaluation.sectorReady();
        snapshot.sectorCoverage = evaluation.sectorCoverage();
        snapshot.featureCoverageJson = json(evaluation.featureCoverage());
        snapshot.classDistributionJson = json(evaluation.classDistribution());
        snapshot.leakageViolationCount = evaluation.leakageViolationCount();
        snapshot.duplicateCount = evaluation.duplicateCount();
        snapshot.mockSourceCount = evaluation.mockSourceCount();
        snapshot.staleSourceCount = evaluation.staleSourceCount();
        snapshot.inferredFactCount = evaluation.inferredFactCount();
        snapshot.status = evaluation.status();
        snapshot.blockingGapsJson = json(evaluation.blockingGaps());
        snapshot.evidenceChecksum = evaluation.evidenceChecksum();
        snapshot.createdAt = now;
        readinessMapper.insertIgnore(snapshot);
        AiTrainingReadinessSnapshot stored = readinessMapper.selectLatestByRunId(run.id);
        if (stored != null && stored.id != null) {
            runMapper.attachReadinessSnapshot(run.id, stored.id, now);
        }
        return toReadinessView(stored == null ? snapshot : stored);
    }

    @Override
    public HistoricalFastStartPayloads.ReadinessView latestReadiness(Long operatorUserId) {
        AiTrainingReadinessSnapshot snapshot = readinessMapper.selectLatest();
        return snapshot == null ? null : toReadinessView(snapshot);
    }

    private void submit(Long runId) {
        taskExecutor.execute(() -> execute(runId));
    }

    private void execute(Long runId) {
        AiHistoricalBackfillRun run = runMapper.selectByRunId(runId);
        if (run == null || terminal(run.status) || "PAUSED".equals(run.status)) {
            return;
        }
        if (shardExecutor == null) {
            executeCompatibility(runId);
            return;
        }
        String owner = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        if (runMapper.claimLease(runId, owner, now.plus(LEASE_DURATION), now) != 1) {
            return;
        }
        try {
            run = runMapper.selectByRunId(runId);
            run.leaseOwner = owner;
            run.leaseUntil = now.plus(LEASE_DURATION);
            run.status = "RUNNING";
            run.currentStage = "RUNNING";
            run.updatedAt = now;
            fencedUpdate(run, owner);

            NormalizedConfig config = configFromRun(run);
            AiHistoricalEvidenceImportService.ColdStartPlan plan = evidenceImportService.plan(
                    run.requestedEndDate, run.targetTradingDays, run.targetStocksPerDay);
            while (true) {
                AiHistoricalBackfillRun latest = runMapper.selectByRunId(runId);
                if (latest == null || "PAUSED".equals(latest.status) || "CANCELLED".equals(latest.status)) {
                    return;
                }
                AiHistoricalBackfillShard next = nextRunnableShard(runId);
                if (next == null) {
                    finishWhenDrained(run, owner);
                    return;
                }
                LocalDateTime claimTime = LocalDateTime.now();
                if (shardMapper.claimLease(next.id, owner, claimTime.plus(LEASE_DURATION), claimTime) != 1) {
                    continue;
                }
                AiHistoricalBackfillShard claimedShard = shardMapper.selectByShardId(next.id);
                if (claimedShard == null) {
                    throw new IllegalStateException("历史补齐分片抢占后无法读取：" + next.id);
                }
                run.currentStage = claimedShard.stageKey;
                run.updatedAt = claimTime;
                fencedUpdate(run, owner);
                try {
                    HistoricalBackfillShardExecutor.ExecutionResult result = shardExecutor.execute(
                            new HistoricalBackfillShardExecutor.ExecutionCommand(
                                    run.id, claimedShard, plan, config.strategyReleaseId(), config.modelVersionId(),
                                    config.featureVersion(), config.factorVersion(), config.labelVersion(),
                                    config.calendarVersion(),
                                    run.runKey, claimTime, value(next.attemptNo),
                                    () -> renewShardLease(claimedShard.id, owner),
                                    (checkpoint, output, rejected) -> checkpointShard(
                                            claimedShard, owner, checkpoint, output, rejected)));
                    if ("READINESS_CHECK".equals(claimedShard.stageKey)) {
                        AiHistoricalBackfillRun latestForReadiness = runMapper.selectByRunId(run.id);
                        if (latestForReadiness == null) {
                            throw new IllegalStateException("readiness 分片完成后无法读取运行：" + run.id);
                        }
                        persistReadiness(latestForReadiness);
                    }
                    completeShard(claimedShard, owner, result);
                } catch (RuntimeException exception) {
                    failShard(run, claimedShard, owner, exception);
                    return;
                }
                refreshCounts(run);
                run.updatedAt = LocalDateTime.now();
                fencedUpdate(run, owner);
            }
        } catch (RuntimeException exception) {
            AiHistoricalBackfillRun latest = runMapper.selectByRunId(runId);
            if (latest != null && ("PAUSED".equals(latest.status) || "CANCELLED".equals(latest.status))) {
                return;
            }
            try {
                run.status = retryable(exception) ? "FAILED_RETRYABLE" : "FAILED_FINAL";
                run.currentStage = "FAILED";
                run.errorSummary = rootMessage(exception);
                run.finishedAt = null;
                run.updatedAt = LocalDateTime.now();
                refreshCounts(run);
                fencedUpdate(run, owner);
            } catch (RuntimeException ignored) {
                runMapper.recoverExpiredLease(runId, LocalDateTime.now(), rootMessage(exception));
            }
        } finally {
            runMapper.releaseLease(runId, owner, LocalDateTime.now());
        }
    }

    private void executeCompatibility(Long runId) {
        if (bootstrapService == null) {
            throw new IllegalStateException("历史分片执行器未配置，且没有兼容执行器");
        }
        // This branch is reachable only from the package-private legacy constructor.
        // Spring production wiring always supplies HistoricalBackfillShardExecutor.
        AiHistoricalBackfillRun run = requiredRun(runId);
        NormalizedConfig config = configFromRun(run);
        AiHistoricalEvidenceImportService.ColdStartPlan plan = evidenceImportService.plan(
                run.requestedEndDate, run.targetTradingDays, run.targetStocksPerDay);
        bootstrapService.run(new AiHistoricalBootstrapService.BootstrapRequest(
                plan.startDate(), plan.endDate(), config.strategyReleaseId(), config.modelVersionId(),
                run.runKey, LocalDateTime.now(), plan, run.id));
    }

    private void createShards(
            Long runId,
            AiHistoricalEvidenceImportService.ColdStartPlan plan,
            String inputFingerprint,
            LocalDateTime now
    ) {
        int block = 0;
        for (int offset = 0; offset < plan.tradingDates().size(); offset += REPLAY_BLOCK_DAYS) {
            block++;
            List<LocalDate> dates = plan.tradingDates().subList(offset,
                    Math.min(offset + REPLAY_BLOCK_DAYS, plan.tradingDates().size()));
            insertShard(runId, "IMPORT_HISTORICAL_EVIDENCE", dates.get(0), block, dates.size(),
                    inputFingerprint + "|IMPORT_HISTORICAL_EVIDENCE|" + block, now);
            insertShard(runId, "REPLAY_BLOCK", dates.get(0), block, dates.size(),
                    inputFingerprint + "|REPLAY_BLOCK|" + block, now);
        }
        insertShard(runId, "MATURE_HISTORICAL_SAMPLE_LABELS", null, 0, 1, inputFingerprint, now);
        insertShard(runId, "EVALUATE_HISTORICAL_PREDICTIONS", null, 0, 1, inputFingerprint, now);
        insertShard(runId, "READINESS_CHECK", null, 0, 1, inputFingerprint, now);
    }

    private void insertShard(
            Long runId,
            String stage,
            LocalDate tradeDate,
            int bucket,
            int inputCount,
            String fingerprint,
            LocalDateTime now
    ) {
        AiHistoricalBackfillShard shard = new AiHistoricalBackfillShard();
        shard.backfillRunId = runId;
        shard.stageKey = stage;
        shard.tradeDate = tradeDate;
        shard.bucketNo = bucket;
        shard.idempotencyKey = runId + ":" + stage + ":" + Objects.toString(tradeDate, "NONE") + ":" + bucket;
        shard.status = "PENDING";
        shard.attemptNo = 0;
        shard.maxAttempts = 5;
        shard.inputCount = inputCount;
        shard.outputCount = 0;
        shard.rejectedCount = 0;
        shard.inputFingerprint = sha256(fingerprint);
        shard.createdAt = now;
        shard.updatedAt = now;
        shardMapper.insertIgnore(shard);
    }

    private void markRemainingShards(Long runId, String owner, String status, List<String> errors) {
        List<AiHistoricalBackfillShard> shards = shardMapper.selectPageByRun(
                runId, null, null, null, 0, 1000);
        LocalDateTime now = LocalDateTime.now();
        for (AiHistoricalBackfillShard shard : shards) {
            if ("SUCCESS".equals(shard.status) || "SUCCESS_WITH_WARNINGS".equals(shard.status)) {
                continue;
            }
            boolean alreadyOwned = "RUNNING".equals(shard.status) && Objects.equals(shard.leaseOwner, owner)
                    && shard.leaseUntil != null && !shard.leaseUntil.isBefore(now);
            if (!alreadyOwned && shardMapper.claimLease(shard.id, owner, now.plus(LEASE_DURATION), now) != 1) {
                continue;
            }
            shard.status = status;
            shard.outputCount = "FAILED_RETRYABLE".equals(status) ? 0 : shard.inputCount;
            shard.rejectedCount = errors == null ? 0 : errors.size();
            shard.errorCode = "FAILED_RETRYABLE".equals(status) ? "COMPAT_EXECUTOR_FAILED" : null;
            shard.errorMessage = summarize(errors);
            shard.errorDetail = shard.errorMessage;
            shard.finishedAt = "FAILED_RETRYABLE".equals(status) ? null : now;
            shard.updatedAt = now;
            updateShard(shard, owner, now);
        }
    }

    private void updateShard(AiHistoricalBackfillShard shard, String owner, LocalDateTime now) {
        if (shardMapper.updateStateFenced(shard, owner, now.plus(LEASE_DURATION), now) != 1) {
            throw new IllegalStateException("历史补齐分片租约已丢失，拒绝写入 checkpoint：" + shard.id);
        }
    }

    private void checkpointShard(
            AiHistoricalBackfillShard shard,
            String owner,
            String checkpoint,
            int outputCount,
            int rejectedCount
    ) {
        shard.status = "RUNNING";
        shard.checkpointJson = checkpoint;
        shard.outputCount = outputCount;
        shard.rejectedCount = rejectedCount;
        shard.outputFingerprint = sha256(checkpoint);
        shard.updatedAt = LocalDateTime.now();
        updateShard(shard, owner, shard.updatedAt);
    }

    private void completeShard(
            AiHistoricalBackfillShard shard,
            String owner,
            HistoricalBackfillShardExecutor.ExecutionResult result
    ) {
        shard.status = result.status();
        shard.outputCount = result.successCount();
        shard.rejectedCount = result.rejectedCount() + result.failedCount();
        shard.checkpointJson = result.checkpointJson();
        shard.outputFingerprint = result.outputFingerprint();
        shard.providerCode = result.providerCode();
        shard.endpointType = result.endpointType();
        shard.nextRetryAt = result.nextRetryAt();
        shard.errorCode = result.errorCode();
        shard.errorMessage = result.errorMessage();
        shard.errorDetail = result.errorDetail();
        shard.finishedAt = LocalDateTime.now();
        shard.updatedAt = shard.finishedAt;
        updateShard(shard, owner, shard.updatedAt);
    }

    private void failShard(
            AiHistoricalBackfillRun run,
            AiHistoricalBackfillShard shard,
            String owner,
            RuntimeException exception
    ) {
        boolean canRetry = retryable(exception) && value(shard.attemptNo) < value(shard.maxAttempts);
        shard.status = canRetry ? "FAILED_RETRYABLE" : "FAILED_FINAL";
        shard.errorCode = canRetry ? "HISTORICAL_SHARD_FAILED_RETRYABLE" : "HISTORICAL_SHARD_FAILED_FINAL";
        shard.errorMessage = rootMessage(exception);
        shard.errorDetail = shard.errorMessage;
        shard.nextRetryAt = canRetry ? LocalDateTime.now().plus(backoff(value(shard.attemptNo))) : null;
        shard.finishedAt = LocalDateTime.now();
        shard.updatedAt = shard.finishedAt;
        updateShard(shard, owner, shard.updatedAt);
        run.status = canRetry ? "FAILED_RETRYABLE" : "FAILED_FINAL";
        run.currentStage = shard.stageKey;
        run.errorSummary = shard.errorMessage;
        run.finishedAt = null;
        run.updatedAt = LocalDateTime.now();
        refreshCounts(run);
        fencedUpdate(run, owner);
    }

    private void finishWhenDrained(AiHistoricalBackfillRun run, String owner) {
        List<AiHistoricalBackfillShard> shards = shardMapper.selectPageByRun(
                run.id, null, null, null, 0, Math.max(1000, value(run.totalShards) + 10));
        boolean hasFinalFailure = shards.stream().anyMatch(shard -> "FAILED_FINAL".equals(shard.status));
        boolean hasRetryableFailure = shards.stream().anyMatch(shard -> "FAILED_RETRYABLE".equals(shard.status));
        boolean hasQuarantine = shards.stream().anyMatch(shard -> "QUARANTINED".equals(shard.status));
        boolean readinessBlocked = shards.stream().anyMatch(shard ->
                "BLOCKED_BY_QUALITY".equals(shard.status) || "INSUFFICIENT_DATA".equals(shard.status));
        boolean allSuccess = !shards.isEmpty() && shards.stream().allMatch(shard ->
                "SUCCESS".equals(shard.status) || "SUCCESS_WITH_WARNINGS".equals(shard.status));
        run.status = hasFinalFailure ? "FAILED_FINAL"
                : hasRetryableFailure ? "FAILED_RETRYABLE"
                : readinessBlocked ? "BLOCKED_BY_QUALITY"
                : hasQuarantine ? "QUARANTINED"
                : allSuccess && shards.stream().anyMatch(shard -> "SUCCESS_WITH_WARNINGS".equals(shard.status))
                ? "PARTIAL_SUCCESS" : allSuccess ? "SUCCESS" : "PARTIAL_SUCCESS";
        run.currentStage = allSuccess ? "HISTORICAL_RESEARCH_READY" : run.currentStage;
        run.finishedAt = LocalDateTime.now();
        run.updatedAt = run.finishedAt;
        refreshCounts(run);
        fencedUpdate(run, owner);
    }

    private AiHistoricalBackfillShard nextRunnableShard(Long runId) {
        List<AiHistoricalBackfillShard> shards = shardMapper.selectPageByRun(
                runId, null, null, null, 0, 1000);
        LocalDateTime now = LocalDateTime.now();
        return shards.stream()
                .filter(shard -> List.of("PENDING", "FAILED_RETRYABLE").contains(shard.status))
                .filter(shard -> shard.nextRetryAt == null || !shard.nextRetryAt.isAfter(now))
                .findFirst().orElse(null);
    }

    private void renewShardLease(Long shardId, String owner) {
        LocalDateTime now = LocalDateTime.now();
        if (shardMapper.renewLease(shardId, owner, now.plus(LEASE_DURATION), now) != 1) {
            throw new IllegalStateException("历史补齐分片租约已丢失，停止写入：" + shardId);
        }
    }

    private void fencedUpdate(AiHistoricalBackfillRun run, String owner) {
        LocalDateTime now = run.updatedAt == null ? LocalDateTime.now() : run.updatedAt;
        if (runMapper.updateStateFenced(run, owner, now.plus(LEASE_DURATION), now) != 1) {
            throw new IllegalStateException("历史补齐运行租约已丢失，拒绝更新状态：" + run.id);
        }
    }

    private AiHistoricalBackfillShard firstPendingShard(Long runId, String stage) {
        List<AiHistoricalBackfillShard> shards = shardMapper.selectPageByRun(
                runId, stage, null, null, 0, 1);
        return shards.isEmpty() ? null : shards.get(0);
    }

    private void refreshCounts(AiHistoricalBackfillRun run) {
        List<AiHistoricalBackfillShard> shards = shardMapper.selectPageByRun(
                run.id, null, null, null, 0, 1000);
        run.totalShards = shards.size();
        run.succeededShards = (int) shards.stream().filter(shard ->
                "SUCCESS".equals(shard.status) || "SUCCESS_WITH_WARNINGS".equals(shard.status)).count();
        run.failedShards = (int) shards.stream().filter(shard ->
                "FAILED_RETRYABLE".equals(shard.status) || "FAILED_FINAL".equals(shard.status)
                        || "BLOCKED_BY_QUALITY".equals(shard.status)
                        || "INSUFFICIENT_DATA".equals(shard.status)).count();
        run.quarantinedShards = (int) shards.stream().filter(shard -> "QUARANTINED".equals(shard.status)).count();
    }

    private HistoricalFastStartPayloads.RunView toRunView(AiHistoricalBackfillRun run) {
        if (run == null) {
            throw new IllegalStateException("历史补齐运行不存在");
        }
        return new HistoricalFastStartPayloads.RunView(
                run.id, run.runKey, run.mode, run.requestedStartDate, run.requestedEndDate,
                run.effectiveSampleStartDate, run.effectiveSampleEndDate, run.targetTradingDays,
                run.targetStocksPerDay, run.featureVersion, run.factorVersion, run.labelVersion,
                run.calendarVersion, run.industryStandard, run.status, run.currentStage,
                run.totalShards, run.succeededShards, run.quarantinedShards, run.failedShards,
                run.readinessSnapshotId, run.errorSummary, run.pipelineRunId, run.createdAt,
                run.startedAt, run.finishedAt, run.updatedAt);
    }

    private static HistoricalFastStartPayloads.ShardView toShardView(AiHistoricalBackfillShard shard) {
        return new HistoricalFastStartPayloads.ShardView(
                shard.id, shard.backfillRunId, shard.stageKey, shard.tradeDate, shard.bucketNo,
                shard.status, shard.attemptNo, shard.maxAttempts, shard.inputCount,
                shard.outputCount, shard.rejectedCount, shard.providerCode, shard.endpointType,
                shard.nextRetryAt, shard.startedAt, shard.finishedAt, shard.errorCode,
                shard.errorMessage, shard.errorDetail);
    }

    private static HistoricalFastStartPayloads.IssueView toIssueView(
            com.maogou.stock.domain.entity.research.AiDataQuarantine issue
    ) {
        return new HistoricalFastStartPayloads.IssueView(
                issue.id, issue.backfillRunId, issue.shardId, issue.providerCode,
                issue.datasetCode, issue.tradeDate, issue.stockCode, issue.industryCode,
                issue.rowNumber, issue.fieldName, issue.reasonCode, issue.reasonMessage,
                Integer.valueOf(1).equals(issue.retryable), issue.resolutionStatus,
                issue.createdAt, issue.resolvedAt);
    }

    private static HistoricalFastStartPayloads.ReadinessView toReadinessView(
            AiTrainingReadinessSnapshot snapshot
    ) {
        return new HistoricalFastStartPayloads.ReadinessView(
                snapshot.id, snapshot.backfillRunId, snapshot.asOfTime, snapshot.featureVersion,
                snapshot.factorVersion, snapshot.labelVersion, snapshot.tradingDays,
                snapshot.stockCount, snapshot.tradabilityReady, snapshot.universeReady,
                snapshot.sectorReady, snapshot.status, snapshot.blockingGapsJson,
                snapshot.evidenceChecksum, snapshot.createdAt, maturityLevel(snapshot.status),
                snapshot.horizonCountsJson, snapshot.regimeDaysJson, snapshot.tradabilityEligible,
                snapshot.tradabilityCoverage, snapshot.universeEligible, snapshot.universeCoverage,
                snapshot.sectorEligible, snapshot.sectorCoverage, snapshot.featureCoverageJson,
                snapshot.classDistributionJson, snapshot.leakageViolationCount, snapshot.duplicateCount,
                snapshot.mockSourceCount, snapshot.staleSourceCount, snapshot.inferredFactCount);
    }

    private static String maturityLevel(String status) {
        return "READY".equals(status) ? "R1_HISTORICAL_FACTS_READY" : "R0_RULES_LIVE";
    }

    private AiHistoricalBackfillRun requiredRun(Long runId) {
        if (runId == null || runId <= 0) {
            throw new IllegalArgumentException("历史补齐运行 ID 不合法");
        }
        AiHistoricalBackfillRun run = runMapper.selectByRunId(runId);
        if (run == null) {
            throw new IllegalArgumentException("历史补齐运行不存在：" + runId);
        }
        return run;
    }

    private AiStrategyRelease activeChampion() {
        return strategyReleaseMapper.selectGlobalActiveChampion(
                AiResearchContract.SYSTEM_UNIVERSE_CODE, AiResearchContract.MODEL_FAMILY);
    }

    private long countReusableSamples(LocalDate start, LocalDate end) {
        return sampleMapper.selectCount(new QueryWrapper<AiSample>()
                .between("trade_date", start, end)
                .in("quality_status", List.of("READY", "PARTIAL"))
                .eq("tradable_status", "TRADABLE"));
    }

    private long countReusableLabels(LocalDate start, LocalDate end, String labelVersion) {
        return labelMapper.selectCount(new QueryWrapper<AiSampleLabel>()
                .between("entry_trade_date", start, end)
                .eq("label_version", labelVersion)
                .eq("label_status", "MATURED")
                .eq("is_current", 1));
    }

    private List<Map<String, Object>> providerCapabilities() {
        return providers.stream().sorted(Comparator.comparing(HistoricalMarketDataProvider::providerCode))
                .map(provider -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("provider", provider.providerCode());
                    value.put("configured", true);
                    value.put("preflightStatus", "NOT_RUN");
                    value.put("sourceOfTruth", true);
                    return value;
                }).toList();
    }

    private static NormalizedConfig normalize(HistoricalFastStartPayloads.PreviewRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("预览请求不能为空");
        }
        LocalDate endDate = request.endDate() == null ? LocalDate.now() : request.endDate();
        int tradingDays = request.targetTradingDays() == null
                ? DEFAULT_TARGET_TRADING_DAYS : request.targetTradingDays();
        int stocks = request.targetStocksPerDay() == null
                ? DEFAULT_TARGET_STOCKS_PER_DAY : request.targetStocksPerDay();
        if (tradingDays < MIN_TARGET_TRADING_DAYS || tradingDays > MAX_TARGET_TRADING_DAYS) {
            throw new IllegalArgumentException("历史训练交易日必须在 120 到 400 之间");
        }
        if (stocks < MIN_TARGET_STOCKS || stocks > MAX_TARGET_STOCKS) {
            throw new IllegalArgumentException("历史训练股票数必须在 200 到 300 之间");
        }
        return new NormalizedConfig(endDate, tradingDays, stocks,
                blankDefault(request.mode(), DEFAULT_MODE),
                blankDefault(request.featureVersion(), DEFAULT_FEATURE_VERSION),
                blankDefault(request.factorVersion(), DEFAULT_FACTOR_VERSION),
                blankDefault(request.labelVersion(), AiResearchContract.LABEL_VERSION),
                blankDefault(request.calendarVersion(), AiResearchContract.CALENDAR_VERSION),
                blankDefault(request.industryStandard(), "SW2021_L1"));
    }

    private static String runConfig(
            NormalizedConfig config,
            AiHistoricalEvidenceImportService.ColdStartPlan plan,
            AiStrategyRelease champion,
            String previewFingerprint
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("engineVersion", ENGINE_VERSION);
        value.put("previewFingerprint", previewFingerprint);
        value.put("strategyReleaseId", champion.id);
        value.put("modelVersionId", champion.modelVersionId);
        value.put("endDate", config.endDate());
        value.put("targetTradingDays", config.targetTradingDays());
        value.put("targetStocksPerDay", config.targetStocksPerDay());
        value.put("mode", config.mode());
        value.put("featureVersion", config.featureVersion());
        value.put("factorVersion", config.factorVersion());
        value.put("labelVersion", config.labelVersion());
        value.put("calendarVersion", config.calendarVersion());
        value.put("industryStandard", config.industryStandard());
        value.put("effectiveRawStartDate", plan.startDate());
        value.put("effectiveRawEndDate", plan.endDate());
        value.put("replayTradingDays", plan.replayTradingDays());
        try {
            return new ObjectMapper().findAndRegisterModules().writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存历史补齐配置", exception);
        }
    }

    private NormalizedConfig configFromRun(AiHistoricalBackfillRun run) {
        try {
            JsonNode root = objectMapper.readTree(run.runConfigJson);
            return new NormalizedConfig(
                    run.requestedEndDate,
                    root.path("targetTradingDays").asInt(run.targetTradingDays),
                    root.path("targetStocksPerDay").asInt(run.targetStocksPerDay),
                    root.path("mode").asText(run.mode),
                    root.path("featureVersion").asText(run.featureVersion),
                    root.path("factorVersion").asText(run.factorVersion),
                    root.path("labelVersion").asText(run.labelVersion),
                    root.path("calendarVersion").asText(run.calendarVersion),
                    root.path("industryStandard").asText(run.industryStandard),
                    root.path("strategyReleaseId").asLong(0),
                    root.path("modelVersionId").isNull() ? null : root.path("modelVersionId").asLong());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("历史补齐配置损坏，无法恢复：" + run.id, exception);
        }
    }

    private String configFingerprint(
            NormalizedConfig config,
            AiHistoricalEvidenceImportService.ColdStartPlan plan,
            AiStrategyRelease champion
    ) {
        return sha256(String.join("|", config.endDate().toString(),
                String.valueOf(config.targetTradingDays()), String.valueOf(config.targetStocksPerDay()),
                config.mode(), config.featureVersion(), config.factorVersion(), config.labelVersion(),
                config.calendarVersion(), config.industryStandard(),
                plan == null ? "" : plan.startDate().toString(),
                plan == null ? "" : plan.endDate().toString(),
                champion == null ? "" : String.valueOf(champion.id),
                champion == null ? "" : String.valueOf(champion.modelVersionId)));
    }

    private static int shardCount(AiHistoricalEvidenceImportService.ColdStartPlan plan) {
        return 4 + (int) Math.ceil(plan.tradingDates().size() / (double) REPLAY_BLOCK_DAYS);
    }

    private static Map<String, Object> issue(String code, String title, String reason, boolean retryable) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("reasonCode", code);
        value.put("title", title);
        value.put("reason", reason);
        value.put("retryable", retryable);
        return value;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim()
                : second != null && !second.isBlank() ? second.trim() : null;
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean terminal(String status) {
        return List.of("SUCCESS", "CANCELLED", "FAILED_FINAL", "BLOCKED_BY_QUALITY",
                "INSUFFICIENT_DATA", "QUARANTINED").contains(status);
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static boolean retryable(Throwable throwable) {
        String message = rootMessage(throwable).toLowerCase(java.util.Locale.ROOT);
        if (message.contains("401") || message.contains("403") || message.contains("forbidden")
                || message.contains("permission") || message.contains("权限")
                || message.contains("schema") || message.contains("字段")) {
            return false;
        }
        return message.contains("timeout") || message.contains("timed out")
                || message.contains("unexpected end of file") || message.contains("eof")
                || message.contains("connection reset") || message.contains("connection refused")
                || message.contains("429") || message.contains("502")
                || message.contains("503") || message.contains("504")
                || message.contains("temporarily") || message.contains("lock wait");
    }

    private static Duration backoff(int attemptNo) {
        return switch (Math.min(Math.max(attemptNo, 1), 5)) {
            case 1 -> Duration.ofSeconds(2);
            case 2 -> Duration.ofSeconds(5);
            case 3 -> Duration.ofSeconds(15);
            case 4 -> Duration.ofSeconds(30);
            default -> Duration.ofSeconds(60);
        };
    }

    private static String summarize(List<String> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        return messages.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank())
                .limit(8).reduce((left, right) -> left + "；" + right).orElse(null);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成历史准入证据", exception);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record NormalizedConfig(
            LocalDate endDate,
            int targetTradingDays,
            int targetStocksPerDay,
            String mode,
            String featureVersion,
            String factorVersion,
            String labelVersion,
            String calendarVersion,
            String industryStandard,
            Long strategyReleaseId,
            Long modelVersionId
    ) {
        private NormalizedConfig(
                LocalDate endDate,
                int targetTradingDays,
                int targetStocksPerDay,
                String mode,
                String featureVersion,
                String factorVersion,
                String labelVersion,
                String calendarVersion,
                String industryStandard
        ) {
            this(endDate, targetTradingDays, targetStocksPerDay, mode, featureVersion, factorVersion,
                    labelVersion, calendarVersion, industryStandard, null, null);
        }
    }
}
