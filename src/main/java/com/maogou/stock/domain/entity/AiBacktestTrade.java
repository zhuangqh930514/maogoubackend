package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_backtest_trade")
public class AiBacktestTrade {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long backtestRunId;
    public Long predictionId;
    public String stockCode;
    public String stockName;
    public LocalDate entryDate;
    public LocalDate exitDate;
    public BigDecimal entryPrice;
    public BigDecimal exitPrice;
    public BigDecimal netReturn;
    public BigDecimal maxDrawdown;
    public Integer rankNo;
    public LocalDateTime createdAt;
}
