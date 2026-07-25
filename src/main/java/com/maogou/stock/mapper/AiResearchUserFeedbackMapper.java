package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiResearchUserFeedback;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AiResearchUserFeedbackMapper extends BaseMapper<AiResearchUserFeedback> {

    @Select("""
            SELECT * FROM ai_research_user_feedback
            WHERE user_id = #{userId} AND report_id = #{reportId}
            ORDER BY updated_at DESC, id DESC
            """)
    List<AiResearchUserFeedback> selectByReport(
            @Param("userId") Long userId,
            @Param("reportId") Long reportId
    );

    @Select("""
            SELECT * FROM ai_research_user_feedback
            WHERE user_id = #{userId} AND report_id = #{reportId} AND stock_code = #{stockCode}
            LIMIT 1
            """)
    AiResearchUserFeedback selectByReportAndStock(
            @Param("userId") Long userId,
            @Param("reportId") Long reportId,
            @Param("stockCode") String stockCode
    );

    @Insert("""
            INSERT INTO ai_research_user_feedback
                (user_id, report_id, stock_code, feedback_type, comment, created_at, updated_at)
            VALUES
                (#{item.userId}, #{item.reportId}, #{item.stockCode}, #{item.feedbackType}, #{item.comment},
                 #{item.createdAt}, #{item.updatedAt})
            ON DUPLICATE KEY UPDATE
                feedback_type = VALUES(feedback_type),
                comment = VALUES(comment),
                updated_at = VALUES(updated_at)
            """)
    int upsert(@Param("item") AiResearchUserFeedback item);
}
