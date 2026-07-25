package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * User experience feedback for a daily report item. This is intentionally not
 * part of the research-label or training-data domain.
 */
@TableName("ai_research_user_feedback")
public class AiResearchUserFeedback {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long reportId;
    public String stockCode;
    public String feedbackType;
    public String comment;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
