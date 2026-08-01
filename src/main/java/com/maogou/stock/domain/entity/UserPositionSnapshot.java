package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("user_position_snapshot")
public class UserPositionSnapshot {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String stockCode;
    public String stockName;
    public Integer quantity;
    public BigDecimal averageCost;
    public BigDecimal totalCost;
    public BigDecimal realizedPnl;
    public BigDecimal currentPrice;
    public BigDecimal marketValue;
    public BigDecimal unrealizedPnl;
    public BigDecimal todayPnl;
    public BigDecimal todayPnlRate;
    public String quoteStatus;
    public String quoteSource;
    public LocalDateTime quoteAsOf;
    public String calculationStatus;
    public String unavailableReason;
    public LocalDateTime updatedAt;
}
