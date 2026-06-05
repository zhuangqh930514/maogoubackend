package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_stock_universe")
public class AiStockUniverse {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String universeCode;
    public String universeName;
    public String sourceType;
    public String filtersJson;
    public Integer active;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
