package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_walk_forward_fold")
public class AiWalkForwardFold {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long walkForwardRunId;
    public Integer foldNo;
    public LocalDate trainStartDate;
    public LocalDate trainEndDate;
    public LocalDate validationStartDate;
    public LocalDate validationEndDate;
    public LocalDate testStartDate;
    public LocalDate testEndDate;
    public Integer trainSampleCount;
    public Integer validationSampleCount;
    public Integer testSampleCount;
    public String metricsJson;
    public String status;
    public LocalDateTime createdAt;
}
