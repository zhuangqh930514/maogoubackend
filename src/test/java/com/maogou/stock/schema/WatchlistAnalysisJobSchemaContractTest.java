package com.maogou.stock.schema;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WatchlistAnalysisJobSchemaContractTest {

    @Test
    void keepsPersistentWatchlistJobSchemaInMysqlH2AndMigrationResources() throws Exception {
        for (String resource : new String[]{
                "src/main/resources/db/schema.sql",
                "src/main/resources/db/schema-h2-body.sql",
                "src/main/resources/db/20260724_watchlist_analysis_job.sql"
        }) {
            String sql = Files.readString(Path.of(resource));

            assertThat(sql).contains("CREATE TABLE IF NOT EXISTS ai_watchlist_analysis_job");
            assertThat(sql).contains("UNIQUE KEY uk_watchlist_analysis_active (active_key)");
            assertThat(sql).contains("idx_watchlist_analysis_user_time (user_id, created_at, id)");
            assertThat(sql).contains("current_stock_code");
            assertThat(sql).contains("issue_details");
        }
    }
}
