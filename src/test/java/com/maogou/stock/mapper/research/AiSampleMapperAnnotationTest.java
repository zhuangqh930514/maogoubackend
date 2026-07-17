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
                "selectPendingLabelCandidates", java.time.LocalDate.class, String.class, int.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("s.id, s.stock_code, s.trade_date, s.tradable_status, s.source_fingerprint")
                .contains("FORCE INDEX (idx_sample_pending_labels)")
                .doesNotContain("SELECT s.*");
    }
}
