package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface AiResearchUniverseItemMapper extends BaseMapper<AiResearchUniverseItem> {

    @Select("""
            SELECT item.*
            FROM ai_research_universe_item item
            INNER JOIN ai_research_universe_snapshot snapshot
                ON snapshot.id = item.universe_snapshot_id
            WHERE item.stock_code = #{stockCode}
              AND item.industry_code IS NOT NULL
              AND item.industry_code <> ''
              AND item.effective_from <= #{asOfDate}
              AND (item.effective_to IS NULL OR item.effective_to >= #{asOfDate})
              AND snapshot.trade_date = #{asOfDate}
              AND snapshot.status = 'FINALIZED'
              AND snapshot.quality_status = 'READY'
              AND snapshot.point_in_time_status = 'READY'
            ORDER BY snapshot.source_observed_at DESC, item.id DESC
            LIMIT 1
            """)
    AiResearchUniverseItem selectIndustryMembershipAt(
            @Param("stockCode") String stockCode,
            @Param("asOfDate") LocalDate asOfDate
    );

    @Select("""
            <script>
            SELECT item.*, sample.id AS sample_id
            FROM ai_sample sample
            INNER JOIN ai_research_universe_snapshot snapshot
              ON snapshot.trade_date = sample.trade_date
             AND snapshot.status = 'FINALIZED'
             AND snapshot.quality_status = 'READY'
             AND snapshot.point_in_time_status = 'READY'
            INNER JOIN ai_research_universe_item item
              ON item.universe_snapshot_id = snapshot.id
             AND item.stock_code = sample.stock_code
             AND item.included = 1
             AND item.listed_status = 'LISTED'
             AND item.industry_code IS NOT NULL
             AND item.industry_code &lt;&gt; ''
             AND item.industry_standard IS NOT NULL
             AND item.industry_standard &lt;&gt; ''
            WHERE sample.id IN
            <foreach collection="sampleIds" item="sampleId" open="(" separator="," close=")">
              #{sampleId}
            </foreach>
              AND snapshot.source_observed_at &lt;= #{asOfTime}
            ORDER BY sample.id, snapshot.source_observed_at DESC, snapshot.id DESC
            </script>
            """)
    List<AiResearchUniverseItem> selectReadyIndustryMembershipsForSamples(
            @Param("sampleIds") List<Long> sampleIds,
            @Param("asOfTime") java.time.LocalDateTime asOfTime
    );
}
