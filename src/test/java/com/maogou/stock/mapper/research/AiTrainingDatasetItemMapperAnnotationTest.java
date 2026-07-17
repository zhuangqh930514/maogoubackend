package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.maogou.stock.domain.entity.research.AiTrainingDatasetSourceQuery;
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
        Method pageMethod = AiTrainingDatasetItemMapper.class.getMethod(
                "selectEligibleSourcesPage", AiTrainingDatasetSourceQuery.class,
                java.time.LocalDate.class, String.class, Long.class, int.class);
        String pageSql = String.join("\n", pageMethod.getAnnotation(Select.class).value());
        assertThat(pageSql)
                .contains("FORCE INDEX (idx_sample_training_source_page)")
                .contains("STRAIGHT_JOIN ai_sample_label l FORCE INDEX (uk_sample_label_version)")
                .contains("s.trade_date &gt; #{afterTradeDate}")
                .contains("s.stock_code &gt; #{afterStockCode}")
                .contains("s.id &gt; #{afterSampleId}")
                .doesNotContain("(s.trade_date, s.stock_code, s.id) &gt;");
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiTrainingDatasetItemMapper.class))
                .doesNotThrowAnyException();
    }
}
