-- Preserve the scope of historical evidence. A strategy-level fallback must
-- never be presented to users as stock-specific verification.
ALTER TABLE ai_daily_decision_item
    ADD COLUMN IF NOT EXISTS evidence_scope VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN'
    AFTER historical_hit_rate;
