package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiArtifactPackageRegistry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiArtifactPackageRegistryMapper extends BaseMapper<AiArtifactPackageRegistry> {

    @Select("""
            SELECT * FROM ai_artifact_package_registry
            WHERE package_checksum = #{checksum}
            LIMIT 1
            """)
    AiArtifactPackageRegistry selectByChecksum(@Param("checksum") String checksum);

    @Insert("""
            INSERT INTO ai_artifact_package_registry (
                package_type, package_format, package_version, package_checksum,
                signature_key_id, signature_status, source_schema_version, source_git_commit,
                preview_status, preview_token_hash, preview_expires_at, import_status,
                imported_by, imported_at, manifest_json, validation_json, error_message,
                created_at, updated_at
            ) VALUES (
                #{item.packageType}, #{item.packageFormat}, #{item.packageVersion},
                #{item.packageChecksum}, #{item.signatureKeyId}, #{item.signatureStatus},
                #{item.sourceSchemaVersion}, #{item.sourceGitCommit}, #{item.previewStatus},
                #{item.previewTokenHash}, #{item.previewExpiresAt}, #{item.importStatus},
                #{item.importedBy}, #{item.importedAt}, #{item.manifestJson},
                #{item.validationJson}, #{item.errorMessage}, #{item.createdAt}, #{item.updatedAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiArtifactPackageRegistry item);
}
