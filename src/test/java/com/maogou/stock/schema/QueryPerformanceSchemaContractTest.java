package com.maogou.stock.schema;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QueryPerformanceSchemaContractTest {

    private static final List<String> INDEXES = List.of(
            "idx_watch_stock_user_list",
            "idx_watch_stock_user_group_list",
            "idx_trade_record_user_list",
            "idx_trade_record_user_active_stock",
            "idx_sample_analysis_lookup",
            "idx_sample_lab_list",
            "idx_sample_pending_labels",
            "idx_sample_training_readiness",
            "idx_sample_training_source_summary",
            "idx_sample_training_source_page",
            "idx_label_training_readiness",
            "idx_label_evaluation_candidate",
            "idx_label_training_source_summary",
            "idx_prediction_evaluation_candidates",
            "idx_evaluation_version_prediction",
            "idx_pipeline_owner_type_time");

    @Test
    void freshSchemasAndRepeatableMigrationDefineTheSamePerformanceIndexes() throws Exception {
        String mysqlSchema = Files.readString(Path.of("src/main/resources/db/schema.sql"));
        String h2Schema = Files.readString(Path.of("src/main/resources/db/schema-h2-body.sql"));
        String migration = Files.readString(
                Path.of("src/main/resources/db/20260715_query_performance_indexes.sql"));

        for (String index : INDEXES) {
            assertThat(mysqlSchema).contains(index);
            assertThat(h2Schema).contains(index);
            assertThat(migration).contains(index, "information_schema.statistics");
        }
    }
}
