package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.WatchStock;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItemPrediction;
import com.maogou.stock.domain.entity.research.AiDailyDecisionSnapshot;
import com.maogou.stock.domain.entity.research.AiPipelineRun;
import com.maogou.stock.domain.entity.research.AiPrediction;
import com.maogou.stock.domain.entity.research.AiSample;
import com.maogou.stock.mapper.TradeRecordMapper;
import com.maogou.stock.mapper.WatchStockMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionItemMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionItemPredictionMapper;
import com.maogou.stock.mapper.research.AiDailyDecisionSnapshotMapper;
import com.maogou.stock.mapper.research.AiFactorPerformanceMapper;
import com.maogou.stock.mapper.research.AiFactorValueMapper;
import com.maogou.stock.mapper.research.AiPipelineRunMapper;
import com.maogou.stock.mapper.research.AiPredictionEvaluationMapper;
import com.maogou.stock.mapper.research.AiPredictionMapper;
import com.maogou.stock.mapper.research.AiSampleMapper;
import com.maogou.stock.service.AiResearchDailyReportService;
import com.maogou.stock.service.research.AiUserDailyProjectionService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiUserDailyProjectionServiceImplTest {

    @Test
    void persistsT1T2T3LineageWithAnExactUnitWeightAndT3AsPrimary() {
        Fixture fixture = fixture(5L, true);

        AiUserDailyProjectionService.ProjectionResult result = fixture.service.project(request(5L));

        assertThat(result.snapshot().userId).isEqualTo(5L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.predictionLinks()).hasSize(3);
        assertThat(result.predictionLinks()).extracting(item -> item.purpose)
                .containsExactlyInAnyOrder("T1_SIGNAL", "T2_SIGNAL", "PRIMARY_RANKING");
        assertThat(result.predictionLinks()).extracting(item -> item.weight)
                .satisfiesExactlyInAnyOrder(
                        value -> assertThat(value).isEqualByComparingTo("0.200000"),
                        value -> assertThat(value).isEqualByComparingTo("0.300000"),
                        value -> assertThat(value).isEqualByComparingTo("0.500000"));
        assertThat(result.predictionLinks().stream()
                .map(item -> item.weight)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1.000000");
        ArgumentCaptor<AiResearchDailyReportService.GenerationRequest> reportRequest =
                ArgumentCaptor.forClass(AiResearchDailyReportService.GenerationRequest.class);
        verify(fixture.dailyReportService).generate(reportRequest.capture());
        assertThat(reportRequest.getValue().userId()).isEqualTo(5L);
        assertThat(reportRequest.getValue().decisionSnapshotId()).isEqualTo(result.snapshot().id);
    }

    @Test
    void missingCorePredictionBecomesDataUnavailableAndNeverEntersDecisionCounts() {
        Fixture fixture = fixture(5L, false);

        AiUserDailyProjectionService.ProjectionResult result = fixture.service.project(request(5L));

        AiDailyDecisionItem item = result.items().get(0);
        assertThat(item.category).isEqualTo("DATA_UNAVAILABLE");
        assertThat(item.unavailableReason).contains("T3");
        assertThat(item.systemScore).isNull();
        assertThat(result.snapshot().unavailableCount).isEqualTo(1);
        assertThat(result.snapshot().recommendationCount).isZero();
        assertThat(result.snapshot().avoidCount).isZero();
    }

    @Test
    void anExistingSnapshotOwnedByAnotherUserIsNeverReused() {
        Fixture fixture = fixture(5L, true);
        AiDailyDecisionSnapshot foreign = new AiDailyDecisionSnapshot();
        foreign.id = 99L;
        foreign.userId = 6L;
        when(fixture.snapshotMapper.selectByIdempotencyForShare(5L, "USER_DAILY:5:2026-07-10:81"))
                .thenReturn(foreign);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service.project(request(5L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("用户隔离");
        verify(fixture.itemMapper, never()).insert(any(AiDailyDecisionItem.class));
    }

    @Test
    void aFailedUserProjectionCannotLeakIdentityIntoTheNextProjection() {
        Fixture fixture = fixture(6L, true);
        when(fixture.snapshotMapper.lockUser(5L)).thenReturn(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.service.project(request(5L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户不存在");
        AiUserDailyProjectionService.ProjectionResult result = fixture.service.project(request(6L));

        assertThat(result.snapshot().userId).isEqualTo(6L);
        ArgumentCaptor<QueryWrapper<WatchStock>> watchQuery = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(fixture.watchMapper).selectList(watchQuery.capture());
        assertThat(watchQuery.getValue().getCustomSqlSegment()).contains("user_id");
        assertThat(watchQuery.getValue().getParamNameValuePairs().values()).contains(6L).doesNotContain(5L);
        assertThat(com.maogou.stock.security.AuthContext.currentUserId()).isEmpty();
    }

    private static Fixture fixture(Long userId, boolean includeT3) {
        AiDailyDecisionSnapshotMapper snapshotMapper = mock(AiDailyDecisionSnapshotMapper.class);
        AiDailyDecisionItemMapper itemMapper = mock(AiDailyDecisionItemMapper.class);
        AiDailyDecisionItemPredictionMapper linkMapper = mock(AiDailyDecisionItemPredictionMapper.class);
        WatchStockMapper watchMapper = mock(WatchStockMapper.class);
        TradeRecordMapper tradeMapper = mock(TradeRecordMapper.class);
        AiPipelineRunMapper runMapper = mock(AiPipelineRunMapper.class);
        AiSampleMapper sampleMapper = mock(AiSampleMapper.class);
        AiPredictionMapper predictionMapper = mock(AiPredictionMapper.class);
        AiPredictionEvaluationMapper evaluationMapper = mock(AiPredictionEvaluationMapper.class);
        AiFactorValueMapper factorValueMapper = mock(AiFactorValueMapper.class);
        AiFactorPerformanceMapper factorPerformanceMapper = mock(AiFactorPerformanceMapper.class);
        AiResearchDailyReportService dailyReportService = mock(AiResearchDailyReportService.class);

        AiPipelineRun run = new AiPipelineRun();
        run.id = 81L;
        run.scopeType = "GLOBAL";
        run.tradeDate = LocalDate.of(2026, 7, 10);
        run.strategyReleaseId = 91L;
        run.modelVersionId = 101L;
        run.dataBatchId = 71L;
        run.status = "SUCCESS";
        when(runMapper.selectById(81L)).thenReturn(run);

        WatchStock stock = new WatchStock();
        stock.userId = userId;
        stock.stockCode = "600519";
        stock.stockName = "贵州茅台";
        when(watchMapper.selectList(any())).thenReturn(List.of(stock));
        when(tradeMapper.selectList(any())).thenReturn(List.of());

        AiSample sample = new AiSample();
        sample.id = 31L;
        sample.dataBatchId = 71L;
        sample.stockCode = "600519";
        sample.stockName = "贵州茅台";
        sample.tradeDate = run.tradeDate;
        sample.asOfTime = LocalDateTime.of(2026, 7, 10, 15, 5);
        sample.marketRegime = "BALANCED";
        sample.dataQualityScore = new BigDecimal("95");
        sample.qualityStatus = "READY";
        sample.tradableStatus = "TRADABLE";
        sample.sourceFingerprint = "sample-fingerprint";
        when(sampleMapper.selectLatestForDecision(71L, run.tradeDate, List.of("600519")))
                .thenReturn(List.of(sample));

        List<AiPrediction> predictions = new java.util.ArrayList<>();
        predictions.add(prediction(41L, 31L, 1, "72", "35"));
        predictions.add(prediction(42L, 31L, 2, "78", "35"));
        if (includeT3) {
            predictions.add(prediction(43L, 31L, 3, "80", "35"));
        }
        when(predictionMapper.selectForDailyDecision(List.of(31L), 91L)).thenReturn(predictions);
        when(evaluationMapper.selectForDecisionEvidence(91L, run.tradeDate)).thenReturn(List.of());
        when(factorValueMapper.selectBySamples(anyList(), any())).thenReturn(List.of());
        when(factorPerformanceMapper.selectForSamplesBefore(anyList(), any())).thenReturn(List.of());

        AtomicLong ids = new AtomicLong(1000);
        when(snapshotMapper.lockUser(anyLong())).thenAnswer(invocation -> invocation.getArgument(0));
        when(snapshotMapper.selectMaxVersionForUpdate(userId, run.tradeDate)).thenReturn(0);
        when(snapshotMapper.insert(any(AiDailyDecisionSnapshot.class))).thenAnswer(invocation -> {
            AiDailyDecisionSnapshot value = invocation.getArgument(0);
            value.id = ids.incrementAndGet();
            return 1;
        });
        when(itemMapper.insert(any(AiDailyDecisionItem.class))).thenAnswer(invocation -> {
            AiDailyDecisionItem value = invocation.getArgument(0);
            value.id = ids.incrementAndGet();
            return 1;
        });
        when(linkMapper.insert(any(AiDailyDecisionItemPrediction.class))).thenReturn(1);

        AiUserDailyProjectionService service = new AiUserDailyProjectionServiceImpl(
                snapshotMapper, itemMapper, linkMapper, watchMapper, tradeMapper, runMapper,
                sampleMapper, predictionMapper, evaluationMapper, factorValueMapper,
                factorPerformanceMapper, new DecisionPolicyV1(), dailyReportService,
                new ObjectMapper().findAndRegisterModules());
        return new Fixture(service, snapshotMapper, itemMapper, watchMapper, dailyReportService);
    }

    private static AiPrediction prediction(Long id, Long sampleId, int horizon, String score, String risk) {
        AiPrediction value = new AiPrediction();
        value.id = id;
        value.sampleId = sampleId;
        value.strategyReleaseId = 91L;
        value.stockCode = "600519";
        value.tradeDate = LocalDate.of(2026, 7, 10);
        value.horizonDays = horizon;
        value.score = new BigDecimal(score);
        value.riskScore = new BigDecimal(risk);
        value.probabilityUp = new BigDecimal("0.75");
        value.probabilityDown = new BigDecimal("0.25");
        value.expectedExcessReturn = new BigDecimal("0.03");
        value.action = "BUY";
        value.inputFingerprint = "prediction-" + horizon;
        return value;
    }

    private static AiUserDailyProjectionService.ProjectionRequest request(Long userId) {
        return new AiUserDailyProjectionService.ProjectionRequest(
                userId, LocalDate.of(2026, 7, 10), 81L, null,
                "USER_DAILY:" + userId + ":2026-07-10:81",
                LocalDateTime.of(2026, 7, 10, 16, 20));
    }

    private record Fixture(
            AiUserDailyProjectionService service,
            AiDailyDecisionSnapshotMapper snapshotMapper,
            AiDailyDecisionItemMapper itemMapper,
            WatchStockMapper watchMapper,
            AiResearchDailyReportService dailyReportService
    ) {
    }
}
