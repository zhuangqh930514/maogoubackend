package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_factor_stat")
public class AiFactorStat {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String factorCode;
    public String factorName;
    public String factorGroup;
    public String marketRegime;
    public Integer sampleCount;
    public Integer successCount;
    public BigDecimal successRate;
    public BigDecimal avgReturn;
    public BigDecimal avgDrawdown;
    public BigDecimal weightScore;
    public LocalDateTime lastEvaluatedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
