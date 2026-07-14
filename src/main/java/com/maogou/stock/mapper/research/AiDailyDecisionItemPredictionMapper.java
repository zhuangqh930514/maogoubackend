package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiDailyDecisionItemPrediction;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiDailyDecisionItemPredictionMapper extends BaseMapper<AiDailyDecisionItemPrediction> {

    @Select("""
            <script>
            SELECT * FROM ai_daily_decision_item_prediction
            WHERE user_id = #{userId} AND decision_item_id IN
              <foreach collection="itemIds" item="itemId" open="(" separator="," close=")">
                #{itemId}
              </foreach>
            ORDER BY decision_item_id, weight
            </script>
            """)
    List<AiDailyDecisionItemPrediction> selectByItems(
            @Param("userId") Long userId,
            @Param("itemIds") List<Long> itemIds
    );
}
