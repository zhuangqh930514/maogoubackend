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
    public Long userId;
    public Long sampleId;
    public String stockCode;
    public String factorCode;
    public String factorVersion;
    public String factorGroup;
    public String direction;
    public BigDecimal rawValue;
    public BigDecimal normalizedValue;
    public Integer hit;
    public Integer missing;
    public String missingReason;
    public String evidence;
    public String inputFingerprint;
    public LocalDateTime calculatedAt;
    public LocalDateTime createdAt;
    @TableField(exist = false)
    public String crossSectionKey;
    @TableField(exist = false)
    public String sourceEvidenceFingerprint;
}
