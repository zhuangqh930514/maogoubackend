package com.maogou.stock.schema;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AiResearchMigrationMatrixTest {

    private static final String MATRIX_RESOURCE = "/db/ai-research-table-matrix.txt";
    private static final Set<String> VALID_ACTIONS = Set.of("KEEP", "DROP", "DROP_AND_RECREATE");

    // Frozen from the pre-unification schema. This is migration evidence, not the target schema contract.
    private static final Set<String> LEGACY_AI_TABLES = Set.of(
            "ai_model_config",
            "ai_prompt_template",
            "ai_analysis_report",
            "ai_trade_rule_config",
            "ai_trade_plan_review",
            "ai_trade_rule_performance",
            "ai_analysis_outcome",
            "ai_analysis_decision",
            "ai_analysis_factor_hit",
            "ai_factor_stat",
            "ai_strategy_version",
            "ai_strategy_evolution_log",
            "ai_chat_session",
            "ai_chat_message",
            "ai_user_memory",
            "ai_stock_universe",
            "ai_prediction_sample",
            "ai_factor_definition",
            "ai_factor_value",
            "ai_factor_value_v2",
            "ai_prediction_result",
            "ai_prediction_label",
            "ai_strategy_experiment",
            "ai_backtest_run",
            "ai_backtest_trade",
            "ai_learning_job_log",
            "ai_model_eval_run",
            "ai_daily_insight_snapshot",
            "ai_daily_insight_item",
            "ai_data_batch",
            "ai_sample_v2",
            "ai_trading_calendar",
            "ai_training_dataset",
            "ai_model_version",
            "ai_strategy_release",
            "ai_prediction_v2",
            "ai_label_v2",
            "ai_label_cost_evidence",
            "ai_training_dataset_item",
            "ai_factor_performance_v2",
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
            "ai_drift_event",
            "ai_strategy_governance_event",
            "ai_research_daily_report"
    );

    @Test
    void migrationMatrixCoversEveryLegacyAiTableExactlyOnce() throws Exception {
        Map<String, MigrationEntry> entries = loadMigrationMatrix();

        assertThat(LEGACY_AI_TABLES).hasSize(54);
        assertThat(entries.keySet()).containsExactlyInAnyOrderElementsOf(LEGACY_AI_TABLES);
        assertThat(entries.values()).allSatisfy(entry -> {
            assertThat(entry.action()).isIn(VALID_ACTIONS);
            assertThat(entry.reason()).isNotBlank();
        });
    }

    @Test
    void parserRejectsDuplicateTableEntries() {
        String matrix = "KEEP|ai_model_config|first\nDROP|ai_model_config|duplicate";

        assertThatIllegalArgumentException()
                .isThrownBy(() -> parseMatrix(matrix))
                .withMessageContaining("duplicate table")
                .withMessageContaining("ai_model_config");
    }

    @Test
    void parserRejectsInvalidActionsAndMalformedRows() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parseMatrix("RENAME|ai_model_config|invalid action"))
                .withMessageContaining("invalid action");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parseMatrix("KEEP|ai_model_config"))
                .withMessageContaining("expected ACTION|TABLE|REASON");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> parseMatrix("KEEP|ai_model_config|"))
                .withMessageContaining("reason must not be blank");
    }

    private static Map<String, MigrationEntry> loadMigrationMatrix() throws IOException {
        try (InputStream input = AiResearchMigrationMatrixTest.class.getResourceAsStream(MATRIX_RESOURCE)) {
            assertThat(input)
                    .as("migration matrix resource %s must exist", MATRIX_RESOURCE)
                    .isNotNull();
            return parseMatrix(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Map<String, MigrationEntry> parseMatrix(String content) {
        Map<String, MigrationEntry> entries = new LinkedHashMap<>();
        List<String> lines = content.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            String[] parts = line.split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("line " + (index + 1)
                        + ": expected ACTION|TABLE|REASON");
            }

            String action = parts[0].trim();
            String table = parts[1].trim();
            String reason = parts[2].trim();
            if (!VALID_ACTIONS.contains(action)) {
                throw new IllegalArgumentException("line " + (index + 1) + ": invalid action " + action);
            }
            if (table.isBlank()) {
                throw new IllegalArgumentException("line " + (index + 1) + ": table must not be blank");
            }
            if (reason.isBlank()) {
                throw new IllegalArgumentException("line " + (index + 1) + ": reason must not be blank");
            }
            if (entries.putIfAbsent(table, new MigrationEntry(action, table, reason)) != null) {
                throw new IllegalArgumentException("line " + (index + 1) + ": duplicate table " + table);
            }
        }
        return entries;
    }

    private record MigrationEntry(String action, String table, String reason) {
    }
}
