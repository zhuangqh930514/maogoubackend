package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AiPredictionMapperAnnotationTest {

    @Test
    void customPredictionQueriesMapTheTradingHorizonToHorizonDays() throws Exception {
        for (String methodName : List.of(
                "selectByIdempotencyKeysForShare",
                "selectUnevaluatedCandidates",
                "selectUnevaluatedCandidatesForBackfillRun",
                "selectDueDailyUnevaluatedCandidates",
                "selectDueDailyUnevaluatedCandidatesForBackfillRun",
                "selectForDailyDecision",
                "selectForAnalysis")) {
            Method method = java.util.Arrays.stream(AiPredictionMapper.class.getMethods())
                    .filter(candidate -> methodName.equals(candidate.getName()))
                    .findFirst()
                    .orElseThrow();
            Select select = method.getAnnotation(Select.class);
            assertThat(String.join("\n", select.value()))
                    .as(methodName)
                    .contains("horizon_trading_days AS horizonDays");
        }
        Method evaluationMethod = java.util.Arrays.stream(AiPredictionMapper.class.getMethods())
                .filter(candidate -> "selectUnevaluatedCandidates".equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
        String evaluationSql = String.join("\n", evaluationMethod.getAnnotation(Select.class).value());
        assertThat(evaluationSql)
                .contains("FORCE INDEX (idx_prediction_evaluation_candidates)")
                .contains("FORCE INDEX (idx_label_evaluation_candidate)")
                .contains("FORCE INDEX (idx_evaluation_version_prediction)")
                .doesNotContain("SELECT p.*");
        Method dueMethod = java.util.Arrays.stream(AiPredictionMapper.class.getMethods())
                .filter(candidate -> "selectDueDailyUnevaluatedCandidates".equals(candidate.getName()))
                .findFirst().orElseThrow();
        String dueSql = String.join("\n", dueMethod.getAnnotation(Select.class).value());
        assertThat(dueSql)
                .contains("l.label_available_at <= #{tradeDate}")
                .contains("p.horizon_trading_days IN (1, 2, 3)")
                .contains("p.trade_date DESC")
                .doesNotContain("SELECT p.*");
        for (String methodName : List.of(
                "selectUnevaluatedCandidatesForBackfillRun",
                "selectDueDailyUnevaluatedCandidatesForBackfillRun")) {
            Method runAwareMethod = java.util.Arrays.stream(AiPredictionMapper.class.getMethods())
                    .filter(candidate -> methodName.equals(candidate.getName()))
                    .findFirst().orElseThrow();
            String runAwareSql = String.join("\n", runAwareMethod.getAnnotation(Select.class).value());
            assertThat(runAwareSql)
                    .as(methodName)
                    .contains("INNER JOIN ai_data_batch b")
                    .contains("b.backfill_run_id = #{historicalBackfillRunId}")
                    .contains("INNER JOIN ai_sample s");
        }
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiPredictionMapper.class))
                .doesNotThrowAnyException();
    }
}
