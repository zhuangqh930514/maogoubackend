package com.maogou.stock.service.impl.research;

import com.maogou.stock.domain.entity.research.AiDataBatch;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.service.research.AiGlobalResearchPreparationService;
import com.maogou.stock.service.research.AiSampleSnapshotService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiGlobalResearchPreparationServiceImplTest {

    @Test
    void preparesARealBatchAndUsesTheActiveChampion() {
        AiSampleSnapshotService snapshotService = mock(AiSampleSnapshotService.class);
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        AiDataBatch batch = new AiDataBatch();
        batch.id = 11L;
        when(snapshotService.startOrGetBatch(any(), any(), any(), any(), any())).thenReturn(batch);
        AiStrategyRelease release = new AiStrategyRelease();
        release.id = 91L;
        release.userId = 5L;
        release.modelVersionId = 101L;
        release.status = "ACTIVE";
        release.releaseRole = "CHAMPION";
        when(releaseMapper.selectActiveChampionForUpdate(5L)).thenReturn(release);
        AiGlobalResearchPreparationService service = new AiGlobalResearchPreparationServiceImpl(
                snapshotService, releaseMapper);

        AiGlobalResearchPreparationService.PreparedPipeline prepared = service.prepare(
                5L,
                LocalDate.of(2026, 7, 10),
                LocalDateTime.of(2026, 7, 10, 16, 0),
                "AUTO_CLOSE:2026-07-10:USER:5");

        assertThat(prepared.dataBatchId()).isEqualTo(11L);
        assertThat(prepared.strategyReleaseId()).isEqualTo(91L);
        assertThat(prepared.modelVersionId()).isEqualTo(101L);
        assertThat(prepared.inputFingerprint()).isNotBlank();
    }

    @Test
    void weekendExecutionStillLocksMarketDataToTheTargetTradingClose() {
        AiSampleSnapshotService snapshotService = mock(AiSampleSnapshotService.class);
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        AiDataBatch batch = new AiDataBatch();
        batch.id = 11L;
        when(snapshotService.startOrGetBatch(any(), any(), any(), any(), any())).thenReturn(batch);
        AiStrategyRelease release = new AiStrategyRelease();
        release.id = 91L;
        release.userId = 5L;
        release.status = "ACTIVE";
        release.releaseRole = "CHAMPION";
        when(releaseMapper.selectActiveChampionForUpdate(5L)).thenReturn(release);
        AiGlobalResearchPreparationService service = new AiGlobalResearchPreparationServiceImpl(
                snapshotService, releaseMapper);

        service.prepare(5L, LocalDate.of(2026, 7, 10),
                LocalDateTime.of(2026, 7, 12, 10, 0), "AUTO_CLOSE:2026-07-10:USER:5");

        verify(snapshotService).startOrGetBatch(
                5L, LocalDate.of(2026, 7, 10), "AFTER_CLOSE",
                LocalDateTime.of(2026, 7, 10, 16, 0), "AUTO_CLOSE:2026-07-10:USER:5:BATCH");
    }

    @Test
    void createsAnExplicitRuleBaselineWhenTheUserHasNoChampion() {
        AiSampleSnapshotService snapshotService = mock(AiSampleSnapshotService.class);
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        AiDataBatch batch = new AiDataBatch();
        batch.id = 12L;
        when(snapshotService.startOrGetBatch(any(), any(), any(), any(), any())).thenReturn(batch);
        when(releaseMapper.selectActiveChampionForUpdate(5L))
                .thenReturn(null)
                .thenAnswer(invocation -> {
                    AiStrategyRelease release = new AiStrategyRelease();
                    release.id = 92L;
                    release.userId = 5L;
                    release.versionNo = "RULE-BASELINE-V1";
                    release.status = "ACTIVE";
                    release.releaseRole = "CHAMPION";
                    return release;
                });
        when(releaseMapper.insert(any(AiStrategyRelease.class))).thenAnswer(invocation -> {
            AiStrategyRelease release = invocation.getArgument(0);
            release.id = 92L;
            return 1;
        });
        AiGlobalResearchPreparationService service = new AiGlobalResearchPreparationServiceImpl(
                snapshotService, releaseMapper);

        AiGlobalResearchPreparationService.PreparedPipeline prepared = service.prepare(
                5L,
                LocalDate.of(2026, 7, 10),
                LocalDateTime.of(2026, 7, 10, 16, 0),
                "AUTO_CLOSE:2026-07-10:USER:5");

        assertThat(prepared.strategyReleaseId()).isEqualTo(92L);
        assertThat(prepared.modelVersionId()).isNull();
        verify(releaseMapper).insert(any(AiStrategyRelease.class));
    }
}
