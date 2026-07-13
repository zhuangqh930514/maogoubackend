package com.maogou.stock.service.impl.v2;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.v2.AiStrategyGovernanceEvent;
import com.maogou.stock.domain.entity.v2.AiStrategyRelease;
import com.maogou.stock.mapper.v2.AiStrategyGovernanceEventMapper;
import com.maogou.stock.mapper.v2.AiStrategyReleaseMapper;
import com.maogou.stock.service.v2.AiStrategyGovernanceService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiStrategyGovernanceServiceImplTest {

    @Test
    void rejectsPromotionWhenMinimumDaysSamplesTradesOrFoldsAreNotMet() {
        Fixture fixture = fixture();
        AiStrategyGovernanceService service = service(fixture);
        AiStrategyRelease challenger = challenger();
        fixture.releases.add(challenger);

        AiStrategyGovernanceService.Assessment result = service.assess(assessmentRequest(
                new AiStrategyGovernanceService.PromotionEvidence(
                        10, 80, 12, 2, new BigDecimal("0.03"),
                        new BigDecimal("-0.08"), new BigDecimal("0.10"),
                        new BigDecimal("0.02"), 0, "evidence-low-sample")));

        assertThat(result.decisionStatus()).isEqualTo("REJECTED");
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("影子天数"));
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("样本数"));
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("交易数"));
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("窗口数"));
        assertThat(result.event().eventType).isEqualTo("PROMOTION_REJECTED");
        assertThat(challenger.status).isEqualTo("SHADOW");
        assertThat(challenger.releaseRole).isEqualTo("CHALLENGER");
        verify(fixture.releaseMapper, never()).updateById(
                org.mockito.ArgumentMatchers.any(AiStrategyRelease.class));
    }

    @Test
    void passingAutomatedGatesStillRequiresHumanConfirmationBeforeChampionActivation() {
        Fixture fixture = fixture();
        AiStrategyGovernanceService service = service(fixture);
        AiStrategyRelease champion = champion();
        AiStrategyRelease challenger = challenger();
        fixture.releases.add(champion);
        fixture.releases.add(challenger);
        AiStrategyGovernanceService.Assessment assessment = service.assess(assessmentRequest(
                passingEvidence("evidence-pass")));

        assertThat(assessment.decisionStatus()).isEqualTo("AWAITING_HUMAN_CONFIRMATION");
        assertThat(challenger.status).isEqualTo("SHADOW");
        assertThat(champion.status).isEqualTo("ACTIVE");

        AiStrategyGovernanceService.PromotionResult promoted = service.confirmPromotion(
                new AiStrategyGovernanceService.ConfirmationRequest(
                        5L, 2L, assessment.event().eventKey, 9001L,
                        "GOVERNANCE_V2_1", "人工确认样本外结果达标",
                        LocalDateTime.of(2026, 8, 1, 10, 0)));

        assertThat(promoted.previousChampion().status).isEqualTo("RETIRED");
        assertThat(promoted.champion().id).isEqualTo(2L);
        assertThat(promoted.champion().status).isEqualTo("ACTIVE");
        assertThat(promoted.champion().releaseRole).isEqualTo("CHAMPION");
        assertThat(promoted.event().eventType).isEqualTo("HUMAN_PROMOTION_CONFIRMED");
        assertThat(promoted.event().actorType).isEqualTo("HUMAN");
        assertThat(promoted.event().actorId).isEqualTo(9001L);
        assertThat(fixture.releases.stream()
                .filter(item -> "CHAMPION".equals(item.releaseRole) && "ACTIVE".equals(item.status)))
                .extracting(item -> item.id).containsExactly(2L);

        AiStrategyGovernanceService.PromotionResult repeated = service.confirmPromotion(
                new AiStrategyGovernanceService.ConfirmationRequest(
                        5L, 2L, assessment.event().eventKey, 9001L,
                        "GOVERNANCE_V2_1", "人工确认样本外结果达标",
                        LocalDateTime.of(2026, 8, 1, 10, 0)));
        assertThat(repeated.event().id).isEqualTo(promoted.event().id);
        assertThat(repeated.champion().id).isEqualTo(2L);
    }

    @Test
    void criticalDegradationRollsBackToThePreviousChampionAndWritesEvidence() {
        Fixture fixture = fixture();
        AiStrategyGovernanceService service = service(fixture);
        AiStrategyRelease previous = champion();
        previous.status = "RETIRED";
        previous.retiredAt = LocalDateTime.of(2026, 8, 1, 10, 0);
        AiStrategyRelease degraded = challenger();
        degraded.status = "ACTIVE";
        degraded.releaseRole = "CHAMPION";
        degraded.activatedAt = LocalDateTime.of(2026, 8, 1, 10, 0);
        fixture.releases.add(previous);
        fixture.releases.add(degraded);

        AiStrategyGovernanceService.RollbackResult result = service.rollback(
                new AiStrategyGovernanceService.RollbackRequest(
                        5L, 2L, 1L, 301L, 2, "critical-drift-window",
                        "GOVERNANCE_V2_1", "连续窗口出现严重漂移",
                        LocalDateTime.of(2026, 8, 15, 16, 0)));

        assertThat(result.retiredChampion().id).isEqualTo(2L);
        assertThat(result.retiredChampion().status).isEqualTo("RETIRED");
        assertThat(result.restoredChampion().id).isEqualTo(1L);
        assertThat(result.restoredChampion().status).isEqualTo("ACTIVE");
        assertThat(result.event().eventType).isEqualTo("DEGRADATION_ROLLBACK");
        assertThat(result.event().decisionStatus).isEqualTo("ROLLED_BACK");
        assertThat(result.event().actorType).isEqualTo("SYSTEM");
        assertThat(fixture.releases.stream()
                .filter(item -> "CHAMPION".equals(item.releaseRole) && "ACTIVE".equals(item.status)))
                .extracting(item -> item.id).containsExactly(1L);
    }

    @Test
    void rejectsDrawdownConcentrationConfidenceAndCriticalDriftBreaches() {
        Fixture fixture = fixture();
        AiStrategyGovernanceService service = service(fixture);
        fixture.releases.add(challenger());

        AiStrategyGovernanceService.Assessment result = service.assess(assessmentRequest(
                new AiStrategyGovernanceService.PromotionEvidence(
                        25, 300, 50, 5, new BigDecimal("0.06"),
                        new BigDecimal("-0.20"), new BigDecimal("0.35"),
                        BigDecimal.ZERO, 1, "evidence-risk-breach")));

        assertThat(result.decisionStatus()).isEqualTo("REJECTED");
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("最大回撤"));
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("单票贡献"));
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("置信区间"));
        assertThat(result.reasons()).anyMatch(reason -> reason.contains("CRITICAL"));
    }

    private static AiStrategyGovernanceService service(Fixture fixture) {
        return new AiStrategyGovernanceServiceImpl(
                fixture.releaseMapper, fixture.eventMapper,
                new ObjectMapper().findAndRegisterModules());
    }

    private static AiStrategyGovernanceService.AssessmentRequest assessmentRequest(
            AiStrategyGovernanceService.PromotionEvidence evidence
    ) {
        return new AiStrategyGovernanceService.AssessmentRequest(
                5L, 2L, 1L, 101L, 201L, 301L, "GOVERNANCE_V2_1",
                new AiStrategyGovernanceService.PromotionPolicy(
                        20, 200, 30, 4, new BigDecimal("-0.15"),
                        new BigDecimal("0.20"), new BigDecimal("0.01")),
                evidence, LocalDateTime.of(2026, 7, 31, 16, 0));
    }

    private static AiStrategyRelease challenger() {
        AiStrategyRelease release = new AiStrategyRelease();
        release.id = 2L;
        release.userId = 5L;
        release.versionNo = "V2.0.0";
        release.title = "V2 Challenger";
        release.status = "SHADOW";
        release.releaseRole = "CHALLENGER";
        release.configJson = "{}";
        release.createdAt = LocalDateTime.of(2026, 7, 1, 16, 0);
        return release;
    }

    private static AiStrategyRelease champion() {
        AiStrategyRelease release = new AiStrategyRelease();
        release.id = 1L;
        release.userId = 5L;
        release.versionNo = "V1.0.0";
        release.title = "V1 Champion";
        release.status = "ACTIVE";
        release.releaseRole = "CHAMPION";
        release.configJson = "{}";
        release.activatedAt = LocalDateTime.of(2026, 6, 1, 16, 0);
        release.createdAt = LocalDateTime.of(2026, 5, 1, 16, 0);
        return release;
    }

    private static AiStrategyGovernanceService.PromotionEvidence passingEvidence(String fingerprint) {
        return new AiStrategyGovernanceService.PromotionEvidence(
                25, 300, 50, 5, new BigDecimal("0.05"),
                new BigDecimal("-0.10"), new BigDecimal("0.15"),
                new BigDecimal("0.02"), 0, fingerprint);
    }

    private static Fixture fixture() {
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        AiStrategyGovernanceEventMapper eventMapper = mock(AiStrategyGovernanceEventMapper.class);
        List<AiStrategyRelease> releases = new ArrayList<>();
        List<AiStrategyGovernanceEvent> events = new ArrayList<>();
        AtomicLong ids = new AtomicLong(3000);
        when(releaseMapper.selectByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> releases.stream()
                        .filter(item -> item.id.equals(invocation.getArgument(0))).findFirst().orElse(null));
        when(releaseMapper.selectActiveChampionForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> releases.stream()
                        .filter(item -> item.userId.equals(invocation.getArgument(0))
                                && "CHAMPION".equals(item.releaseRole)
                                && "ACTIVE".equals(item.status)).findFirst().orElse(null));
        when(releaseMapper.updateById(org.mockito.ArgumentMatchers.any(AiStrategyRelease.class)))
                .thenReturn(1);
        when(eventMapper.insertImmutable(org.mockito.ArgumentMatchers.any(AiStrategyGovernanceEvent.class)))
                .thenAnswer(invocation -> {
                    AiStrategyGovernanceEvent event = invocation.getArgument(0);
                    event.id = ids.incrementAndGet();
                    events.add(event);
                    return 1;
                });
        when(eventMapper.selectByEventKeyForShare(
                org.mockito.ArgumentMatchers.anyLong(), anyString())).thenAnswer(invocation -> events.stream()
                .filter(item -> item.userId.equals(invocation.getArgument(0))
                        && item.eventKey.equals(invocation.getArgument(1))).findFirst().orElse(null));
        return new Fixture(releaseMapper, eventMapper, releases, events);
    }

    private record Fixture(
            AiStrategyReleaseMapper releaseMapper,
            AiStrategyGovernanceEventMapper eventMapper,
            List<AiStrategyRelease> releases,
            List<AiStrategyGovernanceEvent> events
    ) {
    }
}
