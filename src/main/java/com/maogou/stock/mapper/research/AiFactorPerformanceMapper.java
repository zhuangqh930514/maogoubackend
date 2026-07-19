package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiFactorPerformance;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

public interface AiFactorPerformanceMapper extends BaseMapper<AiFactorPerformance> {

    @Insert("""
            <script>
            INSERT INTO ai_factor_performance (
                factor_definition_id, horizon_trading_days, market_regime, window_type,
                window_start_date, window_end_date, revision_no, is_current,
                supersedes_performance_id, revision_reason, input_fingerprint, sample_count, success_count,
                success_rate, wilson_lower_bound, rank_ic, avg_excess_return, avg_adverse_return,
                stability_score, psi_score, confidence_level, drift_status, evaluated_at,
                created_at, updated_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.factorDefinitionId}, #{item.horizonDays},
                    #{item.marketRegime}, #{item.windowType}, #{item.windowStartDate},
                    #{item.windowEndDate}, #{item.revisionNo}, #{item.isCurrent},
                    #{item.supersedesPerformanceId}, #{item.revisionReason},
                    #{item.inputFingerprint}, #{item.sampleCount},
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

    @Update("""
            <script>
            UPDATE ai_factor_performance SET is_current = 0
            WHERE is_current = 1 AND id IN
              <foreach collection="ids" item="id" open="(" separator="," close=")">
                #{id}
              </foreach>
            </script>
            """)
    int markSuperseded(@Param("ids") List<Long> ids);

    @Select("""
            SELECT fp.*, d.factor_code AS factor_code, d.factor_name AS factor_name,
                   d.factor_version AS factor_version
            FROM ai_factor_performance fp
            INNER JOIN ai_factor_definition d ON d.id = fp.factor_definition_id
            WHERE d.factor_version = #{factorVersion}
              AND fp.horizon_trading_days = #{horizonDays}
              AND fp.market_regime = #{marketRegime}
              AND fp.window_type = #{windowType}
              AND fp.window_start_date = #{windowStartDate}
              AND fp.window_end_date = #{windowEndDate}
              AND fp.is_current = 1
            FOR UPDATE
            """)
    List<AiFactorPerformance> selectCurrentWindowForUpdate(
            @Param("factorVersion") String factorVersion,
            @Param("horizonDays") Integer horizonDays,
            @Param("marketRegime") String marketRegime,
            @Param("windowType") String windowType,
            @Param("windowStartDate") LocalDate windowStartDate,
            @Param("windowEndDate") LocalDate windowEndDate
    );

    @Select("""
            SELECT fp.*, d.factor_code AS factor_code, d.factor_name AS factor_name,
                   d.factor_version AS factor_version
            FROM ai_factor_performance fp
            INNER JOIN ai_factor_definition d ON d.id = fp.factor_definition_id
            WHERE d.factor_version = #{factorVersion}
              AND fp.horizon_trading_days = #{horizonDays}
              AND fp.market_regime = #{marketRegime}
              AND fp.window_type = #{windowType}
              AND fp.window_start_date = #{windowStartDate}
              AND fp.window_end_date = #{windowEndDate}
              AND fp.is_current = 1
            FOR SHARE
            """)
    List<AiFactorPerformance> selectWindowForShare(
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
              AND fp.is_current = 1
              AND fp.window_end_date &lt; #{tradeDate}
            ORDER BY fp.window_end_date DESC, fp.evaluated_at DESC
            </script>
            """)
    List<AiFactorPerformance> selectForSamplesBefore(
            @Param("sampleIds") List<Long> sampleIds,
            @Param("tradeDate") LocalDate tradeDate
    );
}
