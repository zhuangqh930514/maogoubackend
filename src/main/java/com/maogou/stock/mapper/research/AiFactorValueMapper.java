package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiFactorDefinition;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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
            SELECT *
            FROM ai_factor_definition
            WHERE factor_version = #{factorVersion} AND enabled = 1
            ORDER BY factor_code
            """)
    List<AiFactorDefinition> selectEnabledDefinitions(@Param("factorVersion") String factorVersion);
}
