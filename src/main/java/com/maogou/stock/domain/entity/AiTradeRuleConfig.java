package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_trade_rule_config")
public class AiTradeRuleConfig {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long strategyReleaseId;
    public String versionNo;
    public String name;
    public String status;
    public String configJson;
    public String seedVersion;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
