package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiStrategyRelease;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiStrategyReleaseMapper extends BaseMapper<AiStrategyRelease> {

    @Select("SELECT * FROM ai_strategy_release WHERE id = #{id} FOR UPDATE")
    AiStrategyRelease selectByIdForUpdate(@Param("id") Long id);

    @Select("""
            SELECT * FROM ai_strategy_release
            WHERE user_id = #{userId} AND release_role = 'CHAMPION' AND status = 'ACTIVE'
            FOR UPDATE
            """)
    AiStrategyRelease selectActiveChampionForUpdate(@Param("userId") Long userId);

    @Select("""
            SELECT * FROM ai_strategy_release
            WHERE user_id = #{userId}
              AND release_role = 'CHALLENGER'
              AND status = 'SHADOW'
            ORDER BY created_at ASC, id ASC
            """)
    List<AiStrategyRelease> selectShadowChallengers(@Param("userId") Long userId);
}
