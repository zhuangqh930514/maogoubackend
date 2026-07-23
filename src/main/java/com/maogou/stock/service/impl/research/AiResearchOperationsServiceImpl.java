package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPipelineStep;
import com.maogou.stock.domain.entity.research.AiStrategyGovernanceEvent;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.mapper.research.AiStrategyGovernanceEventMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiResearchCycleResult;
import com.maogou.stock.service.research.AiGlobalDailyResearchService;
import com.maogou.stock.service.research.AiHistoricalBootstrapService;
import com.maogou.stock.service.research.AiHistoricalEvidenceImportService;
import com.maogou.stock.service.research.AiLabelVerificationCoordinator;
import com.maogou.stock.service.research.AiMonthlyTrainingRunner;
import com.maogou.stock.service.research.AiResearchOperationsService;
import com.maogou.stock.service.research.AiStrategyGovernanceService;
import com.maogou.stock.service.research.AiUserDailyProjectionService;
import com.maogou.stock.service.research.AiWeeklyEvolutionRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class AiResearchOperationsServiceImpl implements AiResearchOperationsService {

    private static final Duration OPERATION_LEASE = Duration.ofHours(12);
    private static final Duration STALE_RUN_GRACE = Duration.ofMinutes(30);

    private final AiPipelineRunMapper runMapper;
    private final AiPipelineStepMapper stepMapper;
    private final AiStrategyReleaseMapper releaseMapper;
    private final AiStrategyGovernanceEventMapper eventMapper;
    private final TradingCalendarService tradingCalendarService;
    private final AiGlobalDailyResearchService dailyResearchService;
    private final AiHistoricalBootstrapService bootstrapService;
    private final AiHistoricalEvidenceImportService historicalEvidenceImportService;
    private final AiLabelVerificationCoordinator labelCoordinator;
    private final AiWeeklyEvolutionRunner weeklyRunner;
    private final AiMonthlyTrainingRunner trainingRunner;
    private final AiUserDailyProjectionService projectionService;
    private final AiStrategyGovernanceService governanceService;
    private final TaskExecutor taskExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public AiResearchOperationsServiceImpl(
            AiPipelineRunMapper runMapper,
            AiPipelineStepMapper stepMapper,
            AiStrategyReleaseMapper releaseMapper,
            AiStrategyGovernanceEventMapper eventMapper,
            TradingCalendarService tradingCalendarService,
            AiGlobalDailyResearchService dailyResearchService,
            AiHistoricalBootstrapService bootstrapService,
            AiHistoricalEvidenceImportService historicalEvidenceImportService,
            AiLabelVerificationCoordinator labelCoordinator,
            AiWeeklyEvolutionRunner weeklyRunner,
            AiMonthlyTrainingRunner trainingRunner,
            AiUserDailyProjectionService projectionService,
            AiStrategyGovernanceService governanceService,
            @Qualifier("researchTaskExecutor") TaskExecutor taskExecutor,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper
    ) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.releaseMapper = releaseMapper;
        this.eventMapper = eventMapper;
        this.tradingCalendarService = tradingCalendarService;
        this.dailyResearchService = dailyResearchService;
        this.bootstrapService = bootstrapService;
        this.historicalEvidenceImportService = historicalEvidenceImportService;
        this.labelCoordinator = labelCoordinator;
        this.weeklyRunner = weeklyRunner;
        this.trainingRunner = trainingRunner;
        this.projectionService = projectionService;
        this.governanceService = governanceService;
        this.taskExecutor = taskExecutor;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResearchLabPayloads.ActionAccepted runDaily(Long actorUserId, ResearchLabPayloads.ActionRequest request) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate tradeDate = resolveClosedResearchTradeDate(request.tradeDate(), now);
        AiStrategyRelease strategy = strategy(request.strategyReleaseId());
        Long modelId = request.modelVersionId() == null ? strategy.modelVersionId : request.modelVersionId();
        String input = fingerprint("GLOBAL_DAILY_RESEARCH", tradeDate, strategy.id, modelId);
        String key = key(request.idempotencyKey(), "MANUAL:GLOBAL_DAILY_RESEARCH:" + tradeDate + ":" + strategy.id);
        AiPipelineRun run = prepareRun("GLOBAL", null, null, tradeDate, "GLOBAL_DAILY_RESEARCH",
                strategy.id, modelId, key, input, actorUserId, null);
        submitExistingRun(run, () -> dailyResearchService.run(new AiGlobalDailyResearchService.PipelineRequest(
                tradeDate, strategy.id, modelId, key, input, now)));
        return accepted(run);
    }

    @Override
    public ResearchLabPayloads.ActionAccepted runHistoricalBootstrap(
            Long actorUserId,
            ResearchLabPayloads.ActionRequest request
    ) {
        int trainingTradingDays = request.historyTradingDays() == null ? 120 : request.historyTradingDays();
        int targetStockCount = request.historyStockCount() == null ? 240 : request.historyStockCount();
        if (trainingTradingDays < 120 || trainingTradingDays > 400) {
            throw new IllegalArgumentException("历史训练交易日必须在 120 到 400 之间");
        }
        if (targetStockCount < 200 || targetStockCount > 300) {
            throw new IllegalArgumentException("历史训练股票数必须在 200 到 300 之间");
        }
        LocalDate requestedEndDate = request.endDate() == null
                ? request.tradeDate() == null ? LocalDate.now() : request.tradeDate()
                : request.endDate();
        AiHistoricalEvidenceImportService.ColdStartPlan plan = historicalEvidenceImportService.plan(
                requestedEndDate, trainingTradingDays, targetStockCount);
        AiStrategyRelease strategy = strategy(request.strategyReleaseId());
        Long modelId = request.modelVersionId() == null ? strategy.modelVersionId : request.modelVersionId();
        String input = fingerprint("GLOBAL_HISTORICAL_BOOTSTRAP/2.0.0", plan.startDate(),
                plan.endDate(), plan.trainingTradingDays(), plan.targetStockCount(), strategy.id, modelId);
        String key = key(request.idempotencyKey(), "MANUAL:GLOBAL_HISTORICAL_BOOTSTRAP:"
                + plan.startDate() + ":" + plan.endDate() + ":" + plan.trainingTradingDays()
                + ":" + plan.targetStockCount() + ":" + strategy.id);
        AiPipelineRun run = prepareRun("GLOBAL", null, null, plan.endDate(),
                "GLOBAL_HISTORICAL_BOOTSTRAP", strategy.id, modelId, key, input, actorUserId, null);
        submitExistingRun(run, () -> bootstrapService.run(new AiHistoricalBootstrapService.BootstrapRequest(
                plan.startDate(), plan.endDate(), strategy.id, modelId, key, LocalDateTime.now(), plan)));
        return accepted(run);
    }

    @Override
    public ResearchLabPayloads.ActionAccepted verifyLabels(
            Long actorUserId,
            ResearchLabPayloads.ActionRequest request
    ) {
        LocalDate tradeDate = request.tradeDate() == null ? LocalDate.now() : request.tradeDate();
        String key = key(request.idempotencyKey(), "MANUAL:VERIFY_LABELS:" + tradeDate);
        AiPipelineRun run = prepareRun("GLOBAL", null, null, tradeDate, "VERIFY_LABELS",
                request.strategyReleaseId(), request.modelVersionId(), key,
                fingerprint("VERIFY_LABELS", tradeDate), actorUserId, null);
        submitManaged(run, "VERIFY_LABELS", () -> {
            LocalDateTime now = LocalDateTime.now();
            AiLabelVerificationCoordinator.VerificationResult labels =
                    labelCoordinator.matureSampleLabels(tradeDate, now);
            AiLabelVerificationCoordinator.VerificationResult evaluations =
                    labelCoordinator.evaluatePredictions(tradeDate, LocalDateTime.now());
            return new Counts(labels.processedCount() + evaluations.processedCount(),
                    labels.successCount() + evaluations.successCount(),
                    labels.failedCount() + evaluations.failedCount());
        });
        return accepted(run);
    }

    @Override
    public ResearchLabPayloads.ActionAccepted runWeekly(Long actorUserId, ResearchLabPayloads.ActionRequest request) {
        LocalDate tradeDate = request.tradeDate() == null ? LocalDate.now() : request.tradeDate();
        String key = key(request.idempotencyKey(), "MANUAL:WEEKLY_RESEARCH:" + tradeDate);
        AiPipelineRun run = prepareRun("GLOBAL", null, null, tradeDate, "GLOBAL_WEEKLY_RESEARCH",
                request.strategyReleaseId(), request.modelVersionId(), key,
                fingerprint("GLOBAL_WEEKLY_RESEARCH", tradeDate), actorUserId, null);
        submitManagedCycle(run, "RUN_WEEKLY", () -> weeklyRunner.run(actorUserId, LocalDateTime.now()));
        return accepted(run);
    }

    @Override
    public ResearchLabPayloads.ActionAccepted runTraining(Long actorUserId, ResearchLabPayloads.ActionRequest request) {
        LocalDate tradeDate = request.tradeDate() == null ? LocalDate.now() : request.tradeDate();
        String key = key(request.idempotencyKey(), "MANUAL:MONTHLY_TRAINING:" + tradeDate);
        AiPipelineRun run = prepareRun("GLOBAL", null, null, tradeDate, "GLOBAL_MONTHLY_TRAINING",
                request.strategyReleaseId(), request.modelVersionId(), key,
                fingerprint("GLOBAL_MONTHLY_TRAINING", tradeDate), actorUserId, null);
        submitManagedCycle(run, "RUN_TRAINING", () -> trainingRunner.run(actorUserId, LocalDateTime.now()));
        return accepted(run);
    }

    @Override
    public ResearchLabPayloads.ActionAccepted runUserProjection(
            Long authenticatedUserId,
            ResearchLabPayloads.ActionRequest request
    ) {
        requireUser(authenticatedUserId);
        LocalDateTime now = LocalDateTime.now();
        LocalDate requestedTradeDate = resolveClosedResearchTradeDate(request.tradeDate(), now);
        AiPipelineRun globalRun = globalRun(request.parentPipelineRunId(), requestedTradeDate);
        LocalDate tradeDate = globalRun.tradeDate == null ? requestedTradeDate : globalRun.tradeDate;
        String key = key(request.idempotencyKey(),
                "MANUAL:USER_DAILY_PROJECTION:" + authenticatedUserId + ":" + tradeDate);
        String input = fingerprint("USER_DAILY_PROJECTION", authenticatedUserId, tradeDate,
                globalRun.id, globalRun.strategyReleaseId, globalRun.modelVersionId);
        AiPipelineRun run = prepareRun("USER", authenticatedUserId, globalRun.id, tradeDate,
                "USER_DAILY_PROJECTION", globalRun.strategyReleaseId, globalRun.modelVersionId,
                key, input, authenticatedUserId, null);
        submitManaged(run, "PROJECT_USER_DAILY", () -> {
            AiUserDailyProjectionService.ProjectionResult result = projectionService.project(
                    new AiUserDailyProjectionService.ProjectionRequest(
                            authenticatedUserId, tradeDate, globalRun.id, run.id, key, LocalDateTime.now()));
            return new Counts(result.items().size(), result.items().size(), 0);
        });
        return accepted(run);
    }

    private LocalDate resolveClosedResearchTradeDate(LocalDate requestedTradeDate, LocalDateTime now) {
        LocalDateTime current = now == null ? LocalDateTime.now() : now;
        LocalDate latestClosedTradeDate = tradingCalendarService.latestExpectedKlineDate(current);
        if (requestedTradeDate == null) {
            return latestClosedTradeDate;
        }
        LocalDate normalized = tradingCalendarService.isTradingDay(requestedTradeDate)
                ? requestedTradeDate
                : tradingCalendarService.previousTradingDay(requestedTradeDate);
        if (normalized.isAfter(latestClosedTradeDate)) {
            return latestClosedTradeDate;
        }
        return normalized;
    }

    @Override
    public ResearchLabPayloads.ActionAccepted promote(
            Long actorUserId,
            Long strategyId,
            ResearchLabPayloads.GovernanceRequest request
    ) {
        requireGovernance(request, true);
        AiStrategyRelease strategy = requiredStrategy(strategyId);
        AiPipelineRun run = governanceRun(actorUserId, strategy, "STRATEGY_PROMOTION", request);
        submitManaged(run, "PROMOTE_STRATEGY", () -> {
            governanceService.confirmPromotion(new AiStrategyGovernanceService.ConfirmationRequest(
                    strategyId, request.assessmentEventKey(), actorUserId,
                    policy(request), request.reason(), LocalDateTime.now()));
            return Counts.one();
        });
        return accepted(run);
    }

    @Override
    public ResearchLabPayloads.ActionAccepted reject(
            Long actorUserId,
            Long strategyId,
            ResearchLabPayloads.GovernanceRequest request
    ) {
        requireGovernance(request, false);
        AiStrategyRelease strategy = requiredStrategy(strategyId);
        AiPipelineRun run = governanceRun(actorUserId, strategy, "STRATEGY_REJECTION", request);
        submitManaged(run, "REJECT_STRATEGY", () -> {
            transactionTemplate.executeWithoutResult(status -> rejectInTransaction(
                    actorUserId, strategyId, request));
            return Counts.one();
        });
        return accepted(run);
    }

    @Override
    public ResearchLabPayloads.ActionAccepted rollback(
            Long actorUserId,
            Long strategyId,
            ResearchLabPayloads.GovernanceRequest request
    ) {
        requireGovernance(request, false);
        if (request.previousChampionReleaseId() == null || request.shadowEvaluationId() == null
                || request.criticalDriftCount() == null || request.degradationFingerprint() == null) {
            throw new IllegalArgumentException("策略回滚缺少上一版 Champion、影子评估或严重退化证据");
        }
        AiStrategyRelease strategy = requiredStrategy(strategyId);
        AiPipelineRun run = governanceRun(actorUserId, strategy, "STRATEGY_ROLLBACK", request);
        submitManaged(run, "ROLLBACK_STRATEGY", () -> {
            governanceService.rollback(new AiStrategyGovernanceService.RollbackRequest(
                    strategyId, request.previousChampionReleaseId(), request.shadowEvaluationId(),
                    request.criticalDriftCount(), request.degradationFingerprint(), policy(request),
                    request.reason(), LocalDateTime.now()));
            return Counts.one();
        });
        return accepted(run);
    }

    private AiPipelineRun governanceRun(
            Long actorUserId,
            AiStrategyRelease strategy,
            String type,
            ResearchLabPayloads.GovernanceRequest request
    ) {
        String key = key(request.idempotencyKey(), "MANUAL:" + type + ":" + strategy.id + ":"
                + fingerprint(request.reason(), request.assessmentEventKey(), request.degradationFingerprint()));
        return prepareRun("GLOBAL", null, null, LocalDate.now(), type,
                strategy.id, strategy.modelVersionId, key,
                fingerprint(type, strategy.id, request.assessmentEventKey(), request.previousChampionReleaseId(),
                        request.shadowEvaluationId(), request.degradationFingerprint(), request.reason()),
                actorUserId, request.reason());
    }

    private AiPipelineRun prepareRun(
            String scope,
            Long ownerUserId,
            Long parentRunId,
            LocalDate tradeDate,
            String pipelineType,
            Long strategyId,
            Long modelId,
            String idempotencyKey,
            String inputFingerprint,
            Long actorUserId,
            String reason
    ) {
        LocalDateTime now = LocalDateTime.now();
        AiPipelineRun expected = new AiPipelineRun();
        expected.scopeType = scope;
        expected.ownerUserId = ownerUserId;
        expected.parentRunId = parentRunId;
        expected.strategyReleaseId = strategyId;
        expected.modelVersionId = modelId;
        expected.tradeDate = tradeDate;
        expected.pipelineType = pipelineType;
        expected.idempotencyKey = idempotencyKey;
        expected.inputFingerprint = inputFingerprint;
        expected.status = "PENDING";
        expected.retryCount = 0;
        expected.processedCount = 0;
        expected.successCount = 0;
        expected.failedCount = 0;
        expected.createdAt = now;
        expected.updatedAt = now;
        runMapper.insertIgnore(expected);
        AiPipelineRun stored = runMapper.selectOne(new QueryWrapper<AiPipelineRun>()
                .eq("idempotency_key", idempotencyKey).last("LIMIT 1"));
        if (stored == null || stored.id == null) {
            throw new IllegalStateException("异步研究任务创建后未读取到流水线");
        }
        if (!Objects.equals(stored.scopeType, scope)
                || !Objects.equals(stored.ownerUserId, ownerUserId)
                || !Objects.equals(stored.parentRunId, parentRunId)
                || !Objects.equals(stored.pipelineType, pipelineType)
                || !Objects.equals(stored.tradeDate, tradeDate)
                || !Objects.equals(stored.strategyReleaseId, strategyId)
                || !Objects.equals(stored.modelVersionId, modelId)
                || !Objects.equals(stored.inputFingerprint, inputFingerprint)) {
            throw new IllegalStateException("幂等键已绑定不同研究任务输入");
        }
        recoverStaleRun(stored, now);
        recordRequest(stored.id, actorUserId, pipelineType, reason, idempotencyKey, now);
        AiPipelineRun current = runMapper.selectById(stored.id);
        return current == null ? stored : current;
    }

    private void recoverStaleRun(AiPipelineRun run, LocalDateTime now) {
        if (run == null || !"RUNNING".equals(run.status) || run.finishedAt != null
                || (run.leaseUntil != null && !run.leaseUntil.isBefore(now))
                || run.updatedAt == null
                || run.updatedAt.isAfter(now.minus(STALE_RUN_GRACE))) {
            return;
        }
        String message = "上次异步任务未正常收尾，已自动回收并允许重试";
        String detail = message + "；任务类型=" + run.pipelineType
                + "；运行ID=" + run.id
                + "；最后更新时间=" + run.updatedAt;
        runMapper.recoverStaleRunning(
                run.id, now.minus(STALE_RUN_GRACE), now, message, detail);
    }

    private void recordRequest(
            Long runId,
            Long actorUserId,
            String operation,
            String reason,
            String idempotencyKey,
            LocalDateTime now
    ) {
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("actorUserId", actorUserId);
        audit.put("operation", operation);
        audit.put("reason", reason == null ? "手动触发" : reason);
        audit.put("idempotencyKey", idempotencyKey);
        String checkpoint = json(audit);
        AiPipelineStep step = new AiPipelineStep();
        step.pipelineRunId = runId;
        step.stepKey = "REQUEST_ACCEPTED";
        step.stepOrder = 0;
        step.status = "SUCCESS";
        step.retryCount = 0;
        step.inputCount = 1;
        step.outputCount = 1;
        step.checkpointJson = checkpoint;
        step.outputFingerprint = sha256(checkpoint);
        step.startedAt = now;
        step.finishedAt = now;
        step.createdAt = now;
        step.updatedAt = now;
        stepMapper.insertIgnore(step);
    }

    private void submitExistingRun(AiPipelineRun run, Runnable operation) {
        if (complete(run.status) || "RUNNING".equals(run.status)) {
            return;
        }
        submit(run, () -> {
            try {
                operation.run();
            } catch (RuntimeException exception) {
                markUnmanagedFailure(run.id, exception);
            }
        });
    }

    private void submitManaged(AiPipelineRun run, String stepKey, Operation operation) {
        if (complete(run.status) || "RUNNING".equals(run.status)) {
            return;
        }
        AiPipelineStep step = prepareExecutionStep(run.id, stepKey);
        submit(run, () -> executeManaged(run.id, step, () -> managedCounts(operation.run())));
    }

    private void submitManagedCycle(AiPipelineRun run, String stepKey, CycleOperation operation) {
        if (complete(run.status) || "RUNNING".equals(run.status)) {
            return;
        }
        AiPipelineStep step = prepareExecutionStep(run.id, stepKey);
        submit(run, () -> executeManaged(run.id, step, () -> managedCycleResult(operation.run())));
    }

    private void submit(AiPipelineRun run, Runnable operation) {
        try {
            taskExecutor.execute(operation);
        } catch (RuntimeException exception) {
            markUnmanagedFailure(run.id, exception);
            throw new IllegalStateException("研究任务队列已满，请稍后重试", exception);
        }
    }

    private AiPipelineStep prepareExecutionStep(Long runId, String stepKey) {
        LocalDateTime now = LocalDateTime.now();
        AiPipelineStep expected = new AiPipelineStep();
        expected.pipelineRunId = runId;
        expected.stepKey = stepKey;
        expected.stepOrder = 1;
        expected.status = "PENDING";
        expected.retryCount = 0;
        expected.inputCount = 0;
        expected.outputCount = 0;
        expected.createdAt = now;
        expected.updatedAt = now;
        stepMapper.insertIgnore(expected);
        AiPipelineStep stored = stepMapper.selectOne(new QueryWrapper<AiPipelineStep>()
                .eq("pipeline_run_id", runId).eq("step_key", stepKey).last("LIMIT 1"));
        if (stored == null) {
            throw new IllegalStateException("异步研究任务缺少执行步骤：" + stepKey);
        }
        return stored;
    }

    private void executeManaged(Long runId, AiPipelineStep step, ManagedOperation operation) {
        String owner = UUID.randomUUID().toString();
        LocalDateTime started = LocalDateTime.now();
        if (runMapper.claimExecution(runId, owner, started.plus(OPERATION_LEASE), started) != 1) {
            return;
        }
        try {
            AiPipelineRun run = requiredRun(runId);
            run.status = "RUNNING";
            run.currentStep = step.stepKey;
            run.startedAt = run.startedAt == null ? started : run.startedAt;
            run.errorMessage = null;
            run.errorDetail = null;
            run.updatedAt = started;
            updateRun(run, owner);

            step.status = "RUNNING";
            step.startedAt = started;
            step.finishedAt = null;
            step.errorMessage = null;
            step.errorDetail = null;
            step.updatedAt = started;
            updateStep(step, owner);

            ManagedResult result = operation.run();
            LocalDateTime finished = LocalDateTime.now();
            step.status = result.stepStatus();
            step.inputCount = result.processed();
            step.outputCount = result.success();
            step.outputFingerprint = fingerprint(run.pipelineType, run.id, result.runStatus(),
                    result.processed(), result.success(), result.failed(), result.message(), finished);
            Map<String, Object> checkpoint = new LinkedHashMap<>();
            checkpoint.put("terminalStatus", result.runStatus());
            checkpoint.put("processedCount", result.processed());
            checkpoint.put("successCount", result.success());
            checkpoint.put("failedCount", result.failed());
            checkpoint.put("message", result.message());
            step.checkpointJson = json(checkpoint);
            step.errorMessage = result.problem() ? PipelineMessageFormatter.summary(result.message()) : null;
            step.errorDetail = result.problem() ? PipelineMessageFormatter.detail(result.message()) : null;
            step.finishedAt = finished;
            step.updatedAt = finished;
            updateStep(step, owner);

            run.status = result.runStatus();
            run.processedCount = result.processed();
            run.successCount = result.success();
            run.failedCount = result.failed();
            run.errorMessage = result.problem() ? PipelineMessageFormatter.summary(result.message()) : null;
            run.errorDetail = result.problem() ? PipelineMessageFormatter.detail(result.message()) : null;
            run.finishedAt = finished;
            run.updatedAt = finished;
            updateRun(run, owner);
        } catch (RuntimeException exception) {
            failManaged(runId, step, owner, exception);
        } finally {
            runMapper.releaseExecution(runId, owner, LocalDateTime.now());
        }
    }

    private void failManaged(Long runId, AiPipelineStep step, String owner, RuntimeException exception) {
        String detail = rootMessage(exception);
        String message = PipelineMessageFormatter.summary(detail);
        LocalDateTime now = LocalDateTime.now();
        step.status = "FAILED";
        step.errorMessage = message;
        step.errorDetail = PipelineMessageFormatter.detail(detail);
        step.finishedAt = now;
        step.updatedAt = now;
        updateStep(step, owner);
        AiPipelineRun run = requiredRun(runId);
        run.status = "FAILED";
        run.processedCount = Math.max(1, value(run.processedCount));
        run.failedCount = Math.max(1, value(run.failedCount));
        run.errorMessage = message;
        run.errorDetail = step.errorDetail;
        run.finishedAt = now;
        run.updatedAt = now;
        updateRun(run, owner);
    }

    private void markUnmanagedFailure(Long runId, RuntimeException exception) {
        AiPipelineRun run = runMapper.selectById(runId);
        if (run == null || complete(run.status)) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        run.status = "FAILED";
        run.failedCount = Math.max(1, value(run.failedCount));
        String detail = rootMessage(exception);
        run.errorMessage = PipelineMessageFormatter.summary(detail);
        run.errorDetail = PipelineMessageFormatter.detail(detail);
        run.finishedAt = now;
        run.updatedAt = now;
        runMapper.updateById(run);
    }

    private void rejectInTransaction(
            Long actorUserId,
            Long strategyId,
            ResearchLabPayloads.GovernanceRequest request
    ) {
        String eventKey = "REJECT:" + strategyId + ":" + sha256(key(request.idempotencyKey(), request.reason()));
        AiStrategyGovernanceEvent existing = eventMapper.selectByEventKeyForShare(eventKey);
        if (existing != null) {
            return;
        }
        AiStrategyRelease release = releaseMapper.selectByIdForUpdate(strategyId);
        if (release == null || !"CHALLENGER".equals(release.releaseRole) || !"SHADOW".equals(release.status)) {
            throw new IllegalArgumentException("只有处于 SHADOW 的 Challenger 可以拒绝");
        }
        LocalDateTime now = LocalDateTime.now();
        release.status = "RETIRED";
        release.rollbackReason = request.reason();
        release.shadowEndedAt = now;
        release.retiredAt = now;
        release.updatedAt = now;
        if (releaseMapper.updateById(release) != 1) {
            throw new IllegalStateException("拒绝 Challenger 时更新策略失败");
        }
        AiStrategyGovernanceEvent event = new AiStrategyGovernanceEvent();
        event.strategyReleaseId = release.id;
        event.previousChampionReleaseId = request.previousChampionReleaseId();
        event.shadowEvaluationId = request.shadowEvaluationId();
        event.eventKey = eventKey;
        event.eventType = "HUMAN_CHALLENGER_REJECTED";
        event.decisionStatus = "REJECTED";
        event.policyVersion = policy(request);
        event.actorType = "HUMAN";
        event.actorUserId = actorUserId;
        event.reason = request.reason();
        event.thresholdSnapshotJson = "{}";
        event.evidenceJson = json(Map.of("strategyReleaseId", strategyId, "previousStatus", "SHADOW"));
        event.occurredAt = now;
        event.createdAt = now;
        eventMapper.insertImmutable(event);
        if (eventMapper.selectByEventKeyForShare(eventKey) == null) {
            throw new IllegalStateException("拒绝 Challenger 的治理事件未写入");
        }
    }

    private AiStrategyRelease strategy(Long requestedId) {
        if (requestedId != null) {
            return requiredStrategy(requestedId);
        }
        AiStrategyRelease active = releaseMapper.selectGlobalActiveChampion(
                com.maogou.stock.service.research.AiResearchContract.SYSTEM_UNIVERSE_CODE,
                com.maogou.stock.service.research.AiResearchContract.MODEL_FAMILY);
        if (active == null) {
            throw new IllegalStateException("当前没有可用的全局 Champion 策略");
        }
        return active;
    }

    private AiStrategyRelease requiredStrategy(Long id) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("缺少策略版本 ID");
        }
        AiStrategyRelease strategy = releaseMapper.selectById(id);
        if (strategy == null) {
            throw new IllegalArgumentException("策略版本不存在");
        }
        return strategy;
    }

    private AiPipelineRun globalRun(Long requestedId, LocalDate tradeDate) {
        QueryWrapper<AiPipelineRun> query = new QueryWrapper<AiPipelineRun>()
                .eq("scope_type", "GLOBAL")
                .in("status", "SUCCESS", "PARTIAL_SUCCESS");
        if (requestedId != null) {
            query.eq("id", requestedId);
        } else {
            query.le("trade_date", tradeDate).orderByDesc("trade_date", "finished_at", "id").last("LIMIT 1");
        }
        AiPipelineRun run = runMapper.selectOne(query);
        if (run == null) {
            throw new IllegalStateException("没有可用于用户投影的已完成全局研究流水线");
        }
        return run;
    }

    private void updateRun(AiPipelineRun run, String owner) {
        if (runMapper.updateStateFenced(run, owner, run.updatedAt) != 1) {
            throw new IllegalStateException("异步研究任务租约已丢失");
        }
    }

    private void updateStep(AiPipelineStep step, String owner) {
        if (stepMapper.updateStateFenced(step, owner, step.updatedAt) != 1) {
            throw new IllegalStateException("异步研究步骤租约已丢失：" + step.stepKey);
        }
    }

    private AiPipelineRun requiredRun(Long id) {
        AiPipelineRun run = runMapper.selectById(id);
        if (run == null) {
            throw new IllegalStateException("异步研究任务不存在：" + id);
        }
        return run;
    }

    private ResearchLabPayloads.ActionAccepted accepted(AiPipelineRun run) {
        AiPipelineRun current = runMapper.selectById(run.id);
        return new ResearchLabPayloads.ActionAccepted(run.id, current == null ? run.status : current.status);
    }

    static ManagedResult managedCycleResult(AiResearchCycleResult result) {
        if (result == null) {
            throw new IllegalStateException("研究运行器未返回执行结果");
        }
        int processed = Math.max(0, result.processedCount());
        int success = Math.max(0, result.successCount());
        int failed = Math.max(0, result.failedCount());
        if (success + failed > processed) {
            processed = success + failed;
        }
        String status = result.status() == null ? "" : result.status().trim().toUpperCase();
        String message = result.message() == null ? "" : result.message().trim();
        return switch (status) {
            case "SUCCESS" -> failed > 0
                    ? new ManagedResult("PARTIAL_SUCCESS", "SUCCESS_WITH_WARNINGS",
                    processed, success, failed, message)
                    : new ManagedResult("SUCCESS", "SUCCESS", processed, success, 0, message);
            case "PARTIAL_SUCCESS", "SUCCESS_WITH_WARNINGS" ->
                    new ManagedResult("PARTIAL_SUCCESS", "SUCCESS_WITH_WARNINGS",
                            processed, success, failed, message);
            case "SKIPPED" -> new ManagedResult(
                    "SKIPPED", "SKIPPED", processed, success, failed, message);
            case "INSUFFICIENT_DATA" -> new ManagedResult(
                    "INSUFFICIENT_DATA", "INSUFFICIENT_DATA", processed, success, failed, message);
            case "FAILED" -> new ManagedResult(
                    "FAILED", "FAILED", Math.max(processed, 1), success, Math.max(failed, 1), message);
            default -> throw new IllegalStateException("研究运行器返回未知状态：" + status);
        };
    }

    private static ManagedResult managedCounts(Counts counts) {
        if (counts == null) {
            throw new IllegalStateException("研究操作未返回执行计数");
        }
        return counts.failed() > 0
                ? new ManagedResult("PARTIAL_SUCCESS", "SUCCESS_WITH_WARNINGS",
                counts.processed(), counts.success(), counts.failed(), "")
                : new ManagedResult("SUCCESS", "SUCCESS",
                counts.processed(), counts.success(), 0, "");
    }

    private static void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("用户投影缺少认证用户");
        }
    }

    private static void requireGovernance(ResearchLabPayloads.GovernanceRequest request, boolean promotion) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("策略治理必须填写原因");
        }
        if (promotion && (request.assessmentEventKey() == null || request.assessmentEventKey().isBlank())) {
            throw new IllegalArgumentException("策略晋级缺少 READY_FOR_REVIEW 评估事件");
        }
    }

    private static String policy(ResearchLabPayloads.GovernanceRequest request) {
        return request.policyVersion() == null || request.policyVersion().isBlank()
                ? "MANUAL_GOVERNANCE_V1" : request.policyVersion();
    }

    private static String key(String requested, String fallback) {
        return requested == null || requested.isBlank() ? fallback : requested;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化研究任务审计证据", exception);
        }
    }

    private static String fingerprint(Object... values) {
        StringBuilder input = new StringBuilder();
        for (Object value : values) {
            if (!input.isEmpty()) {
                input.append('|');
            }
            input.append(String.valueOf(value));
        }
        return sha256(input.toString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static boolean complete(String status) {
        return "SUCCESS".equals(status) || "PARTIAL_SUCCESS".equals(status)
                || "SKIPPED".equals(status) || "INSUFFICIENT_DATA".equals(status);
    }

    private static int value(Integer value) {
        return value == null ? 0 : value;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface Operation {
        Counts run();
    }

    @FunctionalInterface
    private interface CycleOperation {
        AiResearchCycleResult run();
    }

    @FunctionalInterface
    private interface ManagedOperation {
        ManagedResult run();
    }

    record ManagedResult(
            String runStatus,
            String stepStatus,
            int processed,
            int success,
            int failed,
            String message
    ) {
        boolean problem() {
            return !"SUCCESS".equals(runStatus);
        }
    }

    private record Counts(int processed, int success, int failed) {
        private Counts {
            processed = Math.max(0, processed);
            success = Math.max(0, success);
            failed = Math.max(0, failed);
            if (success + failed > processed) {
                processed = success + failed;
            }
        }

        private static Counts one() {
            return new Counts(1, 1, 0);
        }
    }
}
