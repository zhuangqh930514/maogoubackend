package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_analysis_report_prediction")
public class AiAnalysisReportPrediction {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long reportId;
    public Long predictionId;
    public String purpose;
    public BigDecimal weight;
    public LocalDateTime createdAt;
}
