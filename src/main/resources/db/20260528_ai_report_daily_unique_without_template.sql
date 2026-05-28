ALTER TABLE ai_analysis_report
DROP INDEX uk_ai_report_daily_source;

UPDATE ai_analysis_report latest
JOIN (
    SELECT MAX(id) AS keep_id, user_id, stock_code, report_date, source_model
    FROM ai_analysis_report
    WHERE deleted = 0
    GROUP BY user_id, stock_code, report_date, source_model
) keeper
  ON latest.user_id = keeper.user_id
 AND latest.stock_code = keeper.stock_code
 AND latest.report_date = keeper.report_date
 AND ((latest.source_model IS NULL AND keeper.source_model IS NULL) OR latest.source_model = keeper.source_model)
SET latest.deleted = CASE WHEN latest.id = keeper.keep_id THEN latest.deleted ELSE 1 END,
    latest.updated_at = CURRENT_TIMESTAMP;

ALTER TABLE ai_analysis_report
ADD UNIQUE KEY uk_ai_report_daily_source (user_id, stock_code, report_date, source_model, deleted);
