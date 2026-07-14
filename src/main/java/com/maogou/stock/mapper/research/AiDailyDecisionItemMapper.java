package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiDailyDecisionItemMapper extends BaseMapper<AiDailyDecisionItem> {

    @Select("""
            SELECT * FROM ai_daily_decision_item
            WHERE user_id = #{userId} AND decision_snapshot_id = #{snapshotId}
            ORDER BY CASE category
                WHEN 'RECOMMEND' THEN 1 WHEN 'CAUTIOUS' THEN 2
                WHEN 'HOLDING_RISK' THEN 3 WHEN 'AVOID' THEN 4 ELSE 5 END,
                system_score DESC, stock_code
            """)
    List<AiDailyDecisionItem> selectBySnapshot(
            @Param("userId") Long userId,
            @Param("snapshotId") Long snapshotId
    );
}
