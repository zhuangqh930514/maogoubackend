package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_decision_policy_release")
public class AiDecisionPolicyRelease {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String policyKey;
    public String versionNo;
    public String status;
    public String configJson;
    public String codeChecksum;
    public LocalDateTime shadowStartedAt;
    public LocalDateTime activatedAt;
    public LocalDateTime retiredAt;
    public LocalDateTime createdAt;
}
