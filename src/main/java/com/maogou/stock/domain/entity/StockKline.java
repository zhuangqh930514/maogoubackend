package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("stock_kline")
public class StockKline {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String stockCode;
    public String period;
    public LocalDate tradeDate;
    public BigDecimal openPrice;
    public BigDecimal closePrice;
    public BigDecimal lowPrice;
    public BigDecimal highPrice;
    public Long volume;
    public BigDecimal amount;
    public LocalDateTime createdAt;
}
