package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiPortfolioBacktestPosition;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiPortfolioBacktestPositionMapper extends BaseMapper<AiPortfolioBacktestPosition> {
    @Insert("""
            <script>
            INSERT INTO ai_portfolio_backtest_position (
                backtest_run_id, trade_date, stock_code, quantity, average_cost, close_price,
                market_value, weight, unrealized_pnl, daily_pnl, return_contribution,
                tradable_status, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (#{item.backtestRunId}, #{item.tradeDate}, #{item.stockCode}, #{item.quantity},
                 #{item.averageCost}, #{item.closePrice}, #{item.marketValue}, #{item.weight},
                 #{item.unrealizedPnl}, #{item.dailyPnl}, #{item.returnContribution},
                 #{item.tradableStatus}, #{item.createdAt})
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiPortfolioBacktestPosition> items);

    @Select("SELECT * FROM ai_portfolio_backtest_position WHERE backtest_run_id = #{runId} ORDER BY trade_date, stock_code FOR SHARE")
    List<AiPortfolioBacktestPosition> selectByRunIdForShare(@Param("runId") Long runId);
}
