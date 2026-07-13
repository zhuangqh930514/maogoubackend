package com.maogou.stock.domain.entity.v2;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_portfolio_backtest_position")
public class AiPortfolioBacktestPosition {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long backtestRunId;
    public LocalDate tradeDate;
    public String stockCode;
    public BigDecimal quantity;
    public BigDecimal averageCost;
    public BigDecimal closePrice;
    public BigDecimal marketValue;
    public BigDecimal weight;
    public BigDecimal unrealizedPnl;
    public BigDecimal dailyPnl;
    public BigDecimal returnContribution;
    public String tradableStatus;
    public LocalDateTime createdAt;
}
