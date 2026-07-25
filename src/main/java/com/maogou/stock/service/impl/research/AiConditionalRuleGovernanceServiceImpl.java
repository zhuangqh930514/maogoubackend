package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.maogou.stock.domain.entity.AiTradeRuleConfig;
import com.maogou.stock.domain.entity.research.AiConditionalRuleExperiment;
import com.maogou.stock.domain.entity.research.AiConditionalRuleExperimentFold;
import com.maogou.stock.domain.entity.research.AiConditionalRuleExperimentItem;
import com.maogou.stock.domain.entity.research.AiConditionalRuleGovernanceEvent;
import com.maogou.stock.domain.entity.research.AiConditionalRuleShadowItem;
import com.maogou.stock.domain.entity.research.AiConditionalRuleShadowObservation;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.dto.ai.AiConditionalStrategyPayload;
import com.maogou.stock.dto.market.FinanceSnapshotResponse;
import com.maogou.stock.dto.market.KlinePointResponse;
import com.maogou.stock.dto.market.StockDetailResponse;
import com.maogou.stock.dto.market.StockQuoteResponse;
import com.maogou.stock.mapper.AiTradeRuleConfigMapper;
import com.maogou.stock.mapper.research.AiConditionalRuleExperimentFoldMapper;
import com.maogou.stock.mapper.research.AiConditionalRuleExperimentItemMapper;
import com.maogou.stock.mapper.research.AiConditionalRuleExperimentMapper;
import com.maogou.stock.mapper.research.AiConditionalRuleGovernanceEventMapper;
import com.maogou.stock.mapper.research.AiConditionalRuleShadowItemMapper;
import com.maogou.stock.mapper.research.AiConditionalRuleShadowObservationMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.service.impl.ConditionalTradeRuleEngine;
import com.maogou.stock.service.research.AiConditionalRuleGovernanceService;
import com.maogou.stock.service.research.AiResearchContract;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs candidate conditional rules against immutable AFTER_CLOSE samples and
 * matured labels. It intentionally evaluates entry signals only: exit signals
 * require a point-in-time user position and are not synthetically shorted.
 */
@Service
public class AiConditionalRuleGovernanceServiceImpl implements AiConditionalRuleGovernanceService {

    private static final String RULE_RESOURCE = "ai/conditional-trade-rules-v1.json";
    private static final String POLICY_VERSION = "CONDITIONAL_RULE_GOVERNANCE/1.0.0";
    private static final int WRITE_CHUNK_SIZE = 400;
    private static final ExperimentThresholds EXPERIMENT_THRESHOLDS = new ExperimentThresholds(
            120, 20, 3, new BigDecimal("0.05"), new BigDecimal("0.45"),
            BigDecimal.ZERO, new BigDecimal("-0.20"));
    private static final ShadowThresholds SHADOW_THRESHOLDS = new ShadowThresholds(
            60, 12, 10, new BigDecimal("0.05"), BigDecimal.ZERO,
            new BigDecimal("-0.15"), new BigDecimal("0.45"));

    private final AiTradeRuleConfigMapper configMapper;
    private final AiConditionalRuleExperimentMapper experimentMapper;
    private final AiConditionalRuleExperimentFoldMapper foldMapper;
    private final AiConditionalRuleExperimentItemMapper experimentItemMapper;
    private final AiConditionalRuleShadowObservationMapper shadowMapper;
    private final AiConditionalRuleShadowItemMapper shadowItemMapper;
    private final AiConditionalRuleGovernanceEventMapper eventMapper;
    private final AiSampleMapper sampleMapper;
    private final AiSampleLabelMapper labelMapper;
    private final ConditionalTradeRuleEngine ruleEngine;
    private final ObjectMapper objectMapper;

    public AiConditionalRuleGovernanceServiceImpl(
            AiTradeRuleConfigMapper configMapper,
            AiConditionalRuleExperimentMapper experimentMapper,
            AiConditionalRuleExperimentFoldMapper foldMapper,
            AiConditionalRuleExperimentItemMapper experimentItemMapper,
            AiConditionalRuleShadowObservationMapper shadowMapper,
            AiConditionalRuleShadowItemMapper shadowItemMapper,
            AiConditionalRuleGovernanceEventMapper eventMapper,
            AiSampleMapper sampleMapper,
            AiSampleLabelMapper labelMapper,
            ConditionalTradeRuleEngine ruleEngine,
            ObjectMapper objectMapper
    ) {
        this.configMapper = configMapper;
        this.experimentMapper = experimentMapper;
        this.foldMapper = foldMapper;
        this.experimentItemMapper = experimentItemMapper;
        this.shadowMapper = shadowMapper;
        this.shadowItemMapper = shadowItemMapper;
        this.eventMapper = eventMapper;
        this.sampleMapper = sampleMapper;
        this.labelMapper = labelMapper;
        this.ruleEngine = ruleEngine;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public CandidateResult createCandidate(Long actorUserId, CandidateRequest request) {
        requireActor(actorUserId);
        if (request == null || request.sourceTradeRuleConfigId() == null
                || request.versionNo() == null || request.versionNo().isBlank()
                || request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("创建候选规则缺少来源、版本或名称");
        }
        AiTradeRuleConfig source = requiredConfigForUpdate(request.sourceTradeRuleConfigId());
        if (!"ACTIVE".equals(source.status)) {
            throw new IllegalArgumentException("候选规则必须从当前 ACTIVE 正式规则创建");
        }
        String version = request.versionNo().trim();
        AiTradeRuleConfig existing = configMapper.selectOne(new QueryWrapper<AiTradeRuleConfig>()
                .eq("user_id", source.userId).eq("version_no", version).last("LIMIT 1"));
        if (existing != null) {
            throw new IllegalArgumentException("该用户已存在条件规则版本：" + version);
        }

        ObjectNode candidateJson = mergeCandidateJson(source.configJson, request.overrideJson());
        RuleResolution resolution = resolveRuleConfiguration(candidateJson, version);
        LocalDateTime now = request.requestedAt() == null ? LocalDateTime.now() : request.requestedAt();
        AiTradeRuleConfig candidate = new AiTradeRuleConfig();
        candidate.userId = source.userId;
        candidate.strategyReleaseId = source.strategyReleaseId;
        candidate.versionNo = version;
        candidate.name = request.name().trim();
        candidate.status = "CANDIDATE";
        candidate.configJson = json(candidateJson);
        candidate.seedVersion = source.seedVersion;
        candidate.createdAt = now;
        candidate.updatedAt = now;
        configMapper.insert(candidate);

        AiConditionalRuleGovernanceEvent event = event(
                candidate.userId, candidate.id, null, null,
                "CREATE:" + candidate.id + ":" + resolution.fingerprint(),
                "CANDIDATE_CREATED", "CANDIDATE", "HUMAN", actorUserId,
                "从正式规则 " + source.versionNo + " 创建候选规则；尚未参与任何正式决策",
                Map.of("policyVersion", POLICY_VERSION),
                Map.of("sourceTradeRuleConfigId", source.id, "configFingerprint", resolution.fingerprint()), now);
        return new CandidateResult(candidate, event);
    }

    @Override
    @Transactional
    public ExperimentResult runWalkForward(Long actorUserId, ExperimentRequest request) {
        requireActor(actorUserId);
        ExperimentParameters parameters = experimentParameters(request);
        AiTradeRuleConfig candidate = requiredConfig(request.candidateTradeRuleConfigId());
        if (!"CANDIDATE".equals(candidate.status)) {
            throw new IllegalArgumentException("Walk-forward 只能评估 CANDIDATE 条件规则");
        }
        RuleResolution rule = resolveRuleConfiguration(parseObject(candidate.configJson, "候选规则配置"), candidate.versionNo);
        LocalDateTime evaluatedAt = request.evaluatedAt() == null ? LocalDateTime.now() : request.evaluatedAt();
        EvidenceLoad load = loadEvidence(parameters.horizonDays(), evaluatedAt.toLocalDate());
        List<SplitPlan> plans = splitPlans(load.observations(), parameters, evaluatedAt.toLocalDate());
        String expectedInputFingerprint = experimentInputFingerprint(rule, parameters, load);
        String experimentKey = experimentKey(candidate.id, request.idempotencyKey(), parameters,
                rule.fingerprint(), load.fingerprint());
        AiConditionalRuleExperiment existing = experimentMapper.selectByExperimentKeyForShare(experimentKey);
        if (existing != null) {
            ensureExperimentFingerprint(existing, candidate, parameters, expectedInputFingerprint);
            return new ExperimentResult(existing, foldMaps(existing.id), null);
        }

        AiConditionalRuleExperiment experiment = experiment(
                candidate, rule, parameters, load, plans, experimentKey, expectedInputFingerprint, evaluatedAt);
        experimentMapper.insertImmutable(experiment);
        experiment = experimentMapper.selectByExperimentKeyForShare(experimentKey);
        if (experiment == null) {
            throw new IllegalStateException("条件规则实验写入后未读取到记录");
        }
        if (plans.isEmpty()) {
            AiConditionalRuleGovernanceEvent event = event(
                    candidate.userId, candidate.id, experiment.id, null,
                    "WF_INSUFFICIENT:" + experiment.id + ":" + experiment.inputFingerprint,
                    "WALK_FORWARD_INSUFFICIENT_DATA", "INSUFFICIENT_DATA", "SYSTEM", actorUserId,
                    "成熟标签交易日不足，无法生成不泄漏的 Walk-forward 窗口",
                    thresholdMap(EXPERIMENT_THRESHOLDS), load.summary(), evaluatedAt);
            return new ExperimentResult(experiment, List.of(), event);
        }

        List<SignalObservation> allTest = new ArrayList<>();
        for (SplitPlan plan : plans) {
            AiConditionalRuleExperimentFold fold = fold(experiment.id, plan, parameters, load.fingerprint(), evaluatedAt);
            foldMapper.insert(fold);
            List<SignalObservation> train = filterPartition(load.observations(), plan.trainStart(), plan.trainEnd(), plan.validationStart(), evaluatedAt.toLocalDate());
            List<SignalObservation> validation = filterPartition(load.observations(), plan.validationStart(), plan.validationEnd(), plan.testStart(), evaluatedAt.toLocalDate());
            List<SignalObservation> test = filterPartition(load.observations(), plan.testStart(), plan.testEnd(), null, evaluatedAt.toLocalDate());
            List<SignalObservation> evaluatedTrain = evaluate(rule, train);
            List<SignalObservation> evaluatedValidation = evaluate(rule, validation);
            List<SignalObservation> evaluatedTest = evaluate(rule, test);
            allTest.addAll(evaluatedTest);
            persistExperimentItems(experiment.id, fold.id, "TRAIN", evaluatedTrain, evaluatedAt);
            persistExperimentItems(experiment.id, fold.id, "VALIDATION", evaluatedValidation, evaluatedAt);
            persistExperimentItems(experiment.id, fold.id, "TEST", evaluatedTest, evaluatedAt);
            Metrics metrics = metrics(evaluatedTest);
            fold.trainEligibleCount = evaluatedTrain.size();
            fold.validationEligibleCount = evaluatedValidation.size();
            fold.testEligibleCount = evaluatedTest.size();
            fold.testTriggeredCount = metrics.triggeredCount();
            fold.metricsJson = json(metrics.toMap());
            fold.status = "COMPLETED";
            foldMapper.updateById(fold);
        }

        Metrics aggregate = metrics(allTest);
        boolean passed = walkForwardPassed(aggregate, plans.size());
        experiment.status = "COMPLETED";
        experiment.candidateStatus = passed ? "WALK_FORWARD_PASSED" : "WALK_FORWARD_REJECTED";
        experiment.eligibleSampleCount = aggregate.eligibleCount();
        experiment.triggeredSampleCount = aggregate.triggeredCount();
        experiment.aggregateMetricsJson = json(Map.of(
                "evaluationScope", "ENTRY_ONLY_NO_SYNTHETIC_POSITION",
                "metrics", aggregate.toMap(),
                "excludedSamples", load.excludedCount(),
                "excludedReasons", load.excludedReasons()));
        experiment.updatedAt = evaluatedAt;
        experimentMapper.updateById(experiment);
        AiConditionalRuleGovernanceEvent event = event(
                candidate.userId, candidate.id, experiment.id, null,
                "WF:" + experiment.id + ":" + experiment.inputFingerprint,
                passed ? "WALK_FORWARD_PASSED" : "WALK_FORWARD_REJECTED",
                experiment.candidateStatus, "SYSTEM", actorUserId,
                passed ? "候选入场规则通过样本外时序门槛，仅可进入 Shadow 观察"
                        : "候选入场规则未达到样本外时序门槛，禁止进入 Shadow",
                thresholdMap(EXPERIMENT_THRESHOLDS), Map.of("metrics", aggregate.toMap(), "foldCount", plans.size()), evaluatedAt);
        return new ExperimentResult(experiment, foldMaps(experiment.id), event);
    }

    @Override
    @Transactional
    public ShadowResult runShadow(Long actorUserId, ShadowRequest request) {
        requireActor(actorUserId);
        if (request == null || request.experimentId() == null) {
            throw new IllegalArgumentException("Shadow 观察缺少候选实验");
        }
        AiConditionalRuleExperiment experiment = requiredExperiment(request.experimentId());
        if (!List.of("WALK_FORWARD_PASSED", "SHADOW_INSUFFICIENT_DATA").contains(experiment.candidateStatus)) {
            throw new IllegalArgumentException("只有通过 Walk-forward 的候选规则可以进入 Shadow");
        }
        AiTradeRuleConfig candidate = requiredConfig(experiment.tradeRuleConfigId);
        AiTradeRuleConfig baseline = configMapper.selectOne(new QueryWrapper<AiTradeRuleConfig>()
                .eq("user_id", candidate.userId).eq("status", "ACTIVE")
                .ne("id", candidate.id).orderByDesc("updated_at", "id").last("LIMIT 1"));
        if (baseline == null) {
            throw new IllegalStateException("Shadow 观察缺少当前 ACTIVE 正式规则基线");
        }
        LocalDateTime observedAt = request.observedAt() == null ? LocalDateTime.now() : request.observedAt();
        LocalDate end = request.windowEndDate() == null ? observedAt.toLocalDate() : request.windowEndDate();
        LocalDate start = request.windowStartDate() == null ? defaultShadowStart(end) : request.windowStartDate();
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("Shadow 开始日期不能晚于结束日期");
        }
        RuleResolution candidateRule = resolveRuleConfiguration(parseObject(candidate.configJson, "候选规则配置"), candidate.versionNo);
        RuleResolution baselineRule = resolveRuleConfiguration(parseObject(baseline.configJson, "正式规则配置"), baseline.versionNo);
        EvidenceLoad load = loadEvidence(experiment.horizonDays, end);
        List<SignalObservation> window = load.observations().stream()
                .filter(item -> !item.tradeDate().isBefore(start) && !item.tradeDate().isAfter(end))
                .filter(item -> !item.labelAvailableDate().isAfter(observedAt.toLocalDate()))
                .toList();
        String expectedInputFingerprint = shadowInputFingerprint(load, candidateRule, baselineRule, start, end);
        String observationKey = shadowKey(experiment.id, baseline.id, candidate.id, start, end,
                request.idempotencyKey(), candidateRule.fingerprint(), baselineRule.fingerprint(), load.fingerprint());
        AiConditionalRuleShadowObservation existing = shadowMapper.selectByObservationKeyForShare(observationKey);
        if (existing != null) {
            ensureShadowFingerprint(existing, experiment, baseline, candidate, expectedInputFingerprint);
            return new ShadowResult(existing, null);
        }
        List<SignalObservation> candidateSignals = evaluate(candidateRule, window);
        List<SignalObservation> baselineSignals = evaluate(baselineRule, window);
        Map<Long, SignalObservation> candidateBySample = indexBySample(candidateSignals);
        Map<Long, SignalObservation> baselineBySample = indexBySample(baselineSignals);
        Metrics candidateMetrics = metrics(candidateSignals);
        Metrics baselineMetrics = metrics(baselineSignals);
        boolean insufficient = shadowInsufficient(candidateMetrics, baselineMetrics, window);
        boolean ready = !insufficient && shadowPassed(candidateMetrics, baselineMetrics, window);
        AiConditionalRuleShadowObservation observation = new AiConditionalRuleShadowObservation();
        observation.userId = candidate.userId;
        observation.experimentId = experiment.id;
        observation.baselineTradeRuleConfigId = baseline.id;
        observation.candidateTradeRuleConfigId = candidate.id;
        observation.observationKey = observationKey;
        observation.horizonDays = experiment.horizonDays;
        observation.windowStartDate = start;
        observation.windowEndDate = end;
        observation.eligibleSampleCount = window.size();
        observation.baselineTriggeredCount = baselineMetrics.triggeredCount();
        observation.candidateTriggeredCount = candidateMetrics.triggeredCount();
        observation.status = insufficient ? "INSUFFICIENT_DATA" : ready ? "READY_FOR_REVIEW" : "REJECTED";
        observation.metricsJson = json(shadowMetrics(candidateMetrics, baselineMetrics, window));
        observation.thresholdSnapshotJson = json(thresholdMap(SHADOW_THRESHOLDS));
        observation.inputFingerprint = expectedInputFingerprint;
        observation.observedAt = observedAt;
        observation.createdAt = observedAt;
        observation.updatedAt = observedAt;
        shadowMapper.insertImmutable(observation);
        observation = shadowMapper.selectByObservationKeyForShare(observationKey);
        if (observation == null) {
            throw new IllegalStateException("条件规则 Shadow 写入后未读取到记录");
        }
        persistShadowItems(observation.id, window, baselineBySample, candidateBySample, observedAt);
        experiment.candidateStatus = insufficient ? "SHADOW_INSUFFICIENT_DATA"
                : ready ? "READY_FOR_REVIEW" : "SHADOW_REJECTED";
        experiment.updatedAt = observedAt;
        experimentMapper.updateById(experiment);
        AiConditionalRuleGovernanceEvent event = event(
                candidate.userId, candidate.id, experiment.id, observation.id,
                "SHADOW:" + observation.id + ":" + observation.inputFingerprint,
                insufficient ? "SHADOW_INSUFFICIENT_DATA" : ready ? "SHADOW_READY_FOR_REVIEW" : "SHADOW_REJECTED",
                observation.status, "SYSTEM", actorUserId,
                insufficient ? "Shadow 观察样本不足，候选规则保持禁用并等待更多真实样本"
                        : ready ? "Shadow 门槛已通过，仍必须由研究运维人工确认后才能启用"
                        : "Shadow 表现未达到门槛，候选规则保持禁用",
                thresholdMap(SHADOW_THRESHOLDS), Map.of(
                        "candidate", candidateMetrics.toMap(), "baseline", baselineMetrics.toMap(),
                        "insufficientEvidence", insufficient), observedAt);
        return new ShadowResult(observation, event);
    }

    @Override
    @Transactional
    public ApprovalResult approve(Long actorUserId, ApprovalRequest request) {
        requireActor(actorUserId);
        if (request == null || request.shadowObservationId() == null
                || request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("人工审批缺少 Shadow 记录或审批原因");
        }
        AiConditionalRuleShadowObservation shadow = requiredShadowForUpdate(request.shadowObservationId());
        if (!"READY_FOR_REVIEW".equals(shadow.status)) {
            throw new IllegalArgumentException("只有通过 Shadow 门槛的候选规则可以人工审批");
        }
        AiConditionalRuleExperiment experiment = requiredExperiment(shadow.experimentId);
        if (!"READY_FOR_REVIEW".equals(experiment.candidateStatus)) {
            throw new IllegalStateException("候选规则实验状态与 Shadow 状态不一致");
        }
        PromotionConfigs promotion = promotionConfigsForUpdate(shadow);
        AiTradeRuleConfig candidate = promotion.candidate();
        if (!"CANDIDATE".equals(candidate.status)) {
            throw new IllegalStateException("候选规则已不处于可审批状态");
        }
        if (!"ACTIVE".equals(promotion.baseline().status)) {
            throw new IllegalStateException("Shadow 对照基线已变化，候选规则必须基于当前正式规则重新观察");
        }
        LocalDateTime now = request.approvedAt() == null ? LocalDateTime.now() : request.approvedAt();
        int superseded = configMapper.supersedeActiveForCandidate(candidate.userId, candidate.id, now);
        if (superseded != 1) {
            throw new IllegalStateException("当前正式条件规则数量异常，禁止审批候选规则");
        }
        if (configMapper.activateCandidate(candidate.userId, candidate.id, now) != 1) {
            throw new IllegalStateException("启用候选条件规则失败，当前状态可能已变化");
        }
        candidate.status = "ACTIVE";
        candidate.updatedAt = now;
        shadow.status = "APPROVED";
        shadow.updatedAt = now;
        shadowMapper.updateById(shadow);
        experiment.candidateStatus = "PROMOTED";
        experiment.updatedAt = now;
        experimentMapper.updateById(experiment);
        AiConditionalRuleGovernanceEvent event = event(
                candidate.userId, candidate.id, experiment.id, shadow.id,
                "APPROVE:" + shadow.id + ":" + actorUserId,
                "HUMAN_PROMOTION_APPROVED", "PROMOTED", "HUMAN", actorUserId,
                request.reason().trim(), parseThresholds(shadow.thresholdSnapshotJson),
                Map.of("shadowObservationId", shadow.id, "experimentId", experiment.id,
                        "baselineTradeRuleConfigId", shadow.baselineTradeRuleConfigId), now);
        return new ApprovalResult(candidate, event);
    }

    @Override
    @Transactional
    public ApprovalResult reject(Long actorUserId, RejectionRequest request) {
        requireActor(actorUserId);
        if (request == null || request.shadowObservationId() == null
                || request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("拒绝候选规则缺少 Shadow 记录或原因");
        }
        AiConditionalRuleShadowObservation shadow = requiredShadowForUpdate(request.shadowObservationId());
        if (!List.of("READY_FOR_REVIEW", "REJECTED", "INSUFFICIENT_DATA").contains(shadow.status)) {
            throw new IllegalArgumentException("该 Shadow 记录已完成治理，不能重复拒绝");
        }
        AiConditionalRuleExperiment experiment = requiredExperiment(shadow.experimentId);
        AiTradeRuleConfig candidate = requiredConfigForUpdate(shadow.candidateTradeRuleConfigId);
        if (!"CANDIDATE".equals(candidate.status)) {
            throw new IllegalStateException("候选规则已不处于可拒绝状态");
        }
        LocalDateTime now = request.rejectedAt() == null ? LocalDateTime.now() : request.rejectedAt();
        candidate.status = "REJECTED";
        candidate.updatedAt = now;
        configMapper.updateById(candidate);
        if (!"INSUFFICIENT_DATA".equals(shadow.status)) {
            shadow.status = "REJECTED";
            shadow.updatedAt = now;
            shadowMapper.updateById(shadow);
        }
        experiment.candidateStatus = "REJECTED";
        experiment.updatedAt = now;
        experimentMapper.updateById(experiment);
        AiConditionalRuleGovernanceEvent event = event(
                candidate.userId, candidate.id, experiment.id, shadow.id,
                "REJECT:" + shadow.id + ":" + actorUserId,
                "HUMAN_PROMOTION_REJECTED", "REJECTED", "HUMAN", actorUserId,
                request.reason().trim(), parseThresholds(shadow.thresholdSnapshotJson),
                Map.of("shadowObservationId", shadow.id, "experimentId", experiment.id,
                        "shadowEvidenceStatus", shadow.status), now);
        return new ApprovalResult(candidate, event);
    }

    private EvidenceLoad loadEvidence(int horizonDays, LocalDate asOfDate) {
        List<AiSample> samples = sampleMapper.selectList(new QueryWrapper<AiSample>()
                .eq("sample_phase", "AFTER_CLOSE")
                .in("quality_status", List.of("READY", "PARTIAL"))
                .eq("tradable_status", "TRADABLE")
                .le("trade_date", asOfDate)
                .isNotNull("feature_snapshot")
                .orderByAsc("trade_date", "stock_code", "id"));
        Map<Long, AiSampleLabel> labels = maturedLabels(samples, horizonDays);
        List<SignalObservation> observations = new ArrayList<>();
        Map<String, Integer> excluded = new LinkedHashMap<>();
        for (AiSample sample : samples) {
            AiSampleLabel label = labels.get(sample.id);
            if (label == null || label.netReturn == null || label.labelAvailableAt == null) {
                increment(excluded, "MISSING_MATURED_LABEL");
                continue;
            }
            try {
                StockDetailResponse detail = sampleDetail(sample);
                if (detail.quote() == null || detail.quote().price() == null || detail.quote().price().signum() <= 0) {
                    increment(excluded, "INVALID_SNAPSHOT_QUOTE");
                    continue;
                }
                observations.add(new SignalObservation(sample, label, detail, null, null));
            } catch (RuntimeException exception) {
                increment(excluded, "UNREADABLE_IMMUTABLE_SNAPSHOT");
            }
        }
        String fingerprint = sha256(observations.stream().map(item -> item.sample().id + ":"
                        + normalize(item.sample().sourceFingerprint) + ":" + item.label().id + ":"
                        + normalize(item.label().inputFingerprint))
                .collect(Collectors.joining("|", "H" + horizonDays + ":", "")));
        return new EvidenceLoad(List.copyOf(observations), samples.size() - observations.size(), Map.copyOf(excluded), fingerprint);
    }

    private Map<Long, AiSampleLabel> maturedLabels(List<AiSample> samples, int horizonDays) {
        Map<Long, AiSampleLabel> values = new HashMap<>();
        for (List<AiSample> chunk : chunks(samples, WRITE_CHUNK_SIZE)) {
            List<Long> ids = chunk.stream().map(item -> item.id).filter(Objects::nonNull).toList();
            if (ids.isEmpty()) continue;
            for (AiSampleLabel label : labelMapper.selectMaturedForSamples(ids, AiResearchContract.LABEL_VERSION)) {
                if (Objects.equals(label.horizonTradingDays, horizonDays)
                        && "MATURED".equals(label.labelStatus) && Integer.valueOf(1).equals(label.isCurrent)) {
                    values.put(label.sampleId, label);
                }
            }
        }
        return values;
    }

    private List<SplitPlan> splitPlans(List<SignalObservation> observations, ExperimentParameters parameters, LocalDate evaluatedDate) {
        List<LocalDate> dates = observations.stream().map(SignalObservation::tradeDate).distinct().sorted().toList();
        int separation = parameters.purgeDays() + parameters.embargoDays();
        List<SplitPlan> plans = new ArrayList<>();
        for (int index = 0; index < parameters.foldCount(); index++) {
            int trainEnd = parameters.initialTrainDays() - 1 + index * parameters.stepDays();
            int validationStart = trainEnd + separation + 1;
            int validationEnd = validationStart + parameters.validationDays() - 1;
            int testStart = validationEnd + separation + 1;
            int testEnd = testStart + parameters.testDays() - 1;
            if (testEnd >= dates.size()) break;
            plans.add(new SplitPlan(index + 1, dates.get(0), dates.get(trainEnd), dates.get(validationStart),
                    dates.get(validationEnd), dates.get(testStart), dates.get(testEnd)));
        }
        return List.copyOf(plans);
    }

    private List<SignalObservation> filterPartition(
            List<SignalObservation> values,
            LocalDate start,
            LocalDate end,
            LocalDate labelMustBeBefore,
            LocalDate evaluatedDate
    ) {
        return values.stream().filter(item -> between(item.tradeDate(), start, end))
                .filter(item -> labelMustBeBefore == null
                        ? !item.labelAvailableDate().isAfter(evaluatedDate)
                        : item.labelAvailableDate().isBefore(labelMustBeBefore))
                .toList();
    }

    private List<SignalObservation> evaluate(RuleResolution resolution, List<SignalObservation> values) {
        return values.stream().map(item -> item.withSignal(entrySignal(item, resolution))).toList();
    }

    private RuleSignal entrySignal(SignalObservation observation, RuleResolution resolution) {
        AiSample sample = observation.sample();
        AiConditionalStrategyPayload.ResearchLineage lineage = new AiConditionalStrategyPayload.ResearchLineage(
                sample.id, null, null, null, sample.featureVersion, null, resolution.version(),
                resolution.fingerprint(), sample.dataQualityScore, null);
        AiConditionalStrategyPayload.MarketContext market = new AiConditionalStrategyPayload.MarketContext(
                normalize(sample.marketRegime), null, defaultText(sample.sectorName, "未接入可靠板块"), null,
                "UNAVAILABLE", null, "PARTIAL");
        AiConditionalStrategyPayload.PositionContext position = new AiConditionalStrategyPayload.PositionContext(
                false, 0, null, observation.detail().quote().price(), null);
        try {
            AiConditionalStrategyPayload result = ruleEngine.evaluate(new ConditionalTradeRuleEngine.EngineInput(
                    sample.tradeDate, sample.asOfTime, observation.detail(), position, market, lineage,
                    resolution.configuration(), Map.of(), Map.of(),
                    List.of("候选实验仅使用不可变样本快照；缺少板块、资金或持仓时点证据的条件不会触发")));
            return result.buyModels().stream()
                    .filter(item -> item != null && item.triggered() && "BUY".equals(normalize(item.action())))
                    .map(item -> new RuleSignal(item.modelCode(), item.action(), item.confidence(), true))
                    .max(Comparator.comparing(RuleSignal::strength, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(RuleSignal::ruleCode))
                    .orElse(RuleSignal.none());
        } catch (RuntimeException exception) {
            return RuleSignal.none();
        }
    }

    private void persistExperimentItems(
            Long experimentId,
            Long foldId,
            String partition,
            List<SignalObservation> values,
            LocalDateTime now
    ) {
        for (List<SignalObservation> chunk : chunks(values, WRITE_CHUNK_SIZE)) {
            for (SignalObservation value : chunk) {
                AiConditionalRuleExperimentItem item = new AiConditionalRuleExperimentItem();
                item.experimentId = experimentId;
                item.experimentFoldId = foldId;
                item.sampleId = value.sample().id;
                item.sampleLabelId = value.label().id;
                item.stockCode = value.sample().stockCode;
                item.tradeDate = value.tradeDate();
                item.horizonDays = value.label().horizonTradingDays;
                item.evaluationPartition = partition;
                item.ruleCode = value.signal().ruleCode();
                item.suggestedAction = value.signal().action();
                item.triggered = value.signal().triggered() ? 1 : 0;
                item.realizedNetReturn = value.signal().triggered() ? orientedReturn(value) : null;
                item.realizedExcessReturn = value.signal().triggered() ? orientedExcessReturn(value) : null;
                item.actionEffective = value.signal().triggered() ? effective(value) : null;
                item.featureFingerprint = normalize(value.sample().sourceFingerprint);
                item.labelFingerprint = normalize(value.label().inputFingerprint);
                item.evidenceJson = json(value.evidence());
                item.createdAt = now;
                experimentItemMapper.insert(item);
            }
        }
    }

    private void persistShadowItems(
            Long shadowId,
            List<SignalObservation> values,
            Map<Long, SignalObservation> baseline,
            Map<Long, SignalObservation> candidate,
            LocalDateTime now
    ) {
        for (SignalObservation source : values) {
            SignalObservation left = baseline.getOrDefault(source.sample().id, source.withSignal(RuleSignal.none()));
            SignalObservation right = candidate.getOrDefault(source.sample().id, source.withSignal(RuleSignal.none()));
            AiConditionalRuleShadowItem item = new AiConditionalRuleShadowItem();
            item.shadowObservationId = shadowId;
            item.sampleId = source.sample().id;
            item.sampleLabelId = source.label().id;
            item.stockCode = source.sample().stockCode;
            item.tradeDate = source.tradeDate();
            item.horizonDays = source.label().horizonTradingDays;
            item.baselineRuleCode = left.signal().ruleCode();
            item.baselineAction = left.signal().action();
            item.baselineTriggered = left.signal().triggered() ? 1 : 0;
            item.candidateRuleCode = right.signal().ruleCode();
            item.candidateAction = right.signal().action();
            item.candidateTriggered = right.signal().triggered() ? 1 : 0;
            item.realizedNetReturn = source.label().netReturn;
            item.realizedExcessReturn = source.label().excessReturn;
            item.featureFingerprint = normalize(source.sample().sourceFingerprint);
            item.labelFingerprint = normalize(source.label().inputFingerprint);
            item.evidenceJson = json(Map.of("evaluationScope", "ENTRY_ONLY_NO_SYNTHETIC_POSITION"));
            item.createdAt = now;
            shadowItemMapper.insert(item);
        }
    }

    private Metrics metrics(List<SignalObservation> values) {
        List<SignalObservation> triggered = values.stream().filter(item -> item.signal().triggered()).toList();
        List<BigDecimal> returns = triggered.stream().map(this::orientedReturn).filter(Objects::nonNull).toList();
        int effective = (int) returns.stream().filter(item -> item.signum() > 0).count();
        return new Metrics(
                values.size(), triggered.size(), effective,
                ratio(triggered.size(), values.size()),
                ratio(effective, returns.size()),
                wilsonLowerBound(effective, returns.size()),
                average(returns),
                average(triggered.stream().map(this::orientedExcessReturn).filter(Objects::nonNull).toList()),
                maxDrawdown(triggered), tradingDayCount(triggered));
    }

    private boolean walkForwardPassed(Metrics metrics, int folds) {
        return folds >= EXPERIMENT_THRESHOLDS.minimumFolds()
                && metrics.eligibleCount() >= EXPERIMENT_THRESHOLDS.minimumEligibleSamples()
                && metrics.triggeredCount() >= EXPERIMENT_THRESHOLDS.minimumTriggeredSamples()
                && metrics.coverageRate().compareTo(EXPERIMENT_THRESHOLDS.minimumCoverageRate()) >= 0
                && metrics.wilsonLowerBound().compareTo(EXPERIMENT_THRESHOLDS.minimumWilsonLowerBound()) >= 0
                && nonNullAtLeast(metrics.averageNetReturn(), EXPERIMENT_THRESHOLDS.minimumAverageNetReturn())
                && nonNullAtLeast(metrics.maxDrawdown(), EXPERIMENT_THRESHOLDS.maximumDrawdown());
    }

    private boolean shadowPassed(Metrics candidate, Metrics baseline, List<SignalObservation> values) {
        BigDecimal advantage = difference(candidate.averageNetReturn(), baseline.averageNetReturn());
        return values.size() >= SHADOW_THRESHOLDS.minimumEligibleSamples()
                && tradingDayCount(values) >= SHADOW_THRESHOLDS.minimumTradingDays()
                && candidate.triggeredCount() >= SHADOW_THRESHOLDS.minimumCandidateTriggers()
                && baseline.triggeredCount() > 0
                && candidate.coverageRate().compareTo(SHADOW_THRESHOLDS.minimumCoverageRate()) >= 0
                && candidate.wilsonLowerBound().compareTo(SHADOW_THRESHOLDS.minimumWilsonLowerBound()) >= 0
                && nonNullAtLeast(advantage, SHADOW_THRESHOLDS.minimumReturnAdvantage())
                && nonNullAtLeast(candidate.maxDrawdown(), SHADOW_THRESHOLDS.maximumCandidateDrawdown());
    }

    private boolean shadowInsufficient(Metrics candidate, Metrics baseline, List<SignalObservation> values) {
        return values.size() < SHADOW_THRESHOLDS.minimumEligibleSamples()
                || tradingDayCount(values) < SHADOW_THRESHOLDS.minimumTradingDays()
                || candidate.triggeredCount() < SHADOW_THRESHOLDS.minimumCandidateTriggers()
                || baseline.triggeredCount() == 0;
    }

    private AiConditionalRuleExperiment experiment(
            AiTradeRuleConfig candidate,
            RuleResolution rule,
            ExperimentParameters parameters,
            EvidenceLoad load,
            List<SplitPlan> plans,
            String experimentKey,
            String inputFingerprint,
            LocalDateTime evaluatedAt
    ) {
        LocalDate start = load.observations().stream().map(SignalObservation::tradeDate).min(Comparator.naturalOrder())
                .orElse(evaluatedAt.toLocalDate());
        LocalDate end = load.observations().stream().map(SignalObservation::tradeDate).max(Comparator.naturalOrder())
                .orElse(evaluatedAt.toLocalDate());
        AiConditionalRuleExperiment value = new AiConditionalRuleExperiment();
        value.userId = candidate.userId;
        value.tradeRuleConfigId = candidate.id;
        value.experimentKey = experimentKey;
        value.ruleConfigVersion = candidate.versionNo;
        value.horizonDays = parameters.horizonDays();
        value.windowStartDate = start;
        value.windowEndDate = end;
        value.foldCount = plans.size();
        value.status = plans.isEmpty() ? "INSUFFICIENT_DATA" : "RUNNING";
        value.candidateStatus = plans.isEmpty() ? "WALK_FORWARD_INSUFFICIENT_DATA" : "EVALUATING";
        value.eligibleSampleCount = load.observations().size();
        value.triggeredSampleCount = 0;
        value.configSnapshotJson = json(rule.configuration());
        value.thresholdSnapshotJson = json(thresholdMap(EXPERIMENT_THRESHOLDS));
        value.aggregateMetricsJson = json(Map.of("evaluationScope", "ENTRY_ONLY_NO_SYNTHETIC_POSITION",
                "excludedSamples", load.excludedCount(), "excludedReasons", load.excludedReasons()));
        value.inputFingerprint = inputFingerprint;
        value.evaluatedAt = evaluatedAt;
        value.createdAt = evaluatedAt;
        value.updatedAt = evaluatedAt;
        return value;
    }

    private AiConditionalRuleExperimentFold fold(
            Long experimentId,
            SplitPlan plan,
            ExperimentParameters parameters,
            String evidenceFingerprint,
            LocalDateTime now
    ) {
        AiConditionalRuleExperimentFold fold = new AiConditionalRuleExperimentFold();
        fold.experimentId = experimentId;
        fold.foldNo = plan.foldNo();
        fold.trainStartDate = plan.trainStart();
        fold.trainEndDate = plan.trainEnd();
        fold.validationStartDate = plan.validationStart();
        fold.validationEndDate = plan.validationEnd();
        fold.testStartDate = plan.testStart();
        fold.testEndDate = plan.testEnd();
        fold.trainEligibleCount = 0;
        fold.validationEligibleCount = 0;
        fold.testEligibleCount = 0;
        fold.testTriggeredCount = 0;
        fold.metricsJson = "{}";
        fold.inputFingerprint = sha256(evidenceFingerprint + "|" + parameters + "|" + plan);
        fold.status = "RUNNING";
        fold.createdAt = now;
        return fold;
    }

    private StockDetailResponse sampleDetail(AiSample sample) {
        try {
            JsonNode root = objectMapper.readTree(sample.featureSnapshot);
            StockQuoteResponse quote = objectMapper.treeToValue(root.path("quote"), StockQuoteResponse.class);
            FinanceSnapshotResponse finance = root.path("finance").isMissingNode() || root.path("finance").isNull()
                    ? null : objectMapper.treeToValue(root.path("finance"), FinanceSnapshotResponse.class);
            KlinePointResponse[] points = objectMapper.treeToValue(root.path("kline"), KlinePointResponse[].class);
            return new StockDetailResponse(quote, finance, List.of(), points == null ? List.of() : List.of(points), null, null);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("不可变样本快照无法解析：" + sample.id, exception);
        }
    }

    private RuleResolution resolveRuleConfiguration(ObjectNode configured, String version) {
        ObjectNode base = defaultConfigurationNode();
        deepMerge(base, normalizedOverrides(configured));
        try {
            AiConditionalStrategyPayload.RuleConfiguration parsed = objectMapper.treeToValue(
                    base, AiConditionalStrategyPayload.RuleConfiguration.class);
            validateConfiguration(parsed);
            String resolvedVersion = version == null || version.isBlank() ? parsed.version() : version;
            AiConditionalStrategyPayload.RuleConfiguration configuration = new AiConditionalStrategyPayload.RuleConfiguration(
                    resolvedVersion, parsed.thresholds(), parsed.riskWeights(), parsed.positions(),
                    parsed.minimumConditions(), parsed.factorMappings());
            return new RuleResolution(configuration, resolvedVersion, sha256(json(configuration)));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("候选条件规则配置无法解析", exception);
        }
    }

    private ObjectNode mergeCandidateJson(String sourceJson, String overrideJson) {
        ObjectNode source = parseObject(sourceJson, "来源规则配置");
        if (overrideJson == null || overrideJson.isBlank()) return source;
        ObjectNode override = parseObject(overrideJson, "候选规则覆盖配置");
        deepMerge(source, normalizedOverrides(override));
        return source;
    }

    private ObjectNode defaultConfigurationNode() {
        try (var input = new ClassPathResource(RULE_RESOURCE).getInputStream()) {
            JsonNode node = objectMapper.readTree(input);
            if (!(node instanceof ObjectNode object)) throw new IllegalStateException("默认条件规则不是对象");
            return object;
        } catch (IOException exception) {
            throw new IllegalStateException("默认条件规则资源不可用", exception);
        }
    }

    private ObjectNode parseObject(String value, String name) {
        if (value == null || value.isBlank()) return objectMapper.createObjectNode();
        try {
            JsonNode node = objectMapper.readTree(value);
            if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException(name + "必须是 JSON 对象");
            return object.deepCopy();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(name + "无法解析", exception);
        }
    }

    private ObjectNode normalizedOverrides(ObjectNode source) {
        ObjectNode normalized = objectMapper.createObjectNode();
        for (String key : List.of("version", "thresholds", "riskWeights", "positions", "minimumConditions", "factorMappings")) {
            JsonNode node = source.get(key);
            if (node != null && !node.isNull()) normalized.set(key, node.deepCopy());
        }
        JsonNode weights = normalized.get("riskWeights");
        if (weights instanceof ObjectNode object && object.has("capital")) {
            if (!object.has("fund")) object.set("fund", object.get("capital"));
            object.remove("capital");
        }
        return normalized;
    }

    private static void deepMerge(ObjectNode target, ObjectNode source) {
        source.fields().forEachRemaining(entry -> {
            JsonNode current = target.get(entry.getKey());
            if (current instanceof ObjectNode currentObject && entry.getValue() instanceof ObjectNode nextObject) {
                deepMerge(currentObject, nextObject);
            } else {
                target.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
    }

    private static void validateConfiguration(AiConditionalStrategyPayload.RuleConfiguration value) {
        if (value == null || value.thresholds() == null || value.riskWeights() == null
                || value.positions() == null || value.minimumConditions() == null || value.factorMappings() == null) {
            throw new IllegalArgumentException("候选条件规则配置结构不完整");
        }
        BigDecimal total = value.riskWeights().values().stream().filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.0001")) > 0) {
            throw new IllegalArgumentException("候选条件规则风险权重合计必须为 1");
        }
    }

    private ExperimentParameters experimentParameters(ExperimentRequest request) {
        if (request == null || request.candidateTradeRuleConfigId() == null || request.candidateTradeRuleConfigId() <= 0) {
            throw new IllegalArgumentException("Walk-forward 缺少候选规则");
        }
        ExperimentParameters values = new ExperimentParameters(
                requiredHorizon(request.horizonDays()), defaulted(request.initialTrainDays(), 60),
                defaulted(request.validationDays(), 20), defaulted(request.testDays(), 20),
                defaulted(request.stepDays(), 20), defaulted(request.foldCount(), 3),
                defaulted(request.purgeDays(), 5), defaulted(request.embargoDays(), 5));
        if (values.initialTrainDays() < 20 || values.validationDays() < 5 || values.testDays() < 5
                || values.stepDays() < values.testDays() || values.foldCount() < 2
                || values.purgeDays() < 1 || values.embargoDays() < 1) {
            throw new IllegalArgumentException("Walk-forward 窗口不满足最小样本、隔离或非重叠测试要求");
        }
        return values;
    }

    private AiConditionalRuleExperiment requiredExperiment(Long id) {
        AiConditionalRuleExperiment value = experimentMapper.selectById(id);
        if (value == null) throw new IllegalArgumentException("候选规则实验不存在");
        return value;
    }

    private AiConditionalRuleShadowObservation requiredShadowForUpdate(Long id) {
        AiConditionalRuleShadowObservation value = shadowMapper.selectByIdForUpdate(id);
        if (value == null) throw new IllegalArgumentException("候选规则 Shadow 观察不存在");
        return value;
    }

    private AiTradeRuleConfig requiredConfig(Long id) {
        AiTradeRuleConfig value = configMapper.selectById(id);
        if (value == null) throw new IllegalArgumentException("条件规则配置不存在");
        return value;
    }

    private AiTradeRuleConfig requiredConfigForUpdate(Long id) {
        AiTradeRuleConfig value = configMapper.selectByIdForUpdate(id);
        if (value == null) throw new IllegalArgumentException("条件规则配置不存在");
        return value;
    }

    private PromotionConfigs promotionConfigsForUpdate(AiConditionalRuleShadowObservation shadow) {
        Long candidateId = shadow.candidateTradeRuleConfigId;
        Long baselineId = shadow.baselineTradeRuleConfigId;
        if (candidateId == null || baselineId == null || Objects.equals(candidateId, baselineId)) {
            throw new IllegalStateException("Shadow 对照配置不完整，禁止人工审批");
        }
        AiTradeRuleConfig first = requiredConfigForUpdate(Math.min(candidateId, baselineId));
        AiTradeRuleConfig second = requiredConfigForUpdate(Math.max(candidateId, baselineId));
        AiTradeRuleConfig candidate = Objects.equals(first.id, candidateId) ? first : second;
        AiTradeRuleConfig baseline = Objects.equals(first.id, baselineId) ? first : second;
        if (!Objects.equals(candidate.userId, baseline.userId)
                || shadow.userId != null && !Objects.equals(shadow.userId, candidate.userId)) {
            throw new IllegalStateException("Shadow 对照规则不属于同一用户，禁止人工审批");
        }
        return new PromotionConfigs(candidate, baseline);
    }

    private void ensureExperimentFingerprint(
            AiConditionalRuleExperiment existing,
            AiTradeRuleConfig candidate,
            ExperimentParameters parameters,
            String expectedInputFingerprint
    ) {
        if (!Objects.equals(existing.tradeRuleConfigId, candidate.id)
                || !Objects.equals(existing.horizonDays, parameters.horizonDays())
                || !Objects.equals(existing.inputFingerprint, expectedInputFingerprint)) {
            throw new IllegalStateException("已有条件规则实验与当前请求冲突");
        }
    }

    private void ensureShadowFingerprint(
            AiConditionalRuleShadowObservation existing,
            AiConditionalRuleExperiment experiment,
            AiTradeRuleConfig baseline,
            AiTradeRuleConfig candidate,
            String expectedInputFingerprint
    ) {
        if (!Objects.equals(existing.experimentId, experiment.id)
                || !Objects.equals(existing.baselineTradeRuleConfigId, baseline.id)
                || !Objects.equals(existing.candidateTradeRuleConfigId, candidate.id)
                || !Objects.equals(existing.inputFingerprint, expectedInputFingerprint)) {
            throw new IllegalStateException("已有条件规则 Shadow 与当前请求冲突");
        }
    }

    private List<Map<String, Object>> foldMaps(Long experimentId) {
        return foldMapper.selectList(new QueryWrapper<AiConditionalRuleExperimentFold>()
                        .eq("experiment_id", experimentId).orderByAsc("fold_no"))
                .stream().map(item -> Map.<String, Object>of(
                        "foldNo", item.foldNo, "status", item.status,
                        "trainStartDate", item.trainStartDate, "trainEndDate", item.trainEndDate,
                        "validationStartDate", item.validationStartDate, "validationEndDate", item.validationEndDate,
                        "testStartDate", item.testStartDate, "testEndDate", item.testEndDate,
                        "metrics", item.metricsJson)).toList();
    }

    private AiConditionalRuleGovernanceEvent event(
            Long userId,
            Long configId,
            Long experimentId,
            Long shadowId,
            String key,
            String type,
            String decision,
            String actorType,
            Long actorId,
            String reason,
            Object thresholds,
            Object evidence,
            LocalDateTime occurredAt
    ) {
        AiConditionalRuleGovernanceEvent expected = new AiConditionalRuleGovernanceEvent();
        expected.userId = userId;
        expected.tradeRuleConfigId = configId;
        expected.experimentId = experimentId;
        expected.shadowObservationId = shadowId;
        expected.eventKey = key;
        expected.eventType = type;
        expected.decisionStatus = decision;
        expected.policyVersion = POLICY_VERSION;
        expected.actorType = actorType;
        expected.actorUserId = actorId;
        expected.reason = reason;
        expected.thresholdSnapshotJson = json(thresholds);
        expected.evidenceJson = json(evidence);
        expected.occurredAt = occurredAt;
        expected.createdAt = occurredAt;
        eventMapper.insertImmutable(expected);
        AiConditionalRuleGovernanceEvent persisted = eventMapper.selectByEventKeyForShare(key);
        if (persisted == null || !Objects.equals(persisted.eventType, expected.eventType)
                || !Objects.equals(persisted.decisionStatus, expected.decisionStatus)
                || !Objects.equals(persisted.evidenceJson, expected.evidenceJson)) {
            throw new IllegalStateException("条件规则治理事件不可变写入冲突：" + key);
        }
        return persisted;
    }

    private static RuleSignal none() {
        return RuleSignal.none();
    }

    private BigDecimal orientedReturn(SignalObservation value) {
        return value == null || value.label().netReturn == null ? null : value.label().netReturn;
    }

    private BigDecimal orientedExcessReturn(SignalObservation value) {
        return value == null ? null : value.label().excessReturn;
    }

    private Integer effective(SignalObservation value) {
        BigDecimal actual = orientedReturn(value);
        return actual == null ? null : actual.signum() > 0 ? 1 : 0;
    }

    private static Map<Long, SignalObservation> indexBySample(List<SignalObservation> values) {
        return values.stream().collect(Collectors.toMap(item -> item.sample().id, item -> item,
                (left, right) -> left, LinkedHashMap::new));
    }

    private static BigDecimal average(List<BigDecimal> values) {
        List<BigDecimal> valid = values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
        if (valid.isEmpty()) return null;
        return valid.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(valid.size()), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal maxDrawdown(List<SignalObservation> values) {
        Map<LocalDate, List<BigDecimal>> daily = new LinkedHashMap<>();
        values.stream().filter(item -> item.signal().triggered()).sorted(Comparator.comparing(SignalObservation::tradeDate)
                        .thenComparing(item -> item.sample().stockCode))
                .forEach(item -> {
                    BigDecimal value = item.label().netReturn;
                    if (value != null) daily.computeIfAbsent(item.tradeDate(), ignored -> new ArrayList<>()).add(value);
                });
        if (daily.isEmpty()) return null;
        BigDecimal equity = BigDecimal.ONE;
        BigDecimal peak = BigDecimal.ONE;
        BigDecimal worst = BigDecimal.ZERO;
        for (List<BigDecimal> returns : daily.values()) {
            BigDecimal day = average(returns);
            equity = equity.multiply(BigDecimal.ONE.add(day));
            if (equity.compareTo(peak) > 0) peak = equity;
            BigDecimal drawdown = equity.divide(peak, 12, RoundingMode.HALF_UP).subtract(BigDecimal.ONE);
            if (drawdown.compareTo(worst) < 0) worst = drawdown;
        }
        return worst.setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal wilsonLowerBound(int success, int total) {
        if (total <= 0) return BigDecimal.ZERO;
        double z = 1.96d;
        double p = (double) success / total;
        double denominator = 1d + z * z / total;
        double center = p + z * z / (2d * total);
        double spread = z * Math.sqrt((p * (1d - p) + z * z / (4d * total)) / total);
        return BigDecimal.valueOf(Math.max(0d, (center - spread) / denominator)).setScale(8, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(int numerator, int denominator) {
        return denominator <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 8, RoundingMode.HALF_UP);
    }

    private static BigDecimal difference(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.subtract(right).setScale(8, RoundingMode.HALF_UP);
    }

    private static boolean nonNullAtLeast(BigDecimal actual, BigDecimal threshold) {
        return actual != null && actual.compareTo(threshold) >= 0;
    }

    private static int tradingDayCount(List<SignalObservation> values) {
        return (int) values.stream().map(SignalObservation::tradeDate).distinct().count();
    }

    private static LocalDate defaultShadowStart(LocalDate end) {
        return end.minusDays(45);
    }

    private static boolean between(LocalDate value, LocalDate start, LocalDate end) {
        return value != null && !value.isBefore(start) && !value.isAfter(end);
    }

    private static int requiredHorizon(Integer value) {
        int horizon = value == null ? 3 : value;
        if (!List.of(1, 2, 3, 5).contains(horizon)) throw new IllegalArgumentException("条件规则实验周期仅支持 T+1/T+2/T+3/T+5");
        return horizon;
    }

    private static int defaulted(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static void requireActor(Long actorUserId) {
        if (actorUserId == null || actorUserId <= 0) throw new IllegalArgumentException("候选规则治理缺少研究运维操作者");
    }

    private static String experimentKey(
            Long configId,
            String idempotencyKey,
            ExperimentParameters parameters,
            String ruleFingerprint,
            String evidenceFingerprint
    ) {
        String supplied = idempotencyKey == null || idempotencyKey.isBlank() ? "AUTO" : idempotencyKey.trim();
        return "CRWF:" + configId + ":" + sha256(
                supplied + "|" + parameters + "|" + ruleFingerprint + "|" + evidenceFingerprint).substring(0, 48);
    }

    private static String shadowKey(
            Long experimentId,
            Long baselineId,
            Long candidateId,
            LocalDate start,
            LocalDate end,
            String suppliedKey,
            String candidateRuleFingerprint,
            String baselineRuleFingerprint,
            String evidenceFingerprint
    ) {
        String supplied = suppliedKey == null || suppliedKey.isBlank() ? "AUTO" : suppliedKey.trim();
        return "CRSH:" + experimentId + ":" + sha256(
                baselineId + "|" + candidateId + "|" + start + "|" + end + "|" + supplied + "|"
                        + candidateRuleFingerprint + "|" + baselineRuleFingerprint + "|" + evidenceFingerprint).substring(0, 48);
    }

    private static String experimentInputFingerprint(
            RuleResolution rule,
            ExperimentParameters parameters,
            EvidenceLoad load
    ) {
        return sha256(rule.fingerprint() + "|" + load.fingerprint() + "|" + parameters);
    }

    private static String shadowInputFingerprint(
            EvidenceLoad load,
            RuleResolution candidate,
            RuleResolution baseline,
            LocalDate start,
            LocalDate end
    ) {
        return sha256(load.fingerprint() + "|" + candidate.fingerprint() + "|" + baseline.fingerprint()
                + "|" + start + "|" + end);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void increment(Map<String, Integer> values, String key) {
        values.merge(key, 1, Integer::sum);
    }

    private Map<String, Object> thresholdMap(ExperimentThresholds value) {
        return Map.of(
                "minimumEligibleSamples", value.minimumEligibleSamples(),
                "minimumTriggeredSamples", value.minimumTriggeredSamples(),
                "minimumFolds", value.minimumFolds(),
                "minimumCoverageRate", value.minimumCoverageRate(),
                "minimumWilsonLowerBound", value.minimumWilsonLowerBound(),
                "minimumAverageNetReturn", value.minimumAverageNetReturn(),
                "maximumDrawdown", value.maximumDrawdown());
    }

    private Map<String, Object> shadowMetrics(
            Metrics candidate,
            Metrics baseline,
            List<SignalObservation> observations
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("evaluationScope", "ENTRY_ONLY_NO_SYNTHETIC_POSITION");
        values.put("candidate", candidate.toMap());
        values.put("baseline", baseline.toMap());
        values.put("averageReturnAdvantage", difference(candidate.averageNetReturn(), baseline.averageNetReturn()));
        values.put("observedTradingDays", tradingDayCount(observations));
        return values;
    }

    private Map<String, Object> thresholdMap(ShadowThresholds value) {
        return Map.of(
                "minimumEligibleSamples", value.minimumEligibleSamples(),
                "minimumCandidateTriggers", value.minimumCandidateTriggers(),
                "minimumTradingDays", value.minimumTradingDays(),
                "minimumCoverageRate", value.minimumCoverageRate(),
                "minimumReturnAdvantage", value.minimumReturnAdvantage(),
                "maximumCandidateDrawdown", value.maximumCandidateDrawdown(),
                "minimumWilsonLowerBound", value.minimumWilsonLowerBound());
    }

    private Map<String, Object> parseThresholds(String json) {
        if (json == null || json.isBlank()) return Map.of("policyVersion", POLICY_VERSION);
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Shadow 门槛快照损坏，禁止人工审批", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化条件规则治理证据", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static <T> List<List<T>> chunks(List<T> values, int size) {
        if (values == null || values.isEmpty()) return List.of();
        List<List<T>> chunks = new ArrayList<>();
        for (int offset = 0; offset < values.size(); offset += size) {
            chunks.add(values.subList(offset, Math.min(values.size(), offset + size)));
        }
        return chunks;
    }

    private record RuleResolution(
            AiConditionalStrategyPayload.RuleConfiguration configuration,
            String version,
            String fingerprint
    ) {
    }

    private record PromotionConfigs(AiTradeRuleConfig candidate, AiTradeRuleConfig baseline) {
    }

    private record EvidenceLoad(
            List<SignalObservation> observations,
            int excludedCount,
            Map<String, Integer> excludedReasons,
            String fingerprint
    ) {
        Map<String, Object> summary() {
            return Map.of("eligibleSampleCount", observations.size(), "excludedSampleCount", excludedCount,
                    "excludedReasons", excludedReasons, "inputFingerprint", fingerprint);
        }
    }

    private record ExperimentParameters(
            int horizonDays,
            int initialTrainDays,
            int validationDays,
            int testDays,
            int stepDays,
            int foldCount,
            int purgeDays,
            int embargoDays
    ) {
    }

    private record SplitPlan(
            int foldNo,
            LocalDate trainStart,
            LocalDate trainEnd,
            LocalDate validationStart,
            LocalDate validationEnd,
            LocalDate testStart,
            LocalDate testEnd
    ) {
    }

    private record RuleSignal(String ruleCode, String action, BigDecimal strength, boolean triggered) {
        static RuleSignal none() {
            return new RuleSignal(null, null, BigDecimal.ZERO, false);
        }
    }

    private record SignalObservation(
            AiSample sample,
            AiSampleLabel label,
            StockDetailResponse detail,
            RuleSignal signal,
            String ignored
    ) {
        SignalObservation {
            signal = signal == null ? RuleSignal.none() : signal;
        }

        SignalObservation withSignal(RuleSignal next) {
            return new SignalObservation(sample, label, detail, next, null);
        }

        LocalDate tradeDate() {
            return sample.tradeDate;
        }

        LocalDate labelAvailableDate() {
            return label.labelAvailableAt.toLocalDate();
        }

        Map<String, Object> evidence() {
            return Map.of(
                    "sampleId", sample.id,
                    "sampleSourceFingerprint", normalize(sample.sourceFingerprint),
                    "sampleAsOf", sample.asOfTime,
                    "samplePhase", sample.samplePhase,
                    "sampleFeatureVersion", sample.featureVersion,
                    "labelId", label.id,
                    "labelInputFingerprint", normalize(label.inputFingerprint),
                    "labelAvailableAt", label.labelAvailableAt,
                    "evaluationScope", "ENTRY_ONLY_NO_SYNTHETIC_POSITION");
        }
    }

    private record Metrics(
            int eligibleCount,
            int triggeredCount,
            int effectiveCount,
            BigDecimal coverageRate,
            BigDecimal hitRate,
            BigDecimal wilsonLowerBound,
            BigDecimal averageNetReturn,
            BigDecimal averageExcessReturn,
            BigDecimal maxDrawdown,
            int tradingDays
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("eligibleSampleCount", eligibleCount);
            values.put("triggeredSampleCount", triggeredCount);
            values.put("effectiveSampleCount", effectiveCount);
            values.put("coverageRate", coverageRate);
            values.put("hitRate", hitRate);
            values.put("wilsonLowerBound", wilsonLowerBound);
            values.put("averageNetReturn", averageNetReturn);
            values.put("averageExcessReturn", averageExcessReturn);
            values.put("maxDrawdown", maxDrawdown);
            values.put("tradingDays", tradingDays);
            return values;
        }
    }
}
