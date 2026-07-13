package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiTrainingDataset;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiTrainingDatasetMapper extends BaseMapper<AiTrainingDataset> {

    @Insert("""
            INSERT INTO ai_training_dataset (
                user_id, dataset_key, version_no, purpose, feature_version, label_version,
                calendar_version, as_of_time, train_start_date, train_end_date,
                validation_start_date, validation_end_date, test_start_date, test_end_date,
                max_horizon_days, source_query_json, selection_policy_json, lineage_fingerprint,
                artifact_uri, artifact_checksum, row_count, status, finalized_at, created_at
            ) VALUES (
                #{item.userId}, #{item.datasetKey}, #{item.versionNo}, #{item.purpose},
                #{item.featureVersion}, #{item.labelVersion}, #{item.calendarVersion},
                #{item.asOfTime}, #{item.trainStartDate}, #{item.trainEndDate},
                #{item.validationStartDate}, #{item.validationEndDate}, #{item.testStartDate},
                #{item.testEndDate}, #{item.maxHorizonDays}, #{item.sourceQueryJson},
                #{item.selectionPolicyJson}, #{item.lineageFingerprint}, #{item.artifactUri},
                #{item.artifactChecksum}, #{item.rowCount}, #{item.status}, #{item.finalizedAt},
                #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiTrainingDataset item);

    @Select("""
            SELECT * FROM ai_training_dataset
            WHERE user_id = #{userId} AND dataset_key = #{datasetKey} AND version_no = #{versionNo}
            FOR SHARE
            """)
    AiTrainingDataset selectByVersionForShare(
            @Param("userId") Long userId,
            @Param("datasetKey") String datasetKey,
            @Param("versionNo") String versionNo
    );
}
