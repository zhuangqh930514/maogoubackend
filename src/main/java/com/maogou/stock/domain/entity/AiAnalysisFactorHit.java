package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_analysis_factor_hit")
public class AiAnalysisFactorHit {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long reportId;
    public Long outcomeId;
    public String stockCode;
    public String factorCode;
    public String factorName;
    public String factorGroup;
    public String direction;
    public BigDecimal weightScore;
    public String reason;
    public BigDecimal successScore;
    public BigDecimal pctChange;
    public String marketRegime;
    public LocalDateTime createdAt;
}
