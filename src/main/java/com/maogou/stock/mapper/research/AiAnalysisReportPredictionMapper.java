package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiAnalysisReportPrediction;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiAnalysisReportPredictionMapper extends BaseMapper<AiAnalysisReportPrediction> {

    @Select("""
            SELECT * FROM ai_analysis_report_prediction
            WHERE user_id = #{userId} AND report_id = #{reportId}
            ORDER BY weight, id
            """)
    List<AiAnalysisReportPrediction> selectByReport(
            @Param("userId") Long userId,
            @Param("reportId") Long reportId
    );
}
