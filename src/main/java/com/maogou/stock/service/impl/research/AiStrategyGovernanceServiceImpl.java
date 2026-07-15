package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiStrategyGovernanceEvent;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.mapper.research.AiStrategyGovernanceEventMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.service.research.AiStrategyGovernanceService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class AiStrategyGovernanceServiceImpl implements AiStrategyGovernanceService {

    private static final int MINIMUM_ELIGIBLE_SAMPLES = 2_000;
    private static final int MINIMUM_SHADOW_TRADING_DAYS = 20;
    private static final java.math.BigDecimal MINIMUM_COVERAGE_RATE =
            new java.math.BigDecimal("0.60");
    private static final java.math.BigDecimal MAXIMUM_DRAWDOWN_DEGRADATION =
            new java.math.BigDecimal("0.02");

    private final AiStrategyReleaseMapper releaseMapper;
    private final AiStrategyGovernanceEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public AiStrategyGovernanceServiceImpl(
            AiStrategyReleaseMapper releaseMapper,
            AiStrategyGovernanceEventMapper eventMapper,
            ObjectMapper objectMapper
    ) {
        this.releaseMapper = releaseMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Assessment assess(AssessmentRequest request) {
        validate(request);
        AiStrategyRelease challenger = releaseMapper.selectByIdForUpdate(request.challengerReleaseId());
        if (challenger == null || !"CHALLENGER".equals(challenger.releaseRole)
                || !"SHADOW".equals(challenger.status)) {
            throw new IllegalArgumentException("只有处于 SHADOW 的全局 Challenger 可以评估");
        }
        AiStrategyRelease champion = releaseMapper.selectActiveChampionForUpdate(
                challenger.researchUniverseId, challenger.modelFamily);
        if (champion == null || !Objects.equals(champion.id, request.currentChampionReleaseId())) {
            throw new IllegalStateException("当前全局 Champion 与评估请求不一致");
        }
        List<String> reasons = rejectionReasons(request.policy(), request.evidence());
        String decision = reasons.isEmpty() ? "READY_FOR_REVIEW" : "REJECTED";
        String eventType = reasons.isEmpty() ? "PROMOTION_CANDIDATE_READY" : "PROMOTION_REJECTED";
        String reason = reasons.isEmpty()
                ? "所有自动化门槛已通过，等待人工确认" : String.join("；", reasons);
        String eventKey = "ASSESS:" + challenger.id + ":" + request.policyVersion() + ":"
                + sha256(request.evidence().evidenceFingerprint() + "|" + request.policy());

        AiStrategyGovernanceEvent expected = new AiStrategyGovernanceEvent();
        expected.strategyReleaseId = challenger.id;
        expected.previousChampionReleaseId = request.currentChampionReleaseId();
        expected.walkForwardRunId = request.walkForwardRunId();
        expected.backtestRunId = request.backtestRunId();
        expected.shadowEvaluationId = request.shadowEvaluationId();
        expected.eventKey = eventKey;
        expected.eventType = eventType;
        expected.decisionStatus = decision;
        expected.policyVersion = request.policyVersion();
        expected.actorType = "SYSTEM";
        expected.reason = reason;
        expected.thresholdSnapshotJson = json(request.policy());
        expected.evidenceJson = json(request.evidence());
        expected.occurredAt = request.assessedAt();
        expected.createdAt = LocalDateTime.now();
        eventMapper.insertImmutable(expected);
        AiStrategyGovernanceEvent event = eventMapper.selectByEventKeyForShare(eventKey);
        if (event == null) {
            throw new IllegalStateException("策略治理事件写入后未读取到记录");
        }
        if (!Objects.equals(expected.eventType, event.eventType)
                || !Objects.equals(expected.decisionStatus, event.decisionStatus)
                || !Objects.equals(expected.evidenceJson, event.evidenceJson)
                || !Objects.equals(expected.thresholdSnapshotJson, event.thresholdSnapshotJson)) {
            throw new IllegalStateException("不可变策略治理事件冲突：" + eventKey);
        }
        return new Assessment(decision, List.copyOf(reasons), challenger, event);
    }

    @Override
    @Transactional
    public PromotionResult confirmPromotion(ConfirmationRequest request) {
        if (request == null || request.challengerReleaseId() == null || request.challengerReleaseId() <= 0
                || request.assessmentEventKey() == null || request.assessmentEventKey().isBlank()
                || request.actorId() == null || request.actorId() <= 0
                || request.policyVersion() == null || request.policyVersion().isBlank()
                || request.confirmationReason() == null || request.confirmationReason().isBlank()
                || request.confirmedAt() == null) {
            throw new IllegalArgumentException("人工晋级确认缺少操作者、评估事件或策略");
        }
        AiStrategyGovernanceEvent assessment = eventMapper.selectByEventKeyForShare(
                request.assessmentEventKey());
        if (assessment == null
                || !Objects.equals(request.challengerReleaseId(), assessment.strategyReleaseId)
                || !"READY_FOR_REVIEW".equals(assessment.decisionStatus)
                || !"PROMOTION_CANDIDATE_READY".equals(assessment.eventType)) {
            throw new IllegalArgumentException("只有通过自动门槛的评估事件可以人工确认");
        }
        String eventKey = "CONFIRM:" + request.challengerReleaseId() + ":"
                + assessment.id + ":" + request.actorId();
        AiStrategyRelease challenger = releaseMapper.selectByIdForUpdate(request.challengerReleaseId());
        if (challenger == null) {
            throw new IllegalArgumentException("待晋级 Challenger 不存在");
        }
        AiStrategyGovernanceEvent existingEvent = eventMapper.selectByEventKeyForShare(eventKey);
        if (existingEvent != null) {
            AiStrategyRelease active = releaseMapper.selectActiveChampionForUpdate(
                    challenger.researchUniverseId, challenger.modelFamily);
            AiStrategyRelease previous = releaseMapper.selectByIdForUpdate(
                    assessment.previousChampionReleaseId);
            if (active == null || !Objects.equals(active.id, request.challengerReleaseId())
                    || previous == null || !"RETIRED".equals(previous.status)
                    || !"HUMAN_PROMOTION_CONFIRMED".equals(existingEvent.eventType)) {
                throw new IllegalStateException("已存在的晋级事件与当前策略状态不一致");
            }
            return new PromotionResult(active, previous, existingEvent);
        }
        AiStrategyRelease champion = releaseMapper.selectActiveChampionForUpdate(
                challenger.researchUniverseId, challenger.modelFamily);
        if (!"CHALLENGER".equals(challenger.releaseRole)
                || !"SHADOW".equals(challenger.status)) {
            throw new IllegalArgumentException("待晋级策略不再是 SHADOW Challenger");
        }
        if (champion == null || !Objects.equals(assessment.previousChampionReleaseId, champion.id)
                || Objects.equals(champion.id, challenger.id)) {
            throw new IllegalStateException("当前 Champion 与评估时不一致，必须重新评估");
        }

        champion.status = "RETIRED";
        champion.retiredAt = request.confirmedAt();
        champion.updatedAt = request.confirmedAt();
        if (releaseMapper.updateById(champion) != 1) {
            throw new IllegalStateException("退役旧 Champion 失败");
        }
        challenger.status = "ACTIVE";
        challenger.releaseRole = "CHAMPION";
        challenger.shadowEndedAt = request.confirmedAt();
        challenger.activatedAt = request.confirmedAt();
        challenger.promotionReason = request.confirmationReason();
        challenger.updatedAt = request.confirmedAt();
        if (releaseMapper.updateById(challenger) != 1) {
            throw new IllegalStateException("激活新 Champion 失败");
        }

        AiStrategyGovernanceEvent expected = new AiStrategyGovernanceEvent();
        expected.strategyReleaseId = challenger.id;
        expected.previousChampionReleaseId = champion.id;
        expected.walkForwardRunId = assessment.walkForwardRunId;
        expected.backtestRunId = assessment.backtestRunId;
        expected.shadowEvaluationId = assessment.shadowEvaluationId;
        expected.eventKey = eventKey;
        expected.eventType = "HUMAN_PROMOTION_CONFIRMED";
        expected.decisionStatus = "PROMOTED";
        expected.policyVersion = request.policyVersion();
        expected.actorType = "HUMAN";
        expected.actorUserId = request.actorId();
        expected.reason = request.confirmationReason();
        expected.thresholdSnapshotJson = assessment.thresholdSnapshotJson;
        expected.evidenceJson = json(Map.of(
                "assessmentEventId", assessment.id,
                "assessmentEventKey", assessment.eventKey,
                "previousChampionReleaseId", champion.id));
        expected.occurredAt = request.confirmedAt();
        expected.createdAt = LocalDateTime.now();
        eventMapper.insertImmutable(expected);
        AiStrategyGovernanceEvent event = eventMapper.selectByEventKeyForShare(eventKey);
        if (event == null || !Objects.equals(expected.eventType, event.eventType)
                || !Objects.equals(expected.evidenceJson, event.evidenceJson)) {
            throw new IllegalStateException("不可变人工晋级事件冲突：" + eventKey);
        }
        return new PromotionResult(challenger, champion, event);
    }

    @Override
    @Transactional
    public RollbackResult rollback(RollbackRequest request) {
        if (request == null || request.currentChampionReleaseId() == null || request.currentChampionReleaseId() <= 0
                || request.previousChampionReleaseId() == null || request.previousChampionReleaseId() <= 0
                || Objects.equals(request.currentChampionReleaseId(), request.previousChampionReleaseId())
                || request.shadowEvaluationId() == null || request.shadowEvaluationId() <= 0
                || request.criticalDriftCount() == null || request.criticalDriftCount() <= 0
                || request.degradationFingerprint() == null || request.degradationFingerprint().isBlank()
                || request.policyVersion() == null || request.policyVersion().isBlank()
                || request.rollbackReason() == null || request.rollbackReason().isBlank()
                || request.rolledBackAt() == null) {
            throw new IllegalArgumentException("策略回滚缺少严重退化证据或上一版 Champion");
        }
        AiStrategyRelease current = releaseMapper.selectByIdForUpdate(request.currentChampionReleaseId());
        AiStrategyRelease previous = releaseMapper.selectByIdForUpdate(request.previousChampionReleaseId());
        if (current == null || !Objects.equals(current.id, request.currentChampionReleaseId())
                || !"CHAMPION".equals(current.releaseRole) || !"ACTIVE".equals(current.status)) {
            throw new IllegalStateException("当前激活 Champion 与回滚请求不一致");
        }
        AiStrategyRelease active = releaseMapper.selectActiveChampionForUpdate(
                current.researchUniverseId, current.modelFamily);
        if (active == null || !Objects.equals(active.id, current.id)) {
            throw new IllegalStateException("当前全局 Champion 锁定结果不一致");
        }
        if (previous == null
                || !Objects.equals(previous.researchUniverseId, current.researchUniverseId)
                || !Objects.equals(previous.modelFamily, current.modelFamily)
                || !"CHAMPION".equals(previous.releaseRole) || !"RETIRED".equals(previous.status)) {
            throw new IllegalArgumentException("回滚目标必须是同研究范围已退役的 Champion");
        }

        current.status = "RETIRED";
        current.rollbackReason = request.rollbackReason();
        current.retiredAt = request.rolledBackAt();
        current.updatedAt = request.rolledBackAt();
        if (releaseMapper.updateById(current) != 1) {
            throw new IllegalStateException("退役退化 Champion 失败");
        }
        previous.status = "ACTIVE";
        previous.activatedAt = request.rolledBackAt();
        previous.retiredAt = null;
        previous.rollbackReason = null;
        previous.updatedAt = request.rolledBackAt();
        if (releaseMapper.updateById(previous) != 1) {
            throw new IllegalStateException("恢复上一版 Champion 失败");
        }

        String eventKey = "ROLLBACK:" + current.id + ":" + previous.id + ":"
                + sha256(request.degradationFingerprint() + "|" + request.policyVersion());
        AiStrategyGovernanceEvent expected = new AiStrategyGovernanceEvent();
        expected.strategyReleaseId = previous.id;
        expected.previousChampionReleaseId = current.id;
        expected.shadowEvaluationId = request.shadowEvaluationId();
        expected.eventKey = eventKey;
        expected.eventType = "DEGRADATION_ROLLBACK";
        expected.decisionStatus = "ROLLED_BACK";
        expected.policyVersion = request.policyVersion();
        expected.actorType = "SYSTEM";
        expected.reason = request.rollbackReason();
        expected.thresholdSnapshotJson = json(Map.of("criticalDriftCountRequired", 1));
        expected.evidenceJson = json(Map.of(
                "criticalDriftCount", request.criticalDriftCount(),
                "degradationFingerprint", request.degradationFingerprint(),
                "retiredChampionReleaseId", current.id,
                "restoredChampionReleaseId", previous.id));
        expected.occurredAt = request.rolledBackAt();
        expected.createdAt = LocalDateTime.now();
        eventMapper.insertImmutable(expected);
        AiStrategyGovernanceEvent event = eventMapper.selectByEventKeyForShare(eventKey);
        if (event == null || !Objects.equals(expected.eventType, event.eventType)
                || !Objects.equals(expected.evidenceJson, event.evidenceJson)) {
            throw new IllegalStateException("不可变回滚治理事件冲突：" + eventKey);
        }
        return new RollbackResult(previous, current, event);
    }

    private static List<String> rejectionReasons(PromotionPolicy policy, PromotionEvidence evidence) {
        List<String> reasons = new ArrayList<>();
        int requiredShadowDays = Math.max(policy.minimumShadowDays(), MINIMUM_SHADOW_TRADING_DAYS);
        if (evidence.shadowTradingDays() < requiredShadowDays) {
            reasons.add("影子天数不足 " + requiredShadowDays);
        }
        int requiredSamples = Math.max(policy.minimumSamples(), MINIMUM_ELIGIBLE_SAMPLES);
        if (evidence.eligibleSampleCount() < requiredSamples) {
            reasons.add("合格样本数不足 " + requiredSamples);
        }
        if (evidence.coverageRate().compareTo(MINIMUM_COVERAGE_RATE) < 0) {
            reasons.add("覆盖率低于 " + MINIMUM_COVERAGE_RATE);
        }
        if (evidence.tradeCount() < policy.minimumTrades()) {
            reasons.add("交易数不足 " + policy.minimumTrades());
        }
        if (evidence.foldCount() < policy.minimumFolds()) {
            reasons.add("Walk-forward 窗口数不足 " + policy.minimumFolds());
        }
        if (evidence.challengerMaxDrawdown().compareTo(policy.maximumDrawdown()) < 0) {
            reasons.add("最大回撤超过上限 " + policy.maximumDrawdown());
        }
        if (evidence.challengerNetExcessReturn()
                .compareTo(evidence.championNetExcessReturn()) <= 0) {
            reasons.add("Challenger 净超额收益未超过 Champion");
        }
        if (drawdownMagnitude(evidence.challengerMaxDrawdown()).compareTo(
                drawdownMagnitude(evidence.championMaxDrawdown())
                        .add(MAXIMUM_DRAWDOWN_DEGRADATION)) > 0) {
            reasons.add("Challenger 最大回撤比 Champion 恶化超过 0.02");
        }
        if (evidence.challengerCalibrationError()
                .compareTo(evidence.championCalibrationError()) > 0) {
            reasons.add("Challenger 校准误差高于 Champion");
        }
        if (evidence.challengerWilsonLowerBound()
                .compareTo(evidence.championWilsonLowerBound()) < 0) {
            reasons.add("Challenger Wilson 下界低于 Champion");
        }
        if (evidence.maxSingleStockContribution()
                .compareTo(policy.maximumSingleStockContribution()) > 0) {
            reasons.add("单票贡献超过上限 " + policy.maximumSingleStockContribution());
        }
        if (evidence.confidenceIntervalLowerExcessReturn()
                .compareTo(policy.minimumConfidenceIntervalExcessReturn()) < 0) {
            reasons.add("置信区间下界未达到 " + policy.minimumConfidenceIntervalExcessReturn());
        }
        if (evidence.criticalDriftCount() > 0) {
            reasons.add("存在 CRITICAL 漂移告警");
        }
        return reasons;
    }

    private static void validate(AssessmentRequest request) {
        if (request == null || request.challengerReleaseId() == null || request.challengerReleaseId() <= 0
                || request.currentChampionReleaseId() == null || request.currentChampionReleaseId() <= 0
                || request.walkForwardRunId() == null || request.walkForwardRunId() <= 0
                || request.backtestRunId() == null || request.backtestRunId() <= 0
                || request.shadowEvaluationId() == null || request.shadowEvaluationId() <= 0
                || request.policyVersion() == null || request.policyVersion().isBlank()
                || request.policy() == null || request.evidence() == null
                || request.assessedAt() == null) {
            throw new IllegalArgumentException("策略晋级评估缺少策略或证据关联");
        }
        PromotionPolicy policy = request.policy();
        PromotionEvidence evidence = request.evidence();
        if (policy.minimumShadowDays() == null || policy.minimumShadowDays() <= 0
                || policy.minimumSamples() == null || policy.minimumSamples() <= 0
                || policy.minimumTrades() == null || policy.minimumTrades() <= 0
                || policy.minimumFolds() == null || policy.minimumFolds() <= 0
                || policy.maximumDrawdown() == null || policy.maximumDrawdown().signum() > 0
                || policy.maximumSingleStockContribution() == null
                || policy.maximumSingleStockContribution().signum() < 0
                || policy.minimumConfidenceIntervalExcessReturn() == null
                || evidence.shadowTradingDays() == null || evidence.shadowTradingDays() < 0
                || evidence.eligibleSampleCount() == null || evidence.eligibleSampleCount() < 0
                || !validRate(evidence.coverageRate())
                || evidence.tradeCount() == null || evidence.tradeCount() < 0
                || evidence.foldCount() == null || evidence.foldCount() < 0
                || evidence.championNetExcessReturn() == null
                || evidence.challengerNetExcessReturn() == null
                || evidence.championMaxDrawdown() == null
                || evidence.challengerMaxDrawdown() == null
                || evidence.championCalibrationError() == null
                || evidence.challengerCalibrationError() == null
                || !validRate(evidence.championWilsonLowerBound())
                || !validRate(evidence.challengerWilsonLowerBound())
                || evidence.maxSingleStockContribution() == null
                || evidence.confidenceIntervalLowerExcessReturn() == null
                || evidence.criticalDriftCount() == null || evidence.criticalDriftCount() < 0
                || evidence.evidenceFingerprint() == null || evidence.evidenceFingerprint().isBlank()) {
            throw new IllegalArgumentException("策略晋级阈值或证据值无效");
        }
    }

    private static boolean validRate(java.math.BigDecimal value) {
        return value != null && value.signum() >= 0 && value.compareTo(java.math.BigDecimal.ONE) <= 0;
    }

    private static java.math.BigDecimal drawdownMagnitude(java.math.BigDecimal value) {
        return value.abs();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化策略治理证据", ex);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
