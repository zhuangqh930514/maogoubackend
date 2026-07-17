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
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiPredictionMapper.class))
                .doesNotThrowAnyException();
    }
}
