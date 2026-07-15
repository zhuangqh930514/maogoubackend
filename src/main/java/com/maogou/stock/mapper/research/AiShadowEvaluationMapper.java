package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiShadowEvaluation;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface AiShadowEvaluationMapper extends BaseMapper<AiShadowEvaluation> {

    @Insert("""
            INSERT INTO ai_shadow_evaluation (
                pipeline_run_id, training_dataset_id, champion_release_id,
                challenger_release_id, champion_model_version_id, challenger_model_version_id,
                window_start_date, window_end_date, evaluation_version, input_fingerprint,
                sample_count, eligible_sample_count, coverage_rate, action_agreement_rate,
                champion_calibration_error, challenger_calibration_error, champion_excess_return,
                challenger_excess_return, champion_max_drawdown, challenger_max_drawdown,
                feature_drift_score, metrics_json, decision_status, evaluated_at, created_at
            ) VALUES (
                #{item.pipelineRunId}, #{item.trainingDatasetId},
                #{item.championReleaseId}, #{item.challengerReleaseId},
                #{item.championModelVersionId}, #{item.challengerModelVersionId},
                #{item.windowStartDate}, #{item.windowEndDate}, #{item.evaluationVersion},
                #{item.inputFingerprint}, #{item.sampleCount}, #{item.eligibleSampleCount},
                #{item.coverageRate}, #{item.actionAgreementRate},
                #{item.championCalibrationError}, #{item.challengerCalibrationError},
                #{item.championExcessReturn}, #{item.challengerExcessReturn},
                #{item.championMaxDrawdown}, #{item.challengerMaxDrawdown},
                #{item.featureDriftScore}, #{item.metricsJson}, #{item.decisionStatus},
                #{item.evaluatedAt}, #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertImmutable(@Param("item") AiShadowEvaluation item);

    @Select("""
            SELECT * FROM ai_shadow_evaluation
            WHERE champion_release_id = #{championReleaseId}
              AND challenger_release_id = #{challengerReleaseId}
              AND window_start_date = #{windowStartDate}
              AND window_end_date = #{windowEndDate}
              AND evaluation_version = #{evaluationVersion}
            FOR SHARE
            """)
    AiShadowEvaluation selectWindowForShare(
            @Param("championReleaseId") Long championReleaseId,
            @Param("challengerReleaseId") Long challengerReleaseId,
            @Param("windowStartDate") LocalDate windowStartDate,
            @Param("windowEndDate") LocalDate windowEndDate,
            @Param("evaluationVersion") String evaluationVersion
    );
}
