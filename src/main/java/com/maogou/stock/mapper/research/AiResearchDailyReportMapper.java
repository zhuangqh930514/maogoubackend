package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiResearchDailyReport;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AiResearchDailyReportMapper extends BaseMapper<AiResearchDailyReport> {

    @Select("""
            SELECT id FROM user_account WHERE id = #{userId} FOR UPDATE
            """)
    Long lockUser(@Param("userId") Long userId);

    @Select("""
            SELECT * FROM ai_research_daily_report
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}
            LIMIT 1
            """)
    AiResearchDailyReport selectByIdempotencyForShare(
            @Param("userId") Long userId,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Select("""
            SELECT * FROM ai_research_daily_report
            WHERE user_id = #{userId} AND trade_date = #{tradeDate} AND is_current = 1
            LIMIT 1
            FOR UPDATE
            """)
    AiResearchDailyReport selectCurrentForUpdate(
            @Param("userId") Long userId,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT COALESCE(MAX(report_version), 0)
            FROM ai_research_daily_report
            WHERE user_id = #{userId} AND trade_date = #{tradeDate}
            """)
    Integer selectMaxVersionForUpdate(
            @Param("userId") Long userId,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT * FROM ai_research_daily_report
            WHERE user_id = #{userId}
              AND is_current = 1
              AND trade_date <= #{maxTradeDate}
            ORDER BY trade_date DESC
            LIMIT 1
            """)
    AiResearchDailyReport selectLatestCurrent(
            @Param("userId") Long userId,
            @Param("maxTradeDate") LocalDate maxTradeDate
    );

    @Select("""
            SELECT * FROM ai_research_daily_report
            WHERE user_id = #{userId}
            ORDER BY trade_date DESC, report_version DESC
            LIMIT #{limit}
            """)
    List<AiResearchDailyReport> selectRecent(
            @Param("userId") Long userId,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE ai_research_daily_report
            SET is_current = 0, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int retireCurrent(@Param("id") Long id, @Param("updatedAt") LocalDateTime updatedAt);
}
