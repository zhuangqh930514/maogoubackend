package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiTrainingDataset;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiTrainingDatasetMapper extends BaseMapper<AiTrainingDataset> {

    @Insert("""
            INSERT INTO ai_training_dataset (
                research_universe_id, dataset_key, version_no, model_family, purpose, feature_version, label_version,
                calendar_version, as_of_time, train_start_date, train_end_date,
                validation_start_date, validation_end_date, test_start_date, test_end_date,
                max_horizon_days, purge_trading_days, embargo_trading_days,
                source_query_json, selection_policy_json, lineage_fingerprint,
                artifact_uri, artifact_checksum, row_count, status, finalized_at, created_at
            ) VALUES (
                #{item.researchUniverseId}, #{item.datasetKey}, #{item.versionNo}, #{item.modelFamily}, #{item.purpose},
                #{item.featureVersion}, #{item.labelVersion}, #{item.calendarVersion},
                #{item.asOfTime}, #{item.trainStartDate}, #{item.trainEndDate},
                #{item.validationStartDate}, #{item.validationEndDate}, #{item.testStartDate},
                #{item.testEndDate}, #{item.maxHorizonDays}, #{item.purgeTradingDays},
                #{item.embargoTradingDays}, #{item.sourceQueryJson},
                #{item.selectionPolicyJson}, #{item.lineageFingerprint}, #{item.artifactUri},
                #{item.artifactChecksum}, #{item.rowCount}, #{item.status}, #{item.finalizedAt},
                #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiTrainingDataset item);

    @Select("""
            SELECT * FROM ai_training_dataset
            WHERE dataset_key = #{datasetKey} AND version_no = #{versionNo}
            FOR SHARE
            """)
    AiTrainingDataset selectByVersionForShare(
            @Param("datasetKey") String datasetKey,
            @Param("versionNo") String versionNo
    );
}
