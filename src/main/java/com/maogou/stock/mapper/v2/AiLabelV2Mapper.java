package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiLabelV2;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiLabelV2Mapper extends BaseMapper<AiLabelV2> {

    @Insert("""
            <script>
            INSERT INTO ai_label_v2 (
                user_id, prediction_id, sample_id, entry_calendar_id, exit_calendar_id, stock_code,
                horizon_days, label_version, calendar_version, input_fingerprint, entry_trade_date,
                exit_trade_date, entry_price, exit_price, gross_return, net_return, benchmark_return,
                sector_return, excess_return, max_favorable_return, max_adverse_return, execution_status,
                execution_reason, action_evaluation, hit_direction, hit_target, hit_stop_loss, label_score,
                label_status, matured_at, verified_at, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.userId}, #{item.predictionId}, #{item.sampleId}, #{item.entryCalendarId},
                    #{item.exitCalendarId}, #{item.stockCode}, #{item.horizonDays}, #{item.labelVersion},
                    #{item.calendarVersion}, #{item.inputFingerprint}, #{item.entryTradeDate},
                    #{item.exitTradeDate}, #{item.entryPrice}, #{item.exitPrice}, #{item.grossReturn},
                    #{item.netReturn}, #{item.benchmarkReturn}, #{item.sectorReturn}, #{item.excessReturn},
                    #{item.maxFavorableReturn}, #{item.maxAdverseReturn}, #{item.executionStatus},
                    #{item.executionReason}, #{item.actionEvaluation}, #{item.hitDirection},
                    #{item.hitTarget}, #{item.hitStopLoss}, #{item.labelScore}, #{item.labelStatus},
                    #{item.maturedAt}, #{item.verifiedAt}, #{item.createdAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiLabelV2> items);

    @Select("""
            <script>
            SELECT * FROM ai_label_v2
            WHERE user_id = #{userId}
              AND label_version = #{labelVersion}
              AND prediction_id IN
              <foreach collection="predictionIds" item="predictionId" open="(" separator="," close=")">
                  #{predictionId}
              </foreach>
            FOR SHARE
            </script>
            """)
    List<AiLabelV2> selectByPredictionIdsForShare(
            @Param("userId") Long userId,
            @Param("predictionIds") List<Long> predictionIds,
            @Param("labelVersion") String labelVersion
    );
}
