package com.maogou.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.AiWatchlistAnalysisJob;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AiWatchlistAnalysisJobMapper extends BaseMapper<AiWatchlistAnalysisJob> {

    @Select("""
            SELECT * FROM ai_watchlist_analysis_job
            WHERE user_id = #{userId} AND active_key IS NOT NULL
            ORDER BY id DESC
            LIMIT 1
            """)
    AiWatchlistAnalysisJob selectActive(@Param("userId") Long userId);

    @Select("""
            SELECT * FROM ai_watchlist_analysis_job
            WHERE id = #{jobId} AND user_id = #{userId}
            LIMIT 1
            """)
    AiWatchlistAnalysisJob selectOwned(
            @Param("jobId") Long jobId,
            @Param("userId") Long userId
    );

    @Update("""
            UPDATE ai_watchlist_analysis_job
            SET status = 'RUNNING',
                total_count = #{totalCount},
                message = #{message},
                started_at = #{now},
                updated_at = #{now}
            WHERE id = #{jobId} AND user_id = #{userId} AND active_key IS NOT NULL
            """)
    int markRunning(
            @Param("jobId") Long jobId,
            @Param("userId") Long userId,
            @Param("totalCount") int totalCount,
            @Param("message") String message,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_watchlist_analysis_job
            SET current_stock_code = #{stockCode},
                current_stock_name = #{stockName},
                message = #{message},
                updated_at = #{now}
            WHERE id = #{jobId} AND user_id = #{userId} AND active_key IS NOT NULL
            """)
    int updateCurrentStock(
            @Param("jobId") Long jobId,
            @Param("userId") Long userId,
            @Param("stockCode") String stockCode,
            @Param("stockName") String stockName,
            @Param("message") String message,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_watchlist_analysis_job
            SET completed_count = completed_count + 1,
                analyzed_count = analyzed_count + #{analyzedIncrement},
                skipped_count = skipped_count + #{skippedIncrement},
                failed_count = failed_count + #{failedIncrement},
                message = #{message},
                last_error = CASE
                    WHEN #{issueDetail} IS NULL THEN last_error
                    ELSE #{issueDetail}
                END,
                issue_details = CASE
                    WHEN #{issueDetail} IS NULL THEN issue_details
                    WHEN issue_details IS NULL OR issue_details = '' THEN #{issueDetail}
                    ELSE CONCAT(issue_details, CHAR(10), #{issueDetail})
                END,
                updated_at = #{now}
            WHERE id = #{jobId} AND user_id = #{userId} AND active_key IS NOT NULL
            """)
    int recordProgress(
            @Param("jobId") Long jobId,
            @Param("userId") Long userId,
            @Param("analyzedIncrement") int analyzedIncrement,
            @Param("skippedIncrement") int skippedIncrement,
            @Param("failedIncrement") int failedIncrement,
            @Param("message") String message,
            @Param("issueDetail") String issueDetail,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_watchlist_analysis_job
            SET status = #{status},
                active_key = NULL,
                current_stock_code = NULL,
                current_stock_name = NULL,
                message = #{message},
                finished_at = #{now},
                updated_at = #{now}
            WHERE id = #{jobId} AND user_id = #{userId} AND active_key IS NOT NULL
            """)
    int markFinished(
            @Param("jobId") Long jobId,
            @Param("userId") Long userId,
            @Param("status") String status,
            @Param("message") String message,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_watchlist_analysis_job
            SET status = 'FAILED',
                active_key = NULL,
                current_stock_code = NULL,
                current_stock_name = NULL,
                message = #{message},
                last_error = #{errorDetail},
                issue_details = CASE
                    WHEN issue_details IS NULL OR issue_details = '' THEN #{errorDetail}
                    ELSE CONCAT(issue_details, CHAR(10), #{errorDetail})
                END,
                finished_at = #{now},
                updated_at = #{now}
            WHERE id = #{jobId} AND user_id = #{userId} AND active_key IS NOT NULL
            """)
    int markFailed(
            @Param("jobId") Long jobId,
            @Param("userId") Long userId,
            @Param("message") String message,
            @Param("errorDetail") String errorDetail,
            @Param("now") LocalDateTime now
    );

    @Update("""
            UPDATE ai_watchlist_analysis_job
            SET status = 'FAILED',
                active_key = NULL,
                current_stock_code = NULL,
                current_stock_name = NULL,
                message = #{message},
                last_error = #{message},
                finished_at = #{now},
                updated_at = #{now}
            WHERE active_key IS NOT NULL AND status IN ('PENDING', 'RUNNING')
            """)
    int recoverInterrupted(
            @Param("message") String message,
            @Param("now") LocalDateTime now
    );
}
