package com.maogou.stock.schema;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionalTradeStrategySchemaContractTest {

    @Test
    void migrationDefinesVersionedRulesReviewsAndLearningPerformance() throws Exception {
        String migration = read("/db/20260714_conditional_trade_strategy.sql");

        assertThat(migration)
                .contains("column_name = 'conditional_strategy'")
                .contains("ALTER TABLE ai_analysis_report ADD COLUMN conditional_strategy MEDIUMTEXT")
                .contains("CREATE TABLE IF NOT EXISTS ai_trade_rule_config")
                .contains("CREATE TABLE IF NOT EXISTS ai_trade_plan_review")
                .contains("CREATE TABLE IF NOT EXISTS ai_trade_rule_performance")
                .contains("UNIQUE KEY uk_trade_plan_review_report_horizon (user_id, report_id, horizon_days)")
                .contains("UNIQUE KEY uk_trade_rule_performance (user_id, rule_code, horizon_days, market_regime)")
                .contains("CONDITIONAL_RULE_V1.0")
                .contains("minimumConditions")
                .contains("factorMappings")
                .contains("ON DUPLICATE KEY UPDATE id = id");
    }

    @Test
    void fullSchemasExposeTheSameConditionalStrategyContract() throws Exception {
        for (String resource : new String[]{"/db/schema.sql", "/db/schema-h2-body.sql"}) {
            String schema = read(resource);
            assertThat(schema)
                    .contains("conditional_strategy")
                    .contains("CREATE TABLE IF NOT EXISTS ai_trade_rule_config")
                    .contains("CREATE TABLE IF NOT EXISTS ai_trade_plan_review")
                    .contains("CREATE TABLE IF NOT EXISTS ai_trade_rule_performance");
        }
    }

    private static String read(String resource) throws Exception {
        try (InputStream input = ConditionalTradeStrategySchemaContractTest.class.getResourceAsStream(resource)) {
            assertThat(input).as("resource %s", resource).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
