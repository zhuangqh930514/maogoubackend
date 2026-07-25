package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiTradeRuleConfig;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AiTradeRuleConfigMapper extends BaseMapper<AiTradeRuleConfig> {

    @Select("SELECT * FROM ai_trade_rule_config WHERE id = #{id} FOR UPDATE")
    AiTradeRuleConfig selectByIdForUpdate(@Param("id") Long id);

    @Update("""
            UPDATE ai_trade_rule_config
            SET status = 'SUPERSEDED', updated_at = #{now}
            WHERE user_id = #{userId}
              AND status = 'ACTIVE'
              AND id <> #{candidateId}
            """)
    int supersedeActiveForCandidate(
            @Param("userId") Long userId,
            @Param("candidateId") Long candidateId,
            @Param("now") java.time.LocalDateTime now
    );

    @Update("""
            UPDATE ai_trade_rule_config
            SET status = 'ACTIVE', updated_at = #{now}
            WHERE id = #{candidateId}
              AND user_id = #{userId}
              AND status = 'CANDIDATE'
            """)
    int activateCandidate(
            @Param("userId") Long userId,
            @Param("candidateId") Long candidateId,
            @Param("now") java.time.LocalDateTime now
    );
}
