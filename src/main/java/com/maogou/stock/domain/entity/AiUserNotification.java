package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_user_notification")
public class AiUserNotification {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String notificationType;
    public String dedupeKey;
    public String level;
    public String title;
    public String content;
    public Long reportId;
    public LocalDate tradeDate;
    public Integer isRead;
    public LocalDateTime readAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
