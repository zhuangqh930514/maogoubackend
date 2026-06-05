package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_backtest_run")
public class AiBacktestRun {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long strategyVersionId;
    public Long experimentId;
    public String title;
    public String universeCode;
    public Integer horizonDays;
    public Integer topK;
    public LocalDate startDate;
    public LocalDate endDate;
    public BigDecimal totalReturn;
    public BigDecimal winRate;
    public BigDecimal avgReturn;
    public BigDecimal maxDrawdown;
    public BigDecimal benchmarkReturn;
    public Integer tradeCount;
    public String metricsJson;
    public String equityCurveJson;
    public String status;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
