package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiFactorStat;
import org.apache.ibatis.annotations.Insert;

public interface AiFactorStatMapper extends BaseMapper<AiFactorStat> {

    @Insert("""
            INSERT INTO ai_factor_stat (
                user_id,
                factor_code,
                factor_name,
                factor_group,
                market_regime,
                sample_count,
                success_count,
                success_rate,
                avg_return,
                avg_drawdown,
                weight_score,
                last_evaluated_at,
                created_at,
                updated_at
            ) VALUES (
                #{userId},
                #{factorCode},
                #{factorName},
                #{factorGroup},
                #{marketRegime},
                #{sampleCount},
                #{successCount},
                #{successRate},
                #{avgReturn},
                #{avgDrawdown},
                #{weightScore},
                #{lastEvaluatedAt},
                #{createdAt},
                #{updatedAt}
            )
            ON DUPLICATE KEY UPDATE
                factor_name = VALUES(factor_name),
                factor_group = VALUES(factor_group),
                sample_count = VALUES(sample_count),
                success_count = VALUES(success_count),
                success_rate = VALUES(success_rate),
                avg_return = VALUES(avg_return),
                avg_drawdown = VALUES(avg_drawdown),
                weight_score = VALUES(weight_score),
                last_evaluated_at = VALUES(last_evaluated_at),
                updated_at = VALUES(updated_at)
            """)
    int upsert(AiFactorStat stat);
}
