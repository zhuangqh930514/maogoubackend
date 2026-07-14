package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_factor_value")
public class AiFactorValue {
    @TableId(type = IdType.AUTO)
    public Long id;
    @TableField(exist = false)
    public Long userId;
    public Long sampleId;
    public Long factorDefinitionId;
    @TableField(exist = false)
    public String stockCode;
    @TableField(exist = false)
    public String factorCode;
    @TableField(exist = false)
    public String factorName;
    @TableField(exist = false)
    public String factorVersion;
    @TableField(exist = false)
    public String factorGroup;
    @TableField(exist = false)
    public String direction;
    public BigDecimal rawValue;
    public BigDecimal normalizedValue;
    public Integer hit;
    public Integer missing;
    public String missingReason;
    @TableField(exist = false)
    public String evidence;
    public String evidenceJson;
    public String inputFingerprint;
    public LocalDateTime calculatedAt;
    public LocalDateTime createdAt;
    @TableField(exist = false)
    public String crossSectionKey;
    @TableField(exist = false)
    public String sourceEvidenceFingerprint;
}
