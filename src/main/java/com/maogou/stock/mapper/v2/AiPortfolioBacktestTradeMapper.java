package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiPortfolioBacktestTrade;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiPortfolioBacktestTradeMapper extends BaseMapper<AiPortfolioBacktestTrade> {
    @Insert("""
            <script>
            INSERT INTO ai_portfolio_backtest_trade (
                backtest_run_id, prediction_id, trade_key, trade_date, stock_code, side,
                order_quantity, filled_quantity, execution_price, gross_amount,
                commission_amount, stamp_duty_amount, transfer_fee_amount, slippage_amount,
                total_cost_amount, execution_status, rejection_reason, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (#{item.backtestRunId}, #{item.predictionId}, #{item.tradeKey}, #{item.tradeDate},
                 #{item.stockCode}, #{item.side}, #{item.orderQuantity}, #{item.filledQuantity},
                 #{item.executionPrice}, #{item.grossAmount}, #{item.commissionAmount},
                 #{item.stampDutyAmount}, #{item.transferFeeAmount}, #{item.slippageAmount},
                 #{item.totalCostAmount}, #{item.executionStatus}, #{item.rejectionReason},
                 #{item.createdAt})
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiPortfolioBacktestTrade> items);

    @Select("SELECT * FROM ai_portfolio_backtest_trade WHERE backtest_run_id = #{runId} ORDER BY trade_date, trade_key FOR SHARE")
    List<AiPortfolioBacktestTrade> selectByRunIdForShare(@Param("runId") Long runId);
}
