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
            SELECT r.*
            FROM ai_strategy_release r
            INNER JOIN ai_research_universe u ON u.id = r.research_universe_id
            WHERE u.universe_code = #{universeCode}
              AND r.model_family = #{modelFamily}
              AND r.release_role = 'CHAMPION'
              AND r.status = 'ACTIVE'
            LIMIT 1
            FOR UPDATE
            """)
    AiStrategyRelease selectGlobalActiveChampionForUpdate(
            @Param("universeCode") String universeCode,
            @Param("modelFamily") String modelFamily
    );

    @Select("""
            SELECT r.*
            FROM ai_strategy_release r
            INNER JOIN ai_research_universe u ON u.id = r.research_universe_id
            WHERE u.universe_code = #{universeCode}
              AND r.model_family = #{modelFamily}
              AND r.release_role = 'CHAMPION'
              AND r.status = 'ACTIVE'
            LIMIT 1
            """)
    AiStrategyRelease selectGlobalActiveChampion(
            @Param("universeCode") String universeCode,
            @Param("modelFamily") String modelFamily
    );

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
