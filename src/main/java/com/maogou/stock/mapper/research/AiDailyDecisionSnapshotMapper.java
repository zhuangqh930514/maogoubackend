package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiDailyDecisionSnapshot;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;

public interface AiDailyDecisionSnapshotMapper extends BaseMapper<AiDailyDecisionSnapshot> {

    @Select("SELECT id FROM user_account WHERE id = #{userId} FOR UPDATE")
    Long lockUser(@Param("userId") Long userId);

    @Select("""
            SELECT * FROM ai_daily_decision_snapshot
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}
            LIMIT 1
            """)
    AiDailyDecisionSnapshot selectByIdempotencyForShare(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Select("""
            SELECT * FROM ai_daily_decision_snapshot
            WHERE user_id = #{userId} AND trade_date = #{tradeDate} AND is_current = 1
            LIMIT 1 FOR UPDATE
            """)
    AiDailyDecisionSnapshot selectCurrentForUpdate(
            @Param("userId") Long userId,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT COALESCE(MAX(snapshot_version), 0)
            FROM ai_daily_decision_snapshot
            WHERE user_id = #{userId} AND trade_date = #{tradeDate}
            """)
    Integer selectMaxVersionForUpdate(
            @Param("userId") Long userId,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT * FROM ai_daily_decision_snapshot
            WHERE user_id = #{userId} AND trade_date = #{tradeDate} AND is_current = 1
            LIMIT 1
            """)
    AiDailyDecisionSnapshot selectCurrent(
            @Param("userId") Long userId,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Update("""
            UPDATE ai_daily_decision_snapshot
            SET is_current = 0, updated_at = #{updatedAt}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int retireCurrent(
            @Param("id") Long id,
            @Param("userId") Long userId,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
