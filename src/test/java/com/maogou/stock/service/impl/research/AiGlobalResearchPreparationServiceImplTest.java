package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.service.research.AiGlobalResearchPreparationService;
import com.maogou.stock.service.research.AiResearchContract;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiGlobalResearchPreparationServiceImplTest {

    @Test
    void usesTheSingleGlobalChampionWithoutCreatingAUserBatch() {
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        AiStrategyRelease release = champion();
        release.modelVersionId = 101L;
        when(releaseMapper.selectGlobalActiveChampionForUpdate(
                AiResearchContract.SYSTEM_UNIVERSE_CODE,
                AiResearchContract.MODEL_FAMILY)).thenReturn(release);
        AiGlobalResearchPreparationService service = new AiGlobalResearchPreparationServiceImpl(releaseMapper);

        AiGlobalResearchPreparationService.PreparedPipeline prepared = service.prepare(
                LocalDate.of(2026, 7, 10),
                LocalDateTime.of(2026, 7, 10, 16, 0),
                "GLOBAL_DAILY:2026-07-10");

        assertThat(prepared.strategyReleaseId()).isEqualTo(1L);
        assertThat(prepared.modelVersionId()).isEqualTo(101L);
        assertThat(prepared.inputFingerprint()).hasSize(64);
    }

    @Test
    void refusesToRunWithoutTheSeededGlobalChampion() {
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        when(releaseMapper.selectGlobalActiveChampionForUpdate(
                AiResearchContract.SYSTEM_UNIVERSE_CODE,
                AiResearchContract.MODEL_FAMILY)).thenReturn(null);
        AiGlobalResearchPreparationService service = new AiGlobalResearchPreparationServiceImpl(releaseMapper);

        assertThatThrownBy(() -> service.prepare(
                LocalDate.of(2026, 7, 10),
                LocalDateTime.of(2026, 7, 10, 16, 0),
                "GLOBAL_DAILY:2026-07-10"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Champion");
    }

    private static AiStrategyRelease champion() {
        AiStrategyRelease release = new AiStrategyRelease();
        release.id = 1L;
        release.researchUniverseId = 1L;
        release.modelFamily = "A_SHARE_MULTI_HORIZON";
        release.status = "ACTIVE";
        release.releaseRole = "CHAMPION";
        return release;
    }
}
