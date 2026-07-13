package com.maogou.stock.domain.entity.v2;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_label_cost_evidence")
public class AiLabelCostEvidence {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long labelId;
    public String costModelVersion;
    public String currency;
    public BigDecimal quantity;
    public BigDecimal entryNotional;
    public BigDecimal exitNotional;
    public BigDecimal buyCommissionRate;
    public BigDecimal sellCommissionRate;
    public BigDecimal stampDutyRate;
    public BigDecimal transferFeeRate;
    public BigDecimal slippageBps;
    public BigDecimal buyCommissionAmount;
    public BigDecimal sellCommissionAmount;
    public BigDecimal stampDutyAmount;
    public BigDecimal transferFeeAmount;
    public BigDecimal slippageAmount;
    public BigDecimal totalCostAmount;
    public String evidenceJson;
    public String sourceFingerprint;
    public LocalDateTime createdAt;
}
