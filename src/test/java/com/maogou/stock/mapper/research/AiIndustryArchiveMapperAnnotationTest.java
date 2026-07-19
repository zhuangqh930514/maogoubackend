package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AiIndustryArchiveMapperAnnotationTest {

    @Test
    void membershipLookupRequiresExactReadyPointInTimeUniverse() throws Exception {
        Select select = AiResearchUniverseItemMapper.class.getMethod(
                "selectReadyIndustryMembershipsForSamples", List.class, LocalDateTime.class)
                .getAnnotation(Select.class);

        String sql = String.join("\n", select.value()).toLowerCase();
        assertThat(sql)
                .contains("snapshot.trade_date = sample.trade_date")
                .contains("snapshot.status = 'finalized'")
                .contains("snapshot.quality_status = 'ready'")
                .contains("snapshot.point_in_time_status = 'ready'")
                .contains("snapshot.source_observed_at &lt;= #{asoftime}")
                .contains("item.included = 1")
                .contains("item.industry_standard is not null");
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiResearchUniverseItemMapper.class))
                .doesNotThrowAnyException();
    }

    @Test
    void industryBarLookupUsesOnlyCurrentReadyEvidenceVisibleAtVerificationTime() throws Exception {
        Select select = AiIndustryDailyBarMapper.class.getMethod(
                "selectCurrentSeries", List.class, String.class, LocalDate.class,
                LocalDate.class, LocalDateTime.class).getAnnotation(Select.class);

        String sql = String.join("\n", select.value()).toLowerCase();
        assertThat(sql)
                .contains("is_current = 1")
                .contains("quality_status = 'ready'")
                .contains("classification_standard = #{classificationstandard}")
                .contains("trade_date between #{startdate} and #{enddate}")
                .contains("observed_at &lt;= #{asoftime}")
                .contains("order by industry_code, trade_date");
        assertThatCode(() -> new MybatisConfiguration().addMapper(AiIndustryDailyBarMapper.class))
                .doesNotThrowAnyException();
    }
}
