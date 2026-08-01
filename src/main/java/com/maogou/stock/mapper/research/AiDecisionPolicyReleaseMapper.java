package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiDecisionPolicyRelease;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface AiDecisionPolicyReleaseMapper extends BaseMapper<AiDecisionPolicyRelease> {
    @Select("""
            SELECT * FROM ai_decision_policy_release
            WHERE policy_key = #{policyKey} AND status = 'SHADOW'
            ORDER BY created_at DESC, id DESC
            LIMIT 1
            """)
    AiDecisionPolicyRelease selectShadow(@Param("policyKey") String policyKey);
}
