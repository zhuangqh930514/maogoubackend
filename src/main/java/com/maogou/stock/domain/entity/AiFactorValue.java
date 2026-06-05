package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_factor_value")
public class AiFactorValue {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long sampleId;
    public String stockCode;
    public String factorCode;
    public BigDecimal factorValue;
    public BigDecimal normalizedValue;
    public Integer hit;
    public String direction;
    public String evidence;
    public LocalDateTime calculatedAt;
}
