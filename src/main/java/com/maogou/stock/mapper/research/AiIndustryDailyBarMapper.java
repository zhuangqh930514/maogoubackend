package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiIndustryDailyBar;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiIndustryDailyBarMapper extends BaseMapper<AiIndustryDailyBar> {

    @Insert("""
            INSERT INTO ai_industry_daily_bar (
                industry_code, industry_name, classification_standard, trade_date,
                open_price, high_price, low_price, close_price, volume, amount,
                source_name, source_revision, revision_no, is_current, supersedes_bar_id,
                quality_status, source_ref, evidence_json, source_fingerprint, observed_at, created_at
            ) VALUES (
                #{item.industryCode}, #{item.industryName}, #{item.classificationStandard}, #{item.tradeDate},
                #{item.openPrice}, #{item.highPrice}, #{item.lowPrice}, #{item.closePrice},
                #{item.volume}, #{item.amount}, #{item.sourceName}, #{item.sourceRevision},
                #{item.revisionNo}, #{item.isCurrent}, #{item.supersedesBarId}, #{item.qualityStatus},
                #{item.sourceRef}, #{item.evidenceJson}, #{item.sourceFingerprint},
                #{item.observedAt}, #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiIndustryDailyBar item);

    @Select("""
            SELECT * FROM ai_industry_daily_bar
            WHERE industry_code = #{industryCode}
              AND classification_standard = #{classificationStandard}
              AND trade_date = #{tradeDate}
              AND is_current = 1
            FOR UPDATE
            """)
    AiIndustryDailyBar selectCurrentForUpdate(
            @Param("industryCode") String industryCode,
            @Param("classificationStandard") String classificationStandard,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT * FROM ai_industry_daily_bar
            WHERE industry_code = #{industryCode}
              AND classification_standard = #{classificationStandard}
              AND trade_date = #{tradeDate}
              AND is_current = 1
            LIMIT 1
            """)
    AiIndustryDailyBar selectCurrent(
            @Param("industryCode") String industryCode,
            @Param("classificationStandard") String classificationStandard,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Update("UPDATE ai_industry_daily_bar SET is_current = 0 WHERE id = #{id} AND is_current = 1")
    int markSuperseded(@Param("id") Long id);

    @Select("""
            <script>
            SELECT * FROM ai_industry_daily_bar FORCE INDEX (idx_industry_bar_series)
            WHERE is_current = 1
              AND quality_status = 'READY'
              AND classification_standard = #{classificationStandard}
              AND industry_code IN
              <foreach collection="industryCodes" item="industryCode" open="(" separator="," close=")">
                #{industryCode}
              </foreach>
              AND trade_date BETWEEN #{startDate} AND #{endDate}
              AND observed_at &lt;= #{asOfTime}
            ORDER BY industry_code, trade_date
            </script>
            """)
    List<AiIndustryDailyBar> selectCurrentSeries(
            @Param("industryCodes") List<String> industryCodes,
            @Param("classificationStandard") String classificationStandard,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );
}
