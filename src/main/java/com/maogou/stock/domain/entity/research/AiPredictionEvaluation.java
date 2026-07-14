package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("ai_prediction_evaluation")
public class AiPredictionEvaluation {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long predictionId;
    public Long sampleLabelId;
    public String evaluationVersion;
    public Integer directionCorrect;
    public Integer actionEffective;
    public BigDecimal probabilityError;
    public BigDecimal predictedReturnError;
    public BigDecimal netReturn;
    public BigDecimal excessReturn;
    public BigDecimal evaluationScore;
    public String evaluationStatus;
    public String evidenceJson;
    public LocalDateTime evaluatedAt;
    public LocalDateTime createdAt;
}
