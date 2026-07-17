package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiFactorDefinition;
import com.maogou.stock.domain.entity.research.AiFactorPerformanceSource;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;

public interface AiFactorValueMapper extends BaseMapper<AiFactorValue> {

    @Insert("""
            <script>
            INSERT INTO ai_factor_value (
                sample_id, factor_definition_id, raw_value, normalized_value, hit, missing,
                missing_reason, evidence_json, input_fingerprint, calculated_at, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.sampleId}, #{item.factorDefinitionId}, #{item.rawValue},
                    #{item.normalizedValue}, #{item.hit}, #{item.missing}, #{item.missingReason},
                    #{item.evidenceJson}, #{item.inputFingerprint}, #{item.calculatedAt}, #{item.createdAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiFactorValue> items);

    @Select("""
            <script>
            SELECT v.*,
                   d.factor_code AS factor_code,
                   d.factor_name AS factor_name,
                   d.factor_version AS factor_version,
                   d.factor_group AS factor_group,
                   d.direction AS direction
            FROM ai_factor_value v
            JOIN ai_factor_definition d ON d.id = v.factor_definition_id
            WHERE d.factor_version = #{factorVersion}
              AND v.sample_id IN
              <foreach collection="sampleIds" item="sampleId" open="(" separator="," close=")">
                  #{sampleId}
              </foreach>
            FOR SHARE
            </script>
            """)
    List<AiFactorValue> selectBySamplesForShare(
            @Param("sampleIds") List<Long> sampleIds,
            @Param("factorVersion") String factorVersion
    );

    @Select("""
            <script>
            SELECT v.*,
                   d.factor_code AS factor_code,
                   d.factor_name AS factor_name,
                   d.factor_version AS factor_version,
                   d.factor_group AS factor_group,
                   d.direction AS direction
            FROM ai_factor_value v
            JOIN ai_factor_definition d ON d.id = v.factor_definition_id
            WHERE d.factor_version = #{factorVersion}
              AND v.sample_id IN
              <foreach collection="sampleIds" item="sampleId" open="(" separator="," close=")">
                  #{sampleId}
              </foreach>
            ORDER BY v.sample_id, d.factor_code
            </script>
            """)
    List<AiFactorValue> selectBySamples(
            @Param("sampleIds") List<Long> sampleIds,
            @Param("factorVersion") String factorVersion
    );

    @Select("""
            SELECT s.id AS sample_id,
                   s.stock_code,
                   s.trade_date,
                   s.market_regime,
                   s.source_fingerprint AS sample_source_fingerprint,
                   v.id AS factor_value_id,
                   v.factor_definition_id,
                   d.factor_code,
                   d.factor_version,
                   d.direction AS factor_direction,
                   v.raw_value,
                   v.normalized_value,
                   v.missing AS factor_missing,
                   v.input_fingerprint AS factor_input_fingerprint,
                   l.id AS label_id,
                   l.horizon_trading_days,
                   l.input_fingerprint AS label_input_fingerprint,
                   l.excess_return,
                   l.max_adverse_return,
                   l.execution_status,
                   l.label_status,
                   l.label_available_at,
                   l.matured_at,
                   l.verified_at
            FROM ai_factor_value v
            INNER JOIN ai_factor_definition d ON d.id = v.factor_definition_id
            INNER JOIN ai_sample s ON s.id = v.sample_id
            INNER JOIN ai_sample_label l ON l.sample_id = s.id
            WHERE v.factor_definition_id = #{factorDefinitionId}
              AND d.factor_version = #{factorVersion}
              AND s.market_regime = #{marketRegime}
              AND s.trade_date BETWEEN #{startDate} AND #{endDate}
              AND s.as_of_time <= #{asOfTime}
              AND l.label_version = #{labelVersion}
              AND l.horizon_trading_days = #{horizonDays}
              AND l.label_status = 'MATURED'
              AND l.execution_status = 'EXECUTED'
              AND l.label_available_at <= #{asOfTime}
            ORDER BY s.trade_date, s.id, v.id, l.id
            """)
    List<AiFactorPerformanceSource> selectPerformanceSources(
            @Param("factorDefinitionId") Long factorDefinitionId,
            @Param("factorVersion") String factorVersion,
            @Param("labelVersion") String labelVersion,
            @Param("horizonDays") Integer horizonDays,
            @Param("marketRegime") String marketRegime,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate,
            @Param("asOfTime") LocalDateTime asOfTime
    );

    @Select("""
            SELECT id,
                   factor_code AS factorCode,
                   factor_version AS versionNo,
                   factor_name AS factorName,
                   factor_group AS factorGroup,
                   direction,
                   formula_desc AS formulaDesc,
                   required_fields_json AS requiredFieldsJson,
                   default_weight AS defaultWeight,
                   enabled,
                   seed_version AS seedVersion,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM ai_factor_definition
            WHERE factor_version = #{factorVersion} AND enabled = 1
            ORDER BY factor_code
            """)
    List<AiFactorDefinition> selectEnabledDefinitions(@Param("factorVersion") String factorVersion);
}
