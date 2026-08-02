package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiDataQuarantine;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface AiDataQuarantineMapper extends BaseMapper<AiDataQuarantine> {

    @Select("SELECT * FROM ai_data_quarantine WHERE id = #{id} LIMIT 1")
    AiDataQuarantine selectByIssueId(@Param("id") Long id);

    @Select("""
            SELECT COUNT(*) FROM ai_data_quarantine
            WHERE backfill_run_id = #{runId}
              AND (#{reasonCode} IS NULL OR reason_code = #{reasonCode})
              AND (#{stockCode} IS NULL OR stock_code = #{stockCode})
              AND (#{tradeDate} IS NULL OR trade_date = #{tradeDate})
            """)
    long countByRun(
            @Param("runId") Long runId,
            @Param("reasonCode") String reasonCode,
            @Param("stockCode") String stockCode,
            @Param("tradeDate") LocalDate tradeDate
    );

    @Select("""
            SELECT * FROM ai_data_quarantine
            WHERE backfill_run_id = #{runId}
              AND (#{reasonCode} IS NULL OR reason_code = #{reasonCode})
              AND (#{stockCode} IS NULL OR stock_code = #{stockCode})
              AND (#{tradeDate} IS NULL OR trade_date = #{tradeDate})
            ORDER BY created_at DESC, id DESC
            LIMIT #{offset}, #{pageSize}
            """)
    List<AiDataQuarantine> selectPageByRun(
            @Param("runId") Long runId,
            @Param("reasonCode") String reasonCode,
            @Param("stockCode") String stockCode,
            @Param("tradeDate") LocalDate tradeDate,
            @Param("offset") int offset,
            @Param("pageSize") int pageSize
    );

    @Insert("""
            INSERT INTO ai_data_quarantine (
                backfill_run_id, shard_id, provider_code, dataset_code, trade_date,
                stock_code, industry_code, source_row_number, field_name, reason_code,
                reason_message, raw_fingerprint, quarantine_fingerprint, retryable,
                resolution_status, created_at, resolved_at
            ) VALUES (
                #{item.backfillRunId}, #{item.shardId}, #{item.providerCode}, #{item.datasetCode},
                #{item.tradeDate}, #{item.stockCode}, #{item.industryCode}, #{item.rowNumber},
                #{item.fieldName}, #{item.reasonCode}, #{item.reasonMessage},
                #{item.rawFingerprint}, #{item.quarantineFingerprint}, #{item.retryable},
                #{item.resolutionStatus}, #{item.createdAt}, #{item.resolvedAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiDataQuarantine item);
}
