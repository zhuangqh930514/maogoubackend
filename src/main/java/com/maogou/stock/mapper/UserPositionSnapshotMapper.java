package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.UserPositionSnapshot;
import com.maogou.stock.dto.portfolio.PositionSnapshotSummary;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface UserPositionSnapshotMapper extends BaseMapper<UserPositionSnapshot> {

    @Select("""
            SELECT * FROM user_position_snapshot
            WHERE user_id = #{userId}
            ORDER BY CASE WHEN calculation_status = 'AVAILABLE' THEN 0 ELSE 1 END,
                     updated_at DESC, stock_code ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<UserPositionSnapshot> selectPage(
            @Param("userId") Long userId,
            @Param("limit") int limit,
            @Param("offset") long offset
    );

    @Select("SELECT COUNT(*) FROM user_position_snapshot WHERE user_id = #{userId}")
    long countByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT COALESCE(SUM(total_cost), 0) AS total_cost,
                   COALESCE(SUM(market_value), 0) AS total_market_value,
                   COALESCE(SUM(unrealized_pnl), 0) AS total_unrealized_pnl,
                   COALESCE(SUM(today_pnl), 0) AS total_today_pnl,
                   COUNT(*) AS position_total,
                   SUM(CASE WHEN calculation_status = 'AVAILABLE' THEN 1 ELSE 0 END) AS priced_position_count,
                   SUM(CASE WHEN calculation_status = 'AVAILABLE' THEN 0 ELSE 1 END) AS unpriced_position_count
            FROM user_position_snapshot
            WHERE user_id = #{userId}
            """)
    PositionSnapshotSummary selectSummary(@Param("userId") Long userId);

    @Select("""
            SELECT * FROM user_position_snapshot
            WHERE user_id = #{userId} AND stock_code = #{stockCode}
            LIMIT 1
            """)
    UserPositionSnapshot selectByUserAndStock(
            @Param("userId") Long userId,
            @Param("stockCode") String stockCode
    );

    @Insert("""
            INSERT INTO user_position_snapshot (
                user_id, stock_code, stock_name, quantity, average_cost, total_cost, realized_pnl,
                current_price, market_value, unrealized_pnl, today_pnl, today_pnl_rate,
                quote_status, quote_source, quote_as_of, calculation_status, unavailable_reason, updated_at
            ) VALUES (
                #{item.userId}, #{item.stockCode}, #{item.stockName}, #{item.quantity}, #{item.averageCost},
                #{item.totalCost}, #{item.realizedPnl}, #{item.currentPrice}, #{item.marketValue},
                #{item.unrealizedPnl}, #{item.todayPnl}, #{item.todayPnlRate}, #{item.quoteStatus},
                #{item.quoteSource}, #{item.quoteAsOf}, #{item.calculationStatus}, #{item.unavailableReason},
                #{item.updatedAt}
            ) ON DUPLICATE KEY UPDATE
                stock_name = VALUES(stock_name), quantity = VALUES(quantity), average_cost = VALUES(average_cost),
                total_cost = VALUES(total_cost), realized_pnl = VALUES(realized_pnl),
                current_price = VALUES(current_price), market_value = VALUES(market_value),
                unrealized_pnl = VALUES(unrealized_pnl), today_pnl = VALUES(today_pnl),
                today_pnl_rate = VALUES(today_pnl_rate), quote_status = VALUES(quote_status),
                quote_source = VALUES(quote_source), quote_as_of = VALUES(quote_as_of),
                calculation_status = VALUES(calculation_status), unavailable_reason = VALUES(unavailable_reason),
                updated_at = VALUES(updated_at)
            """)
    int upsert(@Param("item") UserPositionSnapshot item);

    @Delete("DELETE FROM user_position_snapshot WHERE user_id = #{userId} AND stock_code = #{stockCode}")
    int deleteByUserAndStock(@Param("userId") Long userId, @Param("stockCode") String stockCode);
}
