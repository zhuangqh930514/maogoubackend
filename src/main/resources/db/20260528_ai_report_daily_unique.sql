ALTER TABLE ai_analysis_report
ADD COLUMN prompt_template_id BIGINT NOT NULL DEFAULT 0 AFTER source_model,
ADD COLUMN report_date DATE NULL AFTER error_message;

UPDATE ai_analysis_report
SET report_date = DATE(generated_at),
    prompt_template_id = COALESCE(prompt_template_id, 0)
WHERE report_date IS NULL;

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
MODIFY COLUMN report_date DATE NOT NULL,
ADD UNIQUE KEY uk_ai_report_daily_source (user_id, stock_code, report_date, source_model, deleted);
