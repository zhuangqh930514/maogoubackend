package com.maogou.stock.schema;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = false)
class AiResearchUniverseSourceLineageMigrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("maogou_universe_lineage_test")
            .withUsername("maogou")
            .withPassword("maogou-test")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

    @BeforeEach
    void reset() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                List<String> tables = new ArrayList<>();
                try (ResultSet rows = statement.executeQuery("SHOW TABLES")) {
                    while (rows.next()) {
                        tables.add(rows.getString(1));
                    }
                }
                for (String table : tables) {
                    statement.execute("DROP TABLE IF EXISTS `" + table + "`");
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            execute(connection, "db/legacy-ai-research-schema.sql");
            execute(connection, "db/20260714_ai_research_unified.sql");
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE ai_research_universe_item_lineage");
            }
        }
    }

    @Test
    void safelyAddsImmutableUserSourceLineageToExistingUnifiedSchema() throws Exception {
        try (Connection connection = MYSQL.createConnection("")) {
            execute(connection, "db/20260725_research_universe_source_lineage.sql");
            execute(connection, "db/20260725_research_universe_source_lineage.sql");

            assertThat(tableExists(connection, "ai_research_universe_item_lineage")).isTrue();
            assertThat(indexes(connection, "ai_research_universe_item_lineage"))
                    .contains("primary", "uk_universe_item_lineage_source",
                            "idx_universe_item_lineage_owner", "idx_universe_item_lineage_active");
            assertThat(importedForeignKeys(connection, "ai_research_universe_item_lineage"))
                    .contains("fk_universe_item_lineage_item");

            seedItem(connection);
            insertLineage(connection, 31L, "USER_WATCHLIST", 5L, 91L, 1);
            assertThatThrownBy(() -> insertLineage(connection, 31L, "USER_WATCHLIST", 5L, 91L, 1))
                    .satisfies(error -> assertThat(sqlState(error)).isEqualTo("23000"));
            assertThatThrownBy(() -> insertLineage(connection, 999L, "USER_WATCHLIST", 5L, 92L, 1))
                    .satisfies(error -> assertThat(sqlState(error)).isEqualTo("23000"));
        }
    }

    @Test
    void h2InitializationSchemaIncludesLineageTableForLocalTests() throws Exception {
        try (Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:universe_lineage_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "")) {
            execute(connection, "db/schema-h2-body.sql");
            assertThat(tableExists(connection, "ai_research_universe_item_lineage")).isTrue();
            assertThat(indexes(connection, "ai_research_universe_item_lineage"))
                    .contains("idx_universe_item_lineage_owner");
            assertThat(hasUniqueIndexFor(connection, "ai_research_universe_item_lineage",
                    "universe_item_id", "source_type", "owner_user_id", "source_record_id")).isTrue();
        }
    }

    private static void seedItem(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO ai_research_universe
                        (id, universe_code, universe_name, market_code, selection_policy_json, minimum_stock_count, enabled)
                    VALUES (11, 'LINEAGE_TEST', '来源测试池', 'CN_A', '{}', 1, 1)
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_research_universe_snapshot
                        (id, research_universe_id, trade_date, as_of_time, universe_version, calendar_version,
                         source_fingerprint, item_count, quality_status, status)
                    VALUES (21, 11, '2026-07-25', '2026-07-25 16:00:00.000', 'LINEAGE/2026-07-25/R0001',
                            'calendar-test', 'snapshot-fingerprint', 1, 'READY', 'FINALIZED')
                    """);
            statement.executeUpdate("""
                    INSERT INTO ai_research_universe_item
                        (id, universe_snapshot_id, stock_code, stock_name, listed_status, source_type,
                         included, effective_from, evidence_json, source_fingerprint)
                    VALUES (31, 21, '600519', '贵州茅台', 'LISTED', 'USER_WATCHLIST', 1,
                            '2026-07-25', '{}', 'item-fingerprint')
                    """);
        }
    }

    private static void insertLineage(
            Connection connection, long itemId, String type, long ownerUserId, long sourceRecordId, int active
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ai_research_universe_item_lineage
                    (universe_item_id, source_type, owner_user_id, source_record_id, active_at_snapshot,
                     source_fingerprint, evidence_json, observed_at)
                VALUES (?, ?, ?, ?, ?, 'test-fingerprint', '{}', '2026-07-25 16:00:00.000')
                """)) {
            statement.setLong(1, itemId);
            statement.setString(2, type);
            statement.setLong(3, ownerUserId);
            statement.setLong(4, sourceRecordId);
            statement.setInt(5, active);
            statement.executeUpdate();
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, table, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private static Set<String> indexes(Connection connection, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (rows.next()) {
                String value = rows.getString("INDEX_NAME");
                if (value != null) {
                    names.add(value.toLowerCase());
                }
            }
        }
        return names;
    }

    private static Set<String> importedForeignKeys(Connection connection, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet rows = metadata.getImportedKeys(connection.getCatalog(), null, table)) {
            while (rows.next()) {
                String value = rows.getString("FK_NAME");
                if (value != null) {
                    names.add(value.toLowerCase());
                }
            }
        }
        return names;
    }

    private static boolean hasUniqueIndexFor(Connection connection, String table, String... expectedColumns) throws SQLException {
        java.util.Map<String, java.util.List<String>> columnsByIndex = new java.util.LinkedHashMap<>();
        java.util.Map<String, Boolean> uniqueness = new java.util.LinkedHashMap<>();
        try (ResultSet rows = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
            while (rows.next()) {
                String index = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (index == null || column == null) {
                    continue;
                }
                columnsByIndex.computeIfAbsent(index, ignored -> new java.util.ArrayList<>()).add(column.toLowerCase());
                uniqueness.put(index, !rows.getBoolean("NON_UNIQUE"));
            }
        }
        List<String> expected = java.util.Arrays.stream(expectedColumns).map(String::toLowerCase).toList();
        return columnsByIndex.entrySet().stream().anyMatch(entry -> Boolean.TRUE.equals(uniqueness.get(entry.getKey()))
                && entry.getValue().equals(expected));
    }

    private static void execute(Connection connection, String resource) {
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(new ClassPathResource(resource), StandardCharsets.UTF_8));
    }

    private static String sqlState(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException exception && exception.getSQLState() != null) {
                return exception.getSQLState();
            }
            current = current.getCause();
        }
        return null;
    }
}
