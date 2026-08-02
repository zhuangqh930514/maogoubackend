package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiRawEvidenceManifest;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiRawEvidenceManifestMapper extends BaseMapper<AiRawEvidenceManifest> {

    @Select("""
            SELECT * FROM ai_raw_evidence_manifest
            WHERE provider_code = #{providerCode}
              AND dataset_code = #{datasetCode}
              AND source_revision = #{sourceRevision}
              AND object_checksum = #{objectChecksum}
            LIMIT 1
            """)
    AiRawEvidenceManifest selectByIdentity(
            @Param("providerCode") String providerCode,
            @Param("datasetCode") String datasetCode,
            @Param("sourceRevision") String sourceRevision,
            @Param("objectChecksum") String objectChecksum
    );

    @Select("""
            SELECT * FROM ai_raw_evidence_manifest
            WHERE backfill_run_id = #{runId}
              AND (#{datasetCode} IS NULL OR dataset_code = #{datasetCode})
            ORDER BY range_start_date, id
            """)
    List<AiRawEvidenceManifest> selectByRun(
            @Param("runId") Long runId,
            @Param("datasetCode") String datasetCode
    );

    @Insert("""
            INSERT INTO ai_raw_evidence_manifest (
                backfill_run_id, provider_code, dataset_code, source_revision, object_uri,
                object_size, object_checksum, schema_version, row_count, range_start_date,
                range_end_date, observed_at, status, manifest_json, created_at
            ) VALUES (
                #{item.backfillRunId}, #{item.providerCode}, #{item.datasetCode},
                #{item.sourceRevision}, #{item.objectUri}, #{item.objectSize},
                #{item.objectChecksum}, #{item.schemaVersion}, #{item.rowCount},
                #{item.rangeStartDate}, #{item.rangeEndDate}, #{item.observedAt},
                #{item.status}, #{item.manifestJson}, #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiRawEvidenceManifest item);
}
