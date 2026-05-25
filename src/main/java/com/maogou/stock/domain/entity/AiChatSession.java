package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_chat_session")
public class AiChatSession {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String title;
    public String modelName;
    @TableLogic
    public Integer deleted;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
