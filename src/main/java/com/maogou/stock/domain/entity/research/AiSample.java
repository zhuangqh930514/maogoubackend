package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_sample")
public class AiSample {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long userId;
    public Long dataBatchId;
    public String stockCode;
    public String stockName;
    public LocalDate tradeDate;
    public String samplePhase;
    public LocalDateTime asOfTime;
    public String universeCode;
    public String universeVersion;
    public String marketRegime;
    public String sectorCode;
    public String sectorName;
    public BigDecimal dataQualityScore;
    public String qualityStatus;
    public String tradableStatus;
    public String excludeReason;
    public String featureVersion;
    public String featureSnapshot;
    public String sourceFingerprint;
    public LocalDateTime createdAt;
}
