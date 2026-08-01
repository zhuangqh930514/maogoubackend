package com.maogou.stock.mapper.research;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.maogou.stock.domain.entity.research.AiPipelineIssue;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface AiPipelineIssueMapper extends BaseMapper<AiPipelineIssue> {

    @Insert("""
            INSERT INTO ai_pipeline_issue (
                user_id, report_id, pipeline_run_id, trade_date, step_key, step_name,
                stock_code, stock_name, provider_code, endpoint_type, reason_code,
                reason_message, retry_count, max_retries, next_retry_at, recoverable,
                source_as_of, attempt_no, created_at, updated_at
            ) VALUES (
                #{item.userId}, #{item.reportId}, #{item.pipelineRunId}, #{item.tradeDate},
                #{item.stepKey}, #{item.stepName}, #{item.stockCode}, #{item.stockName},
                #{item.providerCode}, #{item.endpointType}, #{item.reasonCode}, #{item.reasonMessage},
                #{item.retryCount}, #{item.maxRetries}, #{item.nextRetryAt}, #{item.recoverable},
                #{item.sourceAsOf}, #{item.attemptNo}, #{item.createdAt}, #{item.updatedAt}
            ) ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIgnore(@Param("item") AiPipelineIssue item);
}
