package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("market_quote_current")
public class MarketQuoteCurrent {
    @TableId
    public String symbol;
    public String name;
    public String market;
    public BigDecimal latestPrice;
    public BigDecimal changeAmount;
    public BigDecimal changePercent;
    public BigDecimal volumeRatio;
    public BigDecimal amount;
    public LocalDate tradeDate;
    public String sourceProvider;
    public LocalDateTime sourceAsOf;
    public String sourceFingerprint;
    public String sourceStatus;
    public String dataMode;
    public LocalDateTime updatedAt;
}
