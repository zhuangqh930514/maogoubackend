package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

public interface AiAnalysisReportMapper extends BaseMapper<AiAnalysisReport> {

    @Select("SELECT id FROM user_account WHERE id = #{userId} FOR UPDATE")
    Long lockUser(@Param("userId") Long userId);

    @Select("""
            SELECT * FROM ai_analysis_report
            WHERE user_id = #{userId} AND stock_code = #{stockCode} AND report_date = #{reportDate}
            ORDER BY report_version DESC LIMIT 1 FOR UPDATE
            """)
    AiAnalysisReport selectLatestVersionForUpdate(
            @Param("userId") Long userId,
            @Param("stockCode") String stockCode,
            @Param("reportDate") LocalDate reportDate
    );

    @Select("""
            SELECT id, user_id, stock_code, stock_name, sample_id, strategy_release_id,
                   prompt_template_id, report_date, report_version, supersedes_report_id,
                   idempotency_key, status, system_score, final_action, target_direction,
                   risk_score, risk_level, calibrated_confidence, data_quality_score, advice,
                   technical_analysis, risk_warning, buy_sell_points, conditional_strategy,
                   prompt_summary, source_model, error_message, generated_at, created_at, updated_at
            FROM ai_analysis_report
            WHERE id = #{reportId} AND user_id = #{userId}
            LIMIT 1
            """)
    AiAnalysisReport selectOwned(
            @Param("reportId") Long reportId,
            @Param("userId") Long userId
    );
}
