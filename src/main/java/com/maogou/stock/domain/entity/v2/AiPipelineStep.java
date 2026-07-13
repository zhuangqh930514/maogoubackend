package com.maogou.stock.domain.entity.v2;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_pipeline_step")
public class AiPipelineStep {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long pipelineRunId;
    public String stepKey;
    public Integer stepOrder;
    public String status;
    public Integer retryCount;
    public Integer inputCount;
    public Integer outputCount;
    public String checkpointJson;
    public String outputFingerprint;
    public String errorMessage;
    public LocalDateTime startedAt;
    public LocalDateTime finishedAt;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
