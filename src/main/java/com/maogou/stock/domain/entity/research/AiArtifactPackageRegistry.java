package com.maogou.stock.domain.entity.research;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("ai_artifact_package_registry")
public class AiArtifactPackageRegistry {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String packageType;
    public String packageFormat;
    public String packageVersion;
    public String packageChecksum;
    public String signatureKeyId;
    public String signatureStatus;
    public String sourceSchemaVersion;
    public String sourceGitCommit;
    public String previewStatus;
    public String previewTokenHash;
    public LocalDateTime previewExpiresAt;
    public String importStatus;
    public Long importedBy;
    public LocalDateTime importedAt;
    public String manifestJson;
    public String validationJson;
    public String errorMessage;
    public LocalDateTime createdAt;
    public LocalDateTime updatedAt;
}
