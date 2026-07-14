SET @schema_name = DATABASE();

SET @add_conditional_strategy = IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @schema_name
          AND table_name = 'ai_analysis_report'
          AND column_name = 'conditional_strategy'
    ),
    'SELECT 1',
    'ALTER TABLE ai_analysis_report ADD COLUMN conditional_strategy MEDIUMTEXT NULL AFTER buy_sell_points'
);
PREPARE statement FROM @add_conditional_strategy;
EXECUTE statement;
DEALLOCATE PREPARE statement;

CREATE TABLE IF NOT EXISTS ai_trade_rule_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL DEFAULT 0,
    version_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    config_json MEDIUMTEXT NOT NULL,
    source_strategy_release_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trade_rule_config_user_version (user_id, version_no),
    KEY idx_trade_rule_config_active (user_id, status, updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_trade_plan_review (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    report_id BIGINT NOT NULL,
    stock_code VARCHAR(16) NOT NULL,
    report_date DATE NOT NULL,
    horizon_days INT NOT NULL,
    target_trade_date DATE NULL,
    outcome_trade_date DATE NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    triggered_rule_code VARCHAR(64) NULL,
    rule_type VARCHAR(32) NOT NULL DEFAULT 'HORIZON_PLAN',
    triggered_state VARCHAR(64) NULL,
    suggested_action VARCHAR(32) NULL,
    market_regime VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    trigger_price DECIMAL(18, 4) NULL,
    outcome_price DECIMAL(18, 4) NULL,
    post_trigger_return DECIMAL(12, 4) NULL,
    max_favorable_return DECIMAL(12, 4) NULL,
    max_adverse_return DECIMAL(12, 4) NULL,
    action_effective TINYINT NULL,
    review_score DECIMAL(10, 4) NULL,
    actual_metrics_json MEDIUMTEXT NULL,
    feedback_json MEDIUMTEXT NULL,
    feedback_summary VARCHAR(1024) NULL,
    evaluated_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trade_plan_review_report_horizon (user_id, report_id, horizon_days),
    KEY idx_trade_plan_review_pending (user_id, status, outcome_trade_date),
    KEY idx_trade_plan_review_rule (user_id, triggered_rule_code, horizon_days)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS ai_trade_rule_performance (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    rule_code VARCHAR(64) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    horizon_days INT NOT NULL,
    market_regime VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
    sample_count INT NOT NULL DEFAULT 0,
    effective_count INT NOT NULL DEFAULT 0,
    effectiveness_rate DECIMAL(10, 4) NOT NULL DEFAULT 0,
    avg_post_trigger_return DECIMAL(12, 4) NOT NULL DEFAULT 0,
    avg_adverse_return DECIMAL(12, 4) NOT NULL DEFAULT 0,
    learned_weight DECIMAL(10, 4) NOT NULL DEFAULT 50,
    confidence_level VARCHAR(24) NOT NULL DEFAULT 'LOW_SAMPLE',
    last_evaluated_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_trade_rule_performance (user_id, rule_code, horizon_days, market_regime),
    KEY idx_trade_rule_performance_weight (user_id, learned_weight, sample_count)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO ai_trade_rule_config (
    user_id, version_no, name, status, config_json, source_strategy_release_id
) VALUES (
    0,
    'CONDITIONAL_RULE_V1.0',
    'A股三日条件交易规则 V1',
    'ACTIVE',
    '{"version":"CONDITIONAL_RULE_V1.0","thresholds":{"strongRisePct":2.0,"sidewaysAbsPct":2.0,"weakFallPct":-3.0,"strongVolumeRatio5":1.3,"weakVolumeRatio5":1.3,"breakoutVolumeRatio20":1.5,"nearSupportPct":1.5,"nearMovingAveragePct":1.5,"sectorStrongPct":0.5,"sectorWeakPct":-0.5,"marketWeakPct":-1.0,"twoDaySidewaysPct":2.0,"targetProfitFirstPct":8.0,"targetProfitSecondPct":15.0,"technicalStopLossPct":-8.0,"actionEffectivenessBufferPct":0.3,"highVolatilityPct":5.0,"signalConditionWeight":0.55,"signalFactorWeight":0.20,"signalRuleWeight":0.15,"signalValidationWeight":0.10,"riskUnknownScore":50.0,"riskLowScore":25.0,"riskHighScore":80.0,"riskLowUpperBound":30.0,"riskMediumUpperBound":60.0,"volumeMaintainRatio":0.8,"longLowerShadowRatio":0.4,"stopFallMaxChangePct":1.0},"riskWeights":{"market":0.30,"sector":0.20,"technical":0.30,"fund":0.20},"positions":{"breakout":"30%","pullback":"20%-30%","trendConfirm":"50%","trendMax":"70%","firstSupportReduce":"减仓30%","secondSupportReduce":"减仓50%-100%","firstProfitReduce":"减仓30%","secondProfitReduce":"减仓50%"},"minimumConditions":{"T1_STRONG":3,"T1_SIDEWAYS":3,"T1_WEAK":3,"T2_TREND_STRENGTHEN":3,"T2_UNCLEAR":2,"T2_LOGIC_FAILED":2,"T3_TREND":2,"T3_REBOUND":2,"T3_FAILED":1,"BUY_BREAKOUT":3,"BUY_PULLBACK":3,"BUY_TREND_CONFIRM":3,"SELL_TARGET_PROFIT":1,"SELL_TECHNICAL_STOP":1,"SELL_LOGIC_STOP":1},"factorMappings":{"T1_STRONG":["MOMENTUM_RETURN_3D","VOLUME_RATIO_5D","TREND_MA20_DISTANCE","SECTOR_RELATIVE_STRENGTH"],"T1_SIDEWAYS":["MOMENTUM_RETURN_3D","VOLUME_RATIO_5D","VOLATILITY_10D"],"T1_WEAK":["MOMENTUM_RETURN_3D","VOLUME_RATIO_5D","TREND_MA20_DISTANCE","SECTOR_RELATIVE_STRENGTH"],"T2_TREND_STRENGTHEN":["MOMENTUM_RETURN_3D","TREND_MA5_DISTANCE","VOLUME_RATIO_5D","SECTOR_RELATIVE_STRENGTH"],"T2_UNCLEAR":["MOMENTUM_RETURN_3D","VOLUME_RATIO_5D","VOLATILITY_10D"],"T2_LOGIC_FAILED":["TREND_MA20_DISTANCE","SECTOR_RELATIVE_STRENGTH","NEWS_SENTIMENT"],"T3_TREND":["TREND_MA5_DISTANCE","TREND_MA20_DISTANCE","VOLUME_RATIO_5D","SECTOR_RELATIVE_STRENGTH"],"T3_REBOUND":["MOMENTUM_RETURN_3D","VOLUME_RATIO_5D","TREND_MA20_DISTANCE"],"T3_FAILED":["TREND_MA20_DISTANCE","MARKET_RELATIVE_STRENGTH","VOLATILITY_10D"],"BUY_BREAKOUT":["MOMENTUM_RETURN_3D","VOLUME_RATIO_5D","SECTOR_RELATIVE_STRENGTH"],"BUY_PULLBACK":["TREND_MA5_DISTANCE","TREND_MA20_DISTANCE","VOLUME_RATIO_5D"],"BUY_TREND_CONFIRM":["TREND_MA20_DISTANCE","MOMENTUM_RETURN_3D","NEWS_SENTIMENT"],"SELL_TARGET_PROFIT":["MOMENTUM_RETURN_3D","VOLATILITY_10D"],"SELL_TECHNICAL_STOP":["TREND_MA20_DISTANCE","VOLATILITY_10D"],"SELL_LOGIC_STOP":["SECTOR_RELATIVE_STRENGTH","NEWS_SENTIMENT","MARKET_RELATIVE_STRENGTH"]}}',
    NULL
) ON DUPLICATE KEY UPDATE id = id;
