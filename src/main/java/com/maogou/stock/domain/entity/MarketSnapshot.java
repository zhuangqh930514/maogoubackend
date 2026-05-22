package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("market_snapshot")
public class MarketSnapshot {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String symbol;
    public String name;
    public String market;
    public BigDecimal latestPrice;
    public BigDecimal changeAmount;
    public BigDecimal changePercent;
    public BigDecimal volumeRatio;
    public BigDecimal amount;
    public LocalDateTime quoteTime;
    public LocalDateTime createdAt;
}
