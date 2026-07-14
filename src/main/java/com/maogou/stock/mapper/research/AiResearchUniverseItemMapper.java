package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiResearchUniverseItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

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
              AND snapshot.trade_date <= #{asOfDate}
              AND snapshot.status = 'FINALIZED'
            ORDER BY snapshot.trade_date DESC, item.id DESC
            LIMIT 1
            """)
    AiResearchUniverseItem selectIndustryMembershipAt(
            @Param("stockCode") String stockCode,
            @Param("asOfDate") LocalDate asOfDate
    );
}
