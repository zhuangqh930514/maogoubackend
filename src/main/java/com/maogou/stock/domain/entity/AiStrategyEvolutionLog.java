package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_strategy_evolution_log")
public class AiStrategyEvolutionLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long strategyVersionId;
    public String actionType;
    public String actionSummary;
    public String beforeSnapshot;
    public String afterSnapshot;
    public LocalDateTime createdAt;
}
