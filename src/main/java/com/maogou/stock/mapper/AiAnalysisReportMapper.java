package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiAnalysisReport;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface AiAnalysisReportMapper extends BaseMapper<AiAnalysisReport> {

    @Select("""
            SELECT prediction_id
            FROM ai_analysis_report_prediction
            WHERE user_id = #{userId} AND report_id = #{reportId}
            ORDER BY CASE purpose
                WHEN 'PRIMARY_RANKING' THEN 1
                WHEN 'T3_SIGNAL' THEN 2
                WHEN 'T2_SIGNAL' THEN 3
                WHEN 'T1_SIGNAL' THEN 4
                ELSE 5 END,
                weight DESC, id
            LIMIT 1
            """)
    Long selectPrimaryPredictionId(
            @Param("userId") Long userId,
            @Param("reportId") Long reportId
    );

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
                   pipeline_run_id, lineage_status, lineage_issue_json, input_fingerprint,
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

    @Select("""
            <script>
            SELECT r.*
            FROM ai_analysis_report r
            INNER JOIN (
                SELECT stock_code, MAX(report_version) AS latest_version
                FROM ai_analysis_report
                WHERE user_id = #{userId}
                  AND report_date = #{reportDate}
                  AND status = 'SUCCESS'
                  AND stock_code IN
                <foreach collection="stockCodes" item="stockCode" open="(" separator="," close=")">
                    #{stockCode}
                </foreach>
                GROUP BY stock_code
            ) latest ON latest.stock_code = r.stock_code
                    AND latest.latest_version = r.report_version
            WHERE r.user_id = #{userId}
              AND r.report_date = #{reportDate}
              AND r.status = 'SUCCESS'
            ORDER BY r.stock_code, r.id DESC
            </script>
            """)
    List<AiAnalysisReport> selectLatestSuccessfulForDailyDecision(
            @Param("userId") Long userId,
            @Param("reportDate") LocalDate reportDate,
            @Param("stockCodes") List<String> stockCodes
    );

    @Select("""
            <script>
            SELECT *
            FROM ai_analysis_report
            WHERE user_id = #{userId}
              AND id IN
              <foreach collection="reportIds" item="reportId" open="(" separator="," close=")">
                #{reportId}
              </foreach>
            </script>
            """)
    List<AiAnalysisReport> selectOwnedByIds(
            @Param("userId") Long userId,
            @Param("reportIds") List<Long> reportIds
    );
}
