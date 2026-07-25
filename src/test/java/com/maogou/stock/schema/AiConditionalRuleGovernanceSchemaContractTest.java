package com.maogou.stock.schema;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiConditionalRuleGovernanceSchemaContractTest {

    @Test
    void freshSchemasKeepConditionalRulesSeparateFromModelReleaseGovernance() throws Exception {
        for (String path : List.of("src/main/resources/db/schema.sql", "src/main/resources/db/schema-h2-body.sql")) {
            String schema = Files.readString(Path.of(path));
            assertThat(schema).contains("CREATE TABLE IF NOT EXISTS ai_conditional_rule_experiment (");
            assertThat(schema).contains("CREATE TABLE IF NOT EXISTS ai_conditional_rule_experiment_fold (");
            assertThat(schema).contains("CREATE TABLE IF NOT EXISTS ai_conditional_rule_shadow_observation (");
            assertThat(schema).contains("CREATE TABLE IF NOT EXISTS ai_conditional_rule_governance_event (");
            assertThat(schema).contains("uk_trade_rule_config_single_active");
        }
    }

    @Test
    void productionMigrationDoesNotBorrowModelWalkForwardTables() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/20260725_conditional_rule_governance.sql"));
        assertThat(migration).contains("ai_conditional_rule_experiment");
        assertThat(migration).contains("ai_conditional_rule_shadow_observation");
        assertThat(migration).contains("uk_conditional_rule_experiment_key");
        assertThat(migration).contains("uk_conditional_rule_shadow_observation_key");
        assertThat(migration).doesNotContain("INSERT INTO ai_walk_forward_run");
        assertThat(migration).doesNotContain("INSERT INTO ai_shadow_evaluation");

        String activeGuardMigration = Files.readString(Path.of("src/main/resources/db/20260725_conditional_rule_active_guard.sql"));
        assertThat(activeGuardMigration).contains("uk_trade_rule_config_single_active");
        assertThat(activeGuardMigration).contains("multiple ACTIVE rules");
    }
}
