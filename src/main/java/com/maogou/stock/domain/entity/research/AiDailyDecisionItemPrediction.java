package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_daily_decision_item_prediction")
public class AiDailyDecisionItemPrediction {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long decisionItemId;
    public Long predictionId;
    public String purpose;
    public BigDecimal weight;
    public LocalDateTime createdAt;
}
