package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_training_dataset")
public class AiTrainingDataset {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public String datasetKey;
    public String versionNo;
    public String purpose;
    public String featureVersion;
    public String labelVersion;
    public String calendarVersion;
    public LocalDateTime asOfTime;
    public LocalDate trainStartDate;
    public LocalDate trainEndDate;
    public LocalDate validationStartDate;
    public LocalDate validationEndDate;
    public LocalDate testStartDate;
    public LocalDate testEndDate;
    public Integer maxHorizonDays;
    public String sourceQueryJson;
    public String selectionPolicyJson;
    public String lineageFingerprint;
    public String artifactUri;
    public String artifactChecksum;
    public Integer rowCount;
    public String status;
    public LocalDateTime finalizedAt;
    public LocalDateTime createdAt;
}
