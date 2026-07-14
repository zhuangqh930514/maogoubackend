package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiFactorValue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiFactorValueMapper extends BaseMapper<AiFactorValue> {

    @Insert("""
            <script>
            INSERT INTO ai_factor_value (
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
    int insertBatchImmutable(@Param("items") List<AiFactorValue> items);

    @Select("""
            <script>
            SELECT *
            FROM ai_factor_value
            WHERE factor_version = #{factorVersion}
              AND sample_id IN
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
}
