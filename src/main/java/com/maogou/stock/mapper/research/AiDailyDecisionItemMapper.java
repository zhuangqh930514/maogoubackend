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

    @Select("""
            <script>
            SELECT item.*
            FROM ai_daily_decision_item item
            INNER JOIN ai_daily_decision_snapshot snapshot
                    ON snapshot.id = item.decision_snapshot_id
                   AND snapshot.user_id = item.user_id
                   AND snapshot.is_current = 1
            WHERE item.user_id = #{userId}
              AND item.report_id IN
              <foreach collection="reportIds" item="reportId" open="(" separator="," close=")">
                #{reportId}
              </foreach>
            ORDER BY snapshot.trade_date DESC, snapshot.snapshot_version DESC, item.id DESC
            </script>
            """)
    List<AiDailyDecisionItem> selectCurrentByReportIds(
            @Param("userId") Long userId,
            @Param("reportIds") List<Long> reportIds
    );

    @Select("""
            SELECT item.*
            FROM ai_daily_decision_item item
            INNER JOIN ai_daily_decision_snapshot snapshot
                    ON snapshot.id = item.decision_snapshot_id
                   AND snapshot.user_id = item.user_id
                   AND snapshot.is_current = 1
            WHERE item.user_id = #{userId}
              AND item.stock_code = #{stockCode}
              AND item.trade_date = #{tradeDate}
            LIMIT 1
            """)
    AiDailyDecisionItem selectCurrentByStockAndTradeDate(
            @Param("userId") Long userId,
            @Param("stockCode") String stockCode,
            @Param("tradeDate") java.time.LocalDate tradeDate
    );
}
