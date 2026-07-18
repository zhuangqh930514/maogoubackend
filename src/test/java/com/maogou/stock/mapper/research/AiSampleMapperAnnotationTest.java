package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AiSampleMapperAnnotationTest {

    @Test
    void formalAnalysisReadsTheCanonicalAfterCloseSamplePhase() throws Exception {
        Method method = AiSampleMapper.class.getMethod(
                "selectLatestForAnalysis", String.class, java.time.LocalDate.class);
        Select select = method.getAnnotation(Select.class);
        String sql = String.join("\n", select.value());

        assertThat(sql)
                .contains("sample_phase = 'AFTER_CLOSE'")
                .doesNotContain("'CLOSE'")
                .doesNotContain("'POST_CLOSE'");
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiSampleMapper.class))
                .doesNotThrowAnyException();
    }

    @Test
    void pendingLabelCandidatesUseTheNarrowCoveringIndex() throws Exception {
        Method method = AiSampleMapper.class.getMethod(
                "selectLabelCandidateScanPage", java.time.LocalDate.class,
                java.time.LocalDate.class, String.class, Long.class, int.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("s.id, s.data_batch_id, s.stock_code, s.trade_date")
                .contains("s.tradable_status, s.source_fingerprint")
                .contains("FORCE INDEX (idx_sample_pending_labels)")
                .contains("s.tradable_status = 'TRADABLE'")
                .contains("s.id &lt; #{afterId,jdbcType=BIGINT}")
                .contains("ORDER BY s.trade_date DESC, s.stock_code DESC, s.id DESC")
                .doesNotContain("ai_sample_label")
                .doesNotContain("SELECT s.*");
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiSampleMapper.class))
                .doesNotThrowAnyException();
    }
}
