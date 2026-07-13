package com.maogou.stock.mapper.v2;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.v2.AiFactorValueV2;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiFactorValueV2Mapper extends BaseMapper<AiFactorValueV2> {

    @Insert("""
            <script>
            INSERT INTO ai_factor_value_v2 (
                user_id, sample_id, stock_code, factor_code, factor_version, factor_group,
                direction, raw_value, normalized_value, hit, missing, missing_reason,
                evidence, input_fingerprint, calculated_at, created_at
            ) VALUES
            <foreach collection="items" item="item" separator=",">
                (
                    #{item.userId}, #{item.sampleId}, #{item.stockCode}, #{item.factorCode},
                    #{item.factorVersion}, #{item.factorGroup}, #{item.direction}, #{item.rawValue},
                    #{item.normalizedValue}, #{item.hit}, #{item.missing}, #{item.missingReason},
                    #{item.evidence}, #{item.inputFingerprint}, #{item.calculatedAt}, #{item.createdAt}
                )
            </foreach>
            ON DUPLICATE KEY UPDATE id = id
            </script>
            """)
    int insertBatchImmutable(@Param("items") List<AiFactorValueV2> items);

    @Select("""
            <script>
            SELECT *
            FROM ai_factor_value_v2
            WHERE factor_version = #{factorVersion}
              AND sample_id IN
              <foreach collection="sampleIds" item="sampleId" open="(" separator="," close=")">
                  #{sampleId}
              </foreach>
            FOR SHARE
            </script>
            """)
    List<AiFactorValueV2> selectBySamplesForShare(
            @Param("sampleIds") List<Long> sampleIds,
            @Param("factorVersion") String factorVersion
    );
}
