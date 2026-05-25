package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_user_memory")
public class AiUserMemory {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String memorySummary;
    public LocalDateTime lastInteractionAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
