package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AiTrainingDatasetItemMapperAnnotationTest {

    @Test
    void readinessQueryUsesIndexOnlyMaturedLabelsWithExplicitUnionCollation() throws Exception {
        Method method = AiTrainingDatasetItemMapper.class.getMethod(
                "selectTrainingReadinessMetrics", String.class, java.time.LocalDateTime.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("COLLATE utf8mb4_unicode_ci")
                .contains("l.label_available_at <= #{asOfTime}")
                .doesNotContain("INNER JOIN ai_sample s ON s.id = l.sample_id");
        Method summaryMethod = AiTrainingDatasetItemMapper.class.getMethod(
                "selectDominantSourceSummary", String.class, Integer.class,
                java.time.LocalDateTime.class);
        String summarySql = String.join("\n", summaryMethod.getAnnotation(Select.class).value());
        assertThat(summarySql)
                .contains("FORCE INDEX (idx_sample_training_source_summary)")
                .contains("FORCE INDEX (idx_label_training_source_summary)");
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiTrainingDatasetItemMapper.class))
                .doesNotThrowAnyException();
    }
}
