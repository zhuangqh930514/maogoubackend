package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("ai_raw_evidence_manifest")
public class AiRawEvidenceManifest {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long backfillRunId;
    public String providerCode;
    public String datasetCode;
    public String sourceRevision;
    public String objectUri;
    public Long objectSize;
    public String objectChecksum;
    public String schemaVersion;
    public Long rowCount;
    public LocalDate rangeStartDate;
    public LocalDate rangeEndDate;
    public LocalDateTime observedAt;
    public String status;
    public String manifestJson;
    public LocalDateTime createdAt;
}
