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
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiTrainingDatasetItemMapper.class))
                .doesNotThrowAnyException();
    }
}
