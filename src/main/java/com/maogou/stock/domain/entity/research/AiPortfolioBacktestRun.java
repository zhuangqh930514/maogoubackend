package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_portfolio_backtest_run")
public class AiPortfolioBacktestRun {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long trainingDatasetId;
    public Long walkForwardRunId;
    public Long strategyReleaseId;
    public Long modelVersionId;
    public String runKey;
    public String engineVersion;
    public String configFingerprint;
    public String inputFingerprint;
    public Long randomSeed;
    public LocalDate startTradeDate;
    public LocalDate endTradeDate;
    public Integer horizonDays;
    public Integer topK;
    public String rebalanceFrequency;
    public BigDecimal initialCapital;
    public BigDecimal finalNav;
    public BigDecimal benchmarkFinalNav;
    public BigDecimal totalReturn;
    public BigDecimal benchmarkReturn;
    public BigDecimal alpha;
    public BigDecimal annualizedReturn;
    public BigDecimal sharpeRatio;
    public BigDecimal calmarRatio;
    public BigDecimal maxDrawdown;
    public BigDecimal turnoverRate;
    public Integer tradeCount;
    public String metricsJson;
    public String status;
    public LocalDateTime startedAt;
    public LocalDateTime completedAt;
    public LocalDateTime createdAt;
}
