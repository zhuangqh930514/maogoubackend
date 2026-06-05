package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_factor_definition")
public class AiFactorDefinition {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String factorCode;
    public String factorName;
    public String factorGroup;
    public String direction;
    public String formulaDesc;
    public String requiredFieldsJson;
    public BigDecimal defaultWeight;
    public Integer enabled;
    public String versionNo;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
