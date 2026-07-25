package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.AiTradeRuleConfig;
import com.maogou.stock.domain.entity.research.AiConditionalRuleExperiment;
import com.maogou.stock.domain.entity.research.AiConditionalRuleGovernanceEvent;
import com.maogou.stock.domain.entity.research.AiConditionalRuleShadowObservation;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiConditionalRuleGovernanceServiceImplTest {

    @Test
    void insufficientImmutableEvidenceIsPersistedAsInsufficientInsteadOfCreatingSyntheticSignals() {
        AiTradeRuleConfigMapper configMapper = mock(AiTradeRuleConfigMapper.class);
        AiConditionalRuleExperimentMapper experimentMapper = mock(AiConditionalRuleExperimentMapper.class);
        AiConditionalRuleGovernanceEventMapper eventMapper = mock(AiConditionalRuleGovernanceEventMapper.class);
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        AtomicReference<AiConditionalRuleGovernanceEvent> storedEvent = new AtomicReference<>();
        AtomicReference<AiConditionalRuleExperiment> storedExperiment = new AtomicReference<>();
        AiTradeRuleConfig candidate = new AiTradeRuleConfig();
        candidate.id = 9L;
        candidate.userId = 5L;
        candidate.versionNo = "CONDITIONAL_RULE/CANDIDATE/1";
        candidate.status = "CANDIDATE";
        candidate.configJson = "{}";
        when(configMapper.selectById(9L)).thenReturn(candidate);
        when(sampleMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.doAnswer(invocation -> {
            AiConditionalRuleExperiment value = invocation.getArgument(0);
            value.id = 31L;
            storedExperiment.set(value);
            return 1;
        }).when(experimentMapper).insertImmutable(any());
        when(experimentMapper.selectByExperimentKeyForShare(any())).thenAnswer(invocation -> {
            return storedExperiment.get();
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            storedEvent.set(invocation.getArgument(0));
            return 1;
        }).when(eventMapper).insertImmutable(any());
        when(eventMapper.selectByEventKeyForShare(any())).thenAnswer(invocation -> storedEvent.get());

        AiConditionalRuleGovernanceService service = new AiConditionalRuleGovernanceServiceImpl(
                configMapper, experimentMapper, mock(AiConditionalRuleExperimentFoldMapper.class),
                mock(AiConditionalRuleExperimentItemMapper.class),
                mock(AiConditionalRuleShadowObservationMapper.class), mock(AiConditionalRuleShadowItemMapper.class),
                eventMapper, sampleMapper, mock(AiSampleLabelMapper.class), new ConditionalTradeRuleEngine(),
                new ObjectMapper().findAndRegisterModules());

        AiConditionalRuleGovernanceService.ExperimentResult result = service.runWalkForward(1L,
                new AiConditionalRuleGovernanceService.ExperimentRequest(
                        9L, 3, 60, 20, 20, 20, 3, 5, 5, "no-evidence", LocalDateTime.now()));

        assertThat(result.experiment().status).isEqualTo("INSUFFICIENT_DATA");
        assertThat(result.experiment().candidateStatus).isEqualTo("WALK_FORWARD_INSUFFICIENT_DATA");
        assertThat(result.folds()).isEmpty();
    }

    @Test
    void shadowWithTooFewRealObservationsRemainsInsufficientAndDoesNotRejectCandidate() {
        AiTradeRuleConfigMapper configMapper = mock(AiTradeRuleConfigMapper.class);
        AiConditionalRuleExperimentMapper experimentMapper = mock(AiConditionalRuleExperimentMapper.class);
        AiConditionalRuleShadowObservationMapper shadowMapper = mock(AiConditionalRuleShadowObservationMapper.class);
        AiConditionalRuleGovernanceEventMapper eventMapper = mock(AiConditionalRuleGovernanceEventMapper.class);
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        AiSampleLabelMapper labelMapper = mock(AiSampleLabelMapper.class);
        AtomicReference<AiConditionalRuleShadowObservation> storedShadow = new AtomicReference<>();
        AtomicReference<AiConditionalRuleGovernanceEvent> storedEvent = new AtomicReference<>();

        AiTradeRuleConfig candidate = ruleConfig(11L, "CANDIDATE");
        AiTradeRuleConfig baseline = ruleConfig(12L, "ACTIVE");
        AiConditionalRuleExperiment experiment = experiment(31L, candidate.id, "WALK_FORWARD_PASSED");
        AiSample sample = sample(51L, LocalDate.of(2026, 7, 1));
        AiSampleLabel label = label(61L, sample.id, sample.tradeDate);

        when(experimentMapper.selectById(31L)).thenReturn(experiment);
        when(configMapper.selectById(candidate.id)).thenReturn(candidate);
        when(configMapper.selectOne(any())).thenReturn(baseline);
        when(sampleMapper.selectList(any())).thenReturn(List.of(sample));
        when(labelMapper.selectMaturedForSamples(any(), any())).thenReturn(List.of(label));
        when(shadowMapper.selectByObservationKeyForShare(any())).thenAnswer(invocation -> storedShadow.get());
        org.mockito.Mockito.doAnswer(invocation -> {
            AiConditionalRuleShadowObservation value = invocation.getArgument(0);
            value.id = 71L;
            storedShadow.set(value);
            return 1;
        }).when(shadowMapper).insertImmutable(any());
        org.mockito.Mockito.doAnswer(invocation -> {
            storedEvent.set(invocation.getArgument(0));
            return 1;
        }).when(eventMapper).insertImmutable(any());
        when(eventMapper.selectByEventKeyForShare(any())).thenAnswer(invocation -> storedEvent.get());

        AiConditionalRuleGovernanceService service = service(
                configMapper, experimentMapper, shadowMapper, eventMapper, sampleMapper, labelMapper,
                new ConditionalTradeRuleEngine());

        AiConditionalRuleGovernanceService.ShadowResult result = service.runShadow(5L,
                new AiConditionalRuleGovernanceService.ShadowRequest(
                        experiment.id, sample.tradeDate, sample.tradeDate, "limited-shadow",
                        LocalDateTime.of(2026, 7, 10, 16, 0)));

        assertThat(result.observation().status).isEqualTo("INSUFFICIENT_DATA");
        assertThat(experiment.candidateStatus).isEqualTo("SHADOW_INSUFFICIENT_DATA");
        assertThat(storedEvent.get().eventType).isEqualTo("SHADOW_INSUFFICIENT_DATA");
        assertThat(candidate.status).isEqualTo("CANDIDATE");
    }

    @Test
    void approvalLocksShadowAndOnlyActivatesCandidateAfterReadyForReview() {
        AiTradeRuleConfigMapper configMapper = mock(AiTradeRuleConfigMapper.class);
        AiConditionalRuleExperimentMapper experimentMapper = mock(AiConditionalRuleExperimentMapper.class);
        AiConditionalRuleShadowObservationMapper shadowMapper = mock(AiConditionalRuleShadowObservationMapper.class);
        AiConditionalRuleGovernanceEventMapper eventMapper = mock(AiConditionalRuleGovernanceEventMapper.class);
        AtomicReference<AiConditionalRuleGovernanceEvent> storedEvent = new AtomicReference<>();

        AiTradeRuleConfig candidate = ruleConfig(11L, "CANDIDATE");
        AiTradeRuleConfig baseline = ruleConfig(12L, "ACTIVE");
        AiConditionalRuleExperiment experiment = experiment(31L, candidate.id, "READY_FOR_REVIEW");
        AiConditionalRuleShadowObservation shadow = new AiConditionalRuleShadowObservation();
        shadow.id = 71L;
        shadow.userId = 5L;
        shadow.experimentId = experiment.id;
        shadow.candidateTradeRuleConfigId = candidate.id;
        shadow.baselineTradeRuleConfigId = 12L;
        shadow.status = "READY_FOR_REVIEW";
        shadow.thresholdSnapshotJson = "{}";

        when(shadowMapper.selectByIdForUpdate(shadow.id)).thenReturn(shadow);
        when(experimentMapper.selectById(experiment.id)).thenReturn(experiment);
        when(configMapper.selectByIdForUpdate(candidate.id)).thenReturn(candidate);
        when(configMapper.selectByIdForUpdate(baseline.id)).thenReturn(baseline);
        when(configMapper.supersedeActiveForCandidate(candidate.userId, candidate.id, LocalDateTime.of(2026, 7, 10, 16, 0)))
                .thenReturn(1);
        when(configMapper.activateCandidate(candidate.userId, candidate.id, LocalDateTime.of(2026, 7, 10, 16, 0)))
                .thenReturn(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            storedEvent.set(invocation.getArgument(0));
            return 1;
        }).when(eventMapper).insertImmutable(any());
        when(eventMapper.selectByEventKeyForShare(any())).thenAnswer(invocation -> storedEvent.get());

        AiConditionalRuleGovernanceService service = service(
                configMapper, experimentMapper, shadowMapper, eventMapper,
                mock(AiSampleMapper.class), mock(AiSampleLabelMapper.class), new ConditionalTradeRuleEngine());
        LocalDateTime approvedAt = LocalDateTime.of(2026, 7, 10, 16, 0);

        AiConditionalRuleGovernanceService.ApprovalResult result = service.approve(5L,
                new AiConditionalRuleGovernanceService.ApprovalRequest(
                        shadow.id, "人工核验通过", "test", approvedAt));

        assertThat(result.activeConfig().status).isEqualTo("ACTIVE");
        assertThat(shadow.status).isEqualTo("APPROVED");
        assertThat(experiment.candidateStatus).isEqualTo("PROMOTED");
        assertThat(storedEvent.get().eventType).isEqualTo("HUMAN_PROMOTION_APPROVED");
        verify(shadowMapper).selectByIdForUpdate(shadow.id);
        verify(configMapper).selectByIdForUpdate(candidate.id);
        verify(configMapper).selectByIdForUpdate(baseline.id);
        verify(configMapper).supersedeActiveForCandidate(candidate.userId, candidate.id, approvedAt);
        verify(configMapper).activateCandidate(candidate.userId, candidate.id, approvedAt);
    }

    @Test
    void sameExperimentKeyWithDifferentPersistedEvidenceFingerprintIsRejected() {
        AiTradeRuleConfigMapper configMapper = mock(AiTradeRuleConfigMapper.class);
        AiConditionalRuleExperimentMapper experimentMapper = mock(AiConditionalRuleExperimentMapper.class);
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        AiTradeRuleConfig candidate = ruleConfig(9L, "CANDIDATE");
        AiConditionalRuleExperiment inconsistent = experiment(31L, candidate.id, "WALK_FORWARD_INSUFFICIENT_DATA");
        inconsistent.inputFingerprint = "tampered";
        when(configMapper.selectById(candidate.id)).thenReturn(candidate);
        when(sampleMapper.selectList(any())).thenReturn(List.of());
        when(experimentMapper.selectByExperimentKeyForShare(any())).thenReturn(inconsistent);

        AiConditionalRuleGovernanceService service = service(
                configMapper, experimentMapper, mock(AiConditionalRuleShadowObservationMapper.class),
                mock(AiConditionalRuleGovernanceEventMapper.class), sampleMapper,
                mock(AiSampleLabelMapper.class), new ConditionalTradeRuleEngine());

        assertThatThrownBy(() -> service.runWalkForward(1L,
                new AiConditionalRuleGovernanceService.ExperimentRequest(
                        candidate.id, 3, 60, 20, 20, 20, 3, 5, 5, "same-key", LocalDateTime.now())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("已有条件规则实验与当前请求冲突");
    }

    private static AiConditionalRuleGovernanceService service(
            AiTradeRuleConfigMapper configMapper,
            AiConditionalRuleExperimentMapper experimentMapper,
            AiConditionalRuleShadowObservationMapper shadowMapper,
            AiConditionalRuleGovernanceEventMapper eventMapper,
            AiSampleMapper sampleMapper,
            AiSampleLabelMapper labelMapper,
            ConditionalTradeRuleEngine ruleEngine
    ) {
        return new AiConditionalRuleGovernanceServiceImpl(
                configMapper, experimentMapper, mock(AiConditionalRuleExperimentFoldMapper.class),
                mock(AiConditionalRuleExperimentItemMapper.class), shadowMapper,
                mock(AiConditionalRuleShadowItemMapper.class), eventMapper, sampleMapper, labelMapper,
                ruleEngine, new ObjectMapper().findAndRegisterModules());
    }

    private static AiTradeRuleConfig ruleConfig(Long id, String status) {
        AiTradeRuleConfig value = new AiTradeRuleConfig();
        value.id = id;
        value.userId = 5L;
        value.versionNo = "CONDITIONAL_RULE/" + id;
        value.status = status;
        value.configJson = "{}";
        return value;
    }

    private static AiConditionalRuleExperiment experiment(Long id, Long configId, String candidateStatus) {
        AiConditionalRuleExperiment value = new AiConditionalRuleExperiment();
        value.id = id;
        value.tradeRuleConfigId = configId;
        value.horizonDays = 3;
        value.candidateStatus = candidateStatus;
        return value;
    }

    private static AiSample sample(Long id, LocalDate tradeDate) {
        AiSample value = new AiSample();
        value.id = id;
        value.stockCode = "600519";
        value.tradeDate = tradeDate;
        value.samplePhase = "AFTER_CLOSE";
        value.asOfTime = tradeDate.atTime(15, 10);
        value.marketRegime = "NORMAL";
        value.sectorName = "食品饮料";
        value.dataQualityScore = new BigDecimal("90");
        value.qualityStatus = "READY";
        value.tradableStatus = "TRADABLE";
        value.featureVersion = "FEATURE/1";
        value.sourceFingerprint = "sample-fingerprint";
        value.featureSnapshot = """
                {"quote":{"code":"600519","name":"贵州茅台","price":100,"source":"TEST"},"kline":[]}
                """;
        return value;
    }

    private static AiSampleLabel label(Long id, Long sampleId, LocalDate tradeDate) {
        AiSampleLabel value = new AiSampleLabel();
        value.id = id;
        value.sampleId = sampleId;
        value.stockCode = "600519";
        value.horizonTradingDays = 3;
        value.isCurrent = 1;
        value.labelStatus = "MATURED";
        value.netReturn = new BigDecimal("0.02");
        value.excessReturn = new BigDecimal("0.01");
        value.inputFingerprint = "label-fingerprint";
        value.labelAvailableAt = tradeDate.plusDays(3).atStartOfDay();
        return value;
    }

}
