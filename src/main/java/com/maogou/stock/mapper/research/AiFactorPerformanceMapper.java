package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiFactorPerformance;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface AiFactorPerformanceMapper extends BaseMapper<AiFactorPerformance> {

    @Insert("""
            <script>
            INSERT INTO ai_factor_performance (
                user_id, factor_code, factor_version, horizon_days, market_regime, window_type,
                window_start_date, window_end_date, input_fingerprint, sample_count, success_count,
                success_rate, wilson_lower_bound, rank_ic, avg_excess_return, avg_adverse_return,
                stability_score, psi_score, confidence_level, drift_status, evaluated_at,
                created_at, updated_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.userId}, #{item.factorCode}, #{item.factorVersion}, #{item.horizonDays},
                    #{item.marketRegime}, #{item.windowType}, #{item.windowStartDate},
                    #{item.windowEndDate}, #{item.inputFingerprint}, #{item.sampleCount},
                    #{item.successCount}, #{item.successRate}, #{item.wilsonLowerBound},
                    #{item.rankIc}, #{item.avgExcessReturn}, #{item.avgAdverseReturn},
                    #{item.stabilityScore}, #{item.psiScore}, #{item.confidenceLevel},
                    #{item.driftStatus}, #{item.evaluatedAt}, #{item.createdAt}, #{item.updatedAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiFactorPerformance> items);

    @Select("""
            SELECT * FROM ai_factor_performance
            WHERE user_id = #{userId}
              AND factor_version = #{factorVersion}
              AND horizon_days = #{horizonDays}
              AND market_regime = #{marketRegime}
              AND window_type = #{windowType}
              AND window_start_date = #{windowStartDate}
              AND window_end_date = #{windowEndDate}
            FOR SHARE
            """)
    List<AiFactorPerformance> selectWindowForShare(
            @Param("userId") Long userId,
            @Param("factorVersion") String factorVersion,
            @Param("horizonDays") Integer horizonDays,
            @Param("marketRegime") String marketRegime,
            @Param("windowType") String windowType,
            @Param("windowStartDate") LocalDate windowStartDate,
            @Param("windowEndDate") LocalDate windowEndDate
    );

    @Select("""
            <script>
            SELECT DISTINCT fp.*, d.factor_code AS factor_code, d.factor_name AS factor_name,
                   d.factor_version AS factor_version
            FROM ai_factor_performance fp
            INNER JOIN ai_factor_definition d ON d.id = fp.factor_definition_id
            INNER JOIN ai_factor_value fv ON fv.factor_definition_id = fp.factor_definition_id
            WHERE fv.sample_id IN
              <foreach collection="sampleIds" item="sampleId" open="(" separator="," close=")">
                #{sampleId}
              </foreach>
              AND fv.hit = 1 AND fv.missing = 0
              AND fp.horizon_trading_days = 3
              AND fp.window_end_date < #{tradeDate}
            ORDER BY fp.window_end_date DESC, fp.evaluated_at DESC
            </script>
            """)
    List<AiFactorPerformance> selectForSamplesBefore(
            @Param("sampleIds") List<Long> sampleIds,
            @Param("tradeDate") LocalDate tradeDate
    );
}
