package com.maogou.stock.domain.entity.v2;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_portfolio_backtest_daily")
public class AiPortfolioBacktestDaily {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long backtestRunId;
    public LocalDate tradeDate;
    public BigDecimal cashBalance;
    public BigDecimal marketValue;
    public BigDecimal totalEquity;
    public BigDecimal nav;
    public BigDecimal benchmarkNav;
    public BigDecimal dailyReturn;
    public BigDecimal benchmarkReturn;
    public BigDecimal drawdown;
    public BigDecimal turnoverRate;
    public BigDecimal grossExposure;
    public BigDecimal netExposure;
    public Integer holdingCount;
    public BigDecimal transactionCost;
    public LocalDateTime createdAt;
}
