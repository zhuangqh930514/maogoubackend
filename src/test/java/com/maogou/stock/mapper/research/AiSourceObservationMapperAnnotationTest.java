package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AiSourceObservationMapperAnnotationTest {

    @Test
    void sectorEvidenceQueriesAreBoundToSampleBatchAndPointInTimeVisibility() throws Exception {
        String anchorSql = String.join("\n", AiSourceObservationMapper.class.getMethod(
                        "selectReadyIndustryBenchmarkByBatch", Long.class, String.class)
                .getAnnotation(Select.class).value());
        String subsequentSql = String.join("\n", AiSourceObservationMapper.class.getMethod(
                        "selectRecentReadyIndustryBenchmarksBetween", String.class,
                        LocalDateTime.class, LocalDateTime.class)
                .getAnnotation(Select.class).value());

        assertThat(anchorSql)
                .contains("data_batch_id = #{dataBatchId}")
                .contains("source_type = 'INDUSTRY_BENCHMARK'")
                .contains("quality_status = 'READY'");
        assertThat(subsequentSql)
                .contains("as_of_time > #{afterAsOfTime}")
                .contains("as_of_time <= #{asOfTime}")
                .contains("available_at <= #{asOfTime}")
                .contains("quality_status = 'READY'")
                .contains("ORDER BY as_of_time DESC, id DESC")
                .contains("LIMIT 32");
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiSourceObservationMapper.class))
                .doesNotThrowAnyException();
    }
}
