package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_model_version")
public class AiModelVersion {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long trainingDatasetId;
    public String modelKey;
    public String versionNo;
    public String modelType;
    public String algorithm;
    public String featureVersion;
    public String trainerVersion;
    public Long randomSeed;
    public String artifactUri;
    public String artifactChecksum;
    public String featureManifestUri;
    public String featureManifestChecksum;
    public LocalDate trainStartDate;
    public LocalDate trainEndDate;
    public LocalDate validationStartDate;
    public LocalDate validationEndDate;
    public LocalDate testStartDate;
    public LocalDate testEndDate;
    public String parametersJson;
    public String metricsJson;
    public String calibrationJson;
    public Integer sampleCount;
    public String status;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
