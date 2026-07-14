package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiModelVersion;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiModelVersionMapper extends BaseMapper<AiModelVersion> {

    @Insert("""
            INSERT INTO ai_model_version (
                user_id, training_dataset_id, model_key, version_no, model_type, algorithm,
                feature_version, trainer_version, random_seed, artifact_uri, artifact_checksum,
                feature_manifest_uri, feature_manifest_checksum, train_start_date, train_end_date,
                validation_start_date, validation_end_date, test_start_date, test_end_date,
                parameters_json, metrics_json, calibration_json, sample_count, status,
                created_at, updated_at
            ) VALUES (
                #{item.userId}, #{item.trainingDatasetId}, #{item.modelKey}, #{item.versionNo},
                #{item.modelType}, #{item.algorithm}, #{item.featureVersion}, #{item.trainerVersion},
                #{item.randomSeed}, #{item.artifactUri}, #{item.artifactChecksum},
                #{item.featureManifestUri}, #{item.featureManifestChecksum}, #{item.trainStartDate},
                #{item.trainEndDate}, #{item.validationStartDate}, #{item.validationEndDate},
                #{item.testStartDate}, #{item.testEndDate}, #{item.parametersJson},
                #{item.metricsJson}, #{item.calibrationJson}, #{item.sampleCount}, #{item.status},
                #{item.createdAt}, #{item.updatedAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiModelVersion item);

    @Select("""
            SELECT * FROM ai_model_version
            WHERE user_id = #{userId} AND model_key = #{modelKey} AND version_no = #{versionNo}
            FOR SHARE
            """)
    AiModelVersion selectByVersionForShare(
            @Param("userId") Long userId,
            @Param("modelKey") String modelKey,
            @Param("versionNo") String versionNo
    );
}
