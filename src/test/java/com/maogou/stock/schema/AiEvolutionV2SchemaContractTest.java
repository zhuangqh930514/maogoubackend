package com.maogou.stock.schema;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AiEvolutionV2SchemaContractTest {

    private static final String MIGRATION_RESOURCE = "/db/20260712_ai_evolution_v2.sql";
    private static final String FULL_SCHEMA_RESOURCE = "/db/schema.sql";
    private static final String V2_MARKER = "-- 猫狗智投 AI evolution V2.";

    @Test
    void migrationDefinesAuditableStorageForTasksSevenThroughEighteen() throws Exception {
        String sql = readResource(MIGRATION_RESOURCE);

        List<String> tables = List.of(
                "ai_data_batch",
                "ai_sample_v2",
                "ai_factor_value_v2",
                "ai_trading_calendar",
                "ai_training_dataset",
                "ai_model_version",
                "ai_strategy_release",
                "ai_prediction_v2",
                "ai_label_v2",
                "ai_label_cost_evidence",
                "ai_training_dataset_item",
                "ai_factor_performance_v2",
                "ai_drift_event",
                "ai_walk_forward_run",
                "ai_walk_forward_fold",
                "ai_walk_forward_baseline",
                "ai_portfolio_backtest_run",
                "ai_portfolio_backtest_daily",
                "ai_portfolio_backtest_trade",
                "ai_portfolio_backtest_position",
                "ai_pipeline_run",
                "ai_pipeline_step",
                "ai_shadow_evaluation",
                "ai_shadow_evaluation_item",
                "ai_strategy_governance_event",
                "ai_research_daily_report"
        );
        for (String table : tables) {
            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS " + table);
        }
    }

    @Test
    void predictionAndLabelContractsCapturePointInTimeAndExecutionEvidence() throws Exception {
        String sql = readResource(MIGRATION_RESOURCE);
        String prediction = tableDefinition(sql, "ai_prediction_v2");
        String calendar = tableDefinition(sql, "ai_trading_calendar");
        String label = tableDefinition(sql, "ai_label_v2");
        String costEvidence = tableDefinition(sql, "ai_label_cost_evidence");

        assertThat(prediction)
                .contains("stock_code VARCHAR(16) NOT NULL")
                .contains("trade_date DATE NOT NULL")
                .contains("sample_phase VARCHAR(32) NOT NULL")
                .contains("inference_mode VARCHAR(32) NOT NULL")
                .contains("input_fingerprint VARCHAR(128) NOT NULL")
                .contains("strategy_release_id BIGINT NOT NULL")
                .contains("model_version_id BIGINT NULL")
                .contains("UNIQUE KEY uk_ai_prediction_v2_business (user_id, stock_code, trade_date, sample_phase, horizon_days, strategy_release_id, inference_mode, input_fingerprint)")
                .contains("KEY idx_ai_prediction_v2_trade_rank (user_id, trade_date, sample_phase, inference_mode, action_bucket, rank_no)")
                .contains("CONSTRAINT fk_ai_prediction_v2_sample FOREIGN KEY (sample_id) REFERENCES ai_sample_v2 (id)")
                .contains("CONSTRAINT fk_ai_prediction_v2_release FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id)")
                .contains("CONSTRAINT fk_ai_prediction_v2_model FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id)");

        assertThat(calendar)
                .contains("calendar_version VARCHAR(64) NOT NULL")
                .contains("source_fingerprint VARCHAR(128) NOT NULL")
                .contains("UNIQUE KEY uk_ai_trading_calendar_version (market_code, trade_date, calendar_version)")
                .contains("KEY idx_ai_trading_calendar_lookup (market_code, calendar_version, is_trade_day, trade_date)");

        assertThat(label)
                .contains("entry_calendar_id BIGINT NULL")
                .contains("exit_calendar_id BIGINT NULL")
                .contains("calendar_version VARCHAR(64) NOT NULL")
                .contains("input_fingerprint VARCHAR(128) NOT NULL")
                .contains("CONSTRAINT fk_ai_label_v2_prediction FOREIGN KEY (prediction_id) REFERENCES ai_prediction_v2 (id)")
                .contains("CONSTRAINT fk_ai_label_v2_sample FOREIGN KEY (sample_id) REFERENCES ai_sample_v2 (id)")
                .contains("CONSTRAINT fk_ai_label_v2_entry_calendar FOREIGN KEY (entry_calendar_id) REFERENCES ai_trading_calendar (id)")
                .contains("CONSTRAINT fk_ai_label_v2_exit_calendar FOREIGN KEY (exit_calendar_id) REFERENCES ai_trading_calendar (id)");

        assertThat(costEvidence)
                .contains("label_id BIGINT NOT NULL")
                .contains("cost_model_version VARCHAR(64) NOT NULL")
                .contains("buy_commission_rate DECIMAL(12, 8) NOT NULL")
                .contains("sell_commission_rate DECIMAL(12, 8) NOT NULL")
                .contains("stamp_duty_rate DECIMAL(12, 8) NOT NULL")
                .contains("slippage_bps DECIMAL(12, 4) NOT NULL")
                .contains("total_cost_amount DECIMAL(20, 6) NOT NULL")
                .contains("evidence_json MEDIUMTEXT NOT NULL")
                .contains("source_fingerprint VARCHAR(128) NOT NULL")
                .contains("UNIQUE KEY uk_ai_label_cost_evidence_version (label_id, cost_model_version)")
                .contains("CONSTRAINT fk_ai_label_cost_evidence_label FOREIGN KEY (label_id) REFERENCES ai_label_v2 (id)");
    }

    @Test
    void factorDriftAndWalkForwardContractsUseCompleteWindows() throws Exception {
        String sql = readResource(MIGRATION_RESOURCE);
        String performance = tableDefinition(sql, "ai_factor_performance_v2");
        String drift = tableDefinition(sql, "ai_drift_event");
        String run = tableDefinition(sql, "ai_walk_forward_run");
        String fold = tableDefinition(sql, "ai_walk_forward_fold");
        String baseline = tableDefinition(sql, "ai_walk_forward_baseline");

        assertThat(performance)
                .contains("input_fingerprint VARCHAR(128) NOT NULL")
                .contains("UNIQUE KEY uk_ai_factor_perf_v2_window (user_id, factor_code, factor_version, horizon_days, market_regime, window_type, window_start_date, window_end_date)")
                .contains("KEY idx_ai_factor_perf_v2_window (user_id, factor_code, factor_version, window_start_date, window_end_date)");

        assertThat(drift)
                .contains("event_fingerprint VARCHAR(128) NOT NULL")
                .contains("window_start_date DATE NOT NULL")
                .contains("window_end_date DATE NOT NULL")
                .contains("metric_name VARCHAR(64) NOT NULL")
                .contains("evidence_json MEDIUMTEXT NOT NULL")
                .contains("UNIQUE KEY uk_ai_drift_event_fingerprint (user_id, event_fingerprint)")
                .contains("KEY idx_ai_drift_event_open (user_id, status, severity, detected_at)")
                .contains("CONSTRAINT fk_ai_drift_event_performance FOREIGN KEY (factor_performance_id) REFERENCES ai_factor_performance_v2 (id)");

        assertThat(run)
                .contains("training_dataset_id BIGINT NOT NULL")
                .contains("input_fingerprint VARCHAR(128) NOT NULL")
                .contains("UNIQUE KEY uk_ai_walk_forward_run_key (user_id, run_key)")
                .contains("CONSTRAINT fk_ai_walk_forward_run_dataset FOREIGN KEY (training_dataset_id) REFERENCES ai_training_dataset (id)");
        assertThat(fold)
                .contains("UNIQUE KEY uk_ai_walk_forward_fold_no (walk_forward_run_id, fold_no)")
                .contains("CONSTRAINT chk_ai_walk_forward_fold_dates CHECK (train_start_date <= train_end_date AND train_end_date < validation_start_date AND validation_start_date <= validation_end_date AND validation_end_date < test_start_date AND test_start_date <= test_end_date)")
                .contains("CONSTRAINT fk_ai_walk_forward_fold_run FOREIGN KEY (walk_forward_run_id) REFERENCES ai_walk_forward_run (id)");
        assertThat(baseline)
                .contains("baseline_key VARCHAR(96) NOT NULL")
                .contains("UNIQUE KEY uk_ai_walk_forward_baseline_key (walk_forward_fold_id, baseline_key)")
                .contains("CONSTRAINT fk_ai_walk_forward_baseline_fold FOREIGN KEY (walk_forward_fold_id) REFERENCES ai_walk_forward_fold (id)");
    }

    @Test
    void portfolioBacktestAndStrategyGovernanceAreReproducibleAndAuditable() throws Exception {
        String sql = readResource(MIGRATION_RESOURCE);
        String backtestRun = tableDefinition(sql, "ai_portfolio_backtest_run");
        String daily = tableDefinition(sql, "ai_portfolio_backtest_daily");
        String trade = tableDefinition(sql, "ai_portfolio_backtest_trade");
        String position = tableDefinition(sql, "ai_portfolio_backtest_position");
        String release = tableDefinition(sql, "ai_strategy_release");
        String governance = tableDefinition(sql, "ai_strategy_governance_event");

        assertThat(backtestRun)
                .contains("engine_version VARCHAR(64) NOT NULL")
                .contains("config_fingerprint VARCHAR(128) NOT NULL")
                .contains("input_fingerprint VARCHAR(128) NOT NULL")
                .contains("random_seed BIGINT NOT NULL")
                .contains("UNIQUE KEY uk_ai_portfolio_backtest_run_key (user_id, run_key)")
                .contains("KEY idx_ai_portfolio_backtest_release (strategy_release_id, start_trade_date, end_trade_date, status)")
                .contains("CONSTRAINT fk_ai_portfolio_backtest_dataset FOREIGN KEY (training_dataset_id) REFERENCES ai_training_dataset (id)")
                .contains("CONSTRAINT fk_ai_portfolio_backtest_release FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id)");
        assertThat(daily)
                .contains("UNIQUE KEY uk_ai_portfolio_backtest_daily_date (backtest_run_id, trade_date)")
                .contains("CONSTRAINT fk_ai_portfolio_backtest_daily_run FOREIGN KEY (backtest_run_id) REFERENCES ai_portfolio_backtest_run (id)");
        assertThat(trade)
                .contains("trade_key VARCHAR(128) NOT NULL")
                .contains("UNIQUE KEY uk_ai_portfolio_backtest_trade_key (backtest_run_id, trade_key)")
                .contains("CONSTRAINT fk_ai_portfolio_backtest_trade_prediction FOREIGN KEY (prediction_id) REFERENCES ai_prediction_v2 (id)");
        assertThat(position)
                .contains("UNIQUE KEY uk_ai_portfolio_backtest_position_stock (backtest_run_id, trade_date, stock_code)")
                .contains("KEY idx_ai_portfolio_backtest_position_weight (backtest_run_id, trade_date, weight)")
                .contains("CONSTRAINT fk_ai_portfolio_backtest_position_run FOREIGN KEY (backtest_run_id) REFERENCES ai_portfolio_backtest_run (id)");

        assertThat(release)
                .contains("model_version_id BIGINT NULL")
                .contains("active_champion_guard TINYINT GENERATED ALWAYS AS (CASE WHEN release_role = 'CHAMPION' AND status = 'ACTIVE' THEN 1 ELSE NULL END) STORED")
                .contains("UNIQUE KEY uk_ai_strategy_release_active_champion (user_id, active_champion_guard)")
                .contains("CONSTRAINT fk_ai_strategy_release_model FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id)");
        assertThat(governance)
                .contains("event_key VARCHAR(160) NOT NULL")
                .contains("policy_version VARCHAR(64) NOT NULL")
                .contains("evidence_json MEDIUMTEXT NOT NULL")
                .contains("UNIQUE KEY uk_ai_strategy_governance_event_key (user_id, event_key)")
                .contains("KEY idx_ai_strategy_governance_release (strategy_release_id, occurred_at)")
                .contains("CONSTRAINT fk_ai_strategy_governance_release FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id)")
                .contains("CONSTRAINT fk_ai_strategy_governance_walk_run FOREIGN KEY (walk_forward_run_id) REFERENCES ai_walk_forward_run (id)")
                .contains("CONSTRAINT fk_ai_strategy_governance_backtest FOREIGN KEY (backtest_run_id) REFERENCES ai_portfolio_backtest_run (id)")
                .contains("CONSTRAINT fk_ai_strategy_governance_shadow FOREIGN KEY (shadow_evaluation_id) REFERENCES ai_shadow_evaluation (id)");
    }

    @Test
    void trainingShadowPipelineAndDailyReportContractsPreserveLineageAndIdempotency() throws Exception {
        String sql = readResource(MIGRATION_RESOURCE);
        String dataset = tableDefinition(sql, "ai_training_dataset");
        String datasetItem = tableDefinition(sql, "ai_training_dataset_item");
        String model = tableDefinition(sql, "ai_model_version");
        String shadow = tableDefinition(sql, "ai_shadow_evaluation");
        String shadowItem = tableDefinition(sql, "ai_shadow_evaluation_item");
        String pipeline = tableDefinition(sql, "ai_pipeline_run");
        String pipelineStep = tableDefinition(sql, "ai_pipeline_step");
        String report = tableDefinition(sql, "ai_research_daily_report");

        assertThat(dataset)
                .contains("lineage_fingerprint VARCHAR(128) NOT NULL")
                .contains("source_query_json MEDIUMTEXT NOT NULL")
                .contains("selection_policy_json MEDIUMTEXT NOT NULL")
                .contains("UNIQUE KEY uk_ai_training_dataset_version (user_id, dataset_key, version_no)")
                .contains("UNIQUE KEY uk_ai_training_dataset_lineage (user_id, lineage_fingerprint)");
        assertThat(datasetItem)
                .contains("sample_id BIGINT NOT NULL")
                .contains("label_id BIGINT NOT NULL")
                .contains("split_type VARCHAR(16) NOT NULL")
                .contains("feature_fingerprint VARCHAR(128) NOT NULL")
                .contains("label_fingerprint VARCHAR(128) NOT NULL")
                .contains("UNIQUE KEY uk_ai_training_dataset_item_lineage (training_dataset_id, sample_id, label_id)")
                .contains("CONSTRAINT fk_ai_training_dataset_item_sample FOREIGN KEY (sample_id) REFERENCES ai_sample_v2 (id)")
                .contains("CONSTRAINT fk_ai_training_dataset_item_label FOREIGN KEY (label_id) REFERENCES ai_label_v2 (id)");
        assertThat(model)
                .contains("training_dataset_id BIGINT NOT NULL")
                .contains("random_seed BIGINT NOT NULL")
                .contains("trainer_version VARCHAR(64) NOT NULL")
                .contains("CONSTRAINT fk_ai_model_version_dataset FOREIGN KEY (training_dataset_id) REFERENCES ai_training_dataset (id)");

        assertThat(shadow)
                .contains("champion_release_id BIGINT NOT NULL")
                .contains("challenger_release_id BIGINT NOT NULL")
                .contains("window_start_date DATE NOT NULL")
                .contains("window_end_date DATE NOT NULL")
                .contains("input_fingerprint VARCHAR(128) NOT NULL")
                .contains("UNIQUE KEY uk_ai_shadow_evaluation_window (user_id, champion_release_id, challenger_release_id, window_start_date, window_end_date, evaluation_version)")
                .contains("KEY idx_ai_shadow_evaluation_candidate (user_id, decision_status, evaluated_at)");
        assertThat(shadowItem)
                .contains("champion_prediction_id BIGINT NOT NULL")
                .contains("challenger_prediction_id BIGINT NOT NULL")
                .contains("UNIQUE KEY uk_ai_shadow_evaluation_item_sample (shadow_evaluation_id, sample_id, horizon_days)")
                .contains("CONSTRAINT chk_ai_shadow_evaluation_predictions CHECK (champion_prediction_id <> challenger_prediction_id)")
                .contains("CONSTRAINT fk_ai_shadow_eval_item_sample FOREIGN KEY (sample_id) REFERENCES ai_sample_v2 (id)");

        assertThat(pipeline)
                .contains("data_batch_id BIGINT NULL")
                .contains("strategy_release_id BIGINT NULL")
                .contains("model_version_id BIGINT NULL")
                .contains("input_fingerprint VARCHAR(128) NOT NULL")
                .contains("KEY idx_ai_pipeline_run_trade_status (user_id, trade_date, pipeline_type, status)")
                .contains("CONSTRAINT fk_ai_pipeline_run_batch FOREIGN KEY (data_batch_id) REFERENCES ai_data_batch (id)")
                .contains("CONSTRAINT fk_ai_pipeline_run_release FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id)")
                .contains("CONSTRAINT fk_ai_pipeline_run_model FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id)");
        assertThat(pipelineStep)
                .contains("checkpoint_json MEDIUMTEXT NULL")
                .contains("output_fingerprint VARCHAR(128) NULL")
                .contains("CONSTRAINT fk_ai_pipeline_step_run FOREIGN KEY (pipeline_run_id) REFERENCES ai_pipeline_run (id)");

        assertThat(report)
                .contains("idempotency_key VARCHAR(160) NOT NULL")
                .contains("is_current TINYINT NOT NULL DEFAULT 1")
                .contains("current_report_guard TINYINT GENERATED ALWAYS AS (CASE WHEN is_current = 1 THEN 1 ELSE NULL END) STORED")
                .contains("supersedes_report_id BIGINT NULL")
                .contains("UNIQUE KEY uk_ai_daily_report_idempotency (user_id, idempotency_key)")
                .contains("UNIQUE KEY uk_ai_daily_report_current (user_id, trade_date, current_report_guard)")
                .contains("KEY idx_ai_daily_report_current (user_id, is_current, trade_date)")
                .contains("CONSTRAINT fk_ai_daily_report_pipeline FOREIGN KEY (pipeline_run_id) REFERENCES ai_pipeline_run (id)")
                .contains("CONSTRAINT fk_ai_daily_report_release FOREIGN KEY (strategy_release_id) REFERENCES ai_strategy_release (id)")
                .contains("CONSTRAINT fk_ai_daily_report_model FOREIGN KEY (model_version_id) REFERENCES ai_model_version (id)")
                .contains("CONSTRAINT fk_ai_daily_report_supersedes FOREIGN KEY (supersedes_report_id) REFERENCES ai_research_daily_report (id)");
    }

    @Test
    void migrationUsesForeignKeysAndNeverUsesZeroAsAParentSentinel() throws Exception {
        String sql = readResource(MIGRATION_RESOURCE);
        String sample = tableDefinition(sql, "ai_sample_v2");
        String factor = tableDefinition(sql, "ai_factor_value_v2");

        assertThat(sample)
                .contains("CONSTRAINT fk_ai_sample_v2_batch FOREIGN KEY (data_batch_id) REFERENCES ai_data_batch (id)");
        assertThat(factor)
                .contains("input_fingerprint VARCHAR(128) NOT NULL")
                .contains("CONSTRAINT fk_ai_factor_value_v2_sample FOREIGN KEY (sample_id) REFERENCES ai_sample_v2 (id)");

        Matcher zeroParentDefault = Pattern.compile(
                "(?im)^\\s*[a-z0-9_]+_id\\s+BIGINT[^,\\n]*DEFAULT\\s+0"
        ).matcher(sql);
        assertThat(zeroParentDefault.find())
                .as("parent references must be required or nullable, never DEFAULT 0")
                .isFalse();
    }

    @Test
    void fullSchemaContainsExactlyOneByteEquivalentV2Block() throws Exception {
        String migration = readResource(MIGRATION_RESOURCE).trim();
        String fullSchema = readResource(FULL_SCHEMA_RESOURCE);

        assertThat(countOccurrences(fullSchema, V2_MARKER)).isEqualTo(1);
        assertThat(fullSchema.substring(fullSchema.indexOf(V2_MARKER)).trim()).isEqualTo(migration);
    }

    @Test
    void fullSchemaUsesTheDatabaseSelectedByTheCaller() throws Exception {
        String fullSchema = readResource(FULL_SCHEMA_RESOURCE);

        assertThat(fullSchema)
                .doesNotContainIgnoringCase("CREATE DATABASE")
                .doesNotContainIgnoringCase("USE maogou_stock");
    }

    private String readResource(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertThat(input).as(resource + " resource").isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String tableDefinition(String sql, String table) {
        Pattern pattern = Pattern.compile(
                "(?is)CREATE TABLE IF NOT EXISTS\\s+" + Pattern.quote(table)
                        + "\\s*\\((.*?)\\)\\s*ENGINE\\s*=\\s*InnoDB[^;]*;"
        );
        Matcher matcher = pattern.matcher(sql);
        assertThat(matcher.find()).as("table definition for " + table).isTrue();
        return matcher.group(1);
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
