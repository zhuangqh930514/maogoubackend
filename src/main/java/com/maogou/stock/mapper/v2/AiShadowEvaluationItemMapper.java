package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiShadowEvaluationItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiShadowEvaluationItemMapper extends BaseMapper<AiShadowEvaluationItem> {

    @Insert("""
            <script>
            INSERT INTO ai_shadow_evaluation_item (
                shadow_evaluation_id, sample_id, champion_prediction_id, challenger_prediction_id,
                label_id, horizon_days, action_agreement, score_delta, confidence_delta,
                challenger_excess_return, evaluation_status, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.shadowEvaluationId}, #{item.sampleId}, #{item.championPredictionId},
                    #{item.challengerPredictionId}, #{item.labelId}, #{item.horizonDays},
                    #{item.actionAgreement}, #{item.scoreDelta}, #{item.confidenceDelta},
                    #{item.challengerExcessReturn}, #{item.evaluationStatus}, #{item.createdAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiShadowEvaluationItem> items);

    @Select("""
            SELECT * FROM ai_shadow_evaluation_item
            WHERE shadow_evaluation_id = #{shadowEvaluationId}
            ORDER BY sample_id, horizon_days
            FOR SHARE
            """)
    List<AiShadowEvaluationItem> selectByEvaluationForShare(
            @Param("shadowEvaluationId") Long shadowEvaluationId
    );
}
