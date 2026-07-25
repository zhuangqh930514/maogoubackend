package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiDailyDecisionPlan;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiDailyDecisionPlanMapper extends BaseMapper<AiDailyDecisionPlan> {

    @Select("""
            <script>
            SELECT * FROM ai_daily_decision_plan
            WHERE user_id = #{userId}
              AND decision_item_id IN
              <foreach collection="decisionItemIds" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            ORDER BY decision_item_id, horizon_days
            </script>
            """)
    List<AiDailyDecisionPlan> selectByDecisionItemIds(Long userId, List<Long> decisionItemIds);
}
