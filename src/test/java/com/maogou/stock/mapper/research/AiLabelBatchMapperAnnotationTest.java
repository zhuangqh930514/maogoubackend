package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AiLabelBatchMapperAnnotationTest {

    @Test
    void labelAndCostEvidenceUseImmutableBatchWrites() throws Exception {
        for (Class<?> mapper : List.of(AiSampleLabelMapper.class, AiLabelCostEvidenceMapper.class)) {
            Insert insert = mapper.getMethod("insertBatchImmutable", List.class).getAnnotation(Insert.class);
            assertThat(String.join("\n", insert.value()))
                    .contains("<foreach")
                    .contains("ON DUPLICATE KEY UPDATE id = id");
            assertThatCode(() -> new MybatisConfiguration().addMapper(mapper))
                    .doesNotThrowAnyException();
        }
    }
}
