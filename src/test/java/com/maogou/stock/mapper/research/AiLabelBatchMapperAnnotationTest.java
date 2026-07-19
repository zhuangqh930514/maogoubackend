package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
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
        String labelInsert = String.join("\n", AiSampleLabelMapper.class
                .getMethod("insertBatchImmutable", List.class).getAnnotation(Insert.class).value());
        assertThat(labelInsert).contains("planned_exit_trade_date", "sector_excess_return",
                "sector_membership_fingerprint", "max_drawdown", "holding_volatility", "fill_status");
        String costInsert = String.join("\n", AiLabelCostEvidenceMapper.class
                .getMethod("insertBatchImmutable", List.class).getAnnotation(Insert.class).value());
        assertThat(costInsert).contains("impact_cost_bps", "impact_cost_amount");
    }

    @Test
    void labelReadsAndTrainingSourcesUseOnlyCurrentRevision() throws Exception {
        Select select = AiSampleLabelMapper.class.getMethod(
                "selectForSamplesAndVersion", List.class, String.class).getAnnotation(Select.class);

        assertThat(String.join("\n", select.value()).toLowerCase())
                .contains("is_current = 1");
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiSampleLabelMapper.class))
                .doesNotThrowAnyException();
    }
}
