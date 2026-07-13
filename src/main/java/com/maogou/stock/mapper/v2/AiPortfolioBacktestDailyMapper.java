package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiPortfolioBacktestDaily;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiPortfolioBacktestDailyMapper extends BaseMapper<AiPortfolioBacktestDaily> {
    @Insert("""
            <script>
            INSERT INTO ai_portfolio_backtest_daily (
                backtest_run_id, trade_date, cash_balance, market_value, total_equity, nav,
                benchmark_nav, daily_return, benchmark_return, drawdown, turnover_rate,
                gross_exposure, net_exposure, holding_count, transaction_cost, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (#{item.backtestRunId}, #{item.tradeDate}, #{item.cashBalance}, #{item.marketValue},
                 #{item.totalEquity}, #{item.nav}, #{item.benchmarkNav}, #{item.dailyReturn},
                 #{item.benchmarkReturn}, #{item.drawdown}, #{item.turnoverRate},
                 #{item.grossExposure}, #{item.netExposure}, #{item.holdingCount},
                 #{item.transactionCost}, #{item.createdAt})
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiPortfolioBacktestDaily> items);

    @Select("SELECT * FROM ai_portfolio_backtest_daily WHERE backtest_run_id = #{runId} ORDER BY trade_date FOR SHARE")
    List<AiPortfolioBacktestDaily> selectByRunIdForShare(@Param("runId") Long runId);
}
