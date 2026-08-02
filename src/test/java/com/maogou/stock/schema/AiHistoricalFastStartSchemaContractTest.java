package com.maogou.stock.schema;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiHistoricalFastStartSchemaContractTest {

    private static final List<String> TABLES = List.of(
            "ai_historical_backfill_run",
            "ai_historical_backfill_shard",
            "ai_raw_evidence_manifest",
            "ai_data_quarantine",
            "ai_training_readiness_snapshot",
            "ai_artifact_package_registry"
    );

    @Test
    void mysqlH2AndMigrationContainTheSameFastStartTables() throws Exception {
        String mysql = read("src/main/resources/db/schema.sql");
        String h2 = read("src/main/resources/db/schema-h2-body.sql");
        String migration = read("src/main/resources/db/20260805_ai_historical_fast_start.sql");
        for (String table : TABLES) {
            assertThat(mysql).contains("CREATE TABLE IF NOT EXISTS " + table);
            assertThat(h2).contains("CREATE TABLE IF NOT EXISTS " + table);
            assertThat(migration).contains("CREATE TABLE IF NOT EXISTS " + table);
        }
        assertThat(mysql).contains("quarantine_fingerprint VARCHAR(128) NOT NULL");
        assertThat(h2).contains("quarantine_fingerprint VARCHAR(128) NOT NULL");
        assertThat(mysql).contains("backfill_run_id BIGINT NULL");
        assertThat(h2).contains("backfill_run_id BIGINT NULL");
        assertThat(migration).contains("idx_data_batch_backfill_trade");
    }

    @Test
    void legacyQuarantineMigrationBackfillsBeforeEnforcingUniqueNotNull() throws Exception {
        String migration = read("src/main/resources/db/20260805_ai_historical_fast_start.sql");
        int addColumn = migration.indexOf("ADD COLUMN quarantine_fingerprint");
        int backfill = migration.indexOf("UPDATE ai_data_quarantine\nSET quarantine_fingerprint");
        int duplicateRepair = migration.indexOf("Keep all historical rows");
        int dropLegacyIndex = migration.indexOf("DROP INDEX uk_data_quarantine_fact");
        int modifyNotNull = migration.indexOf("MODIFY COLUMN quarantine_fingerprint VARCHAR(128) NOT NULL");
        int addNewIndex = migration.indexOf("ADD UNIQUE KEY uk_data_quarantine_fingerprint", modifyNotNull);

        assertThat(addColumn).isGreaterThanOrEqualTo(0);
        assertThat(backfill).isGreaterThan(addColumn);
        assertThat(duplicateRepair).isGreaterThan(backfill);
        assertThat(dropLegacyIndex).isGreaterThan(duplicateRepair);
        assertThat(modifyNotNull).isGreaterThan(dropLegacyIndex);
        assertThat(addNewIndex).isGreaterThan(modifyNotNull);
    }

    @Test
    void independentMatrixListsAllTablesOnce() throws Exception {
        String matrix = read("src/main/resources/db/ai-historical-fast-start-table-matrix.txt");
        for (String table : TABLES) {
            assertThat(matrix).contains(table);
            assertThat(countOccurrences(matrix, table)).isEqualTo(1);
        }
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static long countOccurrences(String text, String value) {
        return text.lines().filter(line -> line.contains(value)).count();
    }
}
