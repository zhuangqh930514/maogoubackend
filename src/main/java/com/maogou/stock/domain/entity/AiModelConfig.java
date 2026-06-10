package com.maogou.stock.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_model_config")
public class AiModelConfig {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String apiBaseUrl;
    public String modelName;
    public String apiKey;
    public Integer timeoutMs;
    public BigDecimal temperature;
    public Integer maxTokens;
    public Integer intradayIntervalMinutes;
    public String closeAnalysisTime;
    public String analysisScope;
    public String promptTemplate;
    public Integer autoClosePipelineEnabled;
    public LocalDateTime autoClosePipelineLastRunAt;
    public LocalDateTime autoClosePipelineLastFinishedAt;
    public String autoClosePipelineLastStatus;
    public String autoClosePipelineLastMessage;
    @TableLogic
    public Integer deleted;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
