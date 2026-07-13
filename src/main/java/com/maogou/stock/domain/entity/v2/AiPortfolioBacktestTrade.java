package com.maogou.stock.domain.entity.v2;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_portfolio_backtest_trade")
public class AiPortfolioBacktestTrade {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long backtestRunId;
    public Long predictionId;
    public String tradeKey;
    public LocalDate tradeDate;
    public String stockCode;
    public String side;
    public BigDecimal orderQuantity;
    public BigDecimal filledQuantity;
    public BigDecimal executionPrice;
    public BigDecimal grossAmount;
    public BigDecimal commissionAmount;
    public BigDecimal stampDutyAmount;
    public BigDecimal transferFeeAmount;
    public BigDecimal slippageAmount;
    public BigDecimal totalCostAmount;
    public String executionStatus;
    public String rejectionReason;
    public LocalDateTime createdAt;
}
