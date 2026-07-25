package com.maogou.stock.schema;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiDailyDecisionPlanSchemaContractTest {

    @Test
    void freshSchemasContainSeparateDeterministicDecisionPlanLineage() throws Exception {
        for (String path : List.of("src/main/resources/db/schema.sql", "src/main/resources/db/schema-h2-body.sql")) {
            String schema = Files.readString(Path.of(path));
            assertThat(schema).contains("CREATE TABLE IF NOT EXISTS ai_daily_decision_plan (");
            assertThat(schema).contains("CREATE TABLE IF NOT EXISTS ai_daily_decision_plan_review (");
            assertThat(schema).contains("uk_daily_decision_plan_item_horizon");
        }
    }
}
