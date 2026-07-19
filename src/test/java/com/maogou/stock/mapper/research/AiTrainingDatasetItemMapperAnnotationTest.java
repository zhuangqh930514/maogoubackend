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
                .contains("UNIVERSE_MEMBERSHIP")
                .contains("SECTOR_EVIDENCE")
                .contains("l.sector_excess_return IS NOT NULL")
                .contains("l.sector_membership_fingerprint IS NOT NULL")
                .contains("universe_snapshot.point_in_time_status = 'READY'")
                .contains("universe_snapshot.source_observed_at <= #{asOfTime}");
        Method summaryMethod = AiTrainingDatasetItemMapper.class.getMethod(
                "selectDominantSourceSummary", String.class, Integer.class,
                java.time.LocalDateTime.class);
        String summarySql = String.join("\n", summaryMethod.getAnnotation(Select.class).value());
        assertThat(summarySql)
                .contains("FORCE INDEX (idx_sample_training_source_summary)")
                .contains("FORCE INDEX (idx_label_training_source_summary)")
                .contains("universe_snapshot.point_in_time_status = 'READY'");
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
                .contains("universe_snapshot.point_in_time_status = 'READY'")
                .contains("AS universe_fingerprint")
                .contains("entry_state.source_fingerprint AS trading_state_fingerprint")
                .contains("l.sector_membership_fingerprint")
                .contains("l.sector_excess_return IS NOT NULL")
                .doesNotContain("(s.trade_date, s.stock_code, s.id) &gt;");
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiTrainingDatasetItemMapper.class))
                .doesNotThrowAnyException();
    }
}
