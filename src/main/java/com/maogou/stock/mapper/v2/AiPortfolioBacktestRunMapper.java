package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiPortfolioBacktestRun;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiPortfolioBacktestRunMapper extends BaseMapper<AiPortfolioBacktestRun> {

    @Insert("""
            INSERT INTO ai_portfolio_backtest_run (
                user_id, training_dataset_id, walk_forward_run_id, strategy_release_id,
                model_version_id, run_key, engine_version, config_fingerprint, input_fingerprint,
                random_seed, start_trade_date, end_trade_date, horizon_days, top_k,
                rebalance_frequency, initial_capital, final_nav, benchmark_final_nav, total_return,
                benchmark_return, alpha, annualized_return, sharpe_ratio, calmar_ratio,
                max_drawdown, turnover_rate, trade_count, metrics_json, status, started_at,
                completed_at, created_at
            ) VALUES (
                #{item.userId}, #{item.trainingDatasetId}, #{item.walkForwardRunId},
                #{item.strategyReleaseId}, #{item.modelVersionId}, #{item.runKey},
                #{item.engineVersion}, #{item.configFingerprint}, #{item.inputFingerprint},
                #{item.randomSeed}, #{item.startTradeDate}, #{item.endTradeDate},
                #{item.horizonDays}, #{item.topK}, #{item.rebalanceFrequency},
                #{item.initialCapital}, #{item.finalNav}, #{item.benchmarkFinalNav},
                #{item.totalReturn}, #{item.benchmarkReturn}, #{item.alpha},
                #{item.annualizedReturn}, #{item.sharpeRatio}, #{item.calmarRatio},
                #{item.maxDrawdown}, #{item.turnoverRate}, #{item.tradeCount},
                #{item.metricsJson}, #{item.status}, #{item.startedAt}, #{item.completedAt},
                #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiPortfolioBacktestRun item);

    @Select("""
            SELECT * FROM ai_portfolio_backtest_run
            WHERE user_id = #{userId} AND run_key = #{runKey}
            FOR SHARE
            """)
    AiPortfolioBacktestRun selectByRunKeyForShare(
            @Param("userId") Long userId,
            @Param("runKey") String runKey
    );
}
