package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiTradingCalendar;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface AiTradingCalendarMapper extends BaseMapper<AiTradingCalendar> {

    @Insert("""
            INSERT INTO ai_trading_calendar (
                market_code, trade_date, calendar_version, is_trade_day,
                session_open_time, session_close_time, previous_trade_date, next_trade_date,
                source_name, source_as_of, source_fingerprint, created_at
            ) VALUES (
                #{item.marketCode}, #{item.tradeDate}, #{item.calendarVersion}, #{item.isTradeDay},
                #{item.sessionOpenTime}, #{item.sessionCloseTime}, #{item.previousTradeDate},
                #{item.nextTradeDate}, #{item.sourceName}, #{item.sourceAsOf},
                #{item.sourceFingerprint}, #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiTradingCalendar item);

    @Select("""
            <script>
            SELECT * FROM ai_trading_calendar
            WHERE market_code = #{marketCode}
              AND calendar_version = #{calendarVersion}
              AND trade_date IN
              <foreach collection="dates" item="date" open="(" separator="," close=")">
                  #{date}
              </foreach>
            ORDER BY trade_date
            </script>
            """)
    List<AiTradingCalendar> selectByDates(
            @Param("marketCode") String marketCode,
            @Param("calendarVersion") String calendarVersion,
            @Param("dates") List<LocalDate> dates
    );

    @Select("""
            SELECT * FROM ai_trading_calendar
            WHERE market_code = 'CN_A_SHARE'
              AND calendar_version = #{calendarVersion}
              AND is_trade_day = 1
              AND trade_date <= #{endDate}
              AND source_fingerprint IS NOT NULL
            ORDER BY trade_date DESC
            LIMIT #{limit}
            """)
    List<AiTradingCalendar> selectRecentTradingDays(
            @Param("endDate") LocalDate endDate,
            @Param("calendarVersion") String calendarVersion,
            @Param("limit") int limit
    );
}
