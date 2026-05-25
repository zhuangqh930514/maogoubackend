package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_chat_message")
public class AiChatMessage {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long sessionId;
    public Long userId;
    public String messageRole;
    public String content;
    public String modelName;
    public String status;
    public String errorMessage;
    public LocalDateTime createdAt;
}
