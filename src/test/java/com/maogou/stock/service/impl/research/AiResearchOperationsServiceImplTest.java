package com.maogou.stock.service.impl.research;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import com.maogou.stock.dto.research.ResearchLabPayloads;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPipelineStepMapper;
import com.maogou.stock.mapper.research.AiStrategyGovernanceEventMapper;
import com.maogou.stock.mapper.research.AiStrategyReleaseMapper;
import com.maogou.stock.service.TradingCalendarService;
import com.maogou.stock.service.research.AiGlobalDailyResearchService;
import com.maogou.stock.service.research.AiHistoricalBootstrapService;
import com.maogou.stock.service.research.AiHistoricalEvidenceImportService;
import com.maogou.stock.service.research.AiLabelVerificationCoordinator;
import com.maogou.stock.service.research.AiMonthlyTrainingRunner;
import com.maogou.stock.service.research.AiStrategyGovernanceService;
import com.maogou.stock.service.research.AiUserDailyProjectionService;
import com.maogou.stock.service.research.AiWeeklyEvolutionRunner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiResearchOperationsServiceImplTest {

    @Test
    void runDailyUsesLatestClosedTradingDateWhenRequestedDateIsMissingOnWeekend() {
        Fixture fixture = fixture();
        AiStrategyRelease champion = strategy(11L, 22L);
        when(fixture.releaseMapper.selectGlobalActiveChampion(any(), any())).thenReturn(champion);
        when(fixture.calendarService.latestExpectedKlineDate(any())).thenReturn(LocalDate.of(2026, 7, 17));

        AtomicReference<AiPipelineRun> inserted = new AtomicReference<>();
        when(fixture.runMapper.insertIgnore(any(AiPipelineRun.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(fixture.runMapper.selectOne(any(QueryWrapper.class))).thenAnswer(invocation -> {
            AiPipelineRun storedRun = inserted.get();
            storedRun.id = 301L;
            storedRun.status = "PENDING";
            return storedRun;
        });
        when(fixture.runMapper.selectById(301L)).thenAnswer(invocation -> inserted.get());

        fixture.service.runDaily(5L, new ResearchLabPayloads.ActionRequest(
                null, null, null, null, null, null, null, "weekend-run"));

        ArgumentCaptor<AiGlobalDailyResearchService.PipelineRequest> requestCaptor =
                ArgumentCaptor.forClass(AiGlobalDailyResearchService.PipelineRequest.class);
        verify(fixture.dailyResearchService).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().tradeDate()).isEqualTo(LocalDate.of(2026, 7, 17));

        ArgumentCaptor<AiPipelineRun> runCaptor = ArgumentCaptor.forClass(AiPipelineRun.class);
        verify(fixture.runMapper).insertIgnore(runCaptor.capture());
        assertThat(runCaptor.getValue().tradeDate).isEqualTo(LocalDate.of(2026, 7, 17));
    }

    @Test
    void runUserProjectionAlignsTradeDateToSelectedGlobalRun() {
        Fixture fixture = fixture();
        when(fixture.calendarService.latestExpectedKlineDate(any())).thenReturn(LocalDate.of(2026, 7, 17));
        when(fixture.calendarService.isTradingDay(LocalDate.of(2026, 7, 19))).thenReturn(false);
        when(fixture.calendarService.previousTradingDay(LocalDate.of(2026, 7, 19))).thenReturn(LocalDate.of(2026, 7, 17));

        AiPipelineRun globalRun = pipelineRun(401L, LocalDate.of(2026, 7, 16), "SUCCESS", "GLOBAL");
        globalRun.strategyReleaseId = 61L;
        globalRun.modelVersionId = 62L;
        AtomicReference<AiPipelineRun> inserted = new AtomicReference<>();
        AtomicInteger selectOneCalls = new AtomicInteger();
        when(fixture.runMapper.insertIgnore(any(AiPipelineRun.class))).thenAnswer(invocation -> {
            inserted.set(invocation.getArgument(0));
            return 1;
        });
        when(fixture.runMapper.selectOne(any(QueryWrapper.class))).thenAnswer(invocation -> {
            if (selectOneCalls.getAndIncrement() == 0) {
                return globalRun;
            }
            AiPipelineRun projectionRun = inserted.get();
            projectionRun.id = 402L;
            projectionRun.status = "RUNNING";
            return projectionRun;
        });
        when(fixture.runMapper.selectById(402L)).thenAnswer(invocation -> inserted.get());

        fixture.service.runUserProjection(5L, new ResearchLabPayloads.ActionRequest(
                LocalDate.of(2026, 7, 19), null, null, null, null, null, null, "projection-run"));

        ArgumentCaptor<AiPipelineRun> runCaptor = ArgumentCaptor.forClass(AiPipelineRun.class);
        verify(fixture.runMapper).insertIgnore(runCaptor.capture());
        assertThat(runCaptor.getValue().tradeDate).isEqualTo(LocalDate.of(2026, 7, 16));
        verify(fixture.projectionService, never()).project(any());
    }

    private static Fixture fixture() {
        AiPipelineRunMapper runMapper = mock(AiPipelineRunMapper.class);
        AiPipelineStepMapper stepMapper = mock(AiPipelineStepMapper.class);
        AiStrategyReleaseMapper releaseMapper = mock(AiStrategyReleaseMapper.class);
        AiStrategyGovernanceEventMapper eventMapper = mock(AiStrategyGovernanceEventMapper.class);
        TradingCalendarService calendarService = mock(TradingCalendarService.class);
        AiGlobalDailyResearchService dailyResearchService = mock(AiGlobalDailyResearchService.class);
        AiHistoricalBootstrapService bootstrapService = mock(AiHistoricalBootstrapService.class);
        AiHistoricalEvidenceImportService historicalEvidenceImportService = mock(AiHistoricalEvidenceImportService.class);
        AiLabelVerificationCoordinator labelCoordinator = mock(AiLabelVerificationCoordinator.class);
        AiWeeklyEvolutionRunner weeklyRunner = mock(AiWeeklyEvolutionRunner.class);
        AiMonthlyTrainingRunner trainingRunner = mock(AiMonthlyTrainingRunner.class);
        AiUserDailyProjectionService projectionService = mock(AiUserDailyProjectionService.class);
        AiStrategyGovernanceService governanceService = mock(AiStrategyGovernanceService.class);
        TaskExecutor taskExecutor = Runnable::run;
        TransactionTemplate transactionTemplate = new TransactionTemplate(mock(PlatformTransactionManager.class));

        AiResearchOperationsServiceImpl service = new AiResearchOperationsServiceImpl(
                runMapper,
                stepMapper,
                releaseMapper,
                eventMapper,
                calendarService,
                dailyResearchService,
                bootstrapService,
                historicalEvidenceImportService,
                labelCoordinator,
                weeklyRunner,
                trainingRunner,
                projectionService,
                governanceService,
                taskExecutor,
                transactionTemplate,
                new ObjectMapper());
        return new Fixture(runMapper, releaseMapper, calendarService, dailyResearchService, projectionService, service);
    }

    private static AiStrategyRelease strategy(Long id, Long modelVersionId) {
        AiStrategyRelease release = new AiStrategyRelease();
        release.id = id;
        release.modelVersionId = modelVersionId;
        release.releaseRole = "CHAMPION";
        release.status = "ACTIVE";
        return release;
    }

    private static AiPipelineRun pipelineRun(Long id, LocalDate tradeDate, String status, String scopeType) {
        AiPipelineRun run = new AiPipelineRun();
        run.id = id;
        run.tradeDate = tradeDate;
        run.status = status;
        run.scopeType = scopeType;
        run.idempotencyKey = "idempotency-" + id;
        run.inputFingerprint = "fingerprint-" + id;
        run.createdAt = LocalDateTime.of(2026, 7, 19, 13, 0);
        run.updatedAt = run.createdAt;
        return run;
    }

    private record Fixture(
            AiPipelineRunMapper runMapper,
            AiStrategyReleaseMapper releaseMapper,
            TradingCalendarService calendarService,
            AiGlobalDailyResearchService dailyResearchService,
            AiUserDailyProjectionService projectionService,
            AiResearchOperationsServiceImpl service
    ) {
    }
}
