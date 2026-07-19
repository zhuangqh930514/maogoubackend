package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_training_dataset_item")
public class AiTrainingDatasetItem {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long trainingDatasetId;
    public Long sampleId;
    public Long sampleLabelId;
    public String splitType;
    public Integer sequenceNo;
    public LocalDateTime sampleAsOfTime;
    public LocalDateTime labelAvailableAt;
    public String featureFingerprint;
    public String labelFingerprint;
    public String universeFingerprint;
    public String tradingStateFingerprint;
    public String sectorMembershipFingerprint;
    public LocalDateTime includedAt;
    public LocalDateTime createdAt;
}
