package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiHistoricalBackfillRun;
import com.maogou.stock.domain.entity.research.AiHistoricalBackfillShard;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.dto.research.HistoricalFastStartPayloads;
import com.maogou.stock.infrastructure.market.HistoricalMarketDataProvider;
import com.maogou.stock.mapper.research.AiDataQuarantineMapper;
import com.maogou.stock.mapper.research.AiHistoricalBackfillRunMapper;
import com.maogou.stock.mapper.research.AiHistoricalBackfillShardMapper;
import com.maogou.stock.mapper.research.AiSampleLabelMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.mapper.research.AiTrainingReadinessSnapshotMapper;
import com.maogou.stock.service.research.AiHistoricalBootstrapService;
import com.maogou.stock.service.research.AiHistoricalEvidenceImportService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiHistoricalFastStartServiceImplTest {

    @Test
    void previewUsesRealTradingPlanAndReturnsReusableCounts() {
        Fixture fixture = fixture();
        AiHistoricalEvidenceImportService.ColdStartPlan plan = plan(180, 300);
        when(fixture.evidence.plan(LocalDate.of(2026, 7, 31), 180, 300)).thenReturn(plan);
        when(fixture.strategyMapper.selectGlobalActiveChampion(any(), any())).thenReturn(champion());
        when(fixture.sampleMapper.selectCount(any())).thenReturn(12L);
        when(fixture.labelMapper.selectCount(any())).thenReturn(48L);

        HistoricalFastStartPayloads.PreviewResult result = fixture.service().preview(
                new HistoricalFastStartPayloads.PreviewRequest(
                        LocalDate.of(2026, 7, 31), 180, 300, null, null, null, null, null, null), 5L);

        assertThat(result.previewFingerprint()).isNotBlank();
        assertThat(result.effectiveSampleStartDate()).isEqualTo(plan.tradingDates().get(0));
        assertThat(result.effectiveSampleEndDate()).isEqualTo(plan.tradingDates().get(179));
        assertThat(result.reusable()).containsEntry("samples", 12L).containsEntry("labels", 48L);
        assertThat(result.planned()).containsEntry("samples", 54_000L);
    }

    @Test
    void createRejectsStalePreviewBeforeWritingRun() {
        Fixture fixture = fixture();
        AiHistoricalEvidenceImportService.ColdStartPlan plan = plan(180, 300);
        when(fixture.evidence.plan(any(), anyInt(), anyInt())).thenReturn(plan);
        when(fixture.strategyMapper.selectGlobalActiveChampion(any(), any())).thenReturn(champion());
        when(fixture.sampleMapper.selectCount(any())).thenReturn(0L);
        when(fixture.labelMapper.selectCount(any())).thenReturn(0L);

        assertThatThrownBy(() -> fixture.service().create(
                new HistoricalFastStartPayloads.CreateRequest(
                        LocalDate.of(2026, 7, 31), 180, 300, null, null, null, null, null,
                        null, "stale-preview", "body-key"), "header-key", 5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("预览已过期");
    }

    @Test
    void createIsIdempotentAndGeneratesReplayBlockShards() {
        Fixture fixture = fixture();
        AiHistoricalEvidenceImportService.ColdStartPlan plan = plan(180, 300);
        when(fixture.evidence.plan(any(), anyInt(), anyInt())).thenReturn(plan);
        when(fixture.strategyMapper.selectGlobalActiveChampion(any(), any())).thenReturn(champion());
        when(fixture.sampleMapper.selectCount(any())).thenReturn(0L);
        when(fixture.labelMapper.selectCount(any())).thenReturn(0L);
        AtomicReference<AiHistoricalBackfillRun> inserted = new AtomicReference<>();
        when(fixture.runMapper.insertIgnore(any(AiHistoricalBackfillRun.class))).thenAnswer(invocation -> {
            AiHistoricalBackfillRun value = invocation.getArgument(0);
            value.id = 901L;
            inserted.set(value);
            return 1;
        });
        when(fixture.runMapper.selectByRunKey("header-key")).thenAnswer(invocation -> inserted.get());
        when(fixture.runMapper.selectByRunId(901L)).thenAnswer(invocation -> inserted.get());

        HistoricalFastStartPayloads.PreviewResult preview = fixture.service().preview(
                new HistoricalFastStartPayloads.PreviewRequest(
                        LocalDate.of(2026, 7, 31), 180, 300, null, null, null, null, null, null), 5L);
        HistoricalFastStartPayloads.RunView result = fixture.service().create(
                new HistoricalFastStartPayloads.CreateRequest(
                        LocalDate.of(2026, 7, 31), 180, 300, null, null, null, null, null,
                        null, preview.previewFingerprint(), "body-key"), "header-key", 5L);

        assertThat(result.id()).isEqualTo(901L);
        // Import and replay are independently recoverable for every 20-day block:
        // 10 blocks x 2 stages + label maturation + evaluation + readiness.
        verify(fixture.shardMapper, times(23)).insertIgnore(any(AiHistoricalBackfillShard.class));
    }

    @Test
    void pauseDelegatesToConditionalStateTransition() {
        Fixture fixture = fixture();
        AiHistoricalBackfillRun run = storedRun();
        when(fixture.runMapper.selectByRunId(901L)).thenReturn(run);
        when(fixture.runMapper.pause(anyLong(), any(), any())).thenReturn(1);

        HistoricalFastStartPayloads.RunView result = fixture.service().pause(901L, 5L);

        assertThat(result.id()).isEqualTo(901L);
        verify(fixture.runMapper).pause(anyLong(), any(), any());
    }

    private static Fixture fixture() {
        return new Fixture(
                mock(AiHistoricalBackfillRunMapper.class),
                mock(AiHistoricalBackfillShardMapper.class),
                mock(AiDataQuarantineMapper.class),
                mock(AiTrainingReadinessSnapshotMapper.class),
                mock(AiSampleMapper.class),
                mock(AiSampleLabelMapper.class),
                mock(AiStrategyReleaseMapper.class),
                mock(AiHistoricalEvidenceImportService.class),
                mock(AiHistoricalBootstrapService.class),
                List.of(),
                command -> { },
                new ObjectMapper().findAndRegisterModules());
    }

    private static AiStrategyRelease champion() {
        AiStrategyRelease value = new AiStrategyRelease();
        value.id = 11L;
        value.modelVersionId = 22L;
        value.status = "ACTIVE";
        value.releaseRole = "CHAMPION";
        return value;
    }

    private static AiHistoricalBackfillRun storedRun() {
        AiHistoricalBackfillRun value = new AiHistoricalBackfillRun();
        value.id = 901L;
        value.runKey = "header-key";
        value.mode = "REPAIR_AND_EXPAND";
        value.status = "PLANNED";
        value.targetTradingDays = 180;
        value.targetStocksPerDay = 300;
        value.requestedStartDate = LocalDate.of(2025, 11, 20);
        value.requestedEndDate = LocalDate.of(2026, 7, 31);
        value.createdAt = LocalDateTime.of(2026, 8, 1, 10, 0);
        value.updatedAt = value.createdAt;
        return value;
    }

    private static AiHistoricalEvidenceImportService.ColdStartPlan plan(int days, int stocks) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate start = LocalDate.of(2025, 11, 20);
        for (int i = 0; i < days + 5; i++) {
            dates.add(start.plusDays(i));
        }
        return new AiHistoricalEvidenceImportService.ColdStartPlan(
                dates.get(0), dates.get(dates.size() - 1), days, dates.size(), stocks, dates);
    }

    private record Fixture(
            AiHistoricalBackfillRunMapper runMapper,
            AiHistoricalBackfillShardMapper shardMapper,
            AiDataQuarantineMapper quarantineMapper,
            AiTrainingReadinessSnapshotMapper readinessMapper,
            AiSampleMapper sampleMapper,
            AiSampleLabelMapper labelMapper,
            AiStrategyReleaseMapper strategyMapper,
            AiHistoricalEvidenceImportService evidence,
            AiHistoricalBootstrapService bootstrap,
            List<HistoricalMarketDataProvider> providers,
            TaskExecutor executor,
            ObjectMapper objectMapper
    ) {
        AiHistoricalFastStartServiceImpl service() {
            return new AiHistoricalFastStartServiceImpl(runMapper, shardMapper, quarantineMapper,
                    readinessMapper, sampleMapper, labelMapper, strategyMapper, evidence, bootstrap,
                    providers, executor, objectMapper);
        }
    }
}
