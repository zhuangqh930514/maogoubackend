package com.maogou.stock.service.impl.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.maogou.stock.domain.entity.research.AiPredictionEvaluation;
import com.maogou.stock.domain.entity.research.AiSampleLabel;
import com.maogou.stock.mapper.research.AiPredictionEvaluationMapper;
import com.maogou.stock.service.research.AiPredictionEvaluationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiPredictionEvaluationServiceImplTest {

    @Test
    void championAndChallengerReuseOneMarketLabelAndCreateIndependentEvaluations() {
        AiPredictionEvaluationMapper mapper = mapperAssigningIds();
        AiPredictionEvaluationService service = service(mapper);
        AiSampleLabel label = executedLabel(501L, 88L, 3, "0.065", "0.042", "UP");

        List<AiPredictionEvaluation> result = service.evaluateAndStore(batch(
                List.of(
                        prediction(1001L, "BUY", "UP", "0.05", "0.72", "0.18", "champion-fp"),
                        prediction(1002L, "HOLD", "UP", "0.03", "0.61", "0.23", "challenger-fp")
                ),
                List.of(label)
        ));

        assertThat(result).hasSize(2);
        assertThat(result).extracting(evaluation -> evaluation.sampleLabelId)
                .containsOnly(501L);
        assertThat(result).extracting(evaluation -> evaluation.predictionId)
                .containsExactly(1001L, 1002L);
        assertThat(result).allSatisfy(evaluation -> {
            assertThat(evaluation.evaluationStatus).isEqualTo("EVALUATED");
            assertThat(evaluation.directionCorrect).isEqualTo(1);
            assertThat(evaluation.actionEffective).isEqualTo(1);
            assertThat(evaluation.evidenceJson).contains("maxAdverseReturn");
        });
        verify(mapper, times(2)).insert(any(AiPredictionEvaluation.class));
    }

    @Test
    void reduceAndSellEvaluateAvoidedLossWithoutTurningLabelIntoNakedShortProfit() {
        AiPredictionEvaluationMapper mapper = mapperAssigningIds();
        AiPredictionEvaluationService service = service(mapper);
        AiSampleLabel loss = executedLabel(502L, 88L, 3, "-0.060", "-0.035", "DOWN");

        AiPredictionEvaluation evaluation = service.evaluateAndStore(batch(
                List.of(prediction(1003L, "SELL", "DOWN", "-0.04", "0.12", "0.78", "sell-fp")),
                List.of(loss)
        )).get(0);

        assertThat(evaluation.actionEffective).isEqualTo(1);
        assertThat(evaluation.netReturn).isEqualByComparingTo("-0.060");
        assertThat(evaluation.excessReturn).isEqualByComparingTo("-0.035");
        assertThat(evaluation.evidenceJson).contains("AVOIDED_LOSS");
    }

    @Test
    void watchIsAbstainAndRemainsAvailableForCoverageMetrics() {
        AiPredictionEvaluationMapper mapper = mapperAssigningIds();
        AiPredictionEvaluationService service = service(mapper);
        AiSampleLabel label = executedLabel(503L, 88L, 3, "0.020", "0.010", "UP");

        AiPredictionEvaluation evaluation = service.evaluateAndStore(batch(
                List.of(prediction(1004L, "WATCH", "SIDEWAYS", null, "0.45", "0.35", "watch-fp")),
                List.of(label)
        )).get(0);

        assertThat(evaluation.evaluationStatus).isEqualTo("ABSTAIN");
        assertThat(evaluation.actionEffective).isNull();
        assertThat(evaluation.directionCorrect).isNull();
        assertThat(evaluation.netReturn).isEqualByComparingTo("0.020");
    }

    @Test
    void unfilledLabelIsNotCountedAsModelFailure() {
        AiPredictionEvaluationMapper mapper = mapperAssigningIds();
        AiPredictionEvaluationService service = service(mapper);
        AiSampleLabel label = executedLabel(504L, 88L, 3, null, null, null);
        label.executionStatus = "UNFILLED";
        label.executionReason = "LIMIT_UP_ENTRY";

        AiPredictionEvaluation evaluation = service.evaluateAndStore(batch(
                List.of(prediction(1005L, "BUY", "UP", "0.05", "0.80", "0.10", "unfilled-fp")),
                List.of(label)
        )).get(0);

        assertThat(evaluation.evaluationStatus).isEqualTo("NOT_EXECUTABLE");
        assertThat(evaluation.actionEffective).isNull();
        assertThat(evaluation.directionCorrect).isNull();
        assertThat(evaluation.evidenceJson).contains("LIMIT_UP_ENTRY");
    }

    @Test
    void identicalEvaluationIsIdempotentlyReused() {
        AiPredictionEvaluationMapper mapper = mock(AiPredictionEvaluationMapper.class);
        AiPredictionEvaluation existing = new AiPredictionEvaluation();
        existing.id = 700L;
        AiSampleLabel label = executedLabel(505L, 88L, 3, "0.020", "0.010", "UP");
        AiPredictionEvaluationService.PredictionInput input =
                prediction(1006L, "BUY", "UP", "0.02", "0.65", "0.20", "same-fp");
        AiPredictionEvaluationServiceImpl temporary =
                new AiPredictionEvaluationServiceImpl(mapper, new ObjectMapper().findAndRegisterModules());
        when(mapper.selectOne(any())).thenReturn(null);
        AiPredictionEvaluation first = temporary.evaluateAndStore(batch(List.of(input), List.of(label))).get(0);
        existing.evidenceJson = first.evidenceJson;
        existing.predictionId = first.predictionId;
        existing.sampleLabelId = first.sampleLabelId;
        existing.evaluationVersion = first.evaluationVersion;
        when(mapper.selectOne(any())).thenReturn(existing);

        AiPredictionEvaluation second = temporary.evaluateAndStore(batch(List.of(input), List.of(label))).get(0);

        assertThat(second.id).isEqualTo(700L);
        verify(mapper, times(1)).insert(any(AiPredictionEvaluation.class));
    }

    private static AiPredictionEvaluationService service(AiPredictionEvaluationMapper mapper) {
        return new AiPredictionEvaluationServiceImpl(mapper, new ObjectMapper().findAndRegisterModules());
    }

    private static AiPredictionEvaluationMapper mapperAssigningIds() {
        AiPredictionEvaluationMapper mapper = mock(AiPredictionEvaluationMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        AtomicLong ids = new AtomicLong(1);
        when(mapper.insert(any(AiPredictionEvaluation.class))).thenAnswer(invocation -> {
            AiPredictionEvaluation evaluation = invocation.getArgument(0);
            evaluation.id = ids.getAndIncrement();
            return 1;
        });
        return mapper;
    }

    private static AiPredictionEvaluationService.EvaluationBatch batch(
            List<AiPredictionEvaluationService.PredictionInput> predictions,
            List<AiSampleLabel> labels
    ) {
        return new AiPredictionEvaluationService.EvaluationBatch(
                predictions,
                labels,
                AiPredictionEvaluationServiceImpl.VERSION,
                LocalDateTime.parse("2026-07-22T16:30:00")
        );
    }

    private static AiPredictionEvaluationService.PredictionInput prediction(
            Long id,
            String action,
            String direction,
            String expectedReturn,
            String probabilityUp,
            String probabilityDown,
            String fingerprint
    ) {
        return new AiPredictionEvaluationService.PredictionInput(
                id,
                88L,
                3,
                action,
                action,
                direction,
                decimal(expectedReturn),
                decimal(probabilityUp),
                decimal(probabilityDown),
                fingerprint
        );
    }

    private static AiSampleLabel executedLabel(
            Long id,
            Long sampleId,
            int horizon,
            String netReturn,
            String excessReturn,
            String direction
    ) {
        AiSampleLabel label = new AiSampleLabel();
        label.id = id;
        label.sampleId = sampleId;
        label.horizonTradingDays = horizon;
        label.inputFingerprint = "label-fingerprint-" + id;
        label.labelStatus = "MATURED";
        label.executionStatus = "EXECUTED";
        label.netReturn = decimal(netReturn);
        label.excessReturn = decimal(excessReturn);
        label.maxFavorableReturn = new BigDecimal("0.08");
        label.maxAdverseReturn = new BigDecimal("-0.04");
        label.actualDirection = direction;
        return label;
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
