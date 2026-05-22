package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.maogou.stock.domain.enums.TradeSide;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("trade_record")
public class TradeRecord {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String stockCode;
    public String stockName;
    public TradeSide side;
    public BigDecimal price;
    public Integer quantity;
    public BigDecimal fee;
    public LocalDateTime tradedAt;
    @TableLogic
    public Integer deleted;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
