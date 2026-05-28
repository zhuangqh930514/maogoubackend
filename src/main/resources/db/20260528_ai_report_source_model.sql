ALTER TABLE ai_analysis_report
ADD COLUMN source_model VARCHAR(128) NULL AFTER raw_response;
