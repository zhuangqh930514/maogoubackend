package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_strategy_version")
public class AiStrategyVersion {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String versionNo;
    public String title;
    public String strategySummary;
    public String factorSnapshot;
    public String promptTemplate;
    public BigDecimal avgSuccessRate;
    public BigDecimal avgReturn;
    public BigDecimal maxDrawdown;
    public Integer sampleCount;
    public Integer active;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
