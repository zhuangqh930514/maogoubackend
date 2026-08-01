package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiDecisionPolicyShadowItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface AiDecisionPolicyShadowItemMapper extends BaseMapper<AiDecisionPolicyShadowItem> {
    @Insert("""
            INSERT INTO ai_decision_policy_shadow_item (
                user_id, trade_date, sample_id, stock_code, active_policy_version, shadow_policy_version,
                active_score, shadow_score, active_action, shadow_action, active_risk_score, shadow_risk_score,
                input_fingerprint, t1_prediction_id, t2_prediction_id, t3_prediction_id, evaluation_status, created_at
            ) VALUES (
                #{item.userId}, #{item.tradeDate}, #{item.sampleId}, #{item.stockCode}, #{item.activePolicyVersion},
                #{item.shadowPolicyVersion}, #{item.activeScore}, #{item.shadowScore}, #{item.activeAction},
                #{item.shadowAction}, #{item.activeRiskScore}, #{item.shadowRiskScore}, #{item.inputFingerprint},
                #{item.t1PredictionId}, #{item.t2PredictionId}, #{item.t3PredictionId}, #{item.evaluationStatus}, #{item.createdAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiDecisionPolicyShadowItem item);
}
