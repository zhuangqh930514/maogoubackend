package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_learning_job_log")
public class AiLearningJobLog {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String jobName;
    public String jobType;
    public String status;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public Integer processedCount;
    public Integer successCount;
    public Integer failedCount;
    public String errorMessage;
    public LocalDateTime createdAt;
}
